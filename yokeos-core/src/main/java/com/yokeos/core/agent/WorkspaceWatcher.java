package com.yokeos.core.agent;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 第二条录入路径的执行者（第 30 节，FR-010）：实时监听 {@code .yokeos/agents/}——JDK {@link WatchService} 监听目录级变更，任何
 * Agent 目录新增/改/删都汇到与 API 上传<b>同一段</b> {@link AgentLifecycleService#register(Path)} /
 * 注销（FR-011）。启动全量扫描由装配层既有链路完成（AgentLoader.loadAll）， 本类只管启动之后的实时变更——避免与启动扫描重复登记（尤其重复排定时，research
 * D3）。
 *
 * <p>基础设施守护线程（与 25 节调度池同类的宪法 4 例外口径），Spring 管理执行器承载、 不把异步编程模型引进请求链路。平台语义边界（教学文档坑六）： WatchService
 * 只报注册目录直接子项——改子目录<b>内部</b>文件不保证触发事件，更新走 PUT 显式重注册； 拷目录竞态（目录先建、AGENT.md 后落盘）首注册失败 WARN
 * 跳过、后续子目录变更事件二次注册收敛（集成测试轮询断言）。单个坏目录 try/catch 跳过不拖垮监听。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "lifecycle/executor 均为装配层注入的共享单例（25 节 AgentScheduler 同款），构造注入共享引用正是意图。")
public class WorkspaceWatcher {

  private static final Logger LOG = LoggerFactory.getLogger(WorkspaceWatcher.class);

  /** 拷贝竞态重试上限（队尾排队 + 延迟，见 {@link #handleChange} javadoc）。 */
  private static final int MAX_RETRY = 5;

  /** 每次重试前延迟（毫秒）——覆盖 cp/scp 拷目录窗口。 */
  private static final long RETRY_DELAY_MS = 500;

  private final AgentLifecycleService lifecycle;

  private final Path agentsDir;

  private final Executor watcherExecutor;

  /** workspace root 由装配层注入（与 AgentStore 同源）；监听它的 agents/ 子目录。 */
  public WorkspaceWatcher(
      AgentLifecycleService lifecycle, Path workspaceRoot, Executor watcherExecutor) {
    this.lifecycle = lifecycle;
    this.agentsDir = workspaceRoot.resolve("agents");
    this.watcherExecutor = watcherExecutor;
  }

  /** 装配层 {@code @Bean(initMethod="start")} 调用：注册 WatchService 并把监听循环提交执行器。 */
  public void start() {
    WatchService watchService;
    try {
      Files.createDirectories(agentsDir);
      watchService = agentsDir.getFileSystem().newWatchService();
      agentsDir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
    } catch (IOException e) {
      LOG.warn("WorkspaceWatcher 启动失败，实时监听不可用（原因见异常堆栈）", e);
      return; // 监听是增强能力：起不来不阻断进程（API 录入路径不受影响）
    }
    watcherExecutor.execute(() -> loop(watchService));
  }

  private void loop(WatchService watchService) {
    while (true) {
      WatchKey key;
      try {
        key = watchService.take(); // 阻塞等事件（executor shutdown 中断即退出，坑九）
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt(); // 恢复中断位
        return;
      }
      for (WatchEvent<?> event : key.pollEvents()) {
        if (event.context() instanceof Path child) {
          handleChange(agentsDir.resolve(child), event.kind());
        }
      }
      if (!key.reset()) {
        return; // 监听目录不可用，安静退出
      }
    }
  }

  /**
   * 单个 Agent 目录变更 → 同一段注册/注销；坏目录记 WARN 跳过不拖垮监听（坑六）。 包级可见供单测直调——不依赖真实事件时序。
   *
   * <p>拷贝竞态的主动收敛（30 节实施实证，回填 CLAUDE.md 坑表）：CREATE 事件常先于 AGENT.md 落盘到达， 而写子目录内部文件在 macOS
   * 上<b>不会</b>再触发父级事件（教学文档坑六的「后续事件收敛」假设在 macOS 不成立， 参照实现回写 5.2.3 同结论）——故 CREATE/MODIFY
   * 注册失败时经执行器排队<b>有界延迟重试</b>（队尾天然延迟， AGENT.md 写入通常毫秒级），重试耗尽才 WARN 放弃。DELETE 不重试（目录已删是终态）。
   */
  void handleChange(Path agentDir, WatchEvent.Kind<?> kind) {
    handleChange(agentDir, kind, 0);
  }

  /** 带重试计数的形态（包级可见供单测直调耗尽态）。 */
  void handleChange(Path agentDir, WatchEvent.Kind<?> kind, int attempt) {
    try {
      if (kind == ENTRY_DELETE) {
        lifecycle.unregisterByDir(agentDir); // 手工删目录 → 注销（不归档，目录已没了）
      } else if (Files.isDirectory(agentDir)) {
        lifecycle.register(agentDir); // CREATE/MODIFY：与 API 上传同一段注册代码
      }
    } catch (RuntimeException e) {
      if (attempt < MAX_RETRY && kind != ENTRY_DELETE) {
        // 目录内容未就绪（典型：CREATE 先到、AGENT.md 后落盘）——排队延迟重试，收敛「丢目录即上线」
        watcherExecutor.execute(
            () -> {
              try {
                Thread.sleep(RETRY_DELAY_MS);
              } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
              }
              handleChange(agentDir, kind, attempt + 1);
            });
        LOG.info(
            "Agent 目录内容未就绪，延迟重试（目录名见异常消息）",
            new IllegalArgumentException("dir=" + agentDir.getFileName(), e));
        return;
      }
      // 消息编译期常量，目录名进异常（CRLF 门禁——AgentLoader 剔除日志同款形态）
      LOG.warn(
          "Agent 目录变更处理失败，跳过（目录名与原因见异常消息）",
          new IllegalArgumentException("dir=" + agentDir.getFileName(), e));
    }
  }
}

package com.yokeos.core.agent;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.yokeos.core.profile.Profile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * WorkspaceWatcher 验收（第 30 节 T012，坑六）：handleChange 直调、不起真监听——WatchService 事件时序平台相关，
 * 真拾取归集成测试轮询断言（AgentLifecycleIntegrationTest）；这里只钉「事件 → 同一段注册/注销」的分发语义与容错。
 */
class WorkspaceWatcherTest {

  @TempDir Path temp;

  private final AgentLifecycleService lifecycle = mock(AgentLifecycleService.class);

  /** 收集型执行器：重试任务可观察、可手动驱动（不起线程不睡）。 */
  private final java.util.List<Runnable> retryTasks = new java.util.ArrayList<>();

  private final Executor collector = retryTasks::add;

  private WorkspaceWatcher watcher() {
    return new WorkspaceWatcher(lifecycle, temp, collector);
  }

  /** 造 agents/ 下的一个子目录（父目录 agents/ 按需建）。 */
  private Path agentDir(String name) throws IOException {
    Files.createDirectories(temp.resolve("agents"));
    return Files.createDirectory(temp.resolve("agents").resolve(name));
  }

  @Test
  @DisplayName("手工丢一个 Agent 目录（CREATE）→ 监听事件触发同一段 register、免重启语义")
  void handleChangeCreateRegistersViaLifecycle() throws IOException {
    Path agentDir = agentDir("dropped-agent");

    watcher().handleChange(agentDir, ENTRY_CREATE);

    verify(lifecycle).register(agentDir); // 与 API create 同一段注册代码（FR-011）
  }

  @Test
  @DisplayName("手工删目录（DELETE）→ 触发注销（不归档——目录已没了）")
  void handleChangeDeleteUnregisters() {
    Path agentDir = temp.resolve("agents/removed-agent");

    watcher().handleChange(agentDir, ENTRY_DELETE);

    verify(lifecycle).unregisterByDir(agentDir);
  }

  @Test
  @DisplayName("拷贝竞态：CREATE 先到而 AGENT.md 未落盘 → 注册失败提交延迟重试且二次成功（30 节实证坑）")
  void handleChangeRetriesWhenContentNotReady() throws IOException {
    Path raceDir = agentDir("racing");
    Profile profile =
        new Profile("racing", null, null, null, null, null, null, null, null, null, null, null);
    doThrow(new IllegalArgumentException("缺少 AGENT.md"))
        .doReturn(profile)
        .when(lifecycle)
        .register(any());

    watcher().handleChange(raceDir, ENTRY_CREATE); // 首次失败（AGENT.md 未落盘）

    org.junit.jupiter.api.Assertions.assertEquals(1, retryTasks.size(), "应提交一个延迟重试任务");
    // 真跑重试任务（含 500ms 延迟——AGENT.md 写入窗口的真实尺度，单次可接受）
    assertDoesNotThrow(() -> retryTasks.get(0).run());
    verify(lifecycle, org.mockito.Mockito.times(2)).register(any()); // 首次失败 + 重试成功
  }

  @Test
  @DisplayName("重试耗尽后放弃：WARN 不外溢（监听器活着）")
  void handleChangeGivesUpAfterRetryExhausted() throws IOException {
    Path badDir = agentDir("broken");
    doThrow(new IllegalArgumentException("缺少 AGENT.md")).when(lifecycle).register(any());

    // 直调耗尽态（attempt=MAX_RETRY）：坏目录重试到底也不外溢
    assertDoesNotThrow(
        () ->
            new WorkspaceWatcher(lifecycle, temp, collector).handleChange(badDir, ENTRY_CREATE, 5));
    org.junit.jupiter.api.Assertions.assertTrue(retryTasks.isEmpty(), "耗尽后不再提交重试");
  }

  @Test
  @DisplayName("agents/ 下出现普通文件（非目录）的 MODIFY：不触发注册")
  void handleChangePlainFileIsIgnored() throws IOException {
    Files.createDirectories(temp.resolve("agents"));
    Path plainFile = Files.createFile(temp.resolve("agents/stray.txt"));

    watcher().handleChange(plainFile, ENTRY_CREATE);

    verify(lifecycle, org.mockito.Mockito.never()).register(any());
  }
}

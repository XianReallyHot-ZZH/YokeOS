package com.yokeos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yokeos.cli.YokeosRuntime;
import com.yokeos.core.agent.AgentScheduler;
import com.yokeos.storage.LlmCallRepository;
import com.yokeos.storage.ScheduledTask;
import com.yokeos.storage.ScheduledTaskRepository;
import com.yokeos.storage.Session;
import com.yokeos.storage.SessionRepository;
import com.yokeos.storage.TaskExecution;
import com.yokeos.storage.TaskExecutionRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 重启恢复（第 28 节，{@code @Tag("integration")}、mock 无 key）：两代独立 Spring 上下文指向<b>同一个</b> root 与 db
 * 文件，证明「进程不存状态、状态全在进程外」——第一代登记任务、钟推一次、人推一次、写记忆，然后 {@code close()} 模拟停机；第二代只查不跑，四样必须原样回来（技 §12
 * 验收要点的重启面）。
 *
 * <p><b>坑四</b>：两代上下文的 root/db 全走 {@code SpringApplicationBuilder.properties}（Environment 占位），测试内零
 * {@code System.setProperty}——TestContext 缓存与 {@code @TempDir} 清理时机不交互（25/26 节系统属性污染坑族）。
 * <b>坑三</b>：providers 清单是 classpath yaml 原文直读、属性注入无效，本测试靠 mock 内置常挂起无 key 上下文；压 {@code
 * web-application-type=none}（测试只要引擎不要端口）。
 *
 * <p>跑两次整机上下文较重，打 integration 不进 gate。手动跑：
 *
 * <pre>
 * mvn -pl yokeos-boot -am test -Dgroups=integration -DexcludedGroups= \
 *     -Dtest='RestartRecoveryIntegrationTest'
 * </pre>
 */
@Tag("integration")
class RestartRecoveryIntegrationTest {

  private static final String AGENT = "restart-agent";

  private static final String TASK_ID = AGENT + ":probe";

  /** 钟推事实标记（进 MEMORY.md 与钟推会话历史）与人推事实标记各一个。 */
  private static final String CLOCK_MARK = "restart-probe-clock";

  private static final String HUMAN_MARK = "restart-probe-human";

  @TempDir Path workspace;

  @Test
  @DisplayName("四样跨重启回来_定时状态历史_会话_记忆_审计不断档")
  void fourThings_surviveRestart() throws Exception {
    seedWorkspace();

    // —— 第一代：登记 + 钟推一次 + 人推一次 + 写记忆，然后关闭（模拟停机）——
    long llmCountBefore;
    try (ConfigurableApplicationContext ctx = boot()) {
      ctx.getBean(AgentScheduler.class).runNow(TASK_ID); // mock 驱动：save_memory 真写 MEMORY.md
      var sessionManager = ctx.getBean(com.yokeos.core.session.SessionManager.class);
      var agentService = ctx.getBean(com.yokeos.core.agent.AgentService.class);
      agentService.process(
          sessionManager.getOrCreate("cli", "restart-user", AGENT), "记住：人推事实 " + HUMAN_MARK);
      llmCountBefore = ctx.getBean(LlmCallRepository.class).count();
      assertTrue(llmCountBefore >= 4, "停机前至少 4 次 LLM 调用（钟推 2 + 人推 2，实际: " + llmCountBefore + "）");
    }

    // —— 第二代：只查不跑——四样必须原样回来 ——
    try (ConfigurableApplicationContext ctx = boot()) {
      // ① 定时任务：状态 + 历史（reconcile 不重置 run_count、保留 enabled）
      ScheduledTaskRepository tasks = ctx.getBean(ScheduledTaskRepository.class);
      ScheduledTask task = tasks.findById(TASK_ID).orElseThrow();
      assertEquals(1, task.getRunCount(), "重启后 run_count 仍为 1（reconcile 不重置）");
      assertEquals("success", task.getLastStatus(), "重启后 last_status 仍在");
      assertTrue(task.isEnabled(), "重启后 enabled 保留（不被 reconcile 重置）");

      TaskExecutionRepository executions = ctx.getBean(TaskExecutionRepository.class);
      List<TaskExecution> history =
          executions.findAll().stream().filter(e -> TASK_ID.equals(e.getTaskId())).toList();
      assertEquals(1, history.size(), "重启后执行历史仍查得到");
      assertTrue(history.get(0).isSuccess());
      assertEquals(
          "scheduler:scheduler:" + AGENT,
          history.get(0).getSessionId(),
          "执行历史关联钟推会话（SessionIds 单点拼接口径）");

      // ② 会话：钟推 + 人推两条完整历史
      SessionRepository sessions = ctx.getBean(SessionRepository.class);
      Session clock =
          sessions
              .findById("scheduler:scheduler:" + AGENT)
              .orElseThrow(() -> new AssertionError("重启后应查得到钟推会话"));
      assertTrue(
          clock.getMessagesJson().contains(CLOCK_MARK),
          "钟推会话历史应含例行消息（实际: " + clock.getMessagesJson() + "）");
      Session human =
          sessions
              .findById("cli:restart-user:" + AGENT)
              .orElseThrow(() -> new AssertionError("重启后应查得到人推会话"));
      assertTrue(human.getMessagesJson().contains(HUMAN_MARK), "人推会话历史应含人推消息");

      // ③ 记忆：MEMORY.md 两条事实都在（文件天生跨重启）
      String memory = Files.readString(workspace.resolve("memory").resolve("MEMORY.md"));
      assertTrue(
          memory.contains(CLOCK_MARK) && memory.contains(HUMAN_MARK),
          "MEMORY.md 应同时含钟推与人推写入的事实（实际: " + memory + "）");

      // ④ 审计不断档：重启前后 llm_calls 总数一致（第二代只查不跑，没有丢段也没有冒新）
      assertEquals(
          llmCountBefore, ctx.getBean(LlmCallRepository.class).count(), "llm_calls 跨重启不断档不冒新");
    }
  }

  /** 两代上下文同一形态：root/db 全 builder properties（坑四），web 压 none（坑三），mock 常挂无需任何 provider 配置。 */
  private ConfigurableApplicationContext boot() {
    return new SpringApplicationBuilder(YokeosRuntime.class)
        .properties(
            "yokeos.root=" + workspace.toAbsolutePath(),
            "yokeos.db.dir=" + workspace.toAbsolutePath(),
            "spring.main.web-application-type=none")
        .run();
  }

  private void seedWorkspace() throws Exception {
    Files.createDirectories(workspace.resolve("memory"));
    Files.createDirectories(workspace.resolve("agents").resolve(AGENT));
    Files.writeString(workspace.resolve("AGENTS.md"), "测试工作区约定");
    Files.writeString(workspace.resolve("SOUL.md"), "你是简洁实用的助手");
    Files.writeString(workspace.resolve("USER.md"), "无特殊偏好");
    Files.writeString(
        workspace.resolve("agents").resolve(AGENT).resolve("AGENT.md"),
        "---\n"
            + "name: "
            + AGENT
            + "\n"
            + "identity:\n"
            + "  agent_name: 重启小欧\n"
            + "  prompt: 你是测试助手。\n"
            + "provider:\n"
            + "  name: mock\n"
            + "  model: mock-model\n"
            + "tools:\n"
            + "  - save_memory\n"
            + "  - recall_memory\n"
            + "schedules:\n"
            + "  - id: probe\n"
            + "    cron: \"0 0 0 1 1 *\"\n"
            + "    zone: Asia/Shanghai\n"
            + "    message: 例行巡检，记住钟推事实 "
            + CLOCK_MARK
            + "\n"
            + "---\n"
            + "你是测试助手，被触发时记录一次巡检。\n");
  }
}

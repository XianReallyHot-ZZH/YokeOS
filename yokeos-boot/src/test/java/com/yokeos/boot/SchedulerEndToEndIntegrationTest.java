package com.yokeos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yokeos.core.agent.AgentScheduler;
import com.yokeos.storage.LlmCallRepository;
import com.yokeos.storage.ScheduledTask;
import com.yokeos.storage.ScheduledTaskRepository;
import com.yokeos.storage.SessionRepository;
import com.yokeos.storage.TaskExecutionRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 第 25 节专属端到端（{@code @Tag("integration")}，真 DeepSeek + 真 YokeosRuntime 上下文）：钟推全链路—— 上下文启动即把
 * AGENT.md 的 schedules 登记进 SQLite（initMethod=registerAll 的真装配验证），runNow（人推补跑入口）确定性驱动一次执行，
 * 两表对账（scheduled_tasks / task_executions）+ 钟推 Session 落库 + llm_calls 审计同构。
 *
 * <p>cron 设为每年 1 月 1 日 0 点，测试窗口内绝不自然触发——执行只由 runNow 显式驱动，断言确定（research D6）；真等 cron
 * 到点属人工项（教学文档第五部分）。AGENT.md 不配 tools——模型零工具纯文本答复即可，链路不依赖工具执行。
 *
 * <p><b>运行前提</b>：{@code @Tag("integration")} 默认被 gate 排除；需 {@code DEEPSEEK_API_KEY}（缺 key 时
 * assumeTrue 跳过不失败）与可用网络。手动跑：{@code source ~/.zshrc && mvn -pl yokeos-boot -am test
 * -Dgroups=integration -DexcludedGroups= -Dtest=SchedulerEndToEndIntegrationTest}。
 */
@Tag("integration")
@SpringBootTest(classes = com.yokeos.cli.YokeosRuntime.class)
class SchedulerEndToEndIntegrationTest {

  private static final String DEEPSEEK_KEY = System.getenv("DEEPSEEK_API_KEY");

  private static final String AGENT_NAME = "sched-e2e";

  private static final String TASK_ID = AGENT_NAME + ":report";

  static @TempDir Path workspace;

  @Autowired AgentScheduler scheduler;

  @Autowired ScheduledTaskRepository tasks;

  @Autowired TaskExecutionRepository executions;

  @Autowired SessionRepository sessions;

  @Autowired LlmCallRepository llmCalls;

  @BeforeAll
  static void seedWorkspace() throws Exception {
    Assumptions.assumeTrue(
        DEEPSEEK_KEY != null && !DEEPSEEK_KEY.isBlank(),
        "需要环境变量 DEEPSEEK_API_KEY 才能真调（缺 key 时跳过而非失败）");
    Files.createDirectories(workspace.resolve("memory"));
    Files.createDirectories(workspace.resolve("agents").resolve(AGENT_NAME));
    Files.writeString(
        workspace.resolve("agents/" + AGENT_NAME + "/AGENT.md"),
        """
        ---
        name: %s
        description: 定时任务端到端自测 Agent
        identity:
          agent_name: 定时小欧
          prompt: 你是一个定时巡检助手，回答简短。
        provider:
          name: deepseek
          model: deepseek-chat
        schedules:
          - id: report
            cron: "0 0 0 1 1 *"
            zone: Asia/Shanghai
            message: 报到：一句话确认你在岗即可
        settings:
          max_iterations: 10
          max_history_turns: 20
        ---
        你是一个定时巡检助手，被触发时简短答复。
        """
            .formatted(AGENT_NAME));
    Files.writeString(workspace.resolve("memory/MEMORY.md"), "## 核心记忆\n\n## 归档记忆\n");
    System.setProperty("yokeos.root", workspace.toString());
    System.setProperty("yokeos.db.dir", workspace.toString());
  }

  @Test
  @DisplayName("启动即登记_runNow驱动一次_两表对账加审计同构")
  void startupRegisters_andRunNowLeavesTrace() {
    // ① 启动即登记：registerAll 已在上下文启动期跑完（initMethod），scheduled_tasks 有行、默认启用、零累计
    ScheduledTask task = tasks.findById(TASK_ID).orElseThrow();
    assertEquals(AGENT_NAME, task.getProfileName());
    assertTrue(task.isEnabled(), "新登记默认启用");
    assertEquals(0, task.getRunCount());

    // ② 人推补跑入口确定性驱动一次（cron 每年 1 月 1 日，窗口内绝不自然触发）
    scheduler.runNow(TASK_ID);

    // ③ 两表对账：执行历史恰一行成功，任务行状态同步更新
    List<com.yokeos.storage.TaskExecution> history = executions.findAll();
    assertEquals(1, history.size(), "runNow 一次恰留一行历史");
    assertTrue(history.get(0).isSuccess(), "真模型链路应成功");
    assertEquals(TASK_ID, history.get(0).getTaskId());
    assertEquals(
        "scheduler:scheduler:" + AGENT_NAME,
        history.get(0).getSessionId(),
        "钟推会话三元组拼接（SessionIds 单点）");
    ScheduledTask after = tasks.findById(TASK_ID).orElseThrow();
    assertEquals(1, after.getRunCount(), "累计次数自增");
    assertEquals("success", after.getLastStatus());

    // ④ 钟推 Session 落库 + 审计同构：llm_calls 有该 session 的成功调用（与人推记账无区别）
    assertNotNull(
        sessions.findById("scheduler:scheduler:" + AGENT_NAME).orElse(null), "钟推会话应落 sessions 表");
    assertTrue(
        llmCalls.findAll().stream()
            .anyMatch(
                call ->
                    ("scheduler:scheduler:" + AGENT_NAME).equals(call.getSessionId())
                        && Boolean.TRUE.equals(call.getSuccess())),
        "llm_calls 应有该钟推会话的成功调用记录（审计同构）");
  }
}

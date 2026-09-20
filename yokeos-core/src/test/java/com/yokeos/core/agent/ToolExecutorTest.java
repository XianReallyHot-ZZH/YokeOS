package com.yokeos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.yokeos.core.audit.ToolInvocationAuditor;
import com.yokeos.core.provider.ToolCallRequest;
import com.yokeos.core.tool.ToolResult;
import com.yokeos.core.tool.YokeTool;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ToolExecutor harness（docs/class/017-react-loop.md 第四部分 + 24 节收口回归）：成败双路审计（先落账再还结果）、异常不吞不炸
 * 循环、未注册名/坏 JSON 留痕不抛、可重试指数退避（总尝试 3，退避基值注入 0 不赌真实时钟）、审计最终态一条、沙箱拒绝不可重试且审计恰一条。
 */
class ToolExecutorTest {

  private final ToolInvocationAuditor auditor = mock(ToolInvocationAuditor.class);

  /** 退避基值 0：重试瞬间完成，测试不赌真实时钟。 */
  private final ToolExecutor executor = new ToolExecutor(Map.of(), auditor, 0L);

  private static final ToolCallRequest GET =
      new ToolCallRequest("http_get", "{\"url\":\"https://a\"}");

  @Test
  @DisplayName("执行成功_审计落success为true")
  void successAuditsTrue() {
    ToolExecutor withTools =
        new ToolExecutor(Map.of("http_get", stubReturning(ToolResult.ok("20度晴"))), auditor, 0L);

    ToolResult result = withTools.execute("s-1", GET);

    assertTrue(result.success());
    assertEquals("20度晴", result.content());
    verify(auditor)
        .record(
            eq("s-1"),
            eq("http_get"),
            eq("{\"url\":\"https://a\"}"),
            eq("20度晴"),
            eq(true),
            isNull(),
            anyLong());
  }

  @Test
  @DisplayName("工具返回失败ToolResult_审计落success为false带原因")
  void failureToolResultAuditsFalseWithReason() {
    ToolExecutor withTools =
        new ToolExecutor(
            Map.of("http_get", stubReturning(ToolResult.error("not found", false))), auditor, 0L);

    ToolResult result = withTools.execute("s-1", GET);

    assertFalse(result.success());
    verify(auditor)
        .record(
            eq("s-1"),
            eq("http_get"),
            eq("{\"url\":\"https://a\"}"),
            isNull(), // 失败无执行结果——原因只进 error_message（技 §9.2 列定义）
            eq(false),
            eq("not found"),
            anyLong());
  }

  @Test
  @DisplayName("工具抛RuntimeException_转失败结果不炸循环")
  void toolThrowsConvertedToFailingResultNoPropagate() {
    ToolExecutor withTools =
        new ToolExecutor(Map.of("http_get", new ThrowingTool("boom inside tool")), auditor, 0L);

    ToolResult result = withTools.execute("s-1", GET);

    assertFalse(result.success(), "异常不上抛——转失败结果交还循环");
    assertTrue(result.errorMessage().contains("boom inside tool"), "失败原因带根因");
    verify(auditor)
        .record(eq("s-1"), eq("http_get"), any(), any(), eq(false), contains("boom"), anyLong());
  }

  @Test
  @DisplayName("沙箱拒绝收口_不可重试_审计恰一条")
  void sandboxRejectionCollectedOnceNotRetried() {
    // 坑二回归（24 节）：SandboxViolationException 从工具上抛到这里是 RuntimeException 形态——core 不依赖 Sandbox 类型，
    // 用抛「拒绝消息」的假工具锚同一条收口链：转失败结果、不可重试（重试被拒动作毫无意义）、审计恰一条
    ThrowingTool violating = new ThrowingTool("命令不在白名单内: rm");
    ToolExecutor withTools = new ToolExecutor(Map.of("http_get", violating), auditor, 0L);

    ToolResult result = withTools.execute("s-1", GET);

    assertFalse(result.success());
    assertTrue(result.errorMessage().contains("命令不在白名单内"), "拒绝原因对模型可见: " + result.errorMessage());
    assertEquals(1, violating.attempts.get(), "拒绝不可重试——一次即止");
    verify(auditor, times(1))
        .record(
            eq("s-1"),
            eq("http_get"),
            eq("{\"url\":\"https://a\"}"),
            isNull(),
            eq(false),
            contains("白名单"),
            anyLong());
  }

  @Test
  @DisplayName("未注册工具名_失败结果加审计留痕不抛异常")
  void unknownToolNameFailingResultPlusAudit() {
    ToolResult result = executor.execute("s-1", new ToolCallRequest("no_such_tool", "{}"));

    assertFalse(result.success());
    assertTrue(result.errorMessage().contains("no_such_tool"), "失败原因指名工具");
    verify(auditor)
        .record(
            eq("s-1"),
            eq("no_such_tool"),
            eq("{}"),
            isNull(),
            eq(false),
            contains("no_such_tool"),
            anyLong());
  }

  @Test
  @DisplayName("入参不是合法JSON_失败结果加审计留痕")
  void invalidJsonFailingResultPlusAudit() {
    // 必须注册 http_get（用带工具的执行器）——否则未注册检查先于 JSON 解析短路，测不到解析路径
    ToolExecutor withTools =
        new ToolExecutor(Map.of("http_get", stubReturning(ToolResult.ok("ok"))), auditor, 0L);

    ToolResult result = withTools.execute("s-1", new ToolCallRequest("http_get", "{not-json"));

    assertFalse(result.success());
    verify(auditor)
        .record(
            eq("s-1"),
            eq("http_get"),
            eq("{not-json"),
            isNull(),
            eq(false),
            contains("JSON"),
            anyLong());
  }

  @Test
  @DisplayName("可重试失败_退避内重试成功_审计恰一条最终态")
  void retryableFailsThenSucceedsWithinBackoffRetries() {
    FlakyTool flaky =
        new FlakyTool(
            ToolResult.error("transient net error", true),
            ToolResult.error("transient net error", true),
            ToolResult.ok("第三次成功"));
    ToolExecutor withTools = new ToolExecutor(Map.of("http_get", flaky), auditor, 0L);

    ToolResult result = withTools.execute("s-1", GET);

    assertTrue(result.success(), "fail,fail,ok 脚本在 3 次尝试内成功");
    assertEquals(3, flaky.attempts.get(), "总尝试恰 3 次（首次 + 2 重试，research D5）");
    verify(auditor, times(1))
        .record(any(), any(), any(), eq("第三次成功"), eq(true), isNull(), anyLong());
    verify(auditor, times(0))
        .record(any(), any(), any(), any(), eq(false), any(), anyLong()); // 中间失败不落账
  }

  @Test
  @DisplayName("重试耗尽_落最终失败审计一条")
  void retryExhaustedFinalFailureAuditedOnce() {
    FlakyTool flaky = new FlakyTool(ToolResult.error("always down", true));
    ToolExecutor withTools = new ToolExecutor(Map.of("http_get", flaky), auditor, 0L);

    ToolResult result = withTools.execute("s-1", GET);

    assertFalse(result.success());
    assertEquals(3, flaky.attempts.get(), "总尝试上限 3");
    verify(auditor, times(1))
        .record(
            eq("s-1"),
            eq("http_get"),
            eq("{\"url\":\"https://a\"}"),
            isNull(),
            eq(false),
            eq("always down"),
            anyLong());
  }

  @Test
  @DisplayName("不可重试失败_一次即止")
  void nonRetryableSingleAttempt() {
    FlakyTool flaky = new FlakyTool(ToolResult.error("bad request", false));
    ToolExecutor withTools = new ToolExecutor(Map.of("http_get", flaky), auditor, 0L);

    ToolResult result = withTools.execute("s-1", GET);

    assertFalse(result.success());
    assertEquals(1, flaky.attempts.get(), "不可重试失败一次即止（不进退避循环）");
  }

  private static YokeTool stubReturning(ToolResult result) {
    return new YokeTool() {
      @Override
      public String getName() {
        return "http_get";
      }

      @Override
      public String getDescription() {
        return "桩";
      }

      @Override
      public String getInputSchema() {
        return "{\"type\":\"object\"}";
      }

      @Override
      public ToolResult execute(JsonNode input) {
        return result;
      }
    };
  }

  /** 按脚本出结果的假工具：第 n 次尝试返回 script[n-1]，脚本走完重复最后一项。 */
  private static final class FlakyTool implements YokeTool {

    private final AtomicInteger attempts = new AtomicInteger();

    private final ToolResult[] script;

    FlakyTool(ToolResult... script) {
      this.script = script;
    }

    @Override
    public String getName() {
      return "http_get";
    }

    @Override
    public String getDescription() {
      return "脚本化重试桩";
    }

    @Override
    public String getInputSchema() {
      return "{\"type\":\"object\"}";
    }

    @Override
    public ToolResult execute(JsonNode input) {
      int n = attempts.incrementAndGet();
      return script[Math.min(n, script.length) - 1];
    }
  }

  /** 抛 RuntimeException 的假工具（异常不吞用例；计数器供 24 节「拒绝不重试」回归断言尝试次数）。 */
  private static final class ThrowingTool implements YokeTool {

    private final AtomicInteger attempts = new AtomicInteger();

    private final String message;

    ThrowingTool(String message) {
      this.message = message;
    }

    @Override
    public String getName() {
      return "http_get";
    }

    @Override
    public String getDescription() {
      return "抛异常桩";
    }

    @Override
    public String getInputSchema() {
      return "{\"type\":\"object\"}";
    }

    @Override
    public ToolResult execute(JsonNode input) {
      attempts.incrementAndGet();
      throw new IllegalStateException(message);
    }
  }
}

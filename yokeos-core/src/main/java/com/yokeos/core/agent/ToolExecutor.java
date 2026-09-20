package com.yokeos.core.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yokeos.core.audit.ToolInvocationAuditor;
import com.yokeos.core.provider.ToolCallRequest;
import com.yokeos.core.tool.ToolResult;
import com.yokeos.core.tool.YokeTool;
import java.util.Map;

/**
 * 工具执行的唯一路径（宪法 2：执行权只有这一条路——沙箱检查位与审计写入都挂在这条路上）。执行序列：先解析 argumentsJson（坏 JSON 走失败路径）→〔Sandbox
 * 白名单校验位：24 节接线〕→ 执行 → 可重试失败指数退避（总尝试 上限 3，需 §8.2）→ 先落审计（一次调用请求一条最终态）再还结果。异常转失败结果不上抛——循环不炸，模型下一轮
 * 看到失败原因自行决定（技 §4.2）。退避为同步 Thread.sleep（宪法 4）。
 */
public final class ToolExecutor {

  /** 总尝试次数上限（首次 + 2 重试；技 §4.2 与教学文档措辞张力的采信侧，research D5）。 */
  static final int MAX_ATTEMPTS = 3;

  /** 线程安全（Jackson 官方口径），静态复用免每次重建。 */
  private static final ObjectMapper JSON = new ObjectMapper();

  private final Map<String, YokeTool> tools;

  private final ToolInvocationAuditor auditor;

  /** 指数退避基值毫秒（第 n 次重试睡 baseMs << n）；测试注入 0 不赌真实时钟。 */
  private final long retryBackoffBaseMs;

  /**
   * @param retryBackoffBaseMs 退避基值毫秒（第 n 次重试睡 baseMs &lt;&lt; n）；测试注入 0。
   */
  public ToolExecutor(
      Map<String, YokeTool> tools, ToolInvocationAuditor auditor, long retryBackoffBaseMs) {
    this.tools = Map.copyOf(tools);
    this.auditor = auditor;
    this.retryBackoffBaseMs = retryBackoffBaseMs;
  }

  /** 执行一次工具调用请求：解析 →〔Sandbox 位〕→（执行 + 退避重试）→ 先落审计再还结果。 */
  public ToolResult execute(String sessionId, ToolCallRequest call) {
    long startedAt = System.currentTimeMillis();
    ToolResult result = executeWithRetry(call);
    // 先落审计再还结果（宪法 7）：一次调用请求一条最终态，duration 覆盖重试总耗时。
    auditor.record(
        sessionId,
        call.name(),
        call.argumentsJson(),
        result.success() ? result.content() : null,
        result.success(),
        result.success() ? null : result.errorMessage(),
        System.currentTimeMillis() - startedAt);
    return result;
  }

  private ToolResult executeWithRetry(ToolCallRequest call) {
    ToolResult last = null;
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      if (attempt > 0) {
        sleepBackoff(attempt);
      }
      last = attemptOnce(call);
      if (last.success() || !last.retryable()) {
        return last; // 成功或不可重试（未注册名、坏 JSON、确定性失败）一次即止
      }
      // 可重试失败：退避后进下一轮尝试
    }
    return last; // 耗尽：最后一次失败即最终结果（不再多执行一次）
  }

  private ToolResult attemptOnce(ToolCallRequest call) {
    YokeTool tool = tools.get(call.name());
    if (tool == null) {
      return ToolResult.error("未注册的工具: " + call.name(), false);
    }
    // Sandbox 违规收口位（24 节定稿，张力一裁决）：enforce 落点在各工具动作发生处（specs/008 D7），本位不做第二次
    // 校验——SandboxViolationException 等工具异常在下方 catch 转不可重试失败结果 + 审计留痕（拒绝恰一条、不重试）。
    JsonNode input;
    try {
      input = JSON.readTree(call.argumentsJson());
    } catch (JsonProcessingException e) {
      return ToolResult.error("入参不是合法 JSON: " + call.argumentsJson(), false);
    }
    try {
      return tool.execute(input);
    } catch (RuntimeException e) {
      // 异常不吞也不炸循环：转失败结果（带根因），模型下一轮可见
      return ToolResult.error("工具执行异常: " + e.getMessage(), false);
    }
  }

  private void sleepBackoff(int attempt) {
    long backoff = retryBackoffBaseMs << (attempt - 1); // 指数退避：base * 2^(n-1)
    if (backoff > 0) {
      try {
        Thread.sleep(backoff); // 同步阻塞退避（宪法 4：无异步模型）
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("退避等待被中断", e);
      }
    }
  }
}

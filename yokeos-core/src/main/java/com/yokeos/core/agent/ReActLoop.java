package com.yokeos.core.agent;

import com.yokeos.core.profile.Profile;
import com.yokeos.core.provider.ProviderRequest;
import com.yokeos.core.provider.ProviderResponse;
import com.yokeos.core.provider.ProviderService;
import com.yokeos.core.provider.ToolCallRequest;
import com.yokeos.core.session.Session;
import com.yokeos.core.tool.ToolResult;

/**
 * Agent 的核心循环引擎（宪法 1：自实现，不用框架 Agent 抽象）。本类只做调度——转圈、判断该不该停、把每轮结果 攒进 Session；组装归 PromptBuilder、调模型归
 * ProviderService、执行工具归 ToolExecutor（宪法 2：执行权 只有这一条路）。轮数兜底默认 10（Profile settings.maxIterations
 * 可覆盖），转满强制收尾（坑一）。
 */
public final class ReActLoop {

  /** 转满最大轮数的强制收尾答复（测试断言字面量，教学文档逐字）。 */
  static final String MAX_ITERATIONS_REPLY = "达到最大轮数，已停止";

  private final PromptBuilder promptBuilder;

  private final ProviderService providerService;

  private final ToolExecutor toolExecutor;

  /** 三协作者全注入：组装、调用、执行各归其位，本类只做调度。 */
  public ReActLoop(
      PromptBuilder promptBuilder, ProviderService providerService, ToolExecutor toolExecutor) {
    this.promptBuilder = promptBuilder;
    this.providerService = providerService;
    this.toolExecutor = toolExecutor;
  }

  /**
   * 跑一次多步循环：追加用户消息 → 每轮〔组装 → 带 sessionId 调 LLM（审计关联）→ 先累积再判停 → 无工具调用 即收尾 → 逐个顺序执行并回填〕→
   * 转满轮数强制收尾。全程同步阻塞（宪法 4）。
   */
  public String run(Session session, String userMessage, Profile profile) {
    session.appendUser(userMessage);
    for (int iteration = 0; iteration < profile.settings().maxIterations(); iteration++) {
      ProviderRequest request = promptBuilder.build(session, profile);
      ProviderResponse response = providerService.chat(session.sessionId(), profile, request);
      session.appendAssistant(response); // 先累积再判停：转满轮数的每轮也全留痕（坑三）
      if (!response.hasToolCalls()) {
        return response.text() == null ? "" : response.text(); // 停止条件：没要工具即收尾
      }
      for (ToolCallRequest call : response.toolCalls()) { // 一次多个按顺序执行，不并行（技 §4.3）
        ToolResult result = toolExecutor.execute(session.sessionId(), call);
        session.appendToolResult(call, result); // 失败结果同样回填，模型下一轮自行决定
      }
    }
    return MAX_ITERATIONS_REPLY;
  }
}

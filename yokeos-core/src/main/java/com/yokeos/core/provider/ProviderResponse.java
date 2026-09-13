package com.yokeos.core.provider;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;

/**
 * 一次 LLM 调用的响应侧值对象（契约上移，第 17 节拍板①）。
 *
 * <p>不带 token 用量——审计在 provider 实现体内部闭环（LlmCallAuditor 收 Integer×3，research D1）。 text 可能为
 * null（模型只提工具调用时），由消费方按需判空。
 */
@SuppressFBWarnings(
    value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
    justification = "record 访问器返回不可变副本（紧凑构造器 List.copyOf）；SpotBugs 对 record 的类型级误报")
public record ProviderResponse(String text, List<ToolCallRequest> toolCalls) {

  /** 防御性拷贝构造：工具调用清单不可变化、null 缺省为空清单。 */
  public ProviderResponse {
    toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
  }

  /** 模型是否提出了工具调用请求——ReAct 循环的判停依据（无请求即收尾）。 */
  public boolean hasToolCalls() {
    return !toolCalls.isEmpty();
  }
}

package com.yokeos.core.provider;

import com.yokeos.core.profile.Profile;

/**
 * LLM 调用的中性契约（契约上移，第 17 节拍板①）。
 *
 * <p>ReAct 循环落 core 而core 禁引 Spring AI 类型，本接口把「一次 LLM 调用」抽象为纯 Java 协议， Spring AI 类型不出 provider
 * 模块（实现见 yokeos-provider 的 SpringAiProviderService）。sessionId 随调用传递： llm_calls 审计按 session 关联（16
 * 节签名设计在第 17 节循环里的兑现）。
 */
public interface ProviderService {

  /**
   * 一次 LLM 调用：按 Profile 路由到目标模型；工具经 request 携带只翻译不执行（宪法 2）。
   *
   * @param sessionId 审计关联键
   * @param profile Agent 声明（provider 名、model、temperature）
   * @param request 组装产物（promptText + 可用工具清单）
   * @return 模型响应（text + 工具调用请求清单）
   */
  ProviderResponse chat(String sessionId, Profile profile, ProviderRequest request);
}

package com.yokeos.provider;

import com.yokeos.core.audit.LlmCallAuditor;
import com.yokeos.core.profile.Profile;
import com.yokeos.core.tool.YokeTool;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * LLM 调用统一入口（核心能力一）：按 Profile 经显式映射选模型（宪法 3）、工具 schema 只翻译不执行 （宪法 2，自动执行显式关闭）、成败双路先落审计再返回/抛错（宪法
 * 7）。全程同步阻塞（宪法 4）。
 */
public class ProviderService {

  private final Map<String, ChatModel> providerMap;

  private final ToolSchemaAdapter adapter;

  private final LlmCallAuditor auditor;

  /**
   * 显式映射表由装配方构造传入（{@code Map.of("deepseek", model, "kimi", model)} 形态）——刻意不做 容器类型扫描：Bean
   * 类型相同，扫描必乱（宪法 3）。
   */
  public ProviderService(
      Map<String, ChatModel> providerMap, ToolSchemaAdapter adapter, LlmCallAuditor auditor) {
    this.providerMap = Map.copyOf(providerMap);
    this.adapter = adapter;
    this.auditor = auditor;
  }

  /** 无工具形态的便捷入口（字面量签名 {@code chat(sessionId, Profile, Prompt)} 的保持位）。 */
  public ChatResponse chat(String sessionId, Profile profile, Prompt prompt) {
    return chat(sessionId, profile, prompt, List.of());
  }

  /**
   * 一次 LLM 调用：按名取模型（查无抛 {@link ProviderNotFoundException}，消息含缺失名字）→ 组装请求 （schema 翻译携带、自动执行关闭）→ 同步调用
   * → 响应原样返回。成败都先落审计：成功记 token 三项与 耗时，失败记 error_message 后异常继续上抛（FR4/FR5/FR6）。
   */
  public ChatResponse chat(
      String sessionId, Profile profile, Prompt prompt, List<YokeTool> availableTools) {
    String providerName = profile.providerName();
    ChatModel model = providerMap.get(providerName);
    if (model == null) {
      throw new ProviderNotFoundException(providerName);
    }
    Prompt effective = new Prompt(prompt.getInstructions(), buildOptions(profile, availableTools));
    long startedAt = System.currentTimeMillis();
    try {
      ChatResponse response = model.call(effective);
      Usage usage = usageOf(response);
      auditor.record(
          sessionId,
          providerName,
          profile.provider().model(),
          usage == null ? null : usage.getPromptTokens(),
          usage == null ? null : usage.getCompletionTokens(),
          usage == null ? null : usage.getTotalTokens(),
          true,
          null,
          System.currentTimeMillis() - startedAt);
      return response;
    } catch (RuntimeException e) {
      auditor.record(
          sessionId,
          providerName,
          profile.provider().model(),
          null,
          null,
          null,
          false,
          e.getMessage(),
          System.currentTimeMillis() - startedAt);
      throw e;
    }
  }

  private ToolCallingChatOptions buildOptions(Profile profile, List<YokeTool> availableTools) {
    // 坑二的代码落点：internalToolExecutionEnabled(false)——Spring AI 自带执行循环必须关掉，
    // 否则 tool 被调两次且绕过沙箱（宪法 2，回归测试 callWithToolSchemaDisablesAutoExecution）。
    ToolCallingChatOptions.Builder builder =
        DefaultToolCallingChatOptions.builder()
            .toolCallbacks(adapter.toSpringAiTools(availableTools))
            .internalToolExecutionEnabled(false);
    Double temperature = profile.provider() == null ? null : profile.provider().temperature();
    if (temperature != null) {
      builder.temperature(temperature);
    }
    return builder.build();
  }

  private static Usage usageOf(ChatResponse response) {
    if (response == null) {
      return null;
    }
    return response.getMetadata().getUsage();
  }
}

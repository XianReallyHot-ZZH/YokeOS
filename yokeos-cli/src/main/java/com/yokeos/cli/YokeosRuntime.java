package com.yokeos.cli;

import com.yokeos.channel.cli.CliChannel;
import com.yokeos.cli.command.ProviderListCommand;
import com.yokeos.core.agent.AgentService;
import com.yokeos.core.agent.PromptBuilder;
import com.yokeos.core.agent.ReActLoop;
import com.yokeos.core.agent.ToolExecutor;
import com.yokeos.core.audit.LlmCallAuditor;
import com.yokeos.core.audit.ToolInvocationAuditor;
import com.yokeos.core.context.ContextLoader;
import com.yokeos.core.profile.AgentLoader;
import com.yokeos.core.profile.ProfileRegistry;
import com.yokeos.core.provider.ProviderService;
import com.yokeos.core.session.SessionManager;
import com.yokeos.core.tool.YokeTool;
import com.yokeos.provider.ProvidersProperties;
import com.yokeos.provider.SpringAiProviderService;
import com.yokeos.provider.ToolSchemaAdapter;
import com.yokeos.storage.JpaLlmCallAuditor;
import com.yokeos.storage.JpaSessionManager;
import com.yokeos.storage.JpaToolInvocationAuditor;
import com.yokeos.storage.JpaToolInvocationReader;
import com.yokeos.storage.LlmCallRepository;
import com.yokeos.storage.SessionRepository;
import com.yokeos.storage.ToolInvocationRepository;
import com.yokeos.tool.HttpGetTool;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 重命令（chat/serve/gateway）的 Spring 装配面（第 18 节）。轻命令不进这里（课件坑二：为列个目录不值得等 Spring 启动）。
 *
 * <p>课件坑四：{@code scanBasePackages} 只管普通 Bean，不会带动 JPA
 * 仓库与实体扫描跟着跨模块——存储在独立模块（com.yokeos.storage），必须显式 {@code @EnableJpaRepositories} +
 * {@code @EntityScan}，否则 "Found 0 JPA repository interfaces"，审计与会话静默写不进去 （装配完整性测试钉死：仓库 Bean 数 &gt;
 * 0）。
 *
 * <p>运行链全部 @Bean 显式装配（配方 = 17 节冒烟手工装配转录）：16/17 节交付的类保持纯 POJO 零框架依赖； Provider 显式映射（宪法 3）——provider
 * 清单从 classpath application.yaml 原文读取（Boot 属性绑定会提前解析 ${ENV} 占位， 与 16 节「占位形态校验」冲突，故不走绑定），占位校验走
 * ProvidersProperties.validate、解析经环境变量。boot 的 YokeosBootApplication 组件扫描吸收本类（一套配置两入口，26 节 serve
 * 直接受益）。
 */
@SpringBootApplication(scanBasePackages = "com.yokeos")
@EnableJpaRepositories(basePackages = "com.yokeos.storage")
@EntityScan(basePackages = "com.yokeos.storage")
public class YokeosRuntime {

  // 工作区根目录默认 ./.yokeos；可用属性 yokeos.root 覆盖（集成测试指向临时工作区，默认行为不变）。
  @Value("${yokeos.root:.yokeos}")
  private String yokeosRootProp;

  private Path workspace() {
    return Path.of(yokeosRootProp);
  }

  /** Provider 显式映射（宪法 3）：name → ChatModel；DeepSeek/Kimi 均 OpenAI 兼容协议（16 节 research）。 */
  @Bean
  Map<String, ChatModel> providerMap() {
    ProvidersProperties properties = new ProvidersProperties();
    java.util.List<Map<String, String>> raw =
        ProviderListCommand.readRawProvidersOf(
            YokeosRuntime.class.getResourceAsStream("/application.yaml"));
    raw.forEach(
        entry -> {
          ProvidersProperties.ProviderItem item = new ProvidersProperties.ProviderItem();
          item.setName(entry.get("name"));
          item.setApiKey(entry.get("api-key"));
          item.setBaseUrl(entry.get("base-url"));
          properties.getProviders().add(item);
        });
    properties.validate(); // 缺失/非法配置启动即点名报错，不静默失败（16 节 FR7）

    Map<String, ChatModel> map = new LinkedHashMap<>();
    for (ProvidersProperties.ProviderItem item : properties.getProviders()) {
      OpenAiApi api =
          OpenAiApi.builder()
              .baseUrl(item.getBaseUrl())
              .apiKey(resolvePlaceholder(item.getApiKey()))
              .build();
      // model 不设默认值：随 Profile 逐请求传递（技 §3.3；SpringAiProviderService.buildOptions 18 节补齐）
      map.put(item.getName(), OpenAiChatModel.builder().openAiApi(api).build());
    }
    return map;
  }

  private static final String PLACEHOLDER_PREFIX = "${";
  private static final String PLACEHOLDER_SUFFIX = "}";

  /** ${ENV_VAR} 占位解析（16 节 ConfigLoader 语义的单值形态）。 */
  private static String resolvePlaceholder(String rawKey) {
    if (rawKey != null
        && rawKey.startsWith(PLACEHOLDER_PREFIX)
        && rawKey.endsWith(PLACEHOLDER_SUFFIX)) {
      return System.getenv(
          rawKey.substring(
              PLACEHOLDER_PREFIX.length(), rawKey.length() - PLACEHOLDER_SUFFIX.length()));
    }
    return rawKey;
  }

  @Bean
  LlmCallAuditor llmCallAuditor(LlmCallRepository repository) {
    return new JpaLlmCallAuditor(repository);
  }

  @Bean
  ToolInvocationAuditor toolInvocationAuditor(ToolInvocationRepository repository) {
    return new JpaToolInvocationAuditor(repository);
  }

  @Bean
  com.yokeos.core.audit.ToolInvocationReader toolInvocationReader(
      ToolInvocationRepository repository) {
    return new JpaToolInvocationReader(repository);
  }

  @Bean
  ProviderService providerService(Map<String, ChatModel> providerMap, LlmCallAuditor auditor) {
    return new SpringAiProviderService(providerMap, new ToolSchemaAdapter(), auditor);
  }

  /** 启动扫描 .yokeos/agents/ 派生 Profile 注册（宪法 8；坏文件记错误日志不阻断——16 节行为）。 */
  @Bean
  ProfileRegistry profileRegistry(Map<String, ChatModel> providerMap) {
    AgentLoader agentLoader = new AgentLoader();
    ProfileRegistry registry = new ProfileRegistry();
    agentLoader.loadAll(workspace(), providerMap.keySet()).forEach(registry::register);
    return registry;
  }

  @Bean
  ContextLoader contextLoader() {
    return new ContextLoader(workspace());
  }

  /** 17 节工具集：http_get（20 节 ToolRegistry 就位后换 Map 来源，本 Bean 是唯一替换点）。 */
  @Bean
  Map<String, YokeTool> tools() {
    return Map.of("http_get", new HttpGetTool());
  }

  @Bean
  PromptBuilder promptBuilder(ContextLoader contextLoader, Map<String, YokeTool> tools) {
    return new PromptBuilder(contextLoader, tools, Clock.systemDefaultZone());
  }

  @Bean
  ToolExecutor toolExecutor(Map<String, YokeTool> tools, ToolInvocationAuditor auditor) {
    return new ToolExecutor(tools, auditor, 200L); // 退避基值同 17 节冒烟口径
  }

  @Bean
  ReActLoop reActLoop(
      PromptBuilder promptBuilder, ProviderService providerService, ToolExecutor toolExecutor) {
    return new ReActLoop(promptBuilder, providerService, toolExecutor);
  }

  @Bean
  SessionManager sessionManager(SessionRepository repository) {
    return new JpaSessionManager(repository);
  }

  @Bean
  AgentService agentService(
      ProfileRegistry profileRegistry, ReActLoop reActLoop, SessionManager sessionManager) {
    return new AgentService(profileRegistry, reActLoop, sessionManager);
  }

  @Bean
  CliChannel cliChannel(
      AgentService agentService,
      SessionManager sessionManager,
      com.yokeos.core.audit.ToolInvocationReader toolInvocationReader,
      ProfileRegistry profileRegistry) {
    return new CliChannel(agentService, sessionManager, toolInvocationReader, profileRegistry);
  }
}

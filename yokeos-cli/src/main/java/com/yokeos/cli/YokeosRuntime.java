package com.yokeos.cli;

import com.yokeos.channel.cli.CliChannel;
import com.yokeos.cli.command.ProviderListCommand;
import com.yokeos.core.agent.AgentScheduler;
import com.yokeos.core.agent.AgentService;
import com.yokeos.core.agent.PromptBuilder;
import com.yokeos.core.agent.ReActLoop;
import com.yokeos.core.agent.ScheduledTaskStore;
import com.yokeos.core.agent.ToolExecutor;
import com.yokeos.core.audit.LlmCallAuditor;
import com.yokeos.core.audit.ToolInvocationAuditor;
import com.yokeos.core.context.ContextLoader;
import com.yokeos.core.memory.MemoryService;
import com.yokeos.core.profile.AgentLoader;
import com.yokeos.core.profile.ProfileRegistry;
import com.yokeos.core.provider.ProviderService;
import com.yokeos.core.session.SessionManager;
import com.yokeos.core.tool.YokeTool;
import com.yokeos.memory.LongTermMemoryStore;
import com.yokeos.memory.MarkdownMemoryStore;
import com.yokeos.memory.Mem0MemoryStore;
import com.yokeos.memory.MemoryProperties;
import com.yokeos.memory.MemoryServiceImpl;
import com.yokeos.memory.SqliteMemoryStore;
import com.yokeos.memory.builtin.MemoryTools;
import com.yokeos.provider.MockChatModel;
import com.yokeos.provider.ProvidersProperties;
import com.yokeos.provider.SpringAiProviderService;
import com.yokeos.provider.ToolSchemaAdapter;
import com.yokeos.storage.JpaLlmCallAuditor;
import com.yokeos.storage.JpaScheduledTaskStore;
import com.yokeos.storage.JpaSessionManager;
import com.yokeos.storage.JpaToolInvocationAuditor;
import com.yokeos.storage.JpaToolInvocationReader;
import com.yokeos.storage.LlmCallRepository;
import com.yokeos.storage.MemoryEntryRepository;
import com.yokeos.storage.ScheduledTaskRepository;
import com.yokeos.storage.SessionRepository;
import com.yokeos.storage.TaskExecutionRepository;
import com.yokeos.storage.ToolInvocationRepository;
import com.yokeos.tool.NotifyTools;
import com.yokeos.tool.ToolRegistry;
import com.yokeos.tool.builtin.FileTools;
import com.yokeos.tool.builtin.HttpTools;
import com.yokeos.tool.builtin.ShellTools;
import com.yokeos.tool.mcp.McpClientService;
import com.yokeos.tool.mcp.McpConfigLoader;
import com.yokeos.tool.notify.WebhookNotifyAdapter;
import com.yokeos.tool.sandbox.Sandbox;
import com.yokeos.tool.sandbox.SandboxProperties;
import com.yokeos.tool.sandbox.WhitelistSandbox;
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
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

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
      if (ProvidersProperties.MOCK_PROVIDER_NAME.equals(item.getName())) {
        map.put(item.getName(), new MockChatModel()); // 不连真实端点，无需 key/url（显式配置形态）
        continue;
      }
      OpenAiApi api =
          OpenAiApi.builder()
              .baseUrl(item.getBaseUrl())
              .apiKey(resolvePlaceholder(item.getApiKey()))
              .build();
      // model 不设默认值：随 Profile 逐请求传递（技 §3.3；SpringAiProviderService.buildOptions 18 节补齐）
      map.put(item.getName(), OpenAiChatModel.builder().openAiApi(api).build());
    }
    // 内置保留名常挂（第 27 节拍板②）：mock 不进生产清单，无条件挂进显式映射表——
    // 无 key 全链路自测随时可用；/info 列「Profile 引用到的 provider」，无 Agent 用它就不出现。
    map.putIfAbsent(ProvidersProperties.MOCK_PROVIDER_NAME, new MockChatModel());
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

  /**
   * 启动扫描 .yokeos/agents/ 派生 Profile 注册（宪法 8；坏文件记错误日志不阻断——16 节行为）。 tools 点名传注册面 keySet 做存在性 WARN（20
   * 节拍板⑥，analyze F1：注入现成 tools() Bean）。
   */
  @Bean
  ProfileRegistry profileRegistry(Map<String, ChatModel> providerMap, Map<String, YokeTool> tools) {
    AgentLoader agentLoader = new AgentLoader();
    ProfileRegistry registry = new ProfileRegistry();
    agentLoader
        .loadAll(workspace(), providerMap.keySet(), tools.keySet())
        .forEach(registry::register);
    return registry;
  }

  @Bean
  ContextLoader contextLoader() {
    return new ContextLoader(workspace());
  }

  /**
   * 24 节沙箱（specs/008 D5，宪法 3 同精神——显式构造不进组件扫描）：classpath yaml 原文读三组白名单（22 节 MemoryProperties
   * 同款）；file 组为空时代码补当前工作区根（{@code yokeos.root} 解析值——坑七：工作区随启动目录动态， 静态 yaml 写不了；单一事实源，集成测试把工作区指到
   * TempDir 时白名单自动跟上）。shell/http 组缺省空 = deny-all（配置 注释载明），覆盖 file 组时须自行包含工作区（配置自洽责任随覆盖转移，坑六）。
   */
  @Bean
  Sandbox sandbox() {
    SandboxProperties properties =
        SandboxProperties.load(YokeosRuntime.class.getResourceAsStream("/application.yaml"));
    java.util.List<String> paths =
        properties.allowedPaths().isEmpty()
            ? java.util.List.of(workspace().toString())
            : properties.allowedPaths();
    return new WhitelistSandbox(
        new SandboxProperties(paths, properties.allowedCommands(), properties.allowedDomains()));
  }

  /**
   * 20 节 ToolRegistry 统一注册面（17 节预告的替换兑现）：内置三组注解注册 + notify 直接注册 + MCP server 工具接入（.yokeos/
   * mcp_servers.yaml，失联只 WARN 不拖垮启动）， 22 节补记忆两件（save_memory / recall_memory，specs/006 裁决二—— 随能力三落位
   * memory 模块、注册进注册面一视同仁）， 24 节四件经构造注入过沙箱（specs/008 D7：enforce 落点在动作发生处）。 26 节起注册表本身即 Bean（web 层
   * GET /api/v1/tools 的注入目标——注册表是唯一真相源，research D6）： 既有 {@code tools} Map Bean
   * 改由本注册表派生，PromptBuilder / ToolExecutor 的既有消费形态零变化。
   */
  @Bean
  ToolRegistry toolRegistry(MemoryEntryRepository memoryEntryRepository) {
    Sandbox sandbox = sandbox();
    ToolRegistry registry = new ToolRegistry();
    registry.registerAnnotated(new FileTools(sandbox));
    registry.registerAnnotated(new ShellTools(sandbox));
    registry.registerAnnotated(new HttpTools(sandbox));
    registry.registerAnnotated(new MemoryTools(memoryService(memoryEntryRepository)));
    registry.register(new NotifyTools(Map.of("webhook", new WebhookNotifyAdapter()), sandbox));
    new McpClientService(new McpConfigLoader(workspace().resolve("mcp_servers.yaml")))
        .connectAll(registry);
    return registry;
  }

  @Bean
  Map<String, YokeTool> tools(ToolRegistry toolRegistry) {
    return toolRegistry.asMap(); // 17 节起 PromptBuilder/ToolExecutor 的既有消费形态（Map 注入）不变
  }

  /**
   * 22 节记忆门面（US3 选档版）：按 {@code yokeos.memory.backend} 三选一构造后端注入同一 {@link MemoryServiceImpl}——
   * markdown（缺省，{@code .yokeos/memory/}）/ sqlite（memory_entries 表）/ mem0（自托管 REST）。换档只改配置行，
   * PromptBuilder 与 MemoryTools 不动（接口墙的价值兑现）。配置经 classpath yaml 原文读取（MemoryProperties——占位不提前解析， 16
   * 节 provider 同款策略）；未知名启动即点名报错，不静默回退。
   */
  @Bean
  MemoryService memoryService(MemoryEntryRepository memoryEntryRepository) {
    MemoryProperties properties =
        MemoryProperties.load(YokeosRuntime.class.getResourceAsStream("/application.yaml"));
    String backend = properties.backend();
    LongTermMemoryStore store;
    if (MemoryProperties.BACKEND_MARKDOWN.equals(backend)) {
      store =
          new MarkdownMemoryStore(
              workspace().resolve("memory"), properties.archiveMaxChars(), sandbox());
    } else if (MemoryProperties.BACKEND_SQLITE.equals(backend)) {
      store = new SqliteMemoryStore(memoryEntryRepository, properties.archiveMaxRows());
    } else if (MemoryProperties.BACKEND_MEM0.equals(backend)) {
      store =
          new Mem0MemoryStore(
              properties.mem0().getOrDefault("base-url", ""),
              properties.mem0().getOrDefault("api-key", ""),
              sandbox()); // 域名白名单须含 mem0 host（D9 配置自洽的 mem0 半边）
    } else {
      throw new IllegalStateException("未知的记忆后端: " + backend + "（应为 markdown / sqlite / mem0）");
    }
    return new MemoryServiceImpl(store);
  }

  @Bean
  PromptBuilder promptBuilder(
      ContextLoader contextLoader, Map<String, YokeTool> tools, MemoryService memoryService) {
    return new PromptBuilder(contextLoader, tools, Clock.systemDefaultZone(), memoryService);
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

  /**
   * 调度线程池（宪法 4「全程同步」的唯一明文例外：第 25 节 ThreadPoolTaskScheduler）。daemon 是刚需： chat 这类一次性命令跑完后 JVM
   * 必须能退，不被调度线程挂住（坑七）；chat 会话期间到点的任务也会真触发一次（与参照行为一致）。
   */
  @Bean
  ThreadPoolTaskScheduler taskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(2);
    scheduler.setThreadNamePrefix("yokeos-sched-");
    scheduler.setDaemon(true);
    scheduler.initialize();
    return scheduler;
  }

  /** 任务状态与执行历史落 SQLite（第 25 节）：契约在 core，JPA 实现在 storage（依赖倒置）。 */
  @Bean
  ScheduledTaskStore scheduledTaskStore(
      ScheduledTaskRepository tasks, TaskExecutionRepository executions) {
    return new JpaScheduledTaskStore(tasks, executions);
  }

  /**
   * 第三触发源「钟推」（第 25 节）：initMethod=registerAll——依赖注入完成后启动即扫描全部 Profile.schedules 逐条注册（ProfileRegistry
   * 已在装配期填充）；定时任务随 serve/gateway 常驻调度（技 §8.6）。
   */
  @Bean(initMethod = "registerAll")
  AgentScheduler agentScheduler(
      ThreadPoolTaskScheduler taskScheduler,
      ProfileRegistry profileRegistry,
      AgentService agentService,
      SessionManager sessionManager,
      ScheduledTaskStore scheduledTaskStore) {
    return new AgentScheduler(
        taskScheduler, profileRegistry, agentService, sessionManager, scheduledTaskStore);
  }
}

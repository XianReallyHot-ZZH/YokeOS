package com.yokeos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yokeos.cli.InitCommand;
import com.yokeos.core.agent.AgentService;
import com.yokeos.core.agent.PromptBuilder;
import com.yokeos.core.agent.ReActLoop;
import com.yokeos.core.agent.ToolExecutor;
import com.yokeos.core.context.ContextLoader;
import com.yokeos.core.memory.MemoryService;
import com.yokeos.core.profile.Profile;
import com.yokeos.core.profile.ProfileRegistry;
import com.yokeos.core.session.InMemorySessionManager;
import com.yokeos.core.session.Session;
import com.yokeos.core.tool.YokeTool;
import com.yokeos.memory.InMemoryMemoryStore;
import com.yokeos.memory.MemoryServiceImpl;
import com.yokeos.provider.SpringAiProviderService;
import com.yokeos.provider.ToolSchemaAdapter;
import com.yokeos.storage.JpaLlmCallAuditor;
import com.yokeos.storage.JpaToolInvocationAuditor;
import com.yokeos.storage.LlmCallRepository;
import com.yokeos.storage.ToolInvocationRepository;
import com.yokeos.tool.NotifyTools;
import com.yokeos.tool.ToolRegistry;
import com.yokeos.tool.builtin.FileTools;
import com.yokeos.tool.builtin.HttpTools;
import com.yokeos.tool.builtin.ShellTools;
import com.yokeos.tool.mcp.McpClientService;
import com.yokeos.tool.mcp.McpConfigLoader;
import com.yokeos.tool.notify.WebhookNotifyAdapter;
import com.yokeos.tool.sandbox.SandboxProperties;
import com.yokeos.tool.sandbox.WhitelistSandbox;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.sqlite.SQLiteDataSource;

/**
 * 第 20 节综合端到端（{@code @Tag("integration")}，显式触发 + 环境缺失跳过）：真模型一次对话串联本节全部交付物——
 * 既是验收（每件工具的审计行逐个点名断言），也是代码导读（步骤注释对应实现类，链路图如下）。
 *
 * <p>链路（← 后为每步对应的第 20 节实现，读代码按图索骥）：
 *
 * <pre>
 * InitCommand.initWorkspace ──────────────── ← InitCommand（FR-011：工作区 + mcp_servers.yaml 模板）
 * mcp_servers.yaml 覆写（everything server）
 * AgentLoader.loadAll(…, 注册面 keySet) ──── ← AgentLoader 四参重载（拍板⑥：点名有痕校验）
 * ToolRegistry 组装 ──────────────────────── ← registerAnnotated ×3 + register(NotifyTools)
 *   ├ FileTools/ShellTools/HttpTools ────── ← builtin 包（@Tool 注解管道，拍板②）
 *   │    └ AnnotatedToolAdapter ─────────── ← MethodToolCallbackProvider 仅 schema 生成（宪法 2）
 *   └ McpClientService.connectAll ───────── ← mcp 包（真 stdio 子进程 + 双层容错）
 *        └ McpToolAdapter（echo）────────── ← 参数原样转发 + isError 可重试
 * PromptBuilder + ToolExecutor（asMap）──── ← 两消费方只认注册面（来源无感知）
 * ReActLoop → 真 DeepSeek 多步任务 ─────────  ← 模型按 tools 清单点名调四件内置 + MCP
 * tool_invocations / llm_calls 双审计 ───── ← 零新增审计逻辑（宪法 7）
 * </pre>
 *
 * <p>任务强引导四种工具（write_file → list_dir → read_file → shell argv 直传）； http_get 在册被点名但不引导（17
 * 节冒烟已真链覆盖）、notify 在册不点名（无渠道配置，19 节已覆盖）—— 七件内置的在册状态与点名过滤语义一并断言（MCP 工具数随 server 版本变化，实测 everything
 * 暴露 13 件）。
 *
 * <p>跑法（npx 冷缓存首跑可能 skip，二跑缓存热即绿——CLAUDE.md 陷阱表）：
 *
 * <pre>
 * DEEPSEEK_API_KEY=xxx mvn -pl yokeos-boot -am test \
 *     -Dgroups=integration -DexcludedGroups= -Dtest='ToolSystemEndToEndIntegrationTest'
 * </pre>
 */
@Tag("integration")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ToolSystemEndToEndIntegrationTest.TestJpaConfig.class)
class ToolSystemEndToEndIntegrationTest {

  private static final String DEEPSEEK_KEY = System.getenv("DEEPSEEK_API_KEY");

  /** 表结构唯一权威是 storage 模块的手工脚本（宪法 7）。 */
  private static final String SCHEMA_PATH = "/db/schema-001-audit.sql";

  private static final String JDBC_URL = schemaBackedSqlite();

  /** 断言必须见到审计留痕的四种工具（任务强引导；缺一件即红——这正是本测试的价值）。 */
  private static final List<String> REQUIRED_TOOLS =
      List.of("write_file", "list_dir", "read_file", "shell");

  @Autowired LlmCallRepository llmCallRepository;

  @Autowired ToolInvocationRepository toolInvocationRepository;

  @TempDir Path workspace;

  @BeforeAll
  static void requireRealKeyAndNpx() throws Exception {
    Assumptions.assumeTrue(
        DEEPSEEK_KEY != null && !DEEPSEEK_KEY.isBlank(),
        "需要环境变量 DEEPSEEK_API_KEY 才能真调（缺 key 时跳过而非失败）");
    Process check = new ProcessBuilder("npx", "--version").start();
    Assumptions.assumeTrue(
        check.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) && check.exitValue() == 0,
        "npx 不可用（MCP server 子进程起不来）——跳过而非失败");
  }

  @Test
  @DisplayName("真模型一次对话串联20节全部交付物_四件内置加MCP全程留痕")
  void realModelChainsAllLessonDeliverables() throws Exception {
    // ── 步骤 1：工作区骨架（InitCommand，FR-011——六目录 + 三 Bootstrap + mcp_servers.yaml 注释模板）
    new InitCommand().initWorkspace(workspace);

    // 覆写 MCP 配置：真 everything server（stdio 子进程，无需 key）
    Files.writeString(
        workspace.resolve("mcp_servers.yaml"),
        "servers:\n"
            + "  - name: everything\n"
            + "    transport: stdio\n"
            + "    command: npx -y @modelcontextprotocol/server-everything\n");

    // AGENT.md：tools 清单点名六件 + MCP echo（notify 不点名——点名过滤的语义面）
    Files.createDirectories(workspace.resolve("agents").resolve("file-agent"));
    Files.writeString(
        workspace.resolve("agents").resolve("file-agent").resolve("AGENT.md"),
        "---\n"
            + "name: file-agent\n"
            + "identity:\n"
            + "  agent_name: 文件助手\n"
            + "  prompt: 你是文件工作流演示助手，必须按用户给的步骤逐一调用工具完成，不得凭空作答。\n"
            + "provider:\n"
            + "  name: deepseek\n"
            + "  model: deepseek-flash\n"
            + "tools:\n"
            + "  - write_file\n"
            + "  - list_dir\n"
            + "  - read_file\n"
            + "  - shell\n"
            + "  - http_get\n"
            + "  - echo\n"
            + "---\n"
            + "用户给出多步任务时，逐步调用对应工具：写文件用 write_file、确认目录用 list_dir、"
            + "读回用 read_file、统计行数用 shell（参数是 argv 数组，例如 [\"wc\",\"-l\",\"<文件>\"]）。"
            + "每步拿到工具结果后继续下一步，全部完成后汇总各步结果作答。\n");

    // ── 步骤 2：注册面组装（YokeosRuntime.tools() 同配方——三来源汇合，来源无感知；24 节起内置件过白名单）
    WhitelistSandbox sandbox =
        e2eSandbox(
            java.util.List.of(workspace.toAbsolutePath().toString()),
            java.util.List.of("wc", "echo", "ls", "cat"),
            java.util.List.of("api.open-meteo.com"));
    ToolRegistry registry = new ToolRegistry();
    registry.registerAnnotated(new FileTools(sandbox)); // 注解管道（拍板②）
    registry.registerAnnotated(new ShellTools(sandbox));
    registry.registerAnnotated(new HttpTools(sandbox));
    registry.register(
        new NotifyTools(Map.of("webhook", new WebhookNotifyAdapter()), sandbox)); // 直接实现
    new McpClientService(new McpConfigLoader(workspace.resolve("mcp_servers.yaml")))
        .connectAll(registry); // MCP 来源（真子进程 + 双层容错）
    // 注册面下界断言：七内置 + echo 必须在册（MCP 工具数随 everything server 版本变化，不断言精确值——实测 13 件）
    assertTrue(
        registry
            .asMap()
            .keySet()
            .containsAll(
                List.of(
                    "read_file",
                    "write_file",
                    "list_dir",
                    "shell",
                    "http_get",
                    "http_post",
                    "notify",
                    "echo")),
        "注册面必须含七件内置 + MCP echo（实际: " + registry.asMap().keySet() + "）");
    assertTrue(registry.asMap().size() >= 8, "注册面至少 8 件");
    Map<String, YokeTool> tools = registry.asMap();

    // ── 步骤 3：Profile 派生（AgentLoader 四参重载——tools 点名走有痕校验路径，拍板⑥）
    List<Profile> profiles =
        new com.yokeos.core.profile.AgentLoader()
            .loadAll(workspace, Set.of("deepseek"), tools.keySet());
    assertEquals(1, profiles.size(), "file-agent 必须派生成功");
    assertEquals(6, profiles.get(0).tools().size(), "tools 清单六件原样承载");

    // ── 步骤 4：全链装配（17 节既有消费方——只认 Map，不感知来源）
    OpenAiApi api =
        OpenAiApi.builder().baseUrl("https://api.deepseek.com").apiKey(DEEPSEEK_KEY).build();
    ChatModel deepseek =
        OpenAiChatModel.builder()
            .openAiApi(api)
            .defaultOptions(OpenAiChatOptions.builder().model("deepseek-flash").build())
            .build();
    SpringAiProviderService provider =
        new SpringAiProviderService(
            Map.of("deepseek", deepseek),
            new ToolSchemaAdapter(),
            new JpaLlmCallAuditor(llmCallRepository));
    ToolExecutor executor =
        new ToolExecutor(tools, new JpaToolInvocationAuditor(toolInvocationRepository), 200L);
    PromptBuilder promptBuilder =
        new PromptBuilder(
            new ContextLoader(workspace), tools, Clock.systemDefaultZone(), memoryService());
    ProfileRegistry profileRegistry = new ProfileRegistry();
    profileRegistry.register(profiles.get(0));
    AgentService agentService =
        new AgentService(
            profileRegistry,
            new ReActLoop(promptBuilder, provider, executor),
            new InMemorySessionManager());

    // ── 步骤 5：真模型多步任务（绝对路径入消息——模型不猜路径；shell 直接给 argv 数组形态）
    Path outputDir = workspace.resolve("output").toAbsolutePath();
    Path daily = outputDir.resolve("daily.md");
    String task =
        "请逐步完成文件工作流："
            + "1) 用 write_file 把四行内容写入 "
            + daily
            + "（依次为：# 日报、- 第一条、- 第二条、- 第三条）；"
            + "2) 用 list_dir 列出 "
            + outputDir
            + " 确认文件存在；"
            + "3) 用 read_file 读回 "
            + daily
            + "；"
            + "4) 用 shell 执行 [\"wc\",\"-l\",\""
            + daily
            + "\"] 统计行数；完成后汇总各步结果。";

    Session session = new Session("e2e-tool-20", "file-agent");
    String reply = agentService.process(session, task);

    // ── 断言一：最终答复非空（多步循环的最终产物）
    assertNotNull(reply);
    assertFalse(reply.isBlank(), "多步循环必须给出最终答复");

    // ── 断言二：write_file 的真实副作用落盘（工具真干了活，不只是口头答复）
    assertTrue(Files.isRegularFile(daily), "文件必须真实写入 output/daily.md");
    assertTrue(Files.readString(daily).contains("日报"), "内容含任务指定的标题（实际: 已写入文件）");

    // ── 断言三：四件工具逐一审计留痕（本测试的核心判据——缺一件即红，实际审计行打进失败消息便于 debug）
    var rows = toolInvocationRepository.findBySessionId("e2e-tool-20");
    for (String toolName : REQUIRED_TOOLS) {
      assertTrue(
          rows.stream()
              .anyMatch(
                  r -> toolName.equals(r.getToolName()) && Boolean.TRUE.equals(r.getSuccess())),
          "工具 "
              + toolName
              + " 的成功调用必须留痕；实际审计行: "
              + rows.stream().map(r -> r.getToolName() + "(" + r.getSuccess() + ")").toList());
    }

    // ── 断言四：llm_calls 有本次会话记录（每轮 LLM 调用留痕，宪法 7）
    assertFalse(llmCallRepository.findAll().isEmpty(), "llm_calls 必须有本次会话的调用记录");
  }

  @Test
  @DisplayName("真链路越权动作_拦截留痕一条")
  void realModelUnauthorizedActionInterceptedAndAudited() throws Exception {
    // 24 节 D6 端到端（教学文档第四部分 harness 表末行）：真模型强引导调 http_get，域名白名单不含目标域——
    // 拒绝发生在请求发出之前（坑五），恰落一条 tool_invocations（success=false + 拒绝消息，坑二），模型下一轮可见
    new InitCommand().initWorkspace(workspace);
    Files.createDirectories(workspace.resolve("agents").resolve("web-agent"));
    Files.writeString(
        workspace.resolve("agents").resolve("web-agent").resolve("AGENT.md"),
        "---\n"
            + "name: web-agent\n"
            + "identity:\n"
            + "  agent_name: 网页助手\n"
            + "  prompt: 你是网页抓取演示助手，必须先用 http_get 完成抓取再作答，不得凭空作答。\n"
            + "provider:\n"
            + "  name: deepseek\n"
            + "  model: deepseek-flash\n"
            + "tools:\n"
            + "  - http_get\n"
            + "---\n"
            + "用户要求抓取网页时，必须先调用 http_get 访问用户给的 URL，拿到结果后总结作答。\n");

    // 域名白名单只含 open-meteo——example.com 在白名单外（deny 语义由白名单说了算）
    WhitelistSandbox sandbox =
        e2eSandbox(
            java.util.List.of(workspace.toAbsolutePath().toString()),
            java.util.List.of(),
            java.util.List.of("api.open-meteo.com"));
    ToolRegistry registry = new ToolRegistry();
    registry.registerAnnotated(new HttpTools(sandbox));
    Map<String, YokeTool> tools = registry.asMap();
    List<Profile> profiles =
        new com.yokeos.core.profile.AgentLoader()
            .loadAll(workspace, Set.of("deepseek"), tools.keySet());
    assertEquals(1, profiles.size(), "web-agent 必须派生成功");

    OpenAiApi api =
        OpenAiApi.builder().baseUrl("https://api.deepseek.com").apiKey(DEEPSEEK_KEY).build();
    ChatModel deepseek =
        OpenAiChatModel.builder()
            .openAiApi(api)
            .defaultOptions(OpenAiChatOptions.builder().model("deepseek-flash").build())
            .build();
    SpringAiProviderService provider =
        new SpringAiProviderService(
            Map.of("deepseek", deepseek),
            new ToolSchemaAdapter(),
            new JpaLlmCallAuditor(llmCallRepository));
    ToolExecutor executor =
        new ToolExecutor(tools, new JpaToolInvocationAuditor(toolInvocationRepository), 200L);
    PromptBuilder promptBuilder =
        new PromptBuilder(
            new ContextLoader(workspace), tools, Clock.systemDefaultZone(), memoryService());
    ProfileRegistry profileRegistry = new ProfileRegistry();
    profileRegistry.register(profiles.get(0));
    AgentService agentService =
        new AgentService(
            profileRegistry,
            new ReActLoop(promptBuilder, provider, executor),
            new InMemorySessionManager());

    Session session = new Session("e2e-sandbox-24", "web-agent");
    String reply = agentService.process(session, "请用 http_get 抓取 https://example.com/ 的页面内容并总结。");

    // 拒绝对模型可见：循环未炸、最终答复仍在（模型看到失败原因后作答）
    assertNotNull(reply);
    assertFalse(reply.isBlank(), "拒绝不炸循环——模型基于失败结果继续作答");

    // 恰一条 http_get 拒绝审计：success=false + error_message 是沙箱拒绝消息（不可重试，一次即止）
    var rows = toolInvocationRepository.findBySessionId("e2e-sandbox-24");
    var denials =
        rows.stream()
            .filter(r -> "http_get".equals(r.getToolName()) && !Boolean.TRUE.equals(r.getSuccess()))
            .toList();
    assertFalse(denials.isEmpty(), "http_get 的拒绝必须留痕（实际审计行: " + rows.size() + " 条）");
    assertTrue(
        denials.stream()
            .allMatch(r -> r.getErrorMessage() != null && r.getErrorMessage().contains("域名不在白名单内")),
        "error_message 是沙箱拒绝消息且对模型可读");
  }

  private static String schemaBackedSqlite() {
    try {
      Path db = Files.createTempFile("yokeos-e2e-tool-20", ".db");
      db.toFile().deleteOnExit();
      String url = "jdbc:sqlite:" + db.toAbsolutePath();
      try (Connection connection = DriverManager.getConnection(url)) {
        ScriptUtils.executeSqlScript(connection, new ClassPathResource(SCHEMA_PATH));
      }
      return url;
    } catch (Exception e) {
      throw new IllegalStateException("端到端测试库初始化失败", e);
    }
  }

  /** 与 NotifyEndToEndIntegrationTest 同款最小 JPA 配置（仅审计两表）。 */
  @Configuration
  @EnableJpaRepositories(basePackages = "com.yokeos.storage")
  @EntityScan(basePackages = "com.yokeos.storage")
  static class TestJpaConfig {

    @Bean
    DataSource dataSource() {
      SQLiteDataSource dataSource = new SQLiteDataSource();
      dataSource.setUrl(JDBC_URL);
      return dataSource;
    }

    @Bean
    LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
      LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
      em.setDataSource(dataSource);
      em.setPackagesToScan("com.yokeos.storage");
      em.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
      Map<String, Object> properties = new java.util.HashMap<>();
      properties.put("hibernate.hbm2ddl.auto", "none");
      em.setJpaPropertyMap(properties);
      return em;
    }

    @Bean
    PlatformTransactionManager transactionManager(
        EntityManagerFactory entityManagerFactory, DataSource dataSource) {
      return new JpaTransactionManager(entityManagerFactory);
    }
  }

  /** 22 节构造器扩展：这些测试不测记忆，注入进程内轻量档（零文件副作用）。 */
  private static MemoryService memoryService() {
    return new MemoryServiceImpl(new InMemoryMemoryStore());
  }

  /** 24 节：本测试装配用的白名单沙箱（与 YokeosRuntime.sandbox() 同款构造，条目按测试目标给）。 */
  private static WhitelistSandbox e2eSandbox(
      java.util.List<String> paths,
      java.util.List<String> commands,
      java.util.List<String> domains) {
    return new WhitelistSandbox(new SandboxProperties(paths, commands, domains));
  }
}

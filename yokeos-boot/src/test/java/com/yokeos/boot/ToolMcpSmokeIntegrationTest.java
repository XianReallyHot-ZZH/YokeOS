package com.yokeos.boot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.yokeos.tool.builtin.HttpTools;
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
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
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
 * 第 20 节集成冒烟（{@code @Tag("integration")}，显式触发 + 环境缺失跳过）：真 stdio MCP server（npx everything， 无 key）+
 * 真 DeepSeek 点名调用其工具——「接入外部 MCP server」可演示成果的自动化口径（需 §11 第 20 节行）。
 *
 * <p>链路：mcp_servers.yaml（临时工作区）→ McpClientService.connectAll 真连子进程 → MCP 工具进注册面 → AGENT.md 强指令引导
 * 模型点名调 echo → tool_invocations 落账。跑法：
 *
 * <pre>
 * DEEPSEEK_API_KEY=xxx mvn -pl yokeos-boot -am test \
 *     -Dgroups=integration -DexcludedGroups= -Dtest='ToolMcpSmokeIntegrationTest'
 * </pre>
 */
@Tag("integration")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ToolMcpSmokeIntegrationTest.TestJpaConfig.class)
class ToolMcpSmokeIntegrationTest {

  private static final String DEEPSEEK_KEY = System.getenv("DEEPSEEK_API_KEY");

  /** 表结构唯一权威是 storage 模块的手工脚本（宪法 7）。 */
  private static final String SCHEMA_PATH = "/db/schema-001-audit.sql";

  private static final String JDBC_URL = schemaBackedSqlite();

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

  @AfterAll
  static void interruptReminder() {
    // MCP 子进程生命周期由 SDK 管理（McpToolAdapter 持连接引用，进程随 JVM 退出回收）
  }

  @Test
  @DisplayName("真stdio_server工具进注册面_真模型点名调用全程留痕")
  void realMcpServerToolsReachModelAndAudit() throws Exception {
    writeWorkspaceWithMcpAgent();

    // 注册面：内置 http_get + notify + 真 MCP server（npx everything 的 echo 等工具；24 节起内置件过白名单）
    WhitelistSandbox sandbox =
        e2eSandbox(
            java.util.List.of(workspace.toAbsolutePath().toString()),
            java.util.List.of(),
            java.util.List.of("api.open-meteo.com"));
    ToolRegistry registry = new ToolRegistry();
    registry.registerAnnotated(new HttpTools(sandbox));
    registry.register(new NotifyTools(Map.of("webhook", new WebhookNotifyAdapter()), sandbox));
    new McpClientService(new McpConfigLoader(workspace.resolve("mcp_servers.yaml")))
        .connectAll(registry);
    int mcpToolCount =
        (int)
            registry.all().stream()
                .filter(t -> !"http_get".equals(t.getName()) && !"notify".equals(t.getName()))
                .count();
    assertTrue(mcpToolCount >= 1, "MCP server 的工具必须进注册面（实际 " + registry.all() + "）");
    var echoTool = registry.all().stream().filter(t -> "echo".equals(t.getName())).findFirst();
    Assumptions.assumeTrue(echoTool.isPresent(), "everything server 版本未暴露 echo 工具——锚点缺失跳过而非失败");
    Map<String, YokeTool> tools = registry.asMap();

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
    Profile profile = mcpAgentProfile();
    profileRegistry.register(profile);
    AgentService agentService =
        new AgentService(
            profileRegistry,
            new ReActLoop(promptBuilder, provider, executor),
            new InMemorySessionManager());

    Session session = new Session("smoke-mcp-1", "mcp-agent");
    String reply = agentService.process(session, "请调用 echo 工具，内容原样用：mcp冒烟20节");

    // 断言一：答复非空（模型看到并使用了 MCP 工具后的最终产物）
    assertNotNull(reply);
    assertFalse(reply.isBlank(), "多步循环必须给出最终答复");

    // 断言二：echo 的调用在审计表留痕（success 态——MCP 转发真实发生过）
    var rows = toolInvocationRepository.findBySessionId("smoke-mcp-1");
    assertTrue(
        rows.stream()
            .anyMatch(r -> "echo".equals(r.getToolName()) && Boolean.TRUE.equals(r.getSuccess())),
        "MCP echo 工具的成功调用必须留痕（实际 " + rows.size() + " 行）");

    // 断言三：llm_calls 有本次调用记录
    assertFalse(llmCallRepository.findAll().isEmpty(), "llm_calls 必须有本次会话的调用记录");
  }

  /** 最小工作区：Bootstrap 三件 + mcp_servers.yaml（everything server）+ mcp-agent 的 AGENT.md。 */
  private void writeWorkspaceWithMcpAgent() throws Exception {
    Files.writeString(workspace.resolve("AGENTS.md"), "测试工作区约定");
    Files.writeString(workspace.resolve("SOUL.md"), "你是简洁实用的助手");
    Files.writeString(workspace.resolve("USER.md"), "无特殊偏好");
    Files.writeString(
        workspace.resolve("mcp_servers.yaml"),
        "servers:\n"
            + "  - name: everything\n"
            + "    transport: stdio\n"
            + "    command: npx -y @modelcontextprotocol/server-everything\n");
    Path agentDir = workspace.resolve("agents").resolve("mcp-agent");
    Files.createDirectories(agentDir);
    Files.writeString(
        agentDir.resolve("AGENT.md"),
        "---\n"
            + "name: mcp-agent\n"
            + "identity:\n"
            + "  agent_name: MCP助手\n"
            + "  prompt: 你是演示助手，被要求回显内容时必须先调用 echo 工具再作答。\n"
            + "provider:\n"
            + "  name: deepseek\n"
            + "  model: deepseek-flash\n"
            + "tools:\n"
            + "  - echo\n"
            + "  - http_get\n"
            + "---\n"
            + "用户要求回显内容时，必须先调用 echo 工具（把用户给的内容原样传入）拿到结果，再基于结果简短作答。\n");
  }

  private static Profile mcpAgentProfile() {
    return new Profile(
        "mcp-agent",
        "MCP 冒烟助手",
        new Profile.Identity("MCP助手", "你是演示助手，被要求回显内容时必须先调用 echo 工具再作答。"),
        new Profile.ProviderConfig("deepseek", "deepseek-flash", null),
        java.util.List.of("echo", "http_get"),
        java.util.List.of(),
        java.util.List.of(),
        java.util.List.of(),
        java.util.List.of(),
        java.util.List.of(),
        java.util.List.of(),
        Profile.Settings.DEFAULT);
  }

  private static String schemaBackedSqlite() {
    try {
      Path db = Files.createTempFile("yokeos-mcp-smoke", ".db");
      db.toFile().deleteOnExit();
      String url = "jdbc:sqlite:" + db.toAbsolutePath();
      try (Connection connection = DriverManager.getConnection(url)) {
        ScriptUtils.executeSqlScript(connection, new ClassPathResource(SCHEMA_PATH));
      }
      return url;
    } catch (Exception e) {
      throw new IllegalStateException("冒烟库初始化失败", e);
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

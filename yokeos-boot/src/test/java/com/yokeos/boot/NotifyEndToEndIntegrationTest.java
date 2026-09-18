package com.yokeos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yokeos.core.agent.AgentService;
import com.yokeos.core.agent.PromptBuilder;
import com.yokeos.core.agent.ReActLoop;
import com.yokeos.core.agent.ToolExecutor;
import com.yokeos.core.context.ContextLoader;
import com.yokeos.core.memory.MemoryService;
import com.yokeos.core.profile.AgentLoader;
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
import com.yokeos.tool.notify.WebhookNotifyAdapter;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
 * 第 19 节补强·真模型端到端（{@code @Tag("integration")}，显式触发 + 缺 key 跳过）：串起本节全部接缝—— AGENT.md
 * frontmatter（notify.channels 带 ${TEAM_WEBHOOK_URL} 占位）→ AgentLoader 真派生 → AgentService.process set
 * ProfileContext → 真 DeepSeek 决定调 notify → ToolExecutor → NotifyTools 渠道解析 → WebhookNotifyAdapter
 * 真发 HTTP POST → 接收端收到 → 双审计落 SQLite。
 *
 * <p>与 17 节冒烟的差异：Profile 不再代码构造而是从写下的 AGENT.md 真派生（占位解析入链）；notify 链 + ProfileContext
 * 接缝首次入端到端。YokeOS 侧全链真实零 mock——唯一替身是本地 HttpServer 接收端（扮演群机器人：自动化测试读不到真群， 真群机器人仍是人工项，教学文档坑三）。跑法：
 *
 * <pre>
 * DEEPSEEK_API_KEY=xxx mvn -pl yokeos-boot -am test \
 *     -Dgroups=integration -DexcludedGroups= -Dtest='NotifyEndToEndIntegrationTest'
 * </pre>
 */
@Tag("integration")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = NotifyEndToEndIntegrationTest.TestJpaConfig.class)
class NotifyEndToEndIntegrationTest {

  private static final String DEEPSEEK_KEY = System.getenv("DEEPSEEK_API_KEY");

  /** 表结构唯一权威是 storage 模块的手工脚本（宪法 7）。 */
  private static final String SCHEMA_PATH = "/db/schema-001-audit.sql";

  /** 推送内容标记：AGENT.md 强指令要求「原样推送」，接收端按它断言（模型输出不可控，锚定链路产物）。 */
  private static final String PUSH_MARKER = "19节端到端推送验证-7f3a";

  private static final String JDBC_URL = schemaBackedSqlite();

  @Autowired LlmCallRepository llmCallRepository;

  @Autowired ToolInvocationRepository toolInvocationRepository;

  @TempDir Path workspace;

  private HttpServer webhookReceiver;
  private final List<String> receivedBodies = new ArrayList<>();

  @BeforeAll
  static void requireRealKey() {
    Assumptions.assumeTrue(
        DEEPSEEK_KEY != null && !DEEPSEEK_KEY.isBlank(),
        "需要环境变量 DEEPSEEK_API_KEY 才能真调（缺 key 时跳过而非失败）");
  }

  @BeforeEach
  void startWebhookReceiver() throws IOException {
    webhookReceiver = HttpServer.create(new InetSocketAddress(0), 0);
    webhookReceiver.createContext(
        "/hook",
        exchange -> {
          receivedBodies.add(readBody(exchange));
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    webhookReceiver.start();
  }

  @AfterEach
  void stopWebhookReceiver() {
    if (webhookReceiver != null) {
      webhookReceiver.stop(0);
    }
  }

  private static String readBody(HttpExchange exchange) throws IOException {
    return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
  }

  private String webhookUrl() {
    return "http://127.0.0.1:" + webhookReceiver.getAddress().getPort() + "/hook";
  }

  @Test
  @DisplayName("真模型端到端_frontmatter派生到群机器人收到全程留痕")
  void realModelNotifyPushFromFrontmatterToWebhook() throws Exception {
    writeWorkspaceWithNotifyAgent();
    String receiverUrl = webhookUrl();

    // 接缝一（17 节冒烟未覆盖）：Profile 从写下的 AGENT.md 真派生，${TEAM_WEBHOOK_URL} 占位入链解析
    List<Profile> profiles =
        new AgentLoader()
            .loadAll(workspace, Set.of("deepseek"), Map.of("TEAM_WEBHOOK_URL", receiverUrl)::get);
    assertEquals(1, profiles.size(), "notify-agent 必须派生成功");
    Profile profile = profiles.get(0);
    assertEquals(1, profile.notifyChannels().size(), "前置自检：渠道派生成功");
    assertEquals("ops-hook", profile.notifyChannels().get(0).name());
    assertEquals("webhook", profile.notifyChannels().get(0).type());
    assertEquals(receiverUrl, profile.notifyChannels().get(0).config().get("url"), "占位必须解析成接收端地址");

    // 全真实装配（与 YokeosRuntime 同形态：注册面取 http_get + notify 直接注册、双审计走 SQLite）
    ToolRegistry toolRegistry = new ToolRegistry();
    toolRegistry.registerAnnotated(new HttpTools());
    Map<String, YokeTool> tools =
        Map.of(
            "http_get",
            toolRegistry.get("http_get").orElseThrow(),
            "notify",
            new NotifyTools(Map.of("webhook", new WebhookNotifyAdapter())));
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
    ProfileRegistry registry = new ProfileRegistry();
    registry.register(profile);
    AgentService agentService =
        new AgentService(
            registry,
            new ReActLoop(promptBuilder, provider, executor),
            new InMemorySessionManager());

    // 真模型决策：AgentService.process 内部 set ProfileContext（notify 渠道解析的接缝二）
    Session session = new Session("smoke-notify-1", "notify-agent");
    String reply = agentService.process(session, "请调用 notify 工具，把下面这段内容原样推送出去：" + PUSH_MARKER);

    // 断言一：最终答复非空（思考 → 调 notify → 观察 → 续推的产物）
    assertNotNull(reply, "多步循环必须给出最终答复");
    assertFalse(reply.isBlank(), "答复非空");

    // 断言二：接收端真收到 POST 且 body 携带标记内容（整条出站链的物理证据）
    assertFalse(receivedBodies.isEmpty(), "群机器人替身必须收到至少一次推送");
    assertTrue(
        receivedBodies.stream().anyMatch(b -> b.contains(PUSH_MARKER)), "推送 body 必须携带被要求原样推送的内容");

    // 断言三：tool_invocations 有 notify 的 success=true 行（审计留痕）
    assertTrue(
        toolInvocationRepository.findBySessionId("smoke-notify-1").stream()
            .anyMatch(r -> Boolean.TRUE.equals(r.getSuccess()) && "notify".equals(r.getToolName())),
        "notify 的成功调用必须在审计表留痕");

    // 断言四：llm_calls 有本次调用记录
    assertFalse(llmCallRepository.findAll().isEmpty(), "llm_calls 必须有本次会话的调用记录");
  }

  /** 最小工作区：Bootstrap 三件 + notify-agent 的 AGENT.md（frontmatter 渠道占位 + 正文强指令引导模型调 notify）。 */
  private void writeWorkspaceWithNotifyAgent() throws Exception {
    Files.writeString(workspace.resolve("AGENTS.md"), "测试工作区约定");
    Files.writeString(workspace.resolve("SOUL.md"), "你是简洁实用的助手");
    Files.writeString(workspace.resolve("USER.md"), "无特殊偏好");
    Path agentDir = workspace.resolve("agents").resolve("notify-agent");
    Files.createDirectories(agentDir);
    Files.writeString(
        agentDir.resolve("AGENT.md"),
        "---\n"
            + "name: notify-agent\n"
            + "identity:\n"
            + "  agent_name: 通知小助\n"
            + "  prompt: 你是通知助手，用户让你推送消息时必须调用 notify 工具完成，不许只口头答应。\n"
            + "provider:\n"
            + "  name: deepseek\n"
            + "  model: deepseek-flash\n"
            + "tools:\n"
            + "  - notify\n"
            + "notify:\n"
            + "  channels:\n"
            + "    - name: ops-hook\n"
            + "      type: webhook\n"
            + "      config:\n"
            + "        url: ${TEAM_WEBHOOK_URL}\n"
            + "---\n"
            + "收到用户的推送请求时，必须立即调用 notify 工具，把用户指定的内容原样作为 content 参数传入"
            + "（channel 参数不用传），推送完成后向用户确认已推送。\n");
  }

  private static String schemaBackedSqlite() {
    try {
      Path dir = Files.createTempDirectory("yokeos-notify-e2e");
      String url = "jdbc:sqlite:" + dir.resolve("audit.db");
      try (Connection connection = DriverManager.getConnection(url)) {
        ScriptUtils.executeSqlScript(connection, new ClassPathResource(SCHEMA_PATH));
      }
      return url;
    } catch (Exception e) {
      throw new IllegalStateException("端到端测试库初始化失败", e);
    }
  }

  /** 纯 Java 配置的 JPA 上下文（构造形态照 ReActSmokeIntegrationTest）：SQLite + 手工脚本建表。 */
  @Configuration
  @EnableJpaRepositories(basePackageClasses = ToolInvocationRepository.class)
  @EntityScan(basePackageClasses = com.yokeos.storage.ToolInvocation.class)
  static class TestJpaConfig {

    @Bean
    DataSource dataSource() {
      SQLiteDataSource ds = new SQLiteDataSource();
      ds.setUrl(JDBC_URL);
      return ds;
    }

    @Bean
    HibernateJpaVendorAdapter jpaVendorAdapter() {
      HibernateJpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
      adapter.setDatabasePlatform("org.hibernate.community.dialect.SQLiteDialect");
      return adapter;
    }

    @Bean
    LocalContainerEntityManagerFactoryBean entityManagerFactory(
        DataSource dataSource, HibernateJpaVendorAdapter adapter) {
      LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
      factory.setDataSource(dataSource);
      factory.setJpaVendorAdapter(adapter);
      factory.setPackagesToScan("com.yokeos.storage");
      return factory;
    }

    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
      return new JpaTransactionManager(emf);
    }
  }

  /** 22 节构造器扩展：这些测试不测记忆，注入进程内轻量档（零文件副作用）。 */
  private static MemoryService memoryService() {
    return new MemoryServiceImpl(new InMemoryMemoryStore());
  }
}

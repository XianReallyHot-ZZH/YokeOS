package com.yokeos.boot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yokeos.core.agent.AgentService;
import com.yokeos.core.agent.PromptBuilder;
import com.yokeos.core.agent.ReActLoop;
import com.yokeos.core.agent.ToolExecutor;
import com.yokeos.core.context.ContextLoader;
import com.yokeos.core.profile.Profile;
import com.yokeos.core.profile.Profile.Identity;
import com.yokeos.core.profile.Profile.ProviderConfig;
import com.yokeos.core.profile.Profile.Settings;
import com.yokeos.core.profile.ProfileRegistry;
import com.yokeos.core.session.InMemorySessionManager;
import com.yokeos.core.session.Session;
import com.yokeos.core.tool.YokeTool;
import com.yokeos.provider.SpringAiProviderService;
import com.yokeos.provider.ToolSchemaAdapter;
import com.yokeos.storage.JpaLlmCallAuditor;
import com.yokeos.storage.JpaToolInvocationAuditor;
import com.yokeos.storage.LlmCallRepository;
import com.yokeos.storage.ToolInvocationRepository;
import com.yokeos.tool.ToolRegistry;
import com.yokeos.tool.builtin.HttpTools;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.List;
import java.util.Map;
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
 * 集成冒烟（T022，需 §11 第 17 节行可演示成果）：真 DeepSeek key + 真 http_get（open-meteo 无 key 端点） 手工装配 core +
 * provider + tool + storage 全链路（拍板④：跨四模块落 boot；Spring 装配归 18 节）。断言三件： 答复非空、tool_invocations 新增
 * success=true 行、llm_calls 有本次调用。默认被 @Tag("integration") 排除， 显式触发：
 *
 * <pre>DEEPSEEK_API_KEY=xxx mvn -pl yokeos-boot -am test -Dgroups=integration -DexcludedGroups=
 * </pre>
 */
@Tag("integration")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ReActSmokeIntegrationTest.TestJpaConfig.class)
class ReActSmokeIntegrationTest {

  private static final String DEEPSEEK_KEY = System.getenv("DEEPSEEK_API_KEY");

  /** 表结构唯一权威是 storage 模块的手工脚本（宪法 7）。 */
  private static final String SCHEMA_PATH = "/db/schema-001-audit.sql";

  private static final String JDBC_URL = schemaBackedSqlite();

  @Autowired LlmCallRepository llmCallRepository;

  @Autowired ToolInvocationRepository toolInvocationRepository;

  @TempDir Path workspace;

  @BeforeAll
  static void requireRealKey() {
    Assumptions.assumeTrue(
        DEEPSEEK_KEY != null && !DEEPSEEK_KEY.isBlank(),
        "需要环境变量 DEEPSEEK_API_KEY 才能真调（缺 key 时跳过而非失败）");
  }

  @Test
  @DisplayName("多步循环真实走通_思考调工具观察续推全程留痕")
  void multiStepLoopWithRealHttpTool() throws Exception {
    writeWorkspaceWithWeatherAgent();
    Profile profile = weatherAgentProfile();
    YokeTool httpGet = registryHttpGet();
    Map<String, YokeTool> tools = Map.of("http_get", httpGet);

    JpaToolInvocationAuditor toolAuditor = new JpaToolInvocationAuditor(toolInvocationRepository);
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
    ToolExecutor executor = new ToolExecutor(tools, toolAuditor, 200L);
    PromptBuilder promptBuilder =
        new PromptBuilder(new ContextLoader(workspace), tools, Clock.systemDefaultZone());
    ProfileRegistry registry = new ProfileRegistry();
    registry.register(profile);
    AgentService agentService =
        new AgentService(
            registry,
            new ReActLoop(promptBuilder, provider, executor),
            new InMemorySessionManager());

    Session session = new Session("smoke-react-1", "weather-agent");
    String reply = agentService.process(session, "北京今天天气怎么样？适合穿什么？");

    // 断言一：答复非空（「思考 → 调 Tool → 观察 → 续推」的最终产物）
    assertNotNull(reply, "多步循环必须给出最终答复");
    assertFalse(reply.isBlank(), "答复非空");

    // 断言二：tool_invocations 新增 success=true 行（http_get 真被调过且审计留痕）
    var toolRows = toolInvocationRepository.findBySessionId("smoke-react-1");
    assertTrue(
        toolRows.stream()
            .anyMatch(
                r -> Boolean.TRUE.equals(r.getSuccess()) && "http_get".equals(r.getToolName())),
        "http_get 的成功调用必须在审计表留痕");

    // 断言三：llm_calls 有本次调用的记录（每轮 LLM 调用留痕）
    assertFalse(llmCallRepository.findAll().isEmpty(), "llm_calls 必须有本次会话的调用记录");
  }

  /** 最小工作区：Bootstrap 三件 + weather-agent 的 AGENT.md（强引导先调工具再回答）。 */
  private void writeWorkspaceWithWeatherAgent() throws Exception {
    Files.writeString(workspace.resolve("AGENTS.md"), "测试工作区约定");
    Files.writeString(workspace.resolve("SOUL.md"), "你是简洁实用的助手");
    Files.writeString(workspace.resolve("USER.md"), "无特殊偏好");
    Path agentDir = workspace.resolve("agents").resolve("weather-agent");
    Files.createDirectories(agentDir);
    Files.writeString(
        agentDir.resolve("AGENT.md"),
        "---\n"
            + "name: weather-agent\n"
            + "identity:\n"
            + "  agent_name: 天气小助\n"
            + "  prompt: 你是天气助手，回答天气问题必须先调用 http_get 工具获取实时数据再作答。\n"
            + "provider:\n"
            + "  name: deepseek\n"
            + "  model: deepseek-flash\n"
            + "tools:\n"
            + "  - http_get\n"
            + "---\n"
            + "回答天气问题时，必须先调用 http_get 访问 open-meteo 接口"
            + "（如 https://api.open-meteo.com/v1/forecast?latitude=39.9&longitude=116.4"
            + "&current=temperature_2m,wind_speed_10m）获取实时天气，再基于结果给出穿衣建议。\n");
  }

  /** 经注册面取 http_get（20 节 HttpTools 注解管道——17 节单类形态已退役）。 */
  private static YokeTool registryHttpGet() {
    ToolRegistry registry = new ToolRegistry();
    registry.registerAnnotated(new HttpTools());
    return registry.get("http_get").orElseThrow();
  }

  private static Profile weatherAgentProfile() {
    return new Profile(
        "weather-agent",
        "天气助手",
        new Identity("天气小助", "你是天气助手，回答天气问题必须先调用 http_get 工具获取实时数据再作答。"),
        new ProviderConfig("deepseek", "deepseek-flash", 0.1),
        List.of("http_get"),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Settings.DEFAULT);
  }

  private static String schemaBackedSqlite() {
    try {
      Path dir = Files.createTempDirectory("yokeos-react-smoke");
      String url = "jdbc:sqlite:" + dir.resolve("audit.db");
      try (Connection connection = DriverManager.getConnection(url)) {
        ScriptUtils.executeSqlScript(connection, new ClassPathResource(SCHEMA_PATH));
      }
      return url;
    } catch (Exception e) {
      throw new IllegalStateException("冒烟库初始化失败", e);
    }
  }

  /** 纯 Java 配置的 JPA 上下文（构造形态照 LlmCallRepositoryTest）：SQLite + 手工脚本建表。 */
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
}

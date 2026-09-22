package com.yokeos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yokeos.cli.InitCommand;
import com.yokeos.core.agent.AgentService;
import com.yokeos.core.agent.PromptBuilder;
import com.yokeos.core.agent.ReActLoop;
import com.yokeos.core.agent.ToolExecutor;
import com.yokeos.core.context.ContextLoader;
import com.yokeos.core.profile.AgentLoader;
import com.yokeos.core.profile.Profile;
import com.yokeos.core.profile.ProfileRegistry;
import com.yokeos.core.session.InMemorySessionManager;
import com.yokeos.core.session.Session;
import com.yokeos.core.tool.YokeTool;
import com.yokeos.memory.MarkdownMemoryStore;
import com.yokeos.memory.MemoryServiceImpl;
import com.yokeos.memory.builtin.MemoryTools;
import com.yokeos.provider.MockChatModel;
import com.yokeos.provider.SpringAiProviderService;
import com.yokeos.provider.ToolSchemaAdapter;
import com.yokeos.storage.JpaLlmCallAuditor;
import com.yokeos.storage.JpaToolInvocationAuditor;
import com.yokeos.storage.LlmCallRepository;
import com.yokeos.storage.ToolInvocationRepository;
import com.yokeos.tool.ToolRegistry;
import com.yokeos.tool.sandbox.SandboxProperties;
import com.yokeos.tool.sandbox.WhitelistSandbox;
import com.yokeos.web.GlobalExceptionHandler;
import com.yokeos.web.controller.MemoryApiController;
import com.yokeos.web.controller.SessionApiController;
import com.yokeos.web.controller.ToolApiController;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.sqlite.SQLiteDataSource;

/**
 * 用 mock provider 打通全链路（第 27 节，gate 内、无 key 无网络）：只有「模型」是假的（{@link MockChatModel}），ReActLoop /
 * ToolExecutor / Memory / Session / 审计全部真实。一条「记住…」消息应驱动出 两轮 ReAct、恰一次 save_memory（真写
 * MEMORY.md）、四条完整会话历史；审计对账<b>走真库按 sessionId 过滤</b>—— 「不多不少」是断言出来的，不是 mock verify 出来的（教学文档坑四）。
 *
 * <p>随后管理台三个只读端点（/memory /tools /sessions）读同一批服务，应能查到这条链路留下的数据——「三面同源」 的 gate 内缩影。跑法：随 {@code mvn
 * verify} 自动跑，无需任何环境准备。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = MockProviderFlowTest.TestJpaConfig.class)
class MockProviderFlowTest {

  private static final String AGENT = "mock-agent";

  private static final String FACT = "我在北京，怕冷";

  private static final String JDBC_URL = schemaBackedSqlite();

  @Autowired LlmCallRepository llmCallRepository;

  @Autowired ToolInvocationRepository toolInvocationRepository;

  @TempDir Path workspace;

  @Test
  @DisplayName("mock全链路_两轮ReAct恰一次save_memory_真库对账不多不少_console三端点查得回")
  void mockProviderDrivesFullChainAndConsoleSeesData() throws Exception {
    // ── 装配：真实组件，唯一的假是 MockChatModel（22 节 E2E 骨架，provider 换 mock）──
    new InitCommand().initWorkspace(workspace);
    Files.createDirectories(workspace.resolve("agents").resolve(AGENT));
    Files.writeString(
        workspace.resolve("agents").resolve(AGENT).resolve("AGENT.md"),
        "---\n"
            + "name: "
            + AGENT
            + "\n"
            + "identity:\n"
            + "  agent_name: 自测助手\n"
            + "  prompt: 你是测试助手。\n"
            + "provider:\n"
            + "  name: mock\n"
            + "  model: mock-model\n"
            + "tools:\n"
            + "  - save_memory\n"
            + "  - recall_memory\n"
            + "---\n"
            + "无正文要求。\n");

    MemoryServiceImpl memory = markdownMemoryService();
    ToolRegistry registry = new ToolRegistry();
    registry.registerAnnotated(new MemoryTools(memory));
    Map<String, YokeTool> tools = registry.asMap();

    List<Profile> profiles = new AgentLoader().loadAll(workspace, Set.of("mock"), tools.keySet());
    assertEquals(1, profiles.size(), "mock-agent 必须派生成功");

    SpringAiProviderService provider =
        new SpringAiProviderService(
            Map.of("mock", new MockChatModel()),
            new ToolSchemaAdapter(),
            new JpaLlmCallAuditor(llmCallRepository));
    ToolExecutor executor =
        new ToolExecutor(tools, new JpaToolInvocationAuditor(toolInvocationRepository), 200L);
    PromptBuilder promptBuilder =
        new PromptBuilder(new ContextLoader(workspace), tools, Clock.systemDefaultZone(), memory);
    InMemorySessionManager sessionManager = new InMemorySessionManager();
    ProfileRegistry profileRegistry = new ProfileRegistry();
    profileRegistry.register(profiles.get(0));
    AgentService agentService =
        new AgentService(
            profileRegistry, new ReActLoop(promptBuilder, provider, executor), sessionManager);

    // ── 跑一次「记住…」对话 ──
    Session session = sessionManager.getOrCreate("cli", "mock-user", AGENT);
    String reply = agentService.process(session, "记住：" + FACT);

    // 对话执行：非空最终答复（mock 第二轮固定终答）
    assertEquals(MockChatModel.FINAL_REPLY, reply);
    // ReAct 执行：两轮（第一轮调工具、第二轮收尾）→ assistant 恰 2 条
    assertEquals(2, countRole(session, "assistant"), "ReAct 应跑两轮");
    // 工具执行：恰一次 save_memory（session 历史里 tool 行恰 1 条）
    var toolMessages = session.messages().stream().filter(m -> m.toolName() != null).toList();
    assertEquals(1, toolMessages.size(), "应恰调用一次工具");
    assertEquals("save_memory", toolMessages.get(0).toolName());
    // 对话执行：user + assistant×2 + tool = 4 条完整历史
    assertEquals(4, session.messages().size(), "会话历史应完整累积");
    // 记忆副作用：save_memory 真写了 MEMORY.md
    String persisted = Files.readString(workspace.resolve("memory").resolve("MEMORY.md"));
    assertTrue(persisted.contains("北京"), "记忆文件应记下事实（实际: " + persisted + "）");

    // 审计对账（坑四：按 sessionId 过滤，真库计数）——llm_calls 恰 2、tool_invocations 恰 1，不多不少
    long llmCount =
        llmCallRepository.findAll().stream()
            .filter(l -> session.sessionId().equals(l.getSessionId()))
            .count();
    assertEquals(2, llmCount, "llm_calls 应恰 2 条（实际: " + dumpLlm() + "）");
    long toolCount = toolInvocationRepository.findBySessionId(session.sessionId()).size();
    assertEquals(1, toolCount, "tool_invocations 应恰 1 条");
    assertTrue(
        toolInvocationRepository.findBySessionId(session.sessionId()).stream()
            .allMatch(r -> Boolean.TRUE.equals(r.getSuccess())),
        "唯一一次工具调用必须成功");

    // ── console 三端点读同一批服务：链路留下的数据查得回（三面同源的 gate 内缩影）──
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(
                new MemoryApiController(memory),
                new ToolApiController(registry),
                new SessionApiController(agentService, sessionManager))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    mvc.perform(get("/api/v1/memory"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.containsString("北京")));
    mvc.perform(get("/api/v1/tools"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[*].name").value(org.hamcrest.Matchers.hasItem("save_memory")));
    mvc.perform(get("/api/v1/sessions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].sessionId").value(session.sessionId()))
        .andExpect(jsonPath("$.data[0].agentName").value(AGENT))
        .andExpect(jsonPath("$.data[0].status").value("active"));
  }

  /** 真 markdown 默认档（22 节同款：白名单含工作区，save_memory 的 FILE_WRITE 不被自家拦）。 */
  private MemoryServiceImpl markdownMemoryService() {
    return new MemoryServiceImpl(
        new MarkdownMemoryStore(
            workspace.resolve("memory"),
            4000,
            new WhitelistSandbox(
                new SandboxProperties(
                    List.of(workspace.toAbsolutePath().toString()), List.of(), List.of()))));
  }

  private static long countRole(Session session, String role) {
    return session.messages().stream().filter(m -> role.equals(m.role())).count();
  }

  private String dumpLlm() {
    return llmCallRepository.findAll().stream()
        .map(l -> l.getSessionId() + ":" + l.getSuccess())
        .toList()
        .toString();
  }

  private static String schemaBackedSqlite() {
    try {
      Path db = Files.createTempFile("yokeos-mock-27", ".db");
      db.toFile().deleteOnExit();
      String url = "jdbc:sqlite:" + db.toAbsolutePath();
      try (Connection connection = DriverManager.getConnection(url)) {
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("/db/schema-001-audit.sql"));
      }
      return url;
    } catch (Exception e) {
      throw new IllegalStateException("mock 全链路测试库初始化失败", e);
    }
  }

  /** 与 22 节 E2E 同款最小 JPA 配置（仅审计两表，宪法 7：表结构唯一权威是手工脚本）。 */
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
      return em;
    }

    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
      return new JpaTransactionManager(emf);
    }
  }
}

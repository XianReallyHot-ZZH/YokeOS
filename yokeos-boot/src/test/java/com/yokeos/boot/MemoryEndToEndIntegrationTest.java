package com.yokeos.boot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yokeos.cli.InitCommand;
import com.yokeos.core.agent.AgentService;
import com.yokeos.core.agent.PromptBuilder;
import com.yokeos.core.agent.ReActLoop;
import com.yokeos.core.agent.ToolExecutor;
import com.yokeos.core.context.ContextLoader;
import com.yokeos.core.profile.Profile;
import com.yokeos.core.profile.ProfileRegistry;
import com.yokeos.core.session.InMemorySessionManager;
import com.yokeos.core.session.Session;
import com.yokeos.core.tool.YokeTool;
import com.yokeos.memory.MarkdownMemoryStore;
import com.yokeos.memory.MemoryServiceImpl;
import com.yokeos.memory.builtin.MemoryTools;
import com.yokeos.provider.SpringAiProviderService;
import com.yokeos.provider.ToolSchemaAdapter;
import com.yokeos.storage.JpaLlmCallAuditor;
import com.yokeos.storage.JpaToolInvocationAuditor;
import com.yokeos.storage.LlmCallRepository;
import com.yokeos.storage.ToolInvocationRepository;
import com.yokeos.tool.ToolRegistry;
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
 * 第 22 节综合端到端（{@code @Tag("integration")}，显式触发 + 缺 key 跳过）：真模型两段会话串联本节全部交付物—— 单测链路发现不了的「模型真调到
 * save_memory」「长期记忆跨 Session 生效」在这里闭环（19 节坑的同类守点）。
 *
 * <p>链路（← 后为每步对应的第 22 节实现）：
 *
 * <pre>
 * InitCommand.initWorkspace ──────────────── ← 工作区骨架（memory/ 目录就位）
 * AGENT.md（tools 点名记忆两件）──────────── ← 19 节坑：不点名模型看不到工具
 * MemoryServiceImpl(MarkdownMemoryStore) ── ← 门面 + 默认档（真文件，非 InMemory 替身）
 * ToolRegistry.registerAnnotated(MemoryTools) ← save_memory/recall_memory 进注册面（裁决二）
 * PromptBuilder(四参) ───────────────────── ← [2] 长期记忆位接线（17 节预留兑现）
 * 会话一（user-a）真模型调 save_memory ──── ← ReAct 循环真执行 + 审计留痕
 *   └ MEMORY.md 真实落盘 ───────────────── ← 两分区 + 日期 header（append 副作用）
 * 会话二（user-b，全新 Session）─────────── ← 无共享历史，偏好唯一来源是记忆注入
 *   └ 答复体现记住的偏好 ────────────────── ← 跨 Session 生效（可演示成果的自动化形态）
 * </pre>
 *
 * <p>断言设计要点：会话二用不同 user 标识（session_id 不同、历史独立）——答复里的「Java 21」<b>只能</b>来自 [2] 记忆位注入的 MEMORY.md
 * 现读内容，链路证明干净（坑二/坑七的端到端形态）。
 *
 * <p>跑法：
 *
 * <pre>
 * source ~/.zshrc && mvn -pl yokeos-boot -am test \
 *     -Dgroups=integration -DexcludedGroups= -Dtest='MemoryEndToEndIntegrationTest'
 * </pre>
 */
@Tag("integration")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = MemoryEndToEndIntegrationTest.TestJpaConfig.class)
class MemoryEndToEndIntegrationTest {

  private static final String DEEPSEEK_KEY = System.getenv("DEEPSEEK_API_KEY");

  /** 表结构唯一权威是 storage 模块的手工脚本（宪法 7）——本测试只断言审计两表。 */
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
  @DisplayName("真模型两段会话串联22节全部交付物_跨Session记住用户偏好")
  void realModelRemembersAcrossSessions() throws Exception {
    // ── 步骤 1：工作区 + Agent 定义（tools 点名记忆两件——注册面只挂这两件，模型不跑偏）
    new InitCommand().initWorkspace(workspace);
    Files.createDirectories(workspace.resolve("agents").resolve("memory-agent"));
    Files.writeString(
        workspace.resolve("agents").resolve("memory-agent").resolve("AGENT.md"),
        "---\n"
            + "name: memory-agent\n"
            + "identity:\n"
            + "  agent_name: 记忆助手\n"
            + "  prompt: 你是项目信息助手。用户要求记住信息时，必须调用 save_memory 工具（scope 填 archival）；"
            + "回答项目相关问题时，依据你记住的信息作答，不得凭空编造。\n"
            + "provider:\n"
            + "  name: deepseek\n"
            + "  model: deepseek-flash\n"
            + "tools:\n"
            + "  - save_memory\n"
            + "  - recall_memory\n"
            + "---\n"
            + "记住的信息是跨会话持久的：用户之前让你记过的事，之后的任何对话里你都应当知道。\n");

    // ── 步骤 2：注册面（只挂记忆两件，经 20 节注解管道——执行发起方是 ToolExecutor，宪法 2）
    ToolRegistry registry = new ToolRegistry();
    registry.registerAnnotated(new MemoryTools(markdownMemoryService()));
    Map<String, YokeTool> tools = registry.asMap();
    assertTrue(
        tools.keySet().containsAll(List.of("save_memory", "recall_memory")),
        "记忆两件必须在册（实际: " + tools.keySet() + "）");

    // ── 步骤 3：Profile 派生（tools 点名走有痕校验路径）+ 全链装配
    List<Profile> profiles =
        new com.yokeos.core.profile.AgentLoader()
            .loadAll(workspace, Set.of("deepseek"), tools.keySet());
    assertTrue(profiles.size() == 1, "memory-agent 必须派生成功");

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
            new ContextLoader(workspace),
            tools,
            Clock.systemDefaultZone(),
            markdownMemoryService());
    ProfileRegistry profileRegistry = new ProfileRegistry();
    profileRegistry.register(profiles.get(0));
    InMemorySessionManager sessionManager = new InMemorySessionManager();
    AgentService agentService =
        new AgentService(
            profileRegistry, new ReActLoop(promptBuilder, provider, executor), sessionManager);

    // ── 步骤 4：会话一（user-a）——指令式消息让模型真调 save_memory（20 节「任务强引导」同款手法）
    Session first = sessionManager.getOrCreate("cli", "e2e-user-a", "memory-agent");
    String reply1 =
        agentService.process(first, "请用 save_memory 工具记住这条项目信息：我们项目使用 Java 21，部署在 K8s 集群上。");
    assertNotNull(reply1);
    assertFalse(reply1.isBlank(), "会话一必须给出答复");

    // ── 断言一：save_memory 真实副作用落盘（工具真干了活——MEMORY.md 两分区 + 内容 + 日期 header）
    Path memoryFile = workspace.resolve("memory").resolve("MEMORY.md");
    assertTrue(Files.isRegularFile(memoryFile), "MEMORY.md 必须真实落盘（markdown 默认档）");
    String persisted = Files.readString(memoryFile);
    assertTrue(persisted.contains("Java 21"), "落盘内容含记住的偏好（实际: " + persisted + "）");
    assertTrue(persisted.contains("K8s"), "落盘内容含部署信息");
    assertTrue(
        persisted.contains("## 核心记忆") && persisted.contains("## 归档记忆"),
        "两分区 header 结构在（specs/006 D5）");

    // ── 断言二：save_memory 调用审计留痕（与其他内置 Tool 一视同仁，零新增审计逻辑——宪法 7）
    var rows = toolInvocationRepository.findBySessionId(first.sessionId());
    assertTrue(
        rows.stream()
            .anyMatch(
                r -> "save_memory".equals(r.getToolName()) && Boolean.TRUE.equals(r.getSuccess())),
        "save_memory 成功调用必须留痕；实际审计行: "
            + rows.stream().map(r -> r.getToolName() + "(" + r.getSuccess() + ")").toList());

    // ── 步骤 5：会话二（user-b，全新 Session）——无共享历史，答复里的偏好唯一来源是 [2] 记忆位注入
    Session second = sessionManager.getOrCreate("cli", "e2e-user-b", "memory-agent");
    assertTrue(!second.sessionId().equals(first.sessionId()), "两段会话必须是不同 Session（链路证明的前提）");
    assertTrue(second.messages().isEmpty(), "会话二无历史——答复证据只能来自长期记忆注入");

    String reply2 = agentService.process(second, "我们项目用的 Java 是什么版本？");

    // ── 断言三：跨 Session 记忆生效（可演示成果的自动化形态——需 §11 第 22 节行口径）
    assertNotNull(reply2);
    assertTrue(
        reply2.contains("21") && reply2.contains("Java"),
        "会话二答复必须体现记住的偏好 Java 21（实际: " + reply2 + "）");

    // ── 断言四：llm_calls 留痕（两段会话的每次调用，宪法 7）
    assertTrue(llmCallRepository.findAll().size() >= 2, "两段会话的 LLM 调用必须留痕");
  }

  /** 真 markdown 默认档（真文件落盘——本测试就是来验它的；InMemory 替身是其他 E2E 的便利形态）。 */
  private MemoryServiceImpl markdownMemoryService() {
    return new MemoryServiceImpl(
        new MarkdownMemoryStore(
            workspace.resolve("memory"),
            4000,
            new WhitelistSandbox(
                new SandboxProperties(
                    java.util.List.of(workspace.toAbsolutePath().toString()),
                    java.util.List.of(),
                    java.util.List.of())))); // 24 节：白名单含工作区，save_memory 不被自家拦（坑六）
  }

  private static String schemaBackedSqlite() {
    try {
      Path db = Files.createTempFile("yokeos-e2e-memory-22", ".db");
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

  /** 与 ToolSystemEndToEndIntegrationTest 同款最小 JPA 配置（仅审计两表）。 */
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
}

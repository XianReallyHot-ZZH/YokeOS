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
import com.yokeos.storage.ToolInvocation;
import com.yokeos.storage.ToolInvocationRepository;
import com.yokeos.tool.ToolRegistry;
import com.yokeos.tool.builtin.FileTools;
import com.yokeos.tool.builtin.HttpTools;
import com.yokeos.tool.builtin.ShellTools;
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
 * 第 24 节专属端到端（{@code @Tag("integration")}，真 DeepSeek + 真实 IO）：<b>三重白名单各走一对「拒绝 + 放行」</b>—— 一次运行串完 24
 * 节全部新增功能，也是 debug 阅读源码的导读用例。
 *
 * <p><b>链路与断点导读</b>（按用户消息的六步，IDEA 里下断点后 Debug 跑本用例，按图索骥）：
 *
 * <pre>
 * 用户消息（六步任务）
 *   → AgentService.process ─────────────── 统一入口（17 节）
 *   → PromptBuilder.build ──────────────── [1] system prompt（AGENT.md 正文）+ [4] 工具清单（只带 tools 点名的三件）
 *   → 真 DeepSeek 生成 tool call ────────── 每步一个调用（identity prompt 强制「失败也要继续下一步」）
 *   → ToolExecutor.execute ─────────────── 循环外看：拒绝为何不可重试（retryable=false 一次即止，坑二）
 *   → 工具方法首行 sandbox.enforce ──────── ★ 24 节核心：动作发生处单一落点（FileTools/ShellTools/HttpTools）
 *   → WhitelistSandbox.enforce ──────────── ActionType 四值路由（FILE_READ/FILE_WRITE 同路由，D3 接口先分实现先合）
 *        ├ checkFilePath ────────────────── 步骤 1（/etc/hosts 拒）与 2（SOUL.md 放）：resolveReal 对称解析——
 *        │                                  Watch 里看 target 与 root 双侧过同一函数（symlink 解开后比对，坑一）
 *        ├ checkShellCommand ────────────── 步骤 3（whoami 拒）与 4（echo 放）：Set.contains 字面精确比对（坑三）
 *        └ checkHttpUrl → matchesDomain ─── 步骤 5（example.com 拒：点号边界）与 6（open-meteo 放）：host 解析
 *                                           后通配/精确匹配，端口不参与（坑四、research D3）
 *   → [拒绝] SandboxViolationException ──── 构造器里看消息口径：常量前缀 + 目标（路径/命令）；域名只显 host 不显
 *   │                                      整串 URL——webhook URL 即凭证不回显（19 节坑二、research D7）
 *   → ToolExecutor.attemptOnce catch ────── 收口位：转 ToolResult.error(retryable=false)——张力一裁决的「位」
 *   → JpaToolInvocationAuditor.record ──── 拒绝恰落一条 success=false + error_message（宪法 7，不为 Sandbox 单增）
 *   → 失败结果回填对话历史 → 模型下一轮看到原因续答（US4：拒绝对模型可见，循环不炸）
 *   → [放行] 真实 IO 发生 ──────────────── 步骤 2 真读到文件、步骤 4 真起了进程、步骤 6 真发出 HTTP——同一道闸，
 *                                          白名单说了算
 * </pre>
 *
 * <p><b>白名单（刻意最小化，便于 Watch 推演）</b>：路径 = 工作区根；命令 = 仅 {@code echo}；域名 = 仅 {@code
 * api.open-meteo.com}。六步任务与之对照：每维一拒一放。
 *
 * <p><b>跑法</b>（缺 key 自动 skip 不失败）：
 *
 * <pre>
 * DEEPSEEK_API_KEY=xxx mvn -pl yokeos-boot -am test -Dgroups=integration -DexcludedGroups= \
 *     -Dtest='SandboxEndToEndIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p>IDEA 调试：Run Configuration 里 Environment variables 加 {@code DEEPSEEK_API_KEY}，直接 Debug 本方法（无需
 * Maven）。
 */
@Tag("integration")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = SandboxEndToEndIntegrationTest.TestJpaConfig.class)
class SandboxEndToEndIntegrationTest {

  private static final String DEEPSEEK_KEY = System.getenv("DEEPSEEK_API_KEY");

  /** 表结构唯一权威是 storage 模块的手工脚本（宪法 7）。 */
  private static final String SCHEMA_PATH = "/db/schema-001-audit.sql";

  private static final String JDBC_URL = schemaBackedSqlite();

  private static final String SESSION_ID = "e2e-sandbox-24-full";

  /** open-meteo 无 key 端点（17 节冒烟同款——步骤 6 的放行目标）。 */
  private static final String METEO_URL =
      "https://api.open-meteo.com/v1/forecast?latitude=39.9&longitude=116.4&current=temperature_2m";

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
  @DisplayName("真模型六步串联三重白名单_每维一拒一放_全程留痕")
  void realModelChainsThreeWhitelistsEachWithRejectAndAllow() throws Exception {
    // ── 步骤 1：工作区与 Agent（identity prompt 强制逐工具执行、失败继续——三轮 E2E 实证的强引导形态）
    new InitCommand().initWorkspace(workspace);
    Path agentDir = workspace.resolve("agents").resolve("sandbox-agent");
    Files.createDirectories(agentDir);
    Files.writeString(
        agentDir.resolve("AGENT.md"),
        "---\n"
            + "name: sandbox-agent\n"
            + "identity:\n"
            + "  agent_name: 安全演示助手\n"
            + "  prompt: 你是安全演示助手，必须严格按用户步骤逐一调用工具，不得跳过任何一步、不得凭空作答；"
            + "某步工具返回失败时，原样记录失败原因并继续执行下一步。\n"
            + "provider:\n"
            + "  name: deepseek\n"
            + "  model: deepseek-flash\n"
            + "tools:\n"
            + "  - read_file\n"
            + "  - shell\n"
            + "  - http_get\n"
            + "settings:\n"
            + "  max_iterations: 20\n"
            + "---\n"
            + "用户给出多步任务时逐步调用工具，全部执行完后汇总每一步的成败与原因。\n");

    // ── 步骤 2：装配（YokeosRuntime.tools() 同配方；白名单刻意最小：路径=工作区 / 命令=echo / 域名=open-meteo）
    WhitelistSandbox sandbox =
        new WhitelistSandbox(
            new SandboxProperties(
                List.of(workspace.toAbsolutePath().toString()),
                List.of("echo"),
                List.of("api.open-meteo.com")));
    ToolRegistry registry = new ToolRegistry();
    registry.registerAnnotated(new FileTools(sandbox));
    registry.registerAnnotated(new ShellTools(sandbox));
    registry.registerAnnotated(new HttpTools(sandbox));
    Map<String, YokeTool> tools = registry.asMap();

    List<Profile> profiles =
        new com.yokeos.core.profile.AgentLoader()
            .loadAll(workspace, Set.of("deepseek"), tools.keySet());
    assertEquals(1, profiles.size(), "sandbox-agent 必须派生成功");

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

    // ── 步骤 3：六步任务（每维一拒一放；绝对路径原样给——模型不猜路径）
    String soulFile = workspace.toAbsolutePath().resolve("SOUL.md").toString();
    String task =
        "请逐步执行以下六步，每步都必须真实调用对应工具（即使失败也继续下一步）："
            + "1) 用 read_file 读取 /etc/hosts；"
            + "2) 用 read_file 读取 "
            + soulFile
            + "；"
            + "3) 用 shell 执行 [\"whoami\"]；"
            + "4) 用 shell 执行 [\"echo\",\"sandbox-ok\"]；"
            + "5) 用 http_get 访问 https://example.com/；"
            + "6) 用 http_get 访问 "
            + METEO_URL
            + "；完成后汇总每步成败。";

    Session session = new Session(SESSION_ID, "sandbox-agent");
    String reply = agentService.process(session, task);

    // ── 断言一：循环未被拒绝炸掉，模型基于六步结果给出最终汇总（失败原因对模型可见，US4）
    assertNotNull(reply);
    assertFalse(reply.isBlank(), "拒绝不炸循环——模型看到各步失败原因后仍完成汇总");

    // ── 断言二：三维拒绝各恰一条（success=false + 对应拒绝消息——模型可读，宪法 7）
    var rows = toolInvocationRepository.findBySessionId(SESSION_ID);
    assertOneRejection(rows, "read_file", "路径不在白名单内", "/etc/hosts");
    assertOneRejection(rows, "shell", "命令不在白名单内", "whoami");
    assertOneRejection(rows, "http_get", "域名不在白名单内", "example.com");

    // ── 断言三：三维放行各至少一条（同一道闸，白名单内真实 IO 发生——步骤 2 真读到 Bootstrap 文件）
    assertTrue(
        rows.stream()
            .anyMatch(
                r -> "read_file".equals(r.getToolName()) && Boolean.TRUE.equals(r.getSuccess())),
        "白名单内路径的 read_file 必须成功留痕；实际审计行: " + auditSummary(rows));
    assertTrue(
        rows.stream()
            .anyMatch(r -> "shell".equals(r.getToolName()) && Boolean.TRUE.equals(r.getSuccess())),
        "白名单内命令的 shell(echo) 必须成功留痕；实际审计行: " + auditSummary(rows));
    assertTrue(
        rows.stream()
            .anyMatch(
                r -> "http_get".equals(r.getToolName()) && Boolean.TRUE.equals(r.getSuccess())),
        "白名单内域名的 http_get(open-meteo) 必须成功留痕（真实 HTTP 已发出）；实际审计行: " + auditSummary(rows));

    // ── 断言四：llm_calls 留痕（多轮 ReAct 每轮调用，宪法 7）
    assertFalse(llmCallRepository.findAll().isEmpty(), "llm_calls 必须有本次会话的调用记录");
  }

  /** 断言某工具恰存在一条指定拒绝消息的成功=false 审计行（消息含目标——模型下一轮可见失败原因）。 */
  private static void assertOneRejection(
      List<ToolInvocation> rows, String toolName, String messagePart, String targetPart) {
    var hits =
        rows.stream()
            .filter(
                r ->
                    toolName.equals(r.getToolName())
                        && !Boolean.TRUE.equals(r.getSuccess())
                        && r.getErrorMessage() != null
                        && r.getErrorMessage().contains(messagePart)
                        && r.getErrorMessage().contains(targetPart))
            .toList();
    assertFalse(
        hits.isEmpty(),
        toolName
            + " 的拒绝（"
            + messagePart
            + " 含 "
            + targetPart
            + "）必须留痕；实际审计行: "
            + auditSummary(rows));
  }

  private static String auditSummary(List<ToolInvocation> rows) {
    return rows.stream().map(r -> r.getToolName() + "(" + r.getSuccess() + ")").toList().toString();
  }

  private static String schemaBackedSqlite() {
    try {
      Path db = Files.createTempFile("yokeos-e2e-sandbox-24", ".db");
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

  /** 本测试不测记忆，注入进程内轻量档（零文件副作用）。 */
  private static MemoryService memoryService() {
    return new MemoryServiceImpl(new InMemoryMemoryStore());
  }
}

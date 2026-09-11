package com.yokeos.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yokeos.core.audit.LlmCallAuditor;
import com.yokeos.core.profile.Profile;
import com.yokeos.core.profile.Profile.Identity;
import com.yokeos.core.profile.Profile.ProviderConfig;
import com.yokeos.core.profile.Profile.Settings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

/**
 * 集成冒烟（T020）：真 key 真调一次 DeepSeek，断言非空响应且 {@code llm_calls} 新增 {@code success=true} 行。手工构造 OpenAI 兼容
 * {@link ChatModel}（research D1），审计经 JDBC 直落 手工脚本建的 SQLite——真实落库链路（JPA 映射由 storage 模块测试覆盖）。默认被
 * {@code @Tag("integration")} 排除，显式触发：
 *
 * <pre>DEEPSEEK_API_KEY=xxx mvn -pl yokeos-provider -am test -Dgroups=integration</pre>
 */
@Tag("integration")
class ProviderSmokeIntegrationTest {

  private static final String DEEPSEEK_KEY = System.getenv("DEEPSEEK_API_KEY");

  /** 表结构唯一权威是 storage 模块的手工脚本（宪法 7），按模块相对路径读取，不复制 DDL。 */
  private static final Path SCHEMA =
      Path.of("..", "yokeos-storage", "src", "main", "resources", "db", "schema-001-audit.sql");

  private static final String JDBC_URL = schemaBackedSqlite();

  @BeforeAll
  static void requireRealKey() {
    Assumptions.assumeTrue(
        DEEPSEEK_KEY != null && !DEEPSEEK_KEY.isBlank(),
        "需要环境变量 DEEPSEEK_API_KEY 才能真调（缺 key 时跳过而非失败）");
  }

  @Test
  @DisplayName("真实冒烟_真调deepseek非空回复且审计落success=true行")
  void realCallAuditedSuccessRow() throws Exception {
    OpenAiApi api =
        OpenAiApi.builder().baseUrl("https://api.deepseek.com").apiKey(DEEPSEEK_KEY).build();
    ChatModel deepseek =
        OpenAiChatModel.builder()
            .openAiApi(api)
            .defaultOptions(OpenAiChatOptions.builder().model("deepseek-flash").build())
            .build();
    ProviderService service =
        new ProviderService(Map.of("deepseek", deepseek), new ToolSchemaAdapter(), jdbcAuditor());
    Profile profile =
        new Profile(
            "smoke-agent",
            "冒烟",
            new Identity("冒烟员", "你是冒烟测试助手"),
            new ProviderConfig("deepseek", "deepseek-flash", 0.1),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Settings.DEFAULT);

    ChatResponse response =
        service.chat("smoke-1", profile, new Prompt(new UserMessage("用一句话介绍你自己")));

    assertNotNull(response.getResult(), "真调必须返回结果");
    String text = response.getResult().getOutput().getText();
    assertNotNull(text, "回复文本非空");
    assertFalse(text.isBlank(), "回复文本非空");

    try (Connection connection = DriverManager.getConnection(JDBC_URL);
        Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT success, error_message FROM llm_calls ORDER BY id DESC LIMIT 1")) {
      assertTrue(rs.next(), "审计表必须有本次调用的记录");
      assertEquals(1, rs.getInt("success"), "冒烟调用应 success=true");
      assertTrue(rs.getObject("error_message") == null, "成功行 error_message 应为空");
    }
  }

  /** 轻量 JDBC 审计：真实 INSERT 进手工脚本建的 SQLite（全链路证据，非内存桩）。 */
  private static LlmCallAuditor jdbcAuditor() {
    return (sessionId, provider, model, pt, ct, tt, success, errorMessage, durationMs) -> {
      String sql =
          "INSERT INTO llm_calls(session_id, provider, model, prompt_tokens, completion_tokens,"
              + " total_tokens, success, error_message, duration_ms, created_at)"
              + " VALUES (?,?,?,?,?,?,?,?,?,datetime('now'))";
      try (Connection connection = DriverManager.getConnection(JDBC_URL);
          PreparedStatement ps = connection.prepareStatement(sql)) {
        ps.setString(1, sessionId);
        ps.setString(2, provider);
        ps.setString(3, model);
        setObject(ps, 4, pt);
        setObject(ps, 5, ct);
        setObject(ps, 6, tt);
        ps.setInt(7, success ? 1 : 0);
        ps.setString(8, errorMessage);
        ps.setLong(9, durationMs);
        ps.executeUpdate();
      } catch (SQLException e) {
        throw new IllegalStateException("冒烟审计落库失败", e);
      }
    };
  }

  private static void setObject(PreparedStatement ps, int index, Integer value)
      throws SQLException {
    if (value == null) {
      ps.setObject(index, null);
    } else {
      ps.setInt(index, value);
    }
  }

  private static String schemaBackedSqlite() {
    try {
      Path dir = Files.createTempDirectory("yokeos-smoke-it");
      String url = "jdbc:sqlite:" + dir.resolve("audit.db");
      String ddl = Files.readString(SCHEMA);
      try (Connection connection = DriverManager.getConnection(url)) {
        org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
            connection, new org.springframework.core.io.ByteArrayResource(ddl.getBytes()));
      }
      return url;
    } catch (Exception e) {
      throw new IllegalStateException("冒烟库初始化失败", e);
    }
  }
}

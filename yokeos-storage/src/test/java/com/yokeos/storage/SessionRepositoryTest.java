package com.yokeos.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.yokeos.core.provider.ProviderResponse;
import com.yokeos.core.provider.ToolCallRequest;
import com.yokeos.core.tool.ToolResult;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 * sessions 落库 harness（第 18 节坑四回归）：手工脚本建表存读往返、三类消息序列化回读零丢失序不变、 模拟重启历史还在（恢复是追加不是覆盖）、零消息会话、九列逐一对齐技
 * §9.2。建表走 schema-002-sessions.sql（宪法 7，不让 Hibernate 自动建）。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = SessionRepositoryTest.TestJpaConfig.class)
class SessionRepositoryTest {

  /** 静态初始化 SQLite 临时库并执行手工建表脚本（16 节同款）。 */
  private static final String JDBC_URL = createSchemaBackedSqlite();

  @Autowired SessionRepository repository;

  @Autowired DataSource dataSource;

  @Test
  @DisplayName("存读往返_全字段")
  void saveAndReloadRoundTrip() {
    Session entity = new Session();
    entity.setSessionId("cli:round:trip");
    entity.setAgentName("weather");
    entity.setChannel("cli");
    entity.setUserId("round");
    entity.setMessagesJson("[{\"role\":\"user\",\"content\":\"hi\",\"toolName\":null}]");
    entity.setStatus("active");
    entity.setCreatedAt(LocalDateTime.of(2026, 9, 15, 8, 0));
    entity.setLastActiveAt(LocalDateTime.of(2026, 9, 15, 8, 1));
    repository.saveAndFlush(entity);

    Session reloaded = repository.findById("cli:round:trip").orElseThrow();

    assertEquals("weather", reloaded.getAgentName());
    assertEquals("cli", reloaded.getChannel());
    assertEquals("round", reloaded.getUserId());
    assertNotNull(reloaded.getMessagesJson());
    assertEquals("active", reloaded.getStatus());
    assertNull(reloaded.getArchivedAt(), "本节不产生 archived，列建好恒空");
  }

  @Test
  @DisplayName("三类消息_序列化回读完整且有序")
  void threeRoleMessages_roundTripCompleteAndOrdered() {
    JpaSessionManager manager = new JpaSessionManager(repository);
    var session = manager.getOrCreate("cli", "msg", "weather");
    ToolCallRequest call = new ToolCallRequest("call-st", "http_get", "{}");
    session.appendUser("查天气");
    session.appendAssistant(new ProviderResponse(null, List.of(call)));
    session.appendToolResult(call, ToolResult.ok("{\"temp\":26}"));
    session.appendAssistant(new ProviderResponse("北京 26 度，穿短袖", List.of()));
    manager.save(session);

    var restored = manager.getOrCreate("cli", "msg", "weather");

    assertEquals(4, restored.messages().size(), "零丢失");
    assertEquals("user", restored.messages().get(0).role());
    assertEquals("assistant", restored.messages().get(1).role());
    assertEquals(
        1, restored.messages().get(1).toolCalls().size(), "31 节：assistant 的 toolCalls 落库回读");
    assertEquals("call-st", restored.messages().get(1).toolCalls().get(0).id());
    assertEquals("tool", restored.messages().get(2).role());
    assertEquals("http_get", restored.messages().get(2).toolName());
    assertEquals("{\"temp\":26}", restored.messages().get(2).content());
    assertEquals("call-st", restored.messages().get(2).toolCallId(), "31 节：toolCallId 落库回读（配对不丢）");
    assertEquals("北京 26 度，穿短袖", restored.messages().get(3).content());
  }

  @Test
  @DisplayName("模拟重启_历史还在_恢复后追加不覆盖")
  void simulateRestart_historySurvives() {
    JpaSessionManager firstRun = new JpaSessionManager(repository);
    var session = firstRun.getOrCreate("cli", "restart", "weather");
    session.appendUser("第一句");
    firstRun.save(session);

    // 模拟重启：同一库文件上新建 manager 实例，历史完整恢复
    JpaSessionManager restarted = new JpaSessionManager(repository);
    var restored = restarted.getOrCreate("cli", "restart", "weather");
    assertEquals(1, restored.messages().size());

    // 恢复后的对话在既有历史上追加（analyze M2：不是覆盖）
    restored.appendUser("第二句");
    restarted.save(restored);

    var afterAppend = new JpaSessionManager(repository).getOrCreate("cli", "restart", "weather");
    assertEquals(2, afterAppend.messages().size(), "追加后 +1");
    assertEquals("第一句", afterAppend.messages().get(0).content(), "原序不变");
    assertEquals("第二句", afterAppend.messages().get(1).content());
  }

  @Test
  @DisplayName("零消息新会话_正常保存与恢复")
  void zeroMessageSession_savesAndRestores() {
    JpaSessionManager manager = new JpaSessionManager(repository);
    manager.save(manager.getOrCreate("cli", "empty", "default"));

    var restored = manager.getOrCreate("cli", "empty", "default");

    assertEquals(0, restored.messages().size(), "零消息不是错误");
  }

  @Test
  @DisplayName("九列逐一对齐技§9.2")
  void columnsMatchTechSpec() throws Exception {
    List<String> columns = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        ResultSet rs = connection.getMetaData().getColumns(null, null, "sessions", null)) {
      while (rs.next()) {
        columns.add(rs.getString("COLUMN_NAME"));
      }
    }

    assertEquals(
        List.of(
            "session_id",
            "agent_name",
            "channel",
            "user_id",
            "messages_json",
            "status",
            "created_at",
            "last_active_at",
            "archived_at"),
        columns,
        "列名逐字技 §9.2（agent_name 为拍板②字面量）");
  }

  private static String createSchemaBackedSqlite() {
    try {
      Path dir = Files.createTempDirectory("yokeos-session-repo-test");
      String url = "jdbc:sqlite:" + dir.resolve("sessions-repo.db");
      try (Connection connection = DriverManager.getConnection(url)) {
        ScriptUtils.executeSqlScript(
            connection, new ClassPathResource("/db/schema-002-sessions.sql"));
      }
      return url;
    } catch (Exception e) {
      throw new IllegalStateException("测试库初始化失败", e);
    }
  }

  /** 纯 Java 配置的 JPA 上下文（16 节同款）：SQLite + community 方言 + ddl 不自动建。 */
  @Configuration
  @EnableJpaRepositories(basePackageClasses = SessionRepository.class)
  @EntityScan(basePackageClasses = Session.class)
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

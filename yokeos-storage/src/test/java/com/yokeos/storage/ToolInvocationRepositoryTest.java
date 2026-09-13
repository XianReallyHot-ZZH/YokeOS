package com.yokeos.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.EntityManagerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.annotation.Transactional;
import org.sqlite.SQLiteDataSource;

/**
 * {@code tool_invocations} 落库 harness（第 17 节起写入）：建表必须走手工脚本 db/schema-001-audit.sql（16 节
 * 已建、本节零改动——不让 Hibernate 自动建，否则测试绿了、生产跑真脚本时列名对不上），存读往返 + 成败两列真实 存在 + 按 session_id 关联查询。构造形态照
 * LlmCallRepositoryTest。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ToolInvocationRepositoryTest.TestJpaConfig.class)
@Transactional
class ToolInvocationRepositoryTest {

  /** 静态初始化 SQLite 临时库并执行手工建表脚本——必须先于 Spring 上下文创建（数据源 URL 才就绪）。 */
  private static final String JDBC_URL = createSchemaBackedSqlite();

  @Autowired ToolInvocationRepository repository;

  private JpaToolInvocationAuditor auditor;

  @BeforeEach
  void setUpAuditor() {
    auditor = new JpaToolInvocationAuditor(repository);
  }

  @Test
  @DisplayName("存读往返_全字段含成败两列")
  void saveAndReloadRoundTrip() {
    ToolInvocation row = new ToolInvocation();
    row.setSessionId("s-1");
    row.setToolName("http_get");
    row.setInputJson("{\"url\":\"https://a\"}");
    row.setResultJson("20度晴");
    row.setSuccess(true);
    row.setDurationMs(80L);
    row.setCreatedAt(java.time.LocalDateTime.of(2026, 9, 13, 12, 0));
    repository.saveAndFlush(row);

    ToolInvocation reloaded = repository.findById(row.getId()).orElseThrow();

    assertNotNull(reloaded.getId());
    assertEquals("s-1", reloaded.getSessionId());
    assertEquals("http_get", reloaded.getToolName());
    assertEquals("{\"url\":\"https://a\"}", reloaded.getInputJson());
    assertEquals("20度晴", reloaded.getResultJson());
    assertEquals(true, reloaded.getSuccess());
    assertNull(reloaded.getErrorMessage(), "成功行 error_message 为空");
    assertEquals(80L, reloaded.getDurationMs());
  }

  @Test
  @DisplayName("success与error_message列真实存在_失败行带原因")
  void successAndErrorColumnsExist() {
    ToolInvocation row = new ToolInvocation();
    row.setSessionId("s-2");
    row.setToolName("http_get");
    row.setInputJson("{}");
    row.setSuccess(false);
    row.setErrorMessage("connect refused");
    row.setDurationMs(5L);
    row.setCreatedAt(java.time.LocalDateTime.of(2026, 9, 13, 12, 1));
    repository.saveAndFlush(row);

    ToolInvocation reloaded = repository.findById(row.getId()).orElseThrow();

    assertEquals(false, reloaded.getSuccess());
    assertEquals("connect refused", reloaded.getErrorMessage(), "失败行必带原因");
  }

  @Test
  @DisplayName("按session_id关联查询")
  void findBySessionIdRoundTrip() {
    saveRow("sess-a", "http_get");
    saveRow("sess-a", "shell");
    saveRow("sess-b", "http_get");

    List<ToolInvocation> ofA = repository.findBySessionId("sess-a");

    assertEquals(2, ofA.size(), "只取该 session 的行");
    assertTrue(ofA.stream().allMatch(r -> "sess-a".equals(r.getSessionId())));
  }

  @Test
  @DisplayName("auditor端到端_成败各落一行")
  void auditorPersistsBothPaths() {
    auditor.record("s-9", "http_get", "{\"url\":\"u\"}", "ok-body", true, null, 30L);
    auditor.record("s-9", "shell", "ls", null, false, "not whitelisted", 2L);

    List<ToolInvocation> rows = repository.findBySessionId("s-9");
    assertEquals(2, rows.size());
    assertTrue(rows.stream().anyMatch(r -> Boolean.TRUE.equals(r.getSuccess())));
    assertTrue(
        rows.stream().anyMatch(r -> "not whitelisted".equals(r.getErrorMessage())),
        "失败行留痕（Sandbox 拒绝 24 节也走此路径）");
  }

  private void saveRow(String sessionId, String toolName) {
    ToolInvocation row = new ToolInvocation();
    row.setSessionId(sessionId);
    row.setToolName(toolName);
    row.setInputJson("{}");
    row.setSuccess(true);
    row.setDurationMs(1L);
    row.setCreatedAt(java.time.LocalDateTime.of(2026, 9, 13, 12, 2));
    repository.save(row);
  }

  private static String createSchemaBackedSqlite() {
    try {
      Path dir = Files.createTempDirectory("yokeos-tool-audit-test");
      String url = "jdbc:sqlite:" + dir.resolve("audit.db");
      try (Connection connection = DriverManager.getConnection(url)) {
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("/db/schema-001-audit.sql"));
      }
      return url;
    } catch (Exception e) {
      throw new IllegalStateException("测试库初始化失败", e);
    }
  }

  /** 纯 Java 配置的 JPA 上下文：SQLite + community 方言 + ddl 不自动建（只认手工脚本）。 */
  @Configuration
  @EnableJpaRepositories(basePackageClasses = ToolInvocationRepository.class)
  @EntityScan(basePackageClasses = ToolInvocation.class)
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

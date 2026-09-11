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
 * {@code llm_calls} 落库 harness（FR6）：建表必须走手工脚本 db/schema-001-audit.sql（不让 Hibernate
 * 自动建——否则测试绿了、生产跑真脚本时列名对不上），存读往返 + success/error_message 两列真实存在 + JpaLlmCallAuditor 端到端成败双行。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = LlmCallRepositoryTest.TestJpaConfig.class)
@Transactional
class LlmCallRepositoryTest {

  /** 静态初始化 SQLite 临时库并执行手工建表脚本——必须先于 Spring 上下文创建（数据源 URL 才就绪）。 */
  private static final String JDBC_URL = createSchemaBackedSqlite();

  @Autowired LlmCallRepository repository;

  private JpaLlmCallAuditor auditor;

  @BeforeEach
  void setUpAuditor() {
    auditor = new JpaLlmCallAuditor(repository);
  }

  @Test
  @DisplayName("存读往返_全字段含成败两列")
  void saveAndReloadRoundTrip() {
    LlmCall row = new LlmCall();
    row.setSessionId("s-1");
    row.setProvider("deepseek");
    row.setModel("deepseek-chat");
    row.setPromptTokens(11);
    row.setCompletionTokens(7);
    row.setTotalTokens(18);
    row.setSuccess(false);
    row.setErrorMessage("connect timeout");
    row.setDurationMs(120L);
    row.setCreatedAt(java.time.LocalDateTime.of(2026, 9, 10, 12, 0));
    repository.saveAndFlush(row);

    LlmCall reloaded = repository.findById(row.getId()).orElseThrow();

    assertNotNull(reloaded.getId());
    assertEquals("s-1", reloaded.getSessionId());
    assertEquals("deepseek", reloaded.getProvider());
    assertEquals(18, reloaded.getTotalTokens());
    assertEquals(false, reloaded.getSuccess());
    assertEquals("connect timeout", reloaded.getErrorMessage());
    assertEquals(120L, reloaded.getDurationMs());
  }

  @Test
  @DisplayName("auditor端到端_失败行先落账带原因")
  void auditorPersistsFailureRow() {
    auditor.record("s-2", "kimi", "kimi-latest", null, null, null, false, "rate limited", 55L);

    var rows = repository.findAll();
    assertEquals(1, rows.size());
    assertEquals(false, rows.get(0).getSuccess());
    assertEquals("rate limited", rows.get(0).getErrorMessage());
    assertEquals(55L, rows.get(0).getDurationMs());
    assertTrue(rows.get(0).getCreatedAt() != null, "created_at 必须落值");
  }

  @Test
  @DisplayName("auditor端到端_成功行带token三项无原因")
  void auditorPersistsSuccessRow() {
    auditor.record("s-3", "deepseek", "deepseek-chat", 100, 60, 160, true, null, 88L);

    var rows = repository.findAll();
    assertEquals(1, rows.size());
    assertEquals(true, rows.get(0).getSuccess());
    assertEquals(160, rows.get(0).getTotalTokens());
    assertNull(rows.get(0).getErrorMessage());
  }

  private static String createSchemaBackedSqlite() {
    try {
      Path dir = Files.createTempDirectory("yokeos-audit-test");
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
  @EnableJpaRepositories(basePackageClasses = LlmCallRepository.class)
  @EntityScan(basePackageClasses = LlmCall.class)
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

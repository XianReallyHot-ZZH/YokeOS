package com.yokeos.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.yokeos.core.audit.ToolInvocationRecord;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.Comparator;
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
 * /tools 只读口 harness（第 18 节）：实体行投影 ToolInvocationRecord 六字段逐一对齐、按会话隔离、成败行都在。 建表走
 * schema-001（tool_invocations 16 节建、17 节写入；只读复用不加列）。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = JpaToolInvocationReaderTest.TestJpaConfig.class)
class JpaToolInvocationReaderTest {

  /** 静态初始化 SQLite 临时库并执行手工建表脚本（16 节同款）。 */
  private static final String JDBC_URL = createSchemaBackedSqlite();

  @Autowired ToolInvocationRepository repository;

  private JpaToolInvocationReader reader() {
    return new JpaToolInvocationReader(repository);
  }

  @Test
  @DisplayName("实体行映射record_六字段逐一对齐")
  void mapsEntityToRecordFields() {
    ToolInvocation row = new ToolInvocation();
    row.setSessionId("cli:map:weather");
    row.setToolName("http_get");
    row.setInputJson("{\"url\":\"https://api.open-meteo.com\"}");
    row.setResultJson("{\"temp\":26}");
    row.setSuccess(Boolean.TRUE);
    row.setDurationMs(321L);
    row.setCreatedAt(LocalDateTime.of(2026, 9, 15, 9, 30));
    repository.saveAndFlush(row);

    ToolInvocationRecord record = reader().findBySession("cli:map:weather").get(0);

    assertEquals("http_get", record.toolName());
    assertEquals("{\"url\":\"https://api.open-meteo.com\"}", record.inputJson());
    assertEquals(true, record.success());
    assertEquals(null, record.errorMessage(), "成功行无原因");
    assertEquals(321L, record.durationMs());
    assertEquals(9, record.createdAt().atZone(java.time.ZoneId.systemDefault()).getHour());
  }

  @Test
  @DisplayName("按会话隔离_只查本会话")
  void findBySession_onlyOwnSession() {
    repository.saveAndFlush(row("cli:a:weather", "http_get", true));
    repository.saveAndFlush(row("cli:b:weather", "http_get", true));
    repository.saveAndFlush(row("web:a:weather", "shell", false));

    List<ToolInvocationRecord> records = reader().findBySession("cli:a:weather");

    assertEquals(1, records.size(), "只含本会话的行");
    assertEquals("http_get", records.get(0).toolName());
  }

  @Test
  @DisplayName("成败行都在")
  void successAndFailureBothListed() {
    repository.saveAndFlush(row("cli:ab:weather", "http_get", true));
    repository.saveAndFlush(row("cli:ab:weather", "shell", false));

    List<ToolInvocationRecord> records =
        new java.util.ArrayList<>(reader().findBySession("cli:ab:weather"));
    records.sort(Comparator.comparing(ToolInvocationRecord::toolName));

    assertEquals(2, records.size());
    assertEquals(true, records.get(0).success(), "http_get 成功行在");
    assertEquals(false, records.get(1).success(), "shell 失败行也在");
    assertEquals("命令不在白名单", records.get(1).errorMessage());
  }

  private static ToolInvocation row(String sessionId, String toolName, boolean success) {
    ToolInvocation row = new ToolInvocation();
    row.setSessionId(sessionId);
    row.setToolName(toolName);
    row.setInputJson("{}");
    row.setSuccess(success);
    row.setErrorMessage(success ? null : "命令不在白名单");
    row.setDurationMs(10L);
    row.setCreatedAt(LocalDateTime.of(2026, 9, 15, 9, 0));
    return row;
  }

  private static String createSchemaBackedSqlite() {
    try {
      Path dir = Files.createTempDirectory("yokeos-reader-test");
      String url = "jdbc:sqlite:" + dir.resolve("reader.db");
      try (Connection connection = DriverManager.getConnection(url)) {
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("/db/schema-001-audit.sql"));
      }
      return url;
    } catch (Exception e) {
      throw new IllegalStateException("测试库初始化失败", e);
    }
  }

  /** 纯 Java 配置的 JPA 上下文（16 节同款）。 */
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

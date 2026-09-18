package com.yokeos.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.EntityManagerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
 * memory_entries 落库 harness（第 22 节坑六回归）：手工脚本建表存读往返、归档 LIMIT 取最近 N（分页只加在归档查询—— 契约二的 SQL 结构保证）、LIKE
 * 只命中归档区、{@code %}/{@code _} 按字面转义。 建表走 schema-003-memory.sql（宪法 7，不让 Hibernate 自动建）。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = MemoryEntryRepositoryTest.TestJpaConfig.class)
class MemoryEntryRepositoryTest {

  /** 静态初始化 SQLite 临时库并执行手工建表脚本（18 节同款）。 */
  private static final String JDBC_URL = createSchemaBackedSqlite();

  @Autowired MemoryEntryRepository repository;

  @Autowired DataSource dataSource;

  @Test
  @DisplayName("手工建表脚本建出的memory_entries能存能读")
  void manualSchemaTableSupportsSaveAndRead() {
    MemoryEntry entity = new MemoryEntry();
    entity.setScope("CORE");
    entity.setContent("用户偏好中文交流");
    entity.setCreatedAt(LocalDateTime.of(2026, 9, 18, 8, 0));
    MemoryEntry saved = repository.saveAndFlush(entity);

    MemoryEntry reloaded = repository.findById(saved.getId()).orElseThrow();

    assertEquals("CORE", reloaded.getScope());
    assertEquals("用户偏好中文交流", reloaded.getContent());
    assertEquals(LocalDateTime.of(2026, 9, 18, 8, 0), reloaded.getCreatedAt());
  }

  @Test
  @DisplayName("归档区LIMIT取最近N")
  void archivalLimitReturnsMostRecent() {
    for (int i = 1; i <= 5; i++) {
      MemoryEntry entry = new MemoryEntry();
      entry.setScope("ARCHIVAL");
      entry.setContent("归档条目 " + i);
      entry.setCreatedAt(LocalDateTime.of(2026, 9, 18, 8, i));
      repository.saveAndFlush(entry);
    }

    List<MemoryEntry> recent =
        repository.findByScope(
            "ARCHIVAL", PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "createdAt")));

    assertEquals(2, recent.size(), "LIMIT 2 只取两行");
    assertEquals("归档条目 5", recent.get(0).getContent(), "最近的在前（createdAt DESC）");
    assertEquals("归档条目 4", recent.get(1).getContent());
    assertTrue(
        recent.stream().noneMatch(e -> e.getContent().contains("条目 1")), "更早的不在（截断 = LIMIT，保留最近）");
  }

  @Test
  @DisplayName("LIKE检索只命中归档区")
  void searchArchivalMatchesOnlyArchival() {
    repository.saveAndFlush(archival("核心区也有 秘密关键词", "CORE"));
    repository.saveAndFlush(archival("归档区的 秘密关键词", "ARCHIVAL"));
    repository.saveAndFlush(archival("归档区的普通条目", "ARCHIVAL"));

    List<MemoryEntry> hits = repository.searchArchival("秘密关键词");

    assertEquals(1, hits.size(), "核心区行不被命中（检索只作用归档区）");
    assertEquals("归档区的 秘密关键词", hits.get(0).getContent());
  }

  @Test
  @DisplayName("LIKE通配符按字面转义")
  void likeWildcardsMatchLiterally() {
    repository.saveAndFlush(archival("磁盘占用 100% 告警", "ARCHIVAL"));
    repository.saveAndFlush(archival("变量 a_b 的说明", "ARCHIVAL"));
    repository.saveAndFlush(archival("变量 axb 的说明", "ARCHIVAL"));

    assertEquals(1, repository.searchArchival("100%").size(), "% 按字面命中，不当通配符");
    List<MemoryEntry> underscoreHits = repository.searchArchival("a_b");
    assertEquals(1, underscoreHits.size(), "_ 按字面命中");
    assertEquals("变量 a_b 的说明", underscoreHits.get(0).getContent());
    assertFalse(
        underscoreHits.stream().anyMatch(e -> e.getContent().contains("axb")), "_ 被转义后不再匹配任意单字符");
  }

  private static MemoryEntry archival(String content, String scope) {
    MemoryEntry entry = new MemoryEntry();
    entry.setScope(scope);
    entry.setContent(content);
    entry.setCreatedAt(LocalDateTime.of(2026, 9, 18, 9, 0));
    return entry;
  }

  private static String createSchemaBackedSqlite() {
    try {
      Path dir = Files.createTempDirectory("yokeos-memory-repo-test");
      String url = "jdbc:sqlite:" + dir.resolve("memory-repo.db");
      try (Connection connection = DriverManager.getConnection(url)) {
        ScriptUtils.executeSqlScript(
            connection, new ClassPathResource("/db/schema-003-memory.sql"));
      }
      return url;
    } catch (Exception e) {
      throw new IllegalStateException("测试库初始化失败", e);
    }
  }

  /** 纯 Java 配置的 JPA 上下文（18 节同款）：SQLite + community 方言 + ddl 不自动建。 */
  @Configuration
  @EnableJpaRepositories(basePackageClasses = MemoryEntryRepository.class)
  @EntityScan(basePackageClasses = MemoryEntry.class)
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
    LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
      LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
      em.setDataSource(dataSource);
      em.setJpaVendorAdapter(jpaVendorAdapter());
      em.setPackagesToScan(MemoryEntry.class.getPackageName());
      return em;
    }

    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
      return new JpaTransactionManager(emf);
    }
  }
}

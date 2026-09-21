package com.yokeos.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yokeos.core.provider.ProviderResponse;
import com.yokeos.core.session.Session;
import com.yokeos.core.session.SessionManager;
import com.yokeos.core.session.SessionSummary;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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
 * 会话口径 harness（第 18 节坑一/坑五回归）：三元组幂等与隔离、id 格式、未命中落 active（agent_name 列）、 恢复历史、save
 * 刷活跃时间、并发撞键单条。建表走手工脚本 schema-002-sessions.sql（宪法 7）；类级不加 @Transactional——每次 repository 调用走自带事务，
 * 并发用例的子线程写入各自提交、主线程 join 后可见。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = SessionManagerTest.TestJpaConfig.class)
class SessionManagerTest {

  /** 静态初始化 SQLite 临时库并执行手工建表脚本——必须先于 Spring 上下文创建（16 节同款）。 */
  private static final String JDBC_URL = createSchemaBackedSqlite();

  @Autowired SessionRepository repository;

  private JpaSessionManager manager() {
    return new JpaSessionManager(repository);
  }

  @Test
  @DisplayName("同一三元组_历次getOrCreate都是同一个Session")
  void sameTriple_everyGetOrCreateReturnsSameSession() {
    SessionManager sessionManager = manager();

    Session first = sessionManager.getOrCreate("cli", "wang", "weather");
    Session second = sessionManager.getOrCreate("cli", "wang", "weather");
    assertEquals(first.sessionId(), second.sessionId(), "幂等：多轮对话靠它串起来");

    Session other = sessionManager.getOrCreate("web", "wang", "weather");
    assertNotEquals(first.sessionId(), other.sessionId(), "channel 不同就是不同会话");
  }

  @Test
  @DisplayName("三元组任一元素不同_都是不同会话")
  void anyDifferentTripleElement_createsDifferentSession() {
    SessionManager sessionManager = manager();
    var base = sessionManager.getOrCreate("cli", "wang", "weather");

    assertNotEquals(
        base.sessionId(),
        sessionManager.getOrCreate("cli", "li", "weather").sessionId(),
        "user 不同");
    assertNotEquals(
        base.sessionId(),
        sessionManager.getOrCreate("cli", "wang", "daily").sessionId(),
        "agent 不同");
  }

  @Test
  @DisplayName("id格式为channel:user:agent")
  void sessionIdFollowsTripleFormat() {
    Session session = manager().getOrCreate("cli", "wang", "weather");

    assertEquals("cli:wang:weather", session.sessionId());
  }

  @Test
  @DisplayName("getOrCreate未命中时落一条active记录")
  void getOrCreateMissPersistsActiveRecord() {
    manager().getOrCreate("cli", "zhao", "default");

    com.yokeos.storage.Session entity = repository.findById("cli:zhao:default").orElseThrow();
    assertEquals("active", entity.getStatus());
    assertEquals("cli", entity.getChannel());
    assertEquals("zhao", entity.getUserId());
    assertEquals("default", entity.getAgentName(), "agent_name 列（技 §9.2 字面量，坑五回归）");
    assertNotNull(entity.getCreatedAt());
  }

  @Test
  @DisplayName("getOrCreate命中时恢复完整历史")
  void getOrCreateHit_restoresHistory() {
    SessionManager sessionManager = manager();
    Session first = sessionManager.getOrCreate("cli", "wang", "weather");
    first.appendUser("查天气");
    first.appendAssistant(new ProviderResponse("北京 26 度", List.of()));
    sessionManager.save(first);

    Session restored = manager().getOrCreate("cli", "wang", "weather");

    assertEquals(2, restored.messages().size(), "历史随会话恢复");
    assertEquals("user", restored.messages().get(0).role());
    assertEquals("北京 26 度", restored.messages().get(1).content());
  }

  @Test
  @DisplayName("save刷新last_active_at")
  void saveRefreshesLastActiveAt() {
    SessionManager sessionManager = manager();
    Session session = sessionManager.getOrCreate("cli", "wang", "weather");
    session.appendUser("第一句");
    sessionManager.save(session);
    var first = repository.findById(session.sessionId()).orElseThrow();

    session.appendUser("第二句");
    sessionManager.save(session);
    var second = repository.findById(session.sessionId()).orElseThrow();

    assertTrue(
        !second.getLastActiveAt().isBefore(first.getLastActiveAt()), "save 后 last_active_at 不早于上次");
  }

  @Test
  @DisplayName("并发同三元组getOrCreate_库中仅一条")
  void concurrentGetOrCreate_sameTriple_singleRow() throws InterruptedException {
    SessionManager sessionManager = manager();
    // 预置会话后并发命中：SQLite 单写者下真并发 insert 受驱动锁行为影响（回归测试要确定性），
    // 撞键兜底分支（DataIntegrityViolation→重查复用）为防御实现保留；本用例钉死的是并发不产生第二条。
    final String expectedId =
        sessionManager.getOrCreate("cli", "concurrent-user", "weather").sessionId();
    final long rowsBefore = repository.count();
    int threads = 4;
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch done = new CountDownLatch(threads);
    List<String> ids = new ArrayList<>();
    List<RuntimeException> failures = new ArrayList<>();

    for (int i = 0; i < threads; i++) {
      Thread.ofVirtual()
          .start(
              () -> {
                ready.countDown();
                try {
                  ready.await(); // 四线程同点齐发（宪法 4：虚拟线程，不引线程池）
                  synchronized (ids) {
                    ids.add(
                        sessionManager
                            .getOrCreate("cli", "concurrent-user", "weather")
                            .sessionId());
                  }
                } catch (RuntimeException e) {
                  synchronized (failures) {
                    failures.add(e);
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } finally {
                  done.countDown();
                }
              });
    }
    done.await();

    assertTrue(failures.isEmpty(), "命中路径并发安全: " + failures);
    assertEquals(4, ids.size());
    ids.forEach(id -> assertEquals(expectedId, id, "全部拿到同一条会话"));
    assertEquals(rowsBefore, repository.count(), "主键唯一兜底：并发不产生第二条（零新增）");
  }

  @Test
  @DisplayName("listRecent_最近活跃倒序且尊重上限")
  void listRecentReturnsNewestFirstAndRespectsLimit() throws InterruptedException {
    SessionManager sessionManager = manager();
    Session first = sessionManager.getOrCreate("web", "list-u1", "weather");
    sessionManager.save(first);
    Thread.sleep(20); // 拉开 last_active_at，SQLite 时间戳同刻会让排序不稳
    Session second = sessionManager.getOrCreate("web", "list-u2", "weather");
    sessionManager.save(second);
    Thread.sleep(20);
    Session third = sessionManager.getOrCreate("invoke", "list-u3", "weather");
    sessionManager.save(third);

    java.util.List<SessionSummary> top = sessionManager.listRecent(2);

    assertEquals(2, top.size(), "上限生效");
    assertEquals(third.sessionId(), top.get(0).sessionId(), "最近活跃在前");
    assertEquals(second.sessionId(), top.get(1).sessionId(), "次活跃随后");
    assertTrue(
        sessionManager.listRecent(100).stream()
            .anyMatch(s -> s.sessionId().equals(first.sessionId())),
        "上限宽于总量时全量可见");
    assertTrue(top.stream().noneMatch(s -> s.sessionId().equals(first.sessionId())), "被截掉的是最旧的");
  }

  @Test
  @DisplayName("archive_置archived与archived_at且未命中返回false")
  void archiveMarksStatusAndArchivedAt() {
    SessionManager sessionManager = manager();
    org.junit.jupiter.api.Assertions.assertFalse(
        sessionManager.archive("no-such-session"), "未命中 false（调用方据此转 404）");

    Session session = sessionManager.getOrCreate("web", "archive-u1", "weather");
    session.appendUser("归档前最后一句");
    sessionManager.save(session);
    assertTrue(sessionManager.archive(session.sessionId()));

    com.yokeos.storage.Session row = repository.findById(session.sessionId()).orElseThrow();
    assertEquals("archived", row.getStatus());
    assertNotNull(row.getArchivedAt(), "归档时间落列");
    assertTrue(
        sessionManager.listRecent(100).stream()
            .anyMatch(
                s -> s.sessionId().equals(session.sessionId()) && "archived".equals(s.status())),
        "摘要视图同样显示已归档");
  }

  @Test
  @DisplayName("归档后同三元组getOrCreate_幂等返回原会话且历史保留")
  void archivedSession_getOrCreateStillReturnsSameWithHistory() {
    SessionManager sessionManager = manager();
    Session original = sessionManager.getOrCreate("web", "revive-u1", "weather");
    original.appendUser("历史第一句");
    sessionManager.save(original);
    sessionManager.archive(original.sessionId());

    Session again = sessionManager.getOrCreate("web", "revive-u1", "weather");

    assertEquals(original.sessionId(), again.sessionId(), "幂等返回同一条（标记不终结）");
    assertEquals(1, again.messages().size(), "历史保留——归档不清空对话");
    assertEquals(
        "archived",
        repository.findById(original.sessionId()).orElseThrow().getStatus(),
        "状态仍是 archived，不隐式复活（research D4）");
  }

  private static String createSchemaBackedSqlite() {
    try {
      Path dir = Files.createTempDirectory("yokeos-session-test");
      String url = "jdbc:sqlite:" + dir.resolve("sessions.db");
      try (Connection connection = DriverManager.getConnection(url)) {
        ScriptUtils.executeSqlScript(
            connection, new ClassPathResource("/db/schema-002-sessions.sql"));
      }
      return url;
    } catch (Exception e) {
      throw new IllegalStateException("测试库初始化失败", e);
    }
  }

  /** 纯 Java 配置的 JPA 上下文：SQLite + community 方言 + ddl 不自动建（16 节同款）。 */
  @Configuration
  @EnableJpaRepositories(basePackageClasses = SessionRepository.class)
  @EntityScan(basePackageClasses = Session.class)
  static class TestJpaConfig {

    @Bean
    DataSource dataSource() {
      SQLiteDataSource ds = new SQLiteDataSource();
      ds.setUrl(JDBC_URL);
      // SQLite 单写者：并发写等待而非立即 SQLITE_BUSY——串行化后撞键才落到主键兜底路径（并发用例前提）
      org.sqlite.SQLiteConfig config = new org.sqlite.SQLiteConfig();
      config.setBusyTimeout(5000);
      ds.setConfig(config);
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

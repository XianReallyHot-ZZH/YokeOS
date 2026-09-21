package com.yokeos.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yokeos.core.agent.ScheduledTaskStore;
import com.yokeos.core.agent.ScheduledTaskView;
import com.yokeos.core.agent.TaskExecutionView;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
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
 * scheduled_tasks / task_executions 落库 harness（第 25 节坑十一回归）：reconcile 幂等不冲掉 enabled 与
 * run_count（重启不丢）、recordExecution 历史一行 + 任务状态更新、isEnabled 未登记 fail-open、executions 倒序
 * limit、schema-004 两表 DDL 幂等（宪法 7 守点）。建表走 schema-004-scheduler.sql（不让 Hibernate 自动建）。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = JpaScheduledTaskStoreTest.TestJpaConfig.class)
class JpaScheduledTaskStoreTest {

  private static final String TASK_ID = "ops-agent#1";

  private static final Instant T1 = Instant.parse("2026-09-21T01:00:00Z");

  private static final Instant T2 = Instant.parse("2026-09-21T01:05:00Z");

  /** 静态初始化 SQLite 临时库并执行手工建表脚本（22 节同款）。 */
  private static final String JDBC_URL = createSchemaBackedSqlite();

  @Autowired ScheduledTaskRepository tasks;

  @Autowired TaskExecutionRepository executions;

  @Autowired ScheduledTaskStore store;

  /** 静态单例库共享给全部用例——断言「恰一行/唯一任务」的用例各自清库（22 节用例不查全表故无需此步）。 */
  @org.junit.jupiter.api.BeforeEach
  void cleanTables() {
    executions.deleteAll();
    tasks.deleteAll();
  }

  @Test
  @DisplayName("reconcile新任务默认启用零累计且定义字段入库")
  void reconcileInsertsNewTaskEnabledWithZeroRuns() {
    store.reconcile(TASK_ID, "ops-agent", "0 0 9 * * *", "Asia/Shanghai", "日报", T1);

    ScheduledTaskView view = onlyTask();
    assertEquals(TASK_ID, view.taskId());
    assertEquals("ops-agent", view.profileName());
    assertEquals("0 0 9 * * *", view.cron());
    assertEquals("Asia/Shanghai", view.zone());
    assertEquals("日报", view.message());
    assertTrue(view.enabled(), "新登记默认启用");
    assertEquals(0, view.runCount(), "新登记零累计");
    assertEquals(T1, view.nextRunAt());
    assertNull(view.lastStatus(), "从未执行过 last_status 为空");
  }

  @Test
  @DisplayName("reconcile已存在行只更新定义字段不冲掉enabled与run_count（坑十一，重启不丢）")
  void reconcileKeepsEnabledAndRunCount() {
    store.reconcile(TASK_ID, "ops-agent", "0 0 9 * * *", "Asia/Shanghai", "日报", T1);
    store.recordExecution(TASK_ID, "scheduler:scheduler:ops-agent", T1, true, null, 1200L, T2);
    store.setEnabled(TASK_ID, false); // 模拟管理侧停用后进程重启

    store.reconcile(TASK_ID, "ops-agent", "0 0 18 * * *", "UTC", "改点的日报", T2);

    ScheduledTaskView view = onlyTask();
    assertEquals("0 0 18 * * *", view.cron(), "定义字段更新（改 cron 免重启重协调）");
    assertEquals("UTC", view.zone());
    assertEquals("改点的日报", view.message());
    assertFalse(view.enabled(), "停用状态保留，不被登记冲掉");
    assertEquals(1, view.runCount(), "累计次数保留，不被登记冲掉");
  }

  @Test
  @DisplayName("recordExecution成功路径历史一行且任务状态更新")
  void recordExecutionInsertsHistoryAndUpdatesTask() {
    store.reconcile(TASK_ID, "ops-agent", "0 0 9 * * *", "Asia/Shanghai", "日报", T1);

    store.recordExecution(TASK_ID, "scheduler:scheduler:ops-agent", T1, true, null, 1500L, T2);

    List<TaskExecutionView> history = store.executions(TASK_ID, 10);
    assertEquals(1, history.size(), "执行历史恰多一行");
    TaskExecutionView row = history.get(0);
    assertEquals(TASK_ID, row.taskId());
    assertEquals("scheduler:scheduler:ops-agent", row.sessionId(), "钟推会话关联");
    assertTrue(row.success());
    assertEquals(1500L, row.durationMs());
    ScheduledTaskView view = onlyTask();
    assertEquals("success", view.lastStatus());
    assertEquals(1, view.runCount());
    assertEquals(T2, view.nextRunAt());
  }

  @Test
  @DisplayName("recordExecution失败路径同样留痕（success=false与失败原因）")
  void recordExecutionFailureLeavesTrace() {
    store.reconcile(TASK_ID, "ops-agent", "0 0 9 * * *", "Asia/Shanghai", "日报", T1);

    store.recordExecution(TASK_ID, null, T1, false, "boom", 800L, T2);

    TaskExecutionView row = store.executions(TASK_ID, 10).get(0);
    assertFalse(row.success());
    assertEquals("boom", row.errorMessage());
    assertNull(row.sessionId(), "执行抛在拿到会话之前时 sessionId 为空");
    assertEquals("failed", onlyTask().lastStatus());
  }

  @Test
  @DisplayName("isEnabled未登记返回true（fail-open）")
  void isEnabledFailsOpenForUnknownTask() {
    assertTrue(store.isEnabled("never-registered#1"), "登记滞后不该让任务漏跑");
  }

  @Test
  @DisplayName("setEnabled切换生效")
  void setEnabledToggles() {
    store.reconcile(TASK_ID, "ops-agent", "0 0 9 * * *", "Asia/Shanghai", "日报", T1);

    store.setEnabled(TASK_ID, false);
    assertFalse(store.isEnabled(TASK_ID));

    store.setEnabled(TASK_ID, true);
    assertTrue(store.isEnabled(TASK_ID));
  }

  @Test
  @DisplayName("executions按开始时间倒序取limit")
  void executionsReturnsMostRecentFirstWithLimit() {
    store.reconcile(TASK_ID, "ops-agent", "0 * * * * *", "Asia/Shanghai", "高频", T1);
    for (int i = 0; i < 3; i++) {
      store.recordExecution(
          TASK_ID, "sid", T1.plusSeconds(i * 60), true, null, 10L, T1.plusSeconds((i + 1) * 60));
    }

    List<TaskExecutionView> recent = store.executions(TASK_ID, 2);

    assertEquals(2, recent.size(), "limit 2 只取两行");
    assertEquals(T1.plusSeconds(120), recent.get(0).startedAt(), "最近的在前（started_at DESC）");
    assertEquals(T1.plusSeconds(60), recent.get(1).startedAt());
  }

  @Test
  @DisplayName("schema-004两表DDL幂等（重复执行不报错，宪法7守点）")
  void schemaDdlIsIdempotent() {
    assertDoesNotThrow(
        () -> {
          try (Connection connection = DriverManager.getConnection(JDBC_URL)) {
            ScriptUtils.executeSqlScript(
                connection, new ClassPathResource("/db/schema-004-scheduler.sql"));
          }
        },
        "CREATE TABLE IF NOT EXISTS 重复执行应无副作用");
  }

  private ScheduledTaskView onlyTask() {
    List<ScheduledTaskView> all = store.list();
    assertEquals(1, all.size(), "测试隔离假设：本用例只登记一个任务");
    return all.get(0);
  }

  private static String createSchemaBackedSqlite() {
    try {
      Path dir = Files.createTempDirectory("yokeos-scheduler-store-test");
      String url = "jdbc:sqlite:" + dir.resolve("scheduler-store.db");
      try (Connection connection = DriverManager.getConnection(url)) {
        ScriptUtils.executeSqlScript(
            connection, new ClassPathResource("/db/schema-004-scheduler.sql"));
      }
      return url;
    } catch (Exception e) {
      throw new IllegalStateException("测试库初始化失败", e);
    }
  }

  /** 纯 Java 配置的 JPA 上下文（22 节同款）：SQLite + community 方言 + ddl 不自动建。 */
  @Configuration
  @EnableJpaRepositories(basePackageClasses = ScheduledTaskRepository.class)
  @EntityScan(basePackageClasses = ScheduledTask.class)
  static class TestJpaConfig {

    @Bean
    DataSource dataSource() {
      SQLiteDataSource ds = new SQLiteDataSource();
      ds.setUrl(JDBC_URL);
      return ds;
    }

    @Bean
    ScheduledTaskStore scheduledTaskStore(
        ScheduledTaskRepository tasks, TaskExecutionRepository executions) {
      return new JpaScheduledTaskStore(tasks, executions);
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
      em.setPackagesToScan(ScheduledTask.class.getPackageName());
      return em;
    }

    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
      return new JpaTransactionManager(emf);
    }
  }
}

package com.yokeos.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yokeos.channel.cli.CliChannel;
import com.yokeos.core.agent.AgentService;
import com.yokeos.core.session.SessionManager;
import com.yokeos.storage.JpaSessionManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 装配完整性 harness（第 18 节坑二回归——宪法 9：参照留人工目检的 "Found N JPA repository interfaces"
 * 升格为机器断言）：哑配置（classpath 无 application.yaml → provider 清单为空，无需真 key）+ @TempDir SQLite 起
 * YokeosRuntime 同款上下文，断言仓库 Bean 数 &gt; 0、全链 Bean 就位、真实 Bean 完成会话往返。 本地 hermetic 无网络，正常跑不标
 * integration（research D8；与 YokeosBootApplicationLoadTest 同款定位）。
 */
class YokeosRuntimeAssemblyTest {

  @TempDir static Path dbDir;

  @TempDir Path workspace;

  private ConfigurableApplicationContext bootContext() {
    // web(NONE)：chat/装配测试同款——classpath 的 reactor 会让 Boot 推断 REACTIVE 而起不来（无 Web 栈需求）
    return new SpringApplicationBuilder(YokeosRuntime.class)
        .web(org.springframework.boot.WebApplicationType.NONE)
        .properties(
            "spring.datasource.url=jdbc:sqlite:" + dbDir.resolve("assembly.db"),
            "spring.datasource.driver-class-name=org.sqlite.JDBC",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:db/schema-001-audit.sql,"
                + "classpath:db/schema-002-sessions.sql",
            "yokeos.root=" + workspace.toAbsolutePath())
        .run();
  }

  @Test
  @DisplayName("重命令上下文_JPA仓库必须装配到位")
  void jpaRepositoriesWired_countGreaterThanZero() {
    try (ConfigurableApplicationContext ctx = bootContext()) {
      Map<String, JpaRepository> repositories = ctx.getBeansOfType(JpaRepository.class);
      assertFalse(
          repositories.isEmpty(),
          "坑二：Found 0 = 审计与会话静默写不进去，带病运行必须在此刻红（实际 " + repositories.keySet() + "）");
      assertTrue(repositories.size() >= 3, "至少三仓库：llm_calls / tool_invocations / sessions");
    }
  }

  @Test
  @DisplayName("运行链Bean全部就位")
  void runtimeBeansAllPresent() {
    try (ConfigurableApplicationContext ctx = bootContext()) {
      assertInstanceOf(JpaSessionManager.class, ctx.getBean(SessionManager.class));
      assertTrue(ctx.getBean(AgentService.class) != null, "统一处理入口就位");
      assertTrue(ctx.getBean(CliChannel.class) != null, "交互通道就位");
      assertTrue(ctx.getBean("llmCallAuditor") != null, "LLM 审计就位");
      assertTrue(ctx.getBean("toolInvocationAuditor") != null, "工具审计就位");
    }
  }

  @Test
  @DisplayName("真实Bean完成会话往返_历史恢复")
  void sessionRoundTrip_throughRealBeans() throws Exception {
    try (ConfigurableApplicationContext ctx = bootContext()) {
      SessionManager sessionManager = ctx.getBean(SessionManager.class);
      var session = sessionManager.getOrCreate("cli", "assembly-user", "weather");
      session.appendUser("装配测试消息");
      sessionManager.save(session);

      var restored = sessionManager.getOrCreate("cli", "assembly-user", "weather");

      assertEquals(1, restored.messages().size(), "经真实 Bean 的存取往返，历史完整恢复");
      assertEquals("装配测试消息", restored.messages().get(0).content());
      assertTrue(Files.exists(dbDir.resolve("assembly.db")), "真实库文件在临时目录");
    }
  }
}

package com.yokeos.cli;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yokeos.channel.cli.CliChannel;
import com.yokeos.core.agent.AgentService;
import com.yokeos.core.memory.MemoryService;
import com.yokeos.core.session.SessionManager;
import com.yokeos.storage.JpaSessionManager;
import com.yokeos.tool.sandbox.ActionType;
import com.yokeos.tool.sandbox.Sandbox;
import com.yokeos.tool.sandbox.SandboxAction;
import com.yokeos.tool.sandbox.SandboxViolationException;
import com.yokeos.tool.sandbox.WhitelistSandbox;
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
      // 20 节注册面：内置六件 + notify；22 节补记忆两件 ≥9（MCP 动态面随 mcp_servers.yaml 配置，此断言只锚静态面）
      @SuppressWarnings("unchecked")
      Map<String, Object> tools = ctx.getBean("tools", Map.class);
      assertTrue(
          tools.size() >= 9,
          "ToolRegistry 注册面至少九件（read_file/write_file/list_dir/shell/http_get/http_post/notify"
              + " + save_memory/recall_memory），实际: "
              + tools.keySet());
      assertTrue(
          tools.containsKey("save_memory") && tools.containsKey("recall_memory"),
          "22 节记忆两件进注册面（与其他内置 Tool 一视同仁）");
    }
  }

  @Test
  @DisplayName("缺省路径白名单_随工作区根动态解析")
  void defaultPathWhitelistFollowsWorkspaceRoot() {
    // 坑六/坑七回归（24 节）：本上下文 classpath 无 application.yaml → 三组白名单全空（file 组空）→
    // 代码补 yokeos.root 解析值（指向 @TempDir）——save_memory 经真实 Bean 落盘不被自家拦，区外路径仍拒
    try (ConfigurableApplicationContext ctx = bootContext()) {
      Sandbox sandbox = ctx.getBean(Sandbox.class);
      assertInstanceOf(WhitelistSandbox.class, sandbox, "24 节沙箱 Bean 就位");

      ctx.getBean(MemoryService.class).remember("装配测试记忆", com.yokeos.core.memory.MemoryScope.CORE);
      assertTrue(
          Files.exists(workspace.resolve("memory/MEMORY.md")),
          "缺省白名单含工作区——save_memory 经真实 Bean 落盘（坑六）");

      assertDoesNotThrow(
          () ->
              sandbox.enforce(
                  new SandboxAction(
                      ActionType.FILE_WRITE, workspace.resolve("output/report.md").toString())));
      assertThrows(
          SandboxViolationException.class,
          () -> sandbox.enforce(new SandboxAction(ActionType.FILE_READ, "/etc/passwd")),
          "区外路径仍拒（deny 语义不因缺省放行而松动）");
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

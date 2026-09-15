package com.yokeos.cli.command;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** {@code yokeos session list} harness（第 18 节）：库不存在→「暂无会话」；有库→列概览行（纯只读 JDBC，测试注入库路径）。 */
class SessionListCommandTest {

  @TempDir Path dir;

  @Test
  @DisplayName("库文件不存在_暂无会话不抛异常")
  void missingDb_friendlyHint() {
    String output = SessionListCommand.summarize(dir.resolve("yokeos.db"));

    assertTrue(output.contains("暂无会话"));
  }

  @Test
  @DisplayName("有库有会话_列出概览行")
  void withSessions_listsRows() throws Exception {
    Path db = dir.resolve("yokeos.db");
    String url = "jdbc:sqlite:" + db;
    try (Connection connection = DriverManager.getConnection(url)) {
      ScriptUtils.executeSqlScript(
          connection, new ClassPathResource("/db/schema-002-sessions.sql"));
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate(
            "INSERT INTO sessions (session_id, agent_name, channel, user_id, status, created_at,"
                + " last_active_at) VALUES ('cli:wang:weather', 'weather', 'cli', 'wang',"
                + " 'active', '2026-09-15 08:00:00', '2026-09-15 09:00:00')");
      }
    }

    String output = SessionListCommand.summarize(db);

    assertTrue(output.contains("cli:wang:weather"), output);
    assertTrue(output.contains("weather"));
    assertTrue(output.contains("active"));
  }

  @Test
  @DisplayName("库存在但表未建_按暂无会话处理")
  void dbWithoutTable_treatedAsEmpty() throws Exception {
    Path db = dir.resolve("yokeos.db");
    try (Connection ignored = DriverManager.getConnection("jdbc:sqlite:" + db)) {
      // 只建库文件不建表（如刚 init 未跑过 chat）

    }

    assertTrue(SessionListCommand.summarize(db).contains("暂无会话"));
  }
}

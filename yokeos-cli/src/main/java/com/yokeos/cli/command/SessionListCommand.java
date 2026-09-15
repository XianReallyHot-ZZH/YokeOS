package com.yokeos.cli.command;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * {@code yokeos session list}（轻命令，零 Spring）：纯只读 JDBC 查 sessions 概览（标识/Agent/状态/最后活跃， 倒序前
 * 20）。库文件不存在或表未建 → 「暂无会话」，不抛异常（spec Edge）。命令形态为「组 + list 子命令」（需 §5.13）。
 */
@Command(
    name = "session",
    description = "session 查询组（当前提供 list）",
    mixinStandardHelpOptions = true,
    subcommands = {SessionListCommand.ListCommand.class})
public final class SessionListCommand implements Runnable {

  private static final String WORKSPACE_DB = ".yokeos/yokeos.db";

  /** 逻辑方法（测试注入库路径）：概览文本。 */
  static String summarize(Path dbFile) {
    if (!Files.isRegularFile(dbFile)) {
      return "暂无会话";
    }
    StringBuilder sb = new StringBuilder();
    String url = "jdbc:sqlite:" + dbFile;
    try (Connection connection = DriverManager.getConnection(url);
        Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT session_id, agent_name, status, last_active_at"
                    + " FROM sessions ORDER BY last_active_at DESC LIMIT 20")) {
      while (rs.next()) {
        sb.append(
            String.format(
                "%-34s %-16s %-8s %s%n",
                rs.getString("session_id"),
                rs.getString("agent_name"),
                rs.getString("status"),
                formatTimestamp(rs.getString("last_active_at"))));
      }
    } catch (SQLException e) {
      // 库文件存在但表未建（如刚 init 未跑过 chat）——对用户就是「暂无会话」
      return "暂无会话";
    }
    return sb.isEmpty() ? "暂无会话" : sb.toString();
  }

  /** SQLite 经 Hibernate 存 TIMESTAMP 为 epoch 毫秒字符串——展示层转可读时间（裸数字对用户无意义）。 */
  private static String formatTimestamp(String raw) {
    if (raw == null) {
      return "";
    }
    try {
      long epochMs = Long.parseLong(raw);
      return java.time.LocalDateTime.ofInstant(
              java.time.Instant.ofEpochMilli(epochMs), java.time.ZoneId.systemDefault())
          .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    } catch (NumberFormatException e) {
      return raw; // 非 epoch 形态（其他写入方）原样展示
    }
  }

  @Override
  public void run() {
    CommandLine.usage(this, System.out); // 无子命令打印用法
  }

  /** list 子命令：输出会话概览。 */
  @Command(name = "list", description = "列出会话历史概览（最近 20 条）", mixinStandardHelpOptions = true)
  static final class ListCommand implements Runnable {

    @Override
    public void run() {
      System.out.print(summarize(Paths.get(WORKSPACE_DB)));
    }
  }
}

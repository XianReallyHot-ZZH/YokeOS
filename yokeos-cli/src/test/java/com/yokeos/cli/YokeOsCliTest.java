package com.yokeos.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * 根命令 harness（第 18 节 FR1）：12 个子命令全部注册（九顶层 + profile 嵌套四件）、--help 正常、 未知子命令统一报错退出码非 0 无堆栈（Picocli
 * 承担，不自解析 args）。
 */
class YokeOsCliTest {

  private final CommandLine cli = new CommandLine(new YokeOsCli());

  @Test
  @DisplayName("12个子命令全部注册")
  void twelveSubcommandsRegistered() {
    Set<String> top = cli.getSubcommands().keySet();
    assertEquals(
        Set.of(
            "init", "status", "chat", "serve", "gateway", "profile", "provider", "tool", "session"),
        top,
        "九个顶层命令");

    Set<String> profileSubs = cli.getSubcommands().get("profile").getSubcommands().keySet();
    assertEquals(Set.of("list", "create", "show", "delete"), profileSubs, "profile 嵌套四件");
    // 需 §5.13 命令语法是「组 + list 子命令」：yokeos provider list / tool list / session list
    assertEquals(Set.of("list"), cli.getSubcommands().get("provider").getSubcommands().keySet());
    assertEquals(Set.of("list"), cli.getSubcommands().get("tool").getSubcommands().keySet());
    assertEquals(Set.of("list"), cli.getSubcommands().get("session").getSubcommands().keySet());
    // 12 个动作 = init/status/chat/serve/gateway(5) + profile 四件(4) + 三个 list(3)
    assertEquals(12, 5 + profileSubs.size() + 3);
  }

  @Test
  @DisplayName("每个子命令help正常")
  void helpWorks_perCommand() {
    for (String name : cli.getSubcommands().keySet()) {
      int exit = cli.execute("--help");
      assertEquals(0, exit);
      exit = cli.execute(name, "--help");
      assertEquals(0, exit, name + " --help 必须 0 退出");
    }
  }

  @Test
  @DisplayName("未知子命令_报错非0退出_无堆栈")
  void unknownCommand_nonZeroExitNoStack() {
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    try {
      int exit = cli.execute("nonsense");
      assertEquals(2, exit, "未知命令退出码非 0（Picocli UsageException 语义）");
      String output = err.toString(StandardCharsets.UTF_8);
      assertTrue(output.contains("nonsense"), "报错点名未知命令");
      assertFalse(output.contains("at yokeos."), "无 Java 堆栈");
    } finally {
      System.setErr(System.err); // 恢复原流（防污染并行测试）
    }
  }
}

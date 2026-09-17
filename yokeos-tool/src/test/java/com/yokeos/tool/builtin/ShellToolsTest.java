package com.yokeos.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yokeos.core.tool.YokeTool;
import com.yokeos.tool.ToolRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 课件《第 20 节》验收 harness：ShellToolsTest——正常执行 + 失败带 stderr + 超时兜底。
 *
 * <p>argv 数组直传（拍板③）：不经 shell 解释，参数即 argv。POSIX 命令为锚（echo/ls/sleep——macOS 与 Linux CI 原生可用； Windows
 * 本地跑红属已知平台边界，见 plan Testing 节）。
 *
 * <p>待补（24 节 Sandbox 就位后）：白名单拦截用例（argv[0] 白名单外起进程前被拦）与 InOrder 顺序回归。
 */
class ShellToolsTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private YokeTool shell;

  @BeforeEach
  void setUp() {
    ToolRegistry registry = new ToolRegistry();
    registry.registerAnnotated(new ShellTools());
    shell = registry.get("shell").orElseThrow();
  }

  @Test
  @DisplayName("shell正常执行拿到标准输出")
  void shellReturnsStdout() throws Exception {
    String stdout = shell.execute(JSON.readTree("{\"command\":[\"echo\",\"yoke\"]}")).content();

    assertTrue(stdout.contains("yoke"), "stdout 应含命令输出: " + stdout);
  }

  @Test
  @DisplayName("非零退出码_失败带退出码与stderr")
  void nonZeroExitFailsWithStderr() throws Exception {
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () ->
                shell.execute(JSON.readTree("{\"command\":[\"ls\",\"/__yokeos_no_such_dir__\"]}")));

    assertTrue(ex.getMessage().contains("1"), "失败信息含非零退出码: " + ex.getMessage());
    assertTrue(ex.getMessage().contains("ls"), "失败信息点名命令");
  }

  @Test
  @DisplayName("命令挂死_按超时终止并报失败")
  void hangingCommandIsKilledOnTimeout() throws Exception {
    ToolRegistry registry = new ToolRegistry();
    registry.registerAnnotated(new ShellTools(Duration.ofMillis(300)));
    YokeTool shortTimeoutShell = registry.get("shell").orElseThrow();

    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> shortTimeoutShell.execute(JSON.readTree("{\"command\":[\"sleep\",\"5\"]}")));

    assertTrue(ex.getMessage().contains("超时"), "失败信息注明超时: " + ex.getMessage());
  }

  @Test
  @DisplayName("argv空数组或空白元素_报错点名不启动进程")
  void blankArgvRejectedWithoutStartingProcess() {
    IllegalArgumentException empty =
        assertThrows(
            IllegalArgumentException.class, () -> shell.execute(JSON.readTree("{\"command\":[]}")));
    assertTrue(empty.getMessage().contains("command"));

    IllegalArgumentException blank =
        assertThrows(
            IllegalArgumentException.class,
            () -> shell.execute(JSON.readTree("{\"command\":[\"echo\",\" \"]}")));
    assertTrue(blank.getMessage().contains("空白"), "空白元素点名: " + blank.getMessage());
  }

  @Test
  @DisplayName("参数原样直传_不经shell解释")
  void argsPassedVerbatimWithoutShellInterpretation() throws Exception {
    // 带 shell 语法的参数必须被当作字面量（无解释拼接——拍板③）：echo 后的参数含 * 与 $VAR，输出原样
    String stdout = shell.execute(JSON.readTree("{\"command\":[\"echo\",\"*$HOME\"]}")).content();

    assertTrue(stdout.contains("*$HOME"), "参数按字面量直传，不被 shell 展开: " + stdout);
  }
}

package com.yokeos.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yokeos.core.tool.YokeTool;
import com.yokeos.tool.ToolRegistry;
import com.yokeos.tool.sandbox.SandboxProperties;
import com.yokeos.tool.sandbox.SandboxViolationException;
import com.yokeos.tool.sandbox.WhitelistSandbox;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 第 20 节 harness + 第 24 节 Sandbox 拦截回归：正常执行 + 失败带 stderr + 超时兜底 + 白名单外命令进程根本没跑。
 *
 * <p>argv 数组直传（拍板③）：不经 shell 解释，参数即 argv。POSIX 命令为锚（echo/ls/sleep/touch——macOS 与 Linux CI 原生可 用；
 * Windows 本地跑红属已知平台边界，见 plan Testing 节）。既有用例的沙箱命令白名单含 echo/ls/sleep（真 {@link
 * WhitelistSandbox}，不是放行一切的假货——教学文档拍板②）。
 */
class ShellToolsTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private YokeTool shell;

  @BeforeEach
  void setUp() {
    ToolRegistry registry = new ToolRegistry();
    registry.registerAnnotated(new ShellTools(whitelisting("echo", "ls", "sleep")));
    shell = registry.get("shell").orElseThrow();
  }

  private static WhitelistSandbox whitelisting(String... commands) {
    return new WhitelistSandbox(new SandboxProperties(List.of(), List.of(commands), List.of()));
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
    registry.registerAnnotated(new ShellTools(whitelisting("sleep"), Duration.ofMillis(300)));
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

  @Test
  @DisplayName("白名单外命令_进程根本没跑")
  void commandOutsideWhitelistNeverRuns(@TempDir Path dir) throws Exception {
    // 坑五回归：真 WhitelistSandbox（空命令白名单 = deny-all）+ touch 本可建文件——被拒后目标文件不存在，
    // 证明校验先于进程启动（IO 零发生）
    ToolRegistry registry = new ToolRegistry();
    registry.registerAnnotated(new ShellTools(whitelisting()));
    YokeTool denyingShell = registry.get("shell").orElseThrow();
    Path target = dir.resolve("touched-by-agent.txt");

    SandboxViolationException ex =
        assertThrows(
            SandboxViolationException.class,
            () ->
                denyingShell.execute(
                    JSON.readTree("{\"command\":[\"touch\",\"" + target + "\"]}")));

    assertTrue(ex.getMessage().contains("touch"), "拒绝消息点名 argv[0]: " + ex.getMessage());
    assertFalse(target.toFile().exists(), "校验不过，进程根本没跑、文件根本没建");
  }
}

package com.yokeos.tool.builtin;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 内置 Shell 工具（技 §6.2）：命令以 argv 数组直传 {@code ProcessBuilder}，不经 shell 解释拼接（拍板③——防注入； 24 节白名单对 argv[0]
 * 精确比对也因此干净）。超时兜底（坑四）：命令挂死不能拖死同步的 ReAct 循环，到点强杀报失败。
 */
public class ShellTools {

  /** 默认超时 30 秒（配置化随 24 节 shell 白名单配置一并处理）。 */
  static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  private final Duration timeout;

  /** 默认构造：超时 30 秒。 */
  public ShellTools() {
    this(DEFAULT_TIMEOUT);
  }

  /**
   * @param timeout 超时（测试注入毫秒级，不赌真实时钟）。
   */
  ShellTools(Duration timeout) {
    this.timeout = timeout;
  }

  /** 执行一条命令（argv 数组直传），返回标准输出。 */
  @Tool(name = "shell", description = "执行一条命令（参数以 argv 数组直传，不经 shell 解释），返回标准输出")
  @SuppressFBWarnings(
      value = "COMMAND_INJECTION",
      justification =
          "shell 工具的功能本质就是执行模型给出的命令；argv 直传杜绝 shell 语法拼接，命令白名单由首行 Sandbox 检查位前置校验（24 节）")
  public String shell(@ToolParam(description = "要执行的命令及参数，argv 数组形式") List<String> command) {
    // Sandbox 检查位：24 节接 sandbox.enforce(new SandboxAction(SHELL_COMMAND, command.get(0)))——argv[0]
    // 白名单比对
    if (command == null || command.isEmpty()) {
      throw new IllegalArgumentException("shell 缺少必填参数 command（argv 数组）");
    }
    if (command.stream().anyMatch(arg -> arg == null || arg.isBlank())) {
      throw new IllegalArgumentException("command 数组含空白元素: " + command);
    }
    try {
      Process process = new ProcessBuilder(command).start();
      boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!finished) {
        process.destroyForcibly();
        throw new IllegalStateException(
            "命令超时（" + timeout.toSeconds() + "s）被终止: " + String.join(" ", command));
      }
      String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (process.exitValue() != 0) {
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        throw new IllegalStateException("命令退出码 " + process.exitValue() + ": " + stderr.trim());
      }
      return stdout;
    } catch (IOException e) {
      throw new UncheckedIOException("命令启动失败: " + command, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("命令执行被中断: " + command, e);
    }
  }
}

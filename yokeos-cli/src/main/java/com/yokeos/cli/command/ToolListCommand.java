package com.yokeos.cli.command;

import com.yokeos.tool.NotifyTools;
import com.yokeos.tool.ToolRegistry;
import com.yokeos.tool.builtin.FileTools;
import com.yokeos.tool.builtin.HttpTools;
import com.yokeos.tool.builtin.ShellTools;
import com.yokeos.tool.notify.WebhookNotifyAdapter;
import java.util.Map;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * {@code yokeos tool list}（轻命令，零 Spring）：查真实静态注册面输出工具清单——名与描述来自真实工具实例（18 节「唯一诚实口径」
 * 延续：只列已交付的，不列规划中的；新工具自动出现，无手写双源）。构造与 YokeosRuntime.tools() 的静态部分同构（注释互指）；MCP 动态工具随重命令启动注册、此处不列。
 * 命令形态为「组 + list 子命令」（需 §5.13）。
 */
@Command(
    name = "tool",
    description = "tool 查询组（当前提供 list）",
    mixinStandardHelpOptions = true,
    subcommands = {ToolListCommand.ListCommand.class})
public final class ToolListCommand implements Runnable {

  @Override
  public void run() {
    CommandLine.usage(this, System.out); // 无子命令打印用法
  }

  /**
   * 逻辑方法（测试直调）：工具清单文本——静态注册面（内置三组 + notify）逐件取名与描述。本命令不启 Spring 上下文（18 节分 类：直接文件操作），沙箱传空白名单
   * deny-all 实例——只列不执行，永不触发校验（24 节构造器增参）。
   */
  static String listTools() {
    com.yokeos.tool.sandbox.WhitelistSandbox denyAll =
        new com.yokeos.tool.sandbox.WhitelistSandbox(
            new com.yokeos.tool.sandbox.SandboxProperties(
                java.util.List.of(), java.util.List.of(), java.util.List.of()));
    ToolRegistry registry = new ToolRegistry();
    registry.registerAnnotated(new FileTools(denyAll));
    registry.registerAnnotated(new ShellTools(denyAll));
    registry.registerAnnotated(new HttpTools(denyAll));
    registry.register(
        new NotifyTools(Map.of("webhook", new WebhookNotifyAdapter()), denyAll)); // 构造零网络副作用
    StringBuilder sb = new StringBuilder();
    registry
        .all()
        .forEach(
            tool ->
                sb.append(tool.getName())
                    .append("  —  ")
                    .append(tool.getDescription())
                    .append('\n'));
    sb.append("（MCP server 的工具随 chat/serve 启动注册，此处不列）");
    return sb.toString();
  }

  /** list 子命令：输出工具清单。 */
  @Command(name = "list", description = "列出当前就绪的内置工具", mixinStandardHelpOptions = true)
  static final class ListCommand implements Runnable {

    @Override
    public void run() {
      System.out.println(listTools());
    }
  }
}

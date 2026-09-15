package com.yokeos.cli.command;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * {@code yokeos tool list}（轻命令，零 Spring）：列出当前真实就绪的内置工具并注明扩展位—— 20 节 ToolRegistry
 * 就位后改查注册表（本命令是唯一诚实口径：只列已交付的，不列规划中的）。命令形态为「组 + list 子命令」（需 §5.13）。
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

  /** 逻辑方法（测试直调）：工具清单文本。 */
  static String listTools() {
    return "http_get          发 HTTP GET 取回正文（17 节内置）\n"
        + "（20 节 ToolRegistry 就位后本命令改查注册表：read_file / write_file / shell / … 将逐节接入）";
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

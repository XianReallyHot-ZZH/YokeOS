package com.yokeos.cli;

import com.yokeos.cli.command.ChatCommand;
import com.yokeos.cli.command.GatewayCommand;
import com.yokeos.cli.command.ProfileCommand;
import com.yokeos.cli.command.ProviderListCommand;
import com.yokeos.cli.command.ServeCommand;
import com.yokeos.cli.command.SessionListCommand;
import com.yokeos.cli.command.StatusCommand;
import com.yokeos.cli.command.ToolListCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * YokeOS 命令行入口（第 18 节）：整个可执行 JAR 的 main 函数，注册 12 个子命令（需 §5.13）—— init/status/chat/serve/gateway +
 * profile 四件 + provider list + tool list + session list。 命令按轻重分流（技 §8.7）：轻命令零 Spring 秒回，重命令经 {@link
 * YokeosRuntime} 启动完整运行时。fat JAR 的 Main-Class 指向本类（boot pom mainClass 切换点）。
 */
@Command(
    name = "yokeos",
    description =
        "YokeOS 命令行入口——跟 Agent 对话、把服务跑起来、查配置和状态%n"
            + "快速上手：yokeos init 初始化工作区 → yokeos profile create <name> 建 Agent"
            + " → yokeos chat 对话；任一命令加 --help 看详细用法",
    mixinStandardHelpOptions = true,
    // 31 节（specs/016 拍板④）：随发版 release 化，与父 pom 版本一致
    version = "0.1.0",
    subcommands = {
      InitCommand.class,
      StatusCommand.class,
      ChatCommand.class,
      ServeCommand.class,
      GatewayCommand.class,
      ProfileCommand.class,
      ProviderListCommand.class,
      ToolListCommand.class,
      SessionListCommand.class
    })
public final class YokeOsCli implements Runnable {

  @Override
  public void run() {
    CommandLine.usage(this, System.out); // 无子命令打印用法
  }

  /** fat JAR 入口：退出码透传（Picocli 统一报错，无堆栈）。 */
  public static void main(String[] args) {
    System.exit(new CommandLine(new YokeOsCli()).execute(args));
  }
}

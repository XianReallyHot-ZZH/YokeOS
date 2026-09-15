package com.yokeos.cli.command;

import com.yokeos.channel.cli.CliChannel;
import com.yokeos.cli.YokeosRuntime;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code yokeos chat}（重命令）：启动完整运行时后进入交互对话（需 §5.12）。
 *
 * <p>轻重分流标准：要调模型/跑引擎才起 Spring（技 §8.7）。chat 是终端对话，不占 HTTP 端口（serve 才起 Web）； banner 关掉保持对话界面干净。{@code
 * --message} 单条模式：发一条、打印、即退出（空白值报参数错误退出非 0，不静默进交互——spec Edge）。
 */
@Command(
    name = "chat",
    description = "在终端里和 Agent 交互式对话（/context 上下文 · /tools 调用记录 · /quit 退出）",
    mixinStandardHelpOptions = true)
public final class ChatCommand implements java.util.concurrent.Callable<Integer> {

  @Option(
      names = "--profile",
      defaultValue = "default",
      description =
          "使用的 Agent 名，即 .yokeos/agents/ 下的目录名" + "（可用 yokeos profile list 查看全部，默认 default）")
  String profileName;

  @Option(names = "--message", paramLabel = "<text>", description = "发单条消息后退出（不进交互）；不传此参数则进入交互模式")
  String message;

  @Override
  public Integer call() {
    if (message != null && message.isBlank()) {
      System.err.println("--message 不能为空白（发单条请给出内容，交互模式请不带该参数）");
      return 2;
    }
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(YokeosRuntime.class)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF)
            .run()) {
      CliChannel channel = context.getBean(CliChannel.class);
      String user = currentUser();
      if (message == null) {
        channel.run(profileName, user);
      } else {
        channel.runOnce(profileName, user, message);
      }
      return 0;
    }
  }

  /** 核心阶段无认证体系，「当前用户」取运行环境的系统用户名（spec Assumptions）。 */
  private static String currentUser() {
    return System.getProperty("user.name", "unknown");
  }
}

package com.yokeos.cli.command;

import com.yokeos.cli.YokeosRuntime;
import org.springframework.boot.SpringApplication;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code yokeos serve}（重命令）：启动完整运行时常驻——REST 11 端点 + Web 管理台（{@code /admin}，第 26 节已接线）与 定时任务随行常驻调度（第
 * 25 节：启动即注册全部 AGENT.md 的 schedules，cron 到点自动触发）。Ctrl-C 退出。
 */
@Command(
    name = "serve",
    description = "启动常驻运行时（REST 端点与管理台已接线，定时调度随行）",
    mixinStandardHelpOptions = true)
public final class ServeCommand implements java.util.concurrent.Callable<Integer> {

  @Option(names = "--port", defaultValue = "8080", description = "监听端口（默认 8080）")
  int port;

  @Override
  public Integer call() {
    System.setProperty("server.port", String.valueOf(port));
    System.out.println("YokeOS 运行时已启动（定时调度随行常驻；REST 端点将在第 26 节接线）。Ctrl-C 退出。");
    SpringApplication.run(YokeosRuntime.class);
    keepAlive();
    return 0;
  }

  /** 常驻直到进程被终止（与 gateway 共用）。 */
  static void keepAlive() {
    try {
      Thread.currentThread().join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}

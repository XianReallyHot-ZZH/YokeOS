package com.yokeos.cli.command;

import com.yokeos.cli.YokeosRuntime;
import org.springframework.boot.SpringApplication;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code yokeos serve}（重命令，启动骨架）：启动完整运行时常驻——REST 端点第 26 节接线（本节只起上下文）， 定时任务随 serve/gateway 常驻调度归 25
 * 节。Ctrl-C 退出。
 */
@Command(
    name = "serve",
    description = "启动 HTTP API 服务（REST 端点 26 节接线，当前为运行时骨架）",
    mixinStandardHelpOptions = true)
public final class ServeCommand implements java.util.concurrent.Callable<Integer> {

  @Option(names = "--port", defaultValue = "8080", description = "监听端口（默认 8080）")
  int port;

  @Override
  public Integer call() {
    System.setProperty("server.port", String.valueOf(port));
    System.out.println("YokeOS 运行时已启动（serve 骨架；REST 端点将在第 26 节接线）。Ctrl-C 退出。");
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

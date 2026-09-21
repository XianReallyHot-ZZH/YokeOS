package com.yokeos.cli.command;

import com.yokeos.cli.YokeosRuntime;
import org.springframework.boot.SpringApplication;
import picocli.CommandLine.Command;

/** {@code yokeos gateway}（重命令，启动骨架）：守护进程模式——同时挂多个 Channel（多通道挂载扩展阶段）， 本节起完整运行时常驻。 */
@Command(
    name = "gateway",
    description = "启动守护进程（多通道挂载扩展阶段，当前为运行时骨架）",
    mixinStandardHelpOptions = true)
public final class GatewayCommand implements java.util.concurrent.Callable<Integer> {

  @Override
  public Integer call() {
    System.out.println("YokeOS 运行时已启动（gateway；定时调度随行常驻，多通道挂载扩展阶段接入）。Ctrl-C 退出。");
    SpringApplication.run(YokeosRuntime.class);
    ServeCommand.keepAlive();
    return 0;
  }
}

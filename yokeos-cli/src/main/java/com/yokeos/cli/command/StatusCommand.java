package com.yokeos.cli.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import picocli.CommandLine.Command;

/**
 * {@code yokeos status}（轻命令，零 Spring）：工作区 / Agent 目录 / 库文件 / 配置存在性摘要。 纯文件与 classpath 检查，不启动重运行时（技
 * §8.7 轻重分流）。
 */
@Command(name = "status", description = "查看工作区、Agent、会话库与配置的状态摘要", mixinStandardHelpOptions = true)
public final class StatusCommand implements Runnable {

  private static final String WORKSPACE_DIR = ".yokeos";

  private static final String DB_FILE = "yokeos.db";

  private static final String AGENTS_DIR = "agents";

  /** 逻辑方法（测试注入路径）：逐项检查并产出摘要文本。 */
  static String summarize(Path workspace, boolean classpathYamlPresent) {
    StringBuilder sb = new StringBuilder();
    sb.append("工作区    : ").append(Files.isDirectory(workspace) ? "已初始化" : "未初始化（先跑 yokeos init）");
    if (Files.isDirectory(workspace.resolve(AGENTS_DIR))) {
      try (Stream<Path> agents = Files.list(workspace.resolve(AGENTS_DIR))) {
        sb.append("\nAgent     : ").append(agents.count()).append(" 个目录");
      } catch (IOException e) {
        sb.append("\nAgent     : 读取失败（").append(e.getMessage()).append("）");
      }
    }
    sb.append("\n会话库    : ")
        .append(
            Files.isRegularFile(workspace.resolve("yokeos.db")) ? "yokeos.db 存在" : "尚无 yokeos.db");
    sb.append("\n全局配置  : ")
        .append(
            classpathYamlPresent ? "application.yaml 就绪" : "application.yaml 缺失（provider 无法配置）");
    return sb.toString();
  }

  @Override
  public void run() {
    System.out.println(summarize(Paths.get(WORKSPACE_DIR), yamlOnClasspath()));
  }

  private static boolean yamlOnClasspath() {
    try (java.io.InputStream in = StatusCommand.class.getResourceAsStream("/application.yaml")) {
      return in != null; // try-with-resources：探测也要关流（SpotBugs OS_OPEN_STREAM）
    } catch (IOException e) {
      return false;
    }
  }
}

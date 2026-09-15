package com.yokeos.cli.command;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code yokeos status} harness（第 18 节）：工作区/Agent/库文件/配置四项摘要（零 Spring，注入路径直测）。 */
class StatusCommandTest {

  @TempDir Path dir;

  @Test
  @DisplayName("未初始化工作区_提示先init")
  void uninitializedWorkspace_hintsInit() {
    // summarize 的入参语义是 .yokeos 目录本身——未初始化 = 该目录不存在
    String summary = StatusCommand.summarize(dir.resolve(".yokeos"), false);

    assertTrue(summary.contains("未初始化"), summary);
    assertTrue(summary.contains("yokeos init"), "给下一步指引");
    assertTrue(summary.contains("缺失"), "配置缺失也如实报告");
  }

  @Test
  @DisplayName("就绪工作区_四项摘要齐全")
  void readyWorkspace_summarizesAll() throws IOException {
    Path workspace = dir.resolve(".yokeos");
    Files.createDirectories(workspace.resolve("agents").resolve("weather"));
    Files.createFile(workspace.resolve("yokeos.db"));

    String summary = StatusCommand.summarize(workspace, true);

    assertTrue(summary.contains("已初始化"), summary);
    assertTrue(summary.contains("1"), "Agent 目录计数");
    assertTrue(summary.contains("yokeos.db 存在"));
    assertTrue(summary.contains("application.yaml 就绪"));
  }
}

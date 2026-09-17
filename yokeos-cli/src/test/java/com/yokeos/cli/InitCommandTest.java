package com.yokeos.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** {@code yokeos init} 契约 harness（contracts/cli-init.md）：产物齐全、幂等零变化、模板形态。 */
class InitCommandTest {

  @TempDir Path root;

  private final InitCommand command = new InitCommand();

  @Test
  @DisplayName("首次init_六目录三Bootstrap齐全且为占位模板")
  void initCreatesWorkspaceWithPlaceholderTemplates() throws IOException {
    command.initWorkspace(root.resolve(".yokeos"));

    for (String dir : InitCommand.SUBDIRS) {
      assertTrue(Files.isDirectory(root.resolve(".yokeos").resolve(dir)), "缺少目录 " + dir);
    }
    for (String file : InitCommand.BOOTSTRAP_TEMPLATES.keySet()) {
      Path path = root.resolve(".yokeos").resolve(file);
      assertTrue(Files.isRegularFile(path), "缺少文件 " + file);
      String content = Files.readString(path);
      assertTrue(content.startsWith("# "), "占位模板必须以一级标题开头");
      assertTrue(content.contains("由你自行填写"), "占位模板必须带填写提示");
    }
  }

  @Test
  @DisplayName("二次init_幂等零变化")
  void secondInitChangesNothing() throws IOException {
    Path workspace = root.resolve(".yokeos");
    command.initWorkspace(workspace);
    List<Path> before = snapshotPaths(workspace);
    List<String> beforeContents = snapshotContents(before);

    command.initWorkspace(workspace);

    List<Path> after = snapshotPaths(workspace);
    List<String> afterContents = snapshotContents(after);
    assertEquals(before, after, "二次运行不得增删任何条目");
    assertEquals(beforeContents, afterContents, "二次运行不得改写任何文件内容");
  }

  @Test
  @DisplayName("picocli装配_命令名为init")
  void picocliWiringExposesInitCommand() {
    assertEquals("init", new CommandLine(command).getCommandName());
  }

  @Test
  @DisplayName("init补建mcp_servers_yaml注释模板且幂等不覆盖")
  void initCreatesMcpServersYamlTemplateIdempotently() throws IOException {
    Path workspace = root.resolve(".yokeos");
    command.initWorkspace(workspace);
    Path yaml = workspace.resolve("mcp_servers.yaml");
    assertTrue(Files.isRegularFile(yaml), "工作区结构既列 mcp_servers.yaml（CLAUDE.md）");
    String content = Files.readString(yaml);
    assertTrue(content.contains("servers:"), "模板含 servers 列表占位");
    assertTrue(content.contains("stdio"), "模板注明第一阶段唯一 transport");

    Files.writeString(yaml, "servers: []\n");
    command.initWorkspace(workspace);
    assertEquals("servers: []\n", Files.readString(yaml), "已存在一律不覆盖（幂等）");
  }

  private static List<Path> snapshotPaths(Path workspace) throws IOException {
    try (var stream = Files.walk(workspace)) {
      return stream.sorted().toList();
    }
  }

  private static List<String> snapshotContents(List<Path> paths) {
    return paths.stream().filter(Files::isRegularFile).map(InitCommandTest::uncheckedRead).toList();
  }

  private static String uncheckedRead(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}

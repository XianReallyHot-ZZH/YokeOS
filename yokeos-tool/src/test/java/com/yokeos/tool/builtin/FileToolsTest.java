package com.yokeos.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yokeos.core.tool.YokeTool;
import com.yokeos.tool.ToolRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 课件《第 20 节》验收 harness：FileToolsTest——正常能跑通 + 报错点名 + 截断防护。
 *
 * <p>经 registerAnnotated 注解管道注册后按 {@link YokeTool} 契约测（顺带覆盖 schema 生成路径）；工具层异常直接上抛（「转 ToolResult
 * 落审计」是 ToolExecutor 的既有职责，17 节已测）。
 *
 * <p>待补（24 节 Sandbox 就位后）：白名单拦截用例（Sandbox 拒绝时文件动作零发生）与 InOrder「校验先于 IO」顺序回归。
 */
class FileToolsTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path dir;

  private YokeTool readFile;
  private YokeTool writeFile;
  private YokeTool listDir;

  @BeforeEach
  void setUp() {
    ToolRegistry registry = new ToolRegistry();
    registry.registerAnnotated(new FileTools());
    readFile = registry.get("read_file").orElseThrow();
    writeFile = registry.get("write_file").orElseThrow();
    listDir = registry.get("list_dir").orElseThrow();
  }

  @Test
  @DisplayName("read_file正常读到内容")
  void readFileReturnsContent() throws IOException {
    Files.writeString(dir.resolve("a.txt"), "hello yoke");

    assertEquals(
        "hello yoke",
        readFile.execute(JSON.readTree("{\"path\":\"" + dir + "/a.txt\"}")).content());
  }

  @Test
  @DisplayName("write_file写入成功且可回读_父目录自动创建")
  void writeFilePersistsContentAndCreatesParent() throws IOException {
    writeFile.execute(
        JSON.readTree("{\"path\":\"" + dir + "/out/sub/b.txt\",\"content\":\"written\"}"));

    assertEquals("written", Files.readString(dir.resolve("out/sub/b.txt")));
  }

  @Test
  @DisplayName("list_dir列出文件与子目录且排序稳定")
  void listDirShowsSortedEntries() throws IOException {
    Files.writeString(dir.resolve("x.txt"), "");
    Files.createDirectory(dir.resolve("sub"));

    String listing = listDir.execute(JSON.readTree("{\"path\":\"" + dir + "\"}")).content();

    assertEquals("sub\nx.txt", listing);
  }

  @Test
  @DisplayName("读不存在的文件_报错点名路径")
  void readMissingFileFailsWithPath() {
    String missing = dir.resolve("no.txt").toString();

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> readFile.execute(JSON.readTree("{\"path\":\"" + missing + "\"}")));

    assertTrue(ex.getMessage().contains("no.txt"), "报错必须点名路径");
  }

  @Test
  @DisplayName("read_file超长内容截断并注明总长")
  void readFileTruncatesOversizedContent() throws IOException {
    Files.writeString(dir.resolve("big.txt"), "y".repeat(9000));

    String content =
        readFile.execute(JSON.readTree("{\"path\":\"" + dir + "/big.txt\"}")).content();

    assertTrue(content.length() < 9000, "超长内容必须截断");
    assertTrue(content.contains("截断"), "截断必须注明");
    assertTrue(content.contains("9000"), "注明总长");
  }
}

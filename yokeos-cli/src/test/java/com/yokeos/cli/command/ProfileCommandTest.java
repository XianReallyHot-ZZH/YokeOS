package com.yokeos.cli.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code yokeos profile} 四件 harness（第 18 节）：list/show/create（幂等不覆盖、模板含 provider 缺省）/delete（归档不物理删）。
 */
class ProfileCommandTest {

  @TempDir Path workspace;

  @Test
  @DisplayName("list_列目录清单_空时友好提示")
  void listAgents_sortedNames() throws IOException {
    assertTrue(ProfileCommand.listAgents(workspace).isEmpty(), "空工作区");

    Files.createDirectories(ProfileCommand.agentsDir(workspace).resolve("weather"));
    Files.createDirectories(ProfileCommand.agentsDir(workspace).resolve("daily"));

    assertEquals(java.util.List.of("daily", "weather"), ProfileCommand.listAgents(workspace));
  }

  @Test
  @DisplayName("show_打印AGENT.md原文_不存在点名报错")
  void showAgentMd_rawContent() throws IOException {
    Path md = ProfileCommand.createAgent(workspace, "weather", "deepseek");

    assertEquals(Files.readString(md), ProfileCommand.readAgentMd(workspace, "weather"));

    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> ProfileCommand.readAgentMd(workspace, "ghost"));
    assertTrue(ex.getMessage().contains("ghost"), "点名报错含名字");
  }

  @Test
  @DisplayName("create_写最小模板_幂等不覆盖")
  void createAgent_templateAndIdempotent() throws IOException {
    Path md = ProfileCommand.createAgent(workspace, "weather", "deepseek");

    String content = Files.readString(md);
    assertTrue(content.startsWith("---"), "frontmatter 开头");
    assertTrue(content.contains("name: weather"));
    assertTrue(content.contains("provider:"));
    assertTrue(content.contains("name: deepseek"), "provider 缺省来自全局层第一个");
    assertTrue(content.contains("http_get"), "工具最小集");

    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> ProfileCommand.createAgent(workspace, "weather", "deepseek"));
    assertTrue(ex.getMessage().contains("已存在"), "幂等：已存在报错");
    assertEquals(content, Files.readString(md), "不覆盖既有文件");
  }

  @Test
  @DisplayName("create_全局层无provider_报错不写残缺模板")
  void createAgent_withoutProvider_failsCleanly() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> ProfileCommand.createAgent(workspace, "demo", null));

    assertTrue(ex.getMessage().contains("provider"));
    assertFalse(Files.exists(ProfileCommand.agentsDir(workspace).resolve("demo")), "不写残缺模板");
  }

  @Test
  @DisplayName("delete_归档到archive_不物理删_不存在点名报错")
  void archiveAgent_movesToArchive() throws IOException {
    ProfileCommand.createAgent(workspace, "demo", "deepseek");

    Path archived = ProfileCommand.archiveAgent(workspace, "demo");

    assertTrue(archived.toString().contains("archive"), "移入归档目录: " + archived);
    assertTrue(Files.isRegularFile(archived.resolve("AGENT.md")), "内容完整保留");
    assertFalse(Files.exists(ProfileCommand.agentsDir(workspace).resolve("demo")), "原目录消失（归档非复制）");

    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> ProfileCommand.archiveAgent(workspace, "demo"));
    assertTrue(ex.getMessage().contains("不存在"), "二次删除点名报错");
  }

  @Test
  @DisplayName("provider清单读取_yaml解析name与baseUrl_不解析key")
  void readRawProviders_parsesYaml() {
    String yaml =
        "yokeos:\n"
            + "  providers:\n"
            + "    - name: deepseek\n"
            + "      api-key: ${DEEPSEEK_API_KEY}\n"
            + "      base-url: https://api.deepseek.com\n";
    var entries =
        ProviderListCommand.readRawProvidersOf(
            new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

    assertEquals(1, entries.size());
    assertEquals("deepseek", entries.get(0).get("name"));
    assertEquals("https://api.deepseek.com", entries.get(0).get("base-url"));
    assertEquals("${DEEPSEEK_API_KEY}", entries.get(0).get("api-key"), "保持占位形态不解析");
  }
}

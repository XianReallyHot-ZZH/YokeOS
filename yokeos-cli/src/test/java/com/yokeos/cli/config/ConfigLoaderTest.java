package com.yokeos.cli.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ${ENV_VAR} placeholder resolution and required-key validation (docs/TechnicalSolution.md §8.8).
 */
class ConfigLoaderTest {

  private final ConfigLoader loader = new ConfigLoader();

  @Test
  @DisplayName("无占位符的配置原样通过")
  void plainValuesPassThrough() {
    Map<String, String> resolved = loader.resolve(Map.of("app", "yokeos"), variable -> "unused");
    assertEquals("yokeos", resolved.get("app"));
  }

  @Test
  @DisplayName("占位符从环境变量解析，含字符串内嵌场景")
  void placeholdersResolveFromEnvironment() {
    Map<String, String> resolved =
        loader.resolve(
            Map.of("key", "${DEEPSEEK_API_KEY}", "url", "http://${HOST}/api"),
            variable -> "secret-123");
    assertEquals("secret-123", resolved.get("key"));
    assertEquals("http://secret-123/api", resolved.get("url"));
  }

  @Test
  @DisplayName("环境变量缺失时一次性报出全部缺失项，点名所需配置键")
  void missingVariablesReportedInOneError() {
    ConfigLoadException ex =
        assertThrows(
            ConfigLoadException.class,
            () ->
                loader.resolve(
                    Map.of(
                        "deepseek.key",
                        "${DEEPSEEK_API_KEY}",
                        "mcp.token",
                        "${MCP_TOKEN}",
                        "plain",
                        "no-placeholder"),
                    variable -> null));
    String message = ex.getMessage();
    assertTrue(message.contains("DEEPSEEK_API_KEY"), message);
    assertTrue(message.contains("MCP_TOKEN"), message);
    assertTrue(message.contains("deepseek.key"), message);
    assertFalse(message.contains("plain"), message);
  }

  @Test
  @DisplayName("真实进程环境路径可用（PATH 必然存在）")
  void realProcessEnvironmentResolves() {
    Map<String, String> resolved = loader.resolve(Map.of("p", "${PATH}"));
    assertFalse(resolved.get("p").contains("${"));
  }

  @Test
  @DisplayName("必填键齐全时通过")
  void requireKeysPassesWhenPresent() {
    loader.requireKeys(Map.of("k", "v"), List.of("k"));
  }

  @Test
  @DisplayName("必填键缺失或为空白时报出全部问题键")
  void requireKeysFailsListingAllOffenders() {
    Map<String, String> resolved = Map.of("present", "v", "blank", "  ");
    ConfigLoadException ex =
        assertThrows(
            ConfigLoadException.class,
            () -> loader.requireKeys(resolved, List.of("present", "blank", "absent")));
    assertTrue(ex.getMessage().contains("blank"), ex.getMessage());
    assertTrue(ex.getMessage().contains("absent"), ex.getMessage());
    assertFalse(ex.getMessage().contains("present"), ex.getMessage());
  }
}

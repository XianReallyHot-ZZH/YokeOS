package com.yokeos.cli.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code yokeos provider list} / {@code yokeos tool list} harness（第 18 节 + 20 节注册面升级）：provider
 * 清单（yaml 注入直测）、工具清单查真实注册面。
 */
class ProviderToolListCommandTest {

  @Test
  @DisplayName("provider清单_缺yaml时友好提示")
  void providerList_missingYamlFriendlyHint() {
    var entries = ProviderListCommand.readRawProviders((InputStream) null);

    assertTrue(entries.isEmpty(), "无配置文件返回空（命令层输出友好提示）");
  }

  @Test
  @DisplayName("provider清单_多provider逐条解析")
  void providerList_multipleEntries() {
    String yaml =
        "yokeos:\n"
            + "  providers:\n"
            + "    - name: deepseek\n"
            + "      api-key: ${DEEPSEEK_API_KEY}\n"
            + "      base-url: https://api.deepseek.com\n"
            + "    - name: kimi\n"
            + "      api-key: ${KIMI_API_KEY}\n"
            + "      base-url: https://api.moonshot.cn/v1\n";
    var entries =
        ProviderListCommand.readRawProvidersOf(
            new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));

    assertTrue(entries.size() == 2);
    assertTrue(entries.stream().anyMatch(e -> "kimi".equals(e.get("name"))));
  }

  @Test
  @DisplayName("tool清单_查注册面七件全量并注明MCP动态面")
  void toolList_listsRegisteredFaceWithMcpNote() {
    String output = ToolListCommand.listTools();

    // 20 节：静态注册面七件全量（真实工具实例取名与描述，非手写清单——18 节「唯一诚实口径」延续）
    for (String name :
        List.of(
            "read_file", "write_file", "list_dir", "shell", "http_get", "http_post", "notify")) {
      assertTrue(output.contains(name), "注册面工具必须列出: " + name);
    }
    assertTrue(output.contains("MCP"), "注明 MCP 动态工具随重命令启动注册、此处不列");
    assertFalse(output.contains("save_memory"), "22 节工具未就绪不得列（不列规划中的幽灵条目）");
  }
}

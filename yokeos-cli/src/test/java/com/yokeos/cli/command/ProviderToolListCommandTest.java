package com.yokeos.cli.command;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code yokeos provider list} / {@code yokeos tool list} harness（第 18 节）：provider 清单（yaml 注入直测）、
 * 工具清单含 http_get 与 20 节注明。
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
  @DisplayName("tool清单_含http_get_notify与20节注明")
  void toolList_containsHttpGetAndNote() {
    String output = ToolListCommand.listTools();

    assertTrue(output.contains("http_get"), "只列当前真实就绪的");
    assertTrue(output.contains("notify"), "19 节起 notify 就绪（语义扩充，非削弱）");
    assertTrue(output.contains("20 节"), "注明 ToolRegistry 接线位");
  }
}

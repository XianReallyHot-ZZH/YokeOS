package com.yokeos.provider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yokeos.provider.ProvidersProperties.ProviderItem;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** FR7 启动期校验：占位形态 + 环境变量缺失的清晰报错（analyze E1 补齐的覆盖面）。 */
class ProvidersPropertiesTest {

  @Test
  @DisplayName("明文api-key_启动期被拒且报错含provider名")
  void plaintextApiKeyRejectedAtStartup() {
    var props = new ProvidersProperties();
    props.setProviders(List.of(item("deepseek", "sk-plaintext")));

    var ex = assertThrows(IllegalStateException.class, () -> props.validate(var -> "any"));
    assertTrue(ex.getMessage().contains("deepseek"), "报错必须指出是哪家 provider");
    assertTrue(ex.getMessage().contains("${ENV_VAR}"), "报错必须给出占位写法指引");
  }

  @Test
  @DisplayName("环境变量缺失_清晰报错且含变量名")
  void missingEnvVariableFailsWithVarName() {
    var props = new ProvidersProperties();
    props.setProviders(List.of(item("kimi", "${KIMI_TEST_ABSENT_KEY}")));

    var ex = assertThrows(IllegalStateException.class, () -> props.validate(var -> null));
    assertTrue(ex.getMessage().contains("KIMI_TEST_ABSENT_KEY"), "报错必须含环境变量名");
    assertTrue(ex.getMessage().contains("kimi"), "报错必须含 provider 名");
  }

  @Test
  @DisplayName("占位合法且变量在_校验通过")
  void validPlaceholdersPass() {
    var props = new ProvidersProperties();
    props.setProviders(
        List.of(item("deepseek", "${DEEPSEEK_API_KEY}"), item("kimi", "${KIMI_API_KEY}")));

    assertDoesNotThrow(
        () -> props.validate(Map.of("DEEPSEEK_API_KEY", "a", "KIMI_API_KEY", "b")::get));
  }

  @Test
  @DisplayName("mock保留名_显式配置免凭证校验_无api-key也通过")
  void mockProviderName_skipsCredentialValidation() {
    // 第 27 节坑二：mock 不需要 key——漏特判则显式挂 mock 即启动失败
    var props = new ProvidersProperties();
    ProviderItem mockItem = new ProviderItem();
    mockItem.setName("mock");
    props.setProviders(List.of(mockItem, item("deepseek", "${DEEPSEEK_API_KEY}")));

    assertDoesNotThrow(
        () -> props.validate(Map.of("DEEPSEEK_API_KEY", "a")::get), "mock 条目免 api-key 校验");
  }

  private static ProviderItem item(String name, String apiKey) {
    ProviderItem item = new ProviderItem();
    item.setName(name);
    item.setApiKey(apiKey);
    return item;
  }
}

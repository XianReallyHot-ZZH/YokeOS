package com.yokeos.cli;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yokeos.provider.MockChatModel;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

/**
 * 第 27 节拍板②的守点：mock 保留名在显式映射表里<b>常挂</b>——生产清单（application.yaml）不配 mock，工厂 仍无条件 {@code putIfAbsent}
 * 挂入（无 key 全链路自测随时可用）；生产 yaml 零改动语义由此钉死。
 */
class ProviderMapMockWiringTest {

  @Test
  @DisplayName("mock保留名常挂_未配置也在显式映射表_类型为MockChatModel")
  void mockAlwaysMountedEvenWithoutConfig() {
    Map<String, ChatModel> map = new YokeosRuntime().providerMap();

    assertTrue(map.containsKey("mock"), "mock 必须常挂（实际键集: " + map.keySet() + "）");
    assertInstanceOf(MockChatModel.class, map.get("mock"), "挂的是脚本假模型，不是 OpenAi 构造");
  }
}

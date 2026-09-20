package com.yokeos.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.yokeos.core.tool.YokeTool;
import com.yokeos.tool.builtin.FileTools;
import com.yokeos.tool.builtin.HttpTools;
import com.yokeos.tool.builtin.ShellTools;
import com.yokeos.tool.notify.NotifyChannelAdapter;
import com.yokeos.tool.sandbox.SandboxProperties;
import com.yokeos.tool.sandbox.WhitelistSandbox;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 课件《第 20 节》验收 harness：YokeToolContractTest——参数化遍历注册面每个工具， 任何一个工具漏契约三件套，这里立刻红（「动手前先检查」那条的自动化版）。
 *
 * <p>MethodSource 与装配（YokeosRuntime.tools）同款静态注册面：内置三组注解注册 + notify 直接注册——新工具加进装配面后自动纳入契约检查。本类只
 * 验契约不执行工具，沙箱传空白名单 deny-all 实例（24 节构造器增参）。
 */
class YokeToolContractTest {

  /** 与 YokeosRuntime.tools() 装配同款的静态注册面（MCP 动态面归集成冒烟）。 */
  static Stream<YokeTool> allRegisteredTools() {
    WhitelistSandbox denyAll =
        new WhitelistSandbox(
            new SandboxProperties(java.util.List.of(), java.util.List.of(), java.util.List.of()));
    ToolRegistry registry = new ToolRegistry();
    registry.registerAnnotated(new FileTools(denyAll));
    registry.registerAnnotated(new ShellTools(denyAll));
    registry.registerAnnotated(new HttpTools(denyAll));
    registry.register(
        new NotifyTools(Map.of("webhook", mock(NotifyChannelAdapter.class)), denyAll));
    return registry.all().stream();
  }

  @ParameterizedTest
  @MethodSource("allRegisteredTools")
  @DisplayName("每个工具的契约三件套都不能缺")
  void everyRegisteredToolHasFullContract(YokeTool tool) {
    assertNotNull(tool.getName());
    assertNotNull(tool.getDescription());
    assertNotNull(tool.getInputSchema()); // 缺了它，Provider 翻译 Function Calling 时直接卡死
    assertFalse(tool.getName().isBlank());
    assertFalse(tool.getDescription().isBlank());
    assertFalse(tool.getInputSchema().isBlank());
  }

  @ParameterizedTest
  @MethodSource("allRegisteredTools")
  @DisplayName("注解管道生成的schema含参数定义")
  void everyRegisteredToolSchemaContainsProperties(YokeTool tool) {
    // 全部内置工具都有入参：schema 必须是带 properties 的对象（注解管道 schema 生成有效性）
    assertTrue(
        tool.getInputSchema().contains("properties"),
        tool.getName() + " 的 schema 应含参数定义: " + tool.getInputSchema());
  }
}

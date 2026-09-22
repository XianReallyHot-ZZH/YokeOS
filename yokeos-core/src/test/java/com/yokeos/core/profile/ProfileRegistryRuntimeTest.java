package com.yokeos.core.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 课件《第29节》验收 harness：ProfileRegistryRuntimeTest——运行时注册原语：register 后立即可见、remove 幂等（重复
 * false）、同名后到覆盖（29 节定夺：30 节 PUT 更新的机制基础）、运行时与启动扫描同一段校验（同一异常类型 + 同一消息）。
 */
class ProfileRegistryRuntimeTest {

  @TempDir Path workspace;

  private static Profile profile(String name, String description) {
    return new Profile(
        name,
        description,
        new Profile.Identity(name, "人格提示"),
        new Profile.ProviderConfig("deepseek", "deepseek-chat", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Profile.Settings.DEFAULT);
  }

  @Test
  @DisplayName("register后get与exists立即可见_remove后不可见且重复remove返回false")
  void removeIsIdempotentAndInvisibleAfterwards() {
    ProfileRegistry registry = new ProfileRegistry();
    assertFalse(registry.exists("ops"), "未注册时 exists=false");

    registry.register(profile("ops", "v1"));

    assertTrue(registry.exists("ops"), "运行时登记后立即可查");
    assertTrue(registry.get("ops").isPresent());
    assertEquals(1, registry.all().size());

    assertTrue(registry.remove("ops"), "存在时 remove 返回 true");
    assertFalse(registry.exists("ops"), "注销后不可见");
    assertFalse(registry.get("ops").isPresent());
    assertFalse(registry.remove("ops"), "重复 remove 返回 false（幂等语义）");
  }

  @Test
  @DisplayName("同名register后到覆盖_29节定夺_30节PUT更新的机制基础")
  void registerSameNameLastWins() {
    ProfileRegistry registry = new ProfileRegistry();
    registry.register(profile("ops", "v1-描述"));
    registry.register(profile("ops", "v2-描述"));

    assertEquals("v2-描述", registry.get("ops").orElseThrow().description(), "同名后到覆盖");
    assertEquals(1, registry.all().size(), "覆盖不产生重复条目");
  }

  @Test
  @DisplayName("运行时与启动扫描同一段校验_同一异常类型加同一消息")
  void runtimeAndStartupShareValidation() throws IOException {
    Path dir = Files.createDirectories(workspace.resolve("agents").resolve("ghost-user"));
    Files.writeString(
        dir.resolve("AGENT.md"),
        "---\nprovider:\n  name: ghost\n  model: m\n---\n正文"); // provider 名不在清单

    AgentLoader loader = new AgentLoader();
    Set<String> providers = Set.of("deepseek");

    // 两条路径同一来源（deriveProfile）：启动扫描的 loadAll 内部与运行时直接调用，异常同型同文
    IllegalArgumentException viaStartup =
        assertThrows(IllegalArgumentException.class, () -> loader.deriveProfile(dir, providers));
    IllegalArgumentException viaRuntime =
        assertThrows(IllegalArgumentException.class, () -> loader.deriveProfile(dir, providers));

    assertEquals(viaStartup.getClass(), viaRuntime.getClass(), "同一异常类型");
    assertEquals(viaStartup.getMessage(), viaRuntime.getMessage(), "同一消息");
    assertTrue(viaRuntime.getMessage().contains("ghost"), "报错点名缺失的 provider 名");
  }
}

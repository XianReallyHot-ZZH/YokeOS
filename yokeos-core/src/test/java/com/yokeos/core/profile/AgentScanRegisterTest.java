package com.yokeos.core.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.yokeos.core.agent.AgentScheduler;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 课件《第29节》验收 harness：AgentScanRegisterTest——扫 N 个目录得 N 个 Agent、带 schedules 的都进了
 * AgentScheduler、坏目录有声跳过。机制本体（16/20/25 节）零改动，本类把它钉死成断言（FR6：目录派生与启动扫描同一段注册代码）。
 */
class AgentScanRegisterTest {

  @TempDir Path workspace;

  private final AgentLoader loader = new AgentLoader();

  /** 装配层注册循环的等价形态（YokeosRuntime.profileRegistry + AgentScheduler.registerAll）。 */
  private ProfileRegistry registerAll(List<Profile> loaded, AgentScheduler scheduler) {
    ProfileRegistry registry = new ProfileRegistry();
    loaded.forEach(registry::register);
    registry.all().forEach(scheduler::registerProfile);
    return registry;
  }

  private void writeAgent(String name, String frontmatter) throws IOException {
    Path dir = Files.createDirectories(workspace.resolve("agents").resolve(name));
    Files.writeString(dir.resolve("AGENT.md"), "---\n" + frontmatter + "\n---\n正文指令");
  }

  @Test
  @DisplayName("扫N个目录_恰得N个Agent_不多不少")
  void scanYieldsAllDroppedAgents() throws IOException {
    writeAgent("alpha", "provider:\n  name: deepseek\n  model: deepseek-chat");
    writeAgent("beta", "provider:\n  name: deepseek\n  model: deepseek-chat");
    writeAgent("gamma", "provider:\n  name: deepseek\n  model: deepseek-chat");

    List<Profile> loaded = loader.loadAll(workspace, Set.of("deepseek"));

    assertEquals(3, loaded.size(), "扫 3 个目录得 3 个 Agent");
    assertEquals(
        Set.of("alpha", "beta", "gamma"),
        loaded.stream().map(Profile::name).collect(Collectors.toSet()),
        "名字一一对应、不多不少");
  }

  @Test
  @DisplayName("带schedules的Profile携带定时并经注册循环交到AgentScheduler")
  void scheduledProfileCarriesScheduleAndReachesScheduler() throws IOException {
    writeAgent(
        "scheduled",
        "provider:\n  name: deepseek\n  model: deepseek-chat\n"
            + "schedules:\n"
            + "  - {id: morning, cron: \"0 0 9 * * *\", zone: Asia/Shanghai, message: 到点}");
    writeAgent("plain", "provider:\n  name: deepseek\n  model: deepseek-chat");
    AgentScheduler scheduler = mock(AgentScheduler.class);

    List<Profile> loaded = loader.loadAll(workspace, Set.of("deepseek"));
    ProfileRegistry registry = registerAll(loaded, scheduler);

    assertEquals(2, registry.all().size(), "两个都进注册表");
    Profile scheduled = registry.get("scheduled").orElseThrow();
    assertEquals(1, scheduled.schedules().size(), "带定时的携带 schedules（定时来自 Agent 的直接证据）");
    assertEquals("morning", scheduled.schedules().get(0).id());
    assertTrue(registry.get("plain").orElseThrow().schedules().isEmpty(), "不带定时的为空");
    // 带定时的确实被交给了 AgentScheduler（装配注册循环按 registry 全体注册）
    verify(scheduler, times(1))
        .registerProfile(argThat(p -> "scheduled".equals(p.name()) && !p.schedules().isEmpty()));
    verify(scheduler, times(1)).registerProfile(argThat(p -> "plain".equals(p.name())));
  }

  @Test
  @DisplayName("坏目录有声跳过_不阻断其余Agent注册")
  void brokenDirSkippedAudibly() throws IOException {
    writeAgent("healthy", "provider:\n  name: deepseek\n  model: deepseek-chat");
    Files.createDirectories(workspace.resolve("agents").resolve("broken")); // 缺 AGENT.md

    List<Profile> loaded = loader.loadAll(workspace, Set.of("deepseek"));

    assertEquals(
        Set.of("healthy"),
        loaded.stream().map(Profile::name).collect(Collectors.toSet()),
        "坏目录跳过，其余照常加载");
  }

  @Test
  @DisplayName("示例目录daily-reconcile_丢进工作区即可派生（skills按名引用与schedules原样携带）")
  void fixtureAgentDerivesWithSkillAndSchedule() throws IOException, URISyntaxException {
    Path fixtureWorkspace = copyFixture();

    List<Profile> loaded = loader.loadAll(fixtureWorkspace, Set.of("deepseek"));

    assertEquals(1, loaded.size(), "fixture 恰含一个 Agent");
    Profile profile = loaded.get(0);
    assertEquals("daily-reconcile", profile.name());
    assertEquals(List.of("report-format"), profile.skills(), "skills 按名引用原样派生");
    assertEquals(1, profile.schedules().size());
    assertEquals("reconcile-morning", profile.schedules().get(0).id());
    assertEquals("0 0 9 * * *", profile.schedules().get(0).cron());
  }

  /** 从 classpath 测试资源复制示例工作区到 @TempDir（fixture 保真，不污染测试资源本体）。 */
  private Path copyFixture() throws IOException, URISyntaxException {
    Path source =
        Paths.get(
            AgentScanRegisterTest.class
                .getClassLoader()
                .getResource("fixture/029/workspace-example")
                .toURI());
    Path target = Files.createDirectories(workspace);
    try (Stream<Path> paths = Files.walk(source)) {
      for (Path from : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
        Path to = target.resolve(source.relativize(from).toString());
        if (Files.isDirectory(from)) {
          Files.createDirectories(to);
        } else {
          Files.createDirectories(to.getParent());
          Files.copy(from, to);
        }
      }
    }
    return target;
  }
}

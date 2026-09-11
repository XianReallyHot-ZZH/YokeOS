package com.yokeos.core.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * frontmatter 派生 harness（FR2）：全字段派生、未知名 provider 双防线（加载期跳过）、坏文件不阻断、 ${ENV} 占位解析、ProfileRegistry
 * 注册闭环。
 */
class AgentLoaderTest {

  @TempDir Path workspace;

  private final AgentLoader loader = new AgentLoader();

  private final Set<String> known = Set.of("deepseek", "kimi");

  @Test
  @DisplayName("合法frontmatter_全字段派生并解析ENV占位")
  void validFrontmatterDerivesAllFields() throws IOException {
    writeAgent(
        "ops-agent",
        """
        ---
        name: ops-agent
        description: 运维助手
        identity:
          agent_name: 运维小欧
          prompt: 密语=${YAKEOS_TEST_SECRET}
        provider:
          name: deepseek
          model: deepseek-chat
          temperature: 0.7
        tools: [read_file, shell]
        skills: [weather-helper]
        mcp_servers: [github-mcp]
        channels:
          - name: cli
            config: {}
        notify:
          channels:
            - name: ops-hook
              type: webhook
              config:
                url: https://hooks.example.com/x
        schedules:
          - cron: "0 9 * * *"
            zone: Asia/Shanghai
            message: 早安检查
        bootstrap: [AGENTS.md, SOUL.md, USER.md]
        settings:
          max_iterations: 5
          max_history_turns: 12
        ---
        你是一个专业的运维助手。
        """);

    var profiles = loader.loadAll(workspace, known, Map.of("YAKEOS_TEST_SECRET", "s-3cret")::get);

    assertEquals(1, profiles.size());
    Profile p = profiles.get(0);
    assertEquals("ops-agent", p.name());
    assertEquals("运维助手", p.description());
    assertEquals("运维小欧", p.identity().agentName());
    assertEquals("密语=s-3cret", p.identity().prompt(), "${ENV} 占位必须派生时解析");
    assertEquals("deepseek", p.providerName());
    assertEquals("deepseek-chat", p.provider().model());
    assertEquals(0.7, p.provider().temperature());
    assertEquals(List.of("read_file", "shell"), p.tools());
    assertEquals(List.of("weather-helper"), p.skills());
    assertEquals(List.of("github-mcp"), p.mcpServers());
    assertEquals(1, p.channels().size());
    assertEquals("ops-hook", p.notifyChannels().get(0).name());
    assertEquals("webhook", p.notifyChannels().get(0).type());
    assertEquals("https://hooks.example.com/x", p.notifyChannels().get(0).config().get("url"));
    assertEquals("0 9 * * *", p.schedules().get(0).cron());
    assertEquals("Asia/Shanghai", p.schedules().get(0).zone());
    assertEquals("早安检查", p.schedules().get(0).message());
    assertEquals(List.of("AGENTS.md", "SOUL.md", "USER.md"), p.bootstrap());
    assertEquals(5, p.settings().maxIterations());
    assertEquals(12, p.settings().maxHistoryTurns());
  }

  @Test
  @DisplayName("最小frontmatter_缺省settings与空清单")
  void minimalFrontmatterAppliesDefaults() throws IOException {
    writeAgent(
        "mini",
        """
        ---
        name: mini
        provider:
          name: kimi
          model: kimi-latest
        ---
        最小 Agent。
        """);

    var profiles = loader.loadAll(workspace, known, var -> null);

    assertEquals(1, profiles.size());
    Profile p = profiles.get(0);
    assertEquals(Profile.Settings.DEFAULT, p.settings(), "settings 缺省 10 / 20");
    assertTrue(p.tools().isEmpty());
    assertTrue(p.bootstrap().isEmpty());
  }

  @Test
  @DisplayName("引用不存在provider_加载期跳过且报错含名字")
  void unknownProviderSkippedWithNameInError() throws IOException {
    writeAgent(
        "bad-provider",
        """
        ---
        name: bad-provider
        provider:
          name: nope
          model: x
        ---
        正文
        """);
    writeAgent(
        "good",
        """
        ---
        name: good
        provider:
          name: kimi
          model: kimi-latest
        ---
        正文
        """);

    var profiles = loader.loadAll(workspace, known, var -> null);

    assertEquals(
        List.of("good"), profiles.stream().map(Profile::name).toList(), "坏 provider 的 Agent 被跳过");
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> loader.deriveProfile(workspace.resolve("agents").resolve("bad-provider"), known));
    assertTrue(ex.getMessage().contains("nope"), "报错必须含缺失的 provider 名");
  }

  @Test
  @DisplayName("坏YAML_跳过且不阻断其余Agent加载")
  void brokenYamlDoesNotBlockOthers() throws IOException {
    writeAgent("broken", "这不是: 合法\n  frontmatter: [unclosed\n---\n正文");
    writeAgent(
        "good",
        """
        ---
        name: good
        provider:
          name: deepseek
          model: deepseek-chat
        ---
        正文
        """);

    var profiles = loader.loadAll(workspace, known, var -> null);

    assertEquals(List.of("good"), profiles.stream().map(Profile::name).toList());
  }

  @Test
  @DisplayName("ProfileRegistry_注册按名查找闭环")
  void profileRegistryRoundtrip() throws IOException {
    writeAgent(
        "good",
        """
        ---
        name: good
        provider:
          name: kimi
          model: kimi-latest
        ---
        正文
        """);
    var registry = new ProfileRegistry();
    loader.loadAll(workspace, known, var -> null).forEach(registry::register);

    Optional<Profile> found = registry.get("good");
    assertTrue(found.isPresent());
    assertEquals("kimi", found.get().providerName());
    assertEquals(1, registry.all().size());
  }

  private void writeAgent(String dirName, String markdown) throws IOException {
    Path dir = workspace.resolve("agents").resolve(dirName);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("AGENT.md"), markdown);
  }
}

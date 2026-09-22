package com.yokeos.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yokeos.core.profile.Profile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 课件《第29节》验收 harness：ProgressiveDisclosureTest——常驻层最小化的边界守点： AGENT.md 正文进 system
 * prompt；附属资源（REFERENCE.md、scripts/、Agent 目录内 skills/ 残留）零预载（宪法 8：按需经既有工具取用）； 改正文下次组装即生效（零缓存）。
 */
class ProgressiveDisclosureTest {

  @TempDir Path workspace;

  private ContextLoader loader;

  private Path agentDir;

  // @TempDir 字段注入发生在实例化之后，loader 须在 BeforeEach 里构造（workspace 才非 null）
  @BeforeEach
  void setUp() throws IOException {
    loader = new ContextLoader(workspace);
    agentDir = Files.createDirectories(workspace.resolve("agents").resolve("reconcile"));
  }

  private Profile profileOf(List<String> skills) {
    return new Profile(
        "reconcile",
        "示例对账 Agent",
        new Profile.Identity("对账小欧", "你是对账助手人格。"),
        new Profile.ProviderConfig("deepseek", "deepseek-chat", null),
        List.of("shell", "read_file"),
        skills,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Profile.Settings.DEFAULT);
  }

  private void writeAgentBody(String body) throws IOException {
    Files.writeString(
        agentDir.resolve("AGENT.md"),
        "---\nprovider:\n  name: deepseek\n  model: deepseek-chat\n---\n" + body);
  }

  @Test
  @DisplayName("正文进systemPrompt_附属资源REFERENCE与scripts零预载")
  void bodyInjectedSubResourcesNotPreloaded() throws IOException {
    writeAgentBody("AGENT_BODY 跑 python3 scripts/reconcile.py，拿不准读 REFERENCE.md。");
    Files.writeString(agentDir.resolve("REFERENCE.md"), "REFERENCE_SECRET 字段字典");
    Path scripts = Files.createDirectories(agentDir.resolve("scripts"));
    Files.writeString(scripts.resolve("reconcile.py"), "SCRIPT_CODE_SECRET 差异比对代码");

    String prompt = loader.loadSystemPrompt(profileOf(List.of()));

    assertTrue(prompt.contains("AGENT_BODY"), "AGENT.md 正文进 system prompt（常驻）");
    assertFalse(prompt.contains("REFERENCE_SECRET"), "参考不预载（用到才 read_file）");
    assertFalse(prompt.contains("SCRIPT_CODE_SECRET"), "脚本代码不进上下文（用到才 shell 跑、只有产出进）");
  }

  @Test
  @DisplayName("Agent目录内skills子目录残留_不读不注入_公共库是唯一注入来源")
  void agentDirSkillsSubdirNotInjected() throws IOException {
    writeAgentBody("AGENT_BODY 你是每日订单对账助手。");
    Path legacy = Files.createDirectories(agentDir.resolve("skills"));
    Files.writeString(legacy.resolve("legacy-format.md"), "LEGACY_SKILL_SECRET 参照形态残留");
    Path shared = Files.createDirectories(workspace.resolve("skills").resolve("report-format"));
    Files.writeString(
        shared.resolve("SKILL.md"), "---\nname: report-format\n---\nPUBLIC_SKILL_BODY 公共库规范正文");

    String prompt = loader.loadSystemPrompt(profileOf(List.of("report-format")));

    assertFalse(prompt.contains("LEGACY_SKILL_SECRET"), "目录内 skills/ 残留不注入（research D8）");
    assertTrue(prompt.contains("PUBLIC_SKILL_BODY"), "公共库点名照常注入");
  }

  @Test
  @DisplayName("改AGENT正文_下一次组装立即读到新内容_零缓存")
  void bodyEditTakesEffectWithoutRestart() throws IOException {
    writeAgentBody("v1Instruction");
    Profile profile = profileOf(List.of());
    assertTrue(loader.loadSystemPrompt(profile).contains("v1Instruction"));

    writeAgentBody("v2Instruction");

    String reloaded = loader.loadSystemPrompt(profile);
    assertTrue(reloaded.contains("v2Instruction"), "改正文下一次组装即生效");
    assertEquals(-1, reloaded.indexOf("v1Instruction"), "旧内容不再出现");
  }
}

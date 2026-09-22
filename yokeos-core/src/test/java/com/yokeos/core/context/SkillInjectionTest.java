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
 * 课件《第29节》验收 harness：SkillInjectionTest——点名公共 Skill 正文注入的五守点： 点名注入带段头、未点名不注入（坑③）、点名不存在 WARN
 * 有痕跳过（坑①）、SKILL.md frontmatter 剥离（坑②）、段序 Bootstrap → Skill → AGENT.md 正文（坑④）、 改 SKILL.md
 * 即时生效（零缓存）。
 */
class SkillInjectionTest {

  @TempDir Path workspace;

  private ContextLoader loader;

  // @TempDir 字段注入发生在实例化之后，loader 须在BeforeEach 里构造（workspace 才非 null）
  @BeforeEach
  void setUp() {
    loader = new ContextLoader(workspace);
  }

  /** 组装目标 Profile（点名清单由用例给出）。 */
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

  private void writeSkill(String name, String frontmatter, String body) throws IOException {
    Path dir = Files.createDirectories(workspace.resolve("skills").resolve(name));
    String content = frontmatter == null ? body : "---\n" + frontmatter + "\n---\n" + body;
    Files.writeString(dir.resolve("SKILL.md"), content);
  }

  private void writeAgent(String body) throws IOException {
    Path dir = Files.createDirectories(workspace.resolve("agents").resolve("reconcile"));
    Files.writeString(
        dir.resolve("AGENT.md"),
        "---\nprovider:\n  name: deepseek\n  model: deepseek-chat\n---\n" + body);
  }

  private void writeBootstrap() throws IOException {
    Files.writeString(workspace.resolve("AGENTS.md"), "BOOTSTRAP_ANCHOR 项目约定内容");
  }

  @Test
  @DisplayName("点名skill注入_未点名不注入_frontmatter剥离_段序在正文之前")
  void referencedSkillInjectedOthersSkipped() throws IOException {
    writeBootstrap();
    writeSkill(
        "report-format", "name: report-format\ndescription: 组稿规范", "REPORT_FORMAT_BODY 对账差异报告规范");
    writeSkill("digest-format", "name: digest-format", "DIGEST_FORMAT_BODY 日报组稿规范");
    writeAgent("AGENT_BODY 你是每日订单对账助手。");

    String prompt = loader.loadSystemPrompt(profileOf(List.of("report-format")));

    assertTrue(prompt.contains("## 技能（report-format）"), "点名段带段头注入");
    assertTrue(prompt.contains("REPORT_FORMAT_BODY"), "点名正文注入");
    assertFalse(prompt.contains("DIGEST_FORMAT_BODY"), "未点名不注入（坑③：越权+爆炸）");
    assertFalse(prompt.contains("name: report-format"), "frontmatter 剥离（坑②：YAML 头不进 prompt）");
    // 段序固定：identity → Bootstrap → Skill → AGENT.md 正文（坑④）
    assertTrue(prompt.indexOf("你是对账助手人格。") < prompt.indexOf("BOOTSTRAP_ANCHOR"));
    assertTrue(prompt.indexOf("BOOTSTRAP_ANCHOR") < prompt.indexOf("REPORT_FORMAT_BODY"));
    assertTrue(prompt.indexOf("REPORT_FORMAT_BODY") < prompt.indexOf("AGENT_BODY"));
  }

  @Test
  @DisplayName("点名skill不存在_WARN跳过_其余段照常注入_组装不失败")
  void missingSkillWarnsAndSkipsWithoutBlocking() throws IOException {
    writeSkill("report-format", "name: report-format", "REPORT_FORMAT_BODY 规范内容");
    writeAgent("AGENT_BODY 你是每日订单对账助手。");

    // ghost 在公共库不存在：跳过它，report-format 与正文照常（静默略过变有痕——WARN 由日志层承载，行为面是跳过不炸）
    String prompt = loader.loadSystemPrompt(profileOf(List.of("ghost", "report-format")));

    assertFalse(prompt.contains("## 技能（ghost）"), "不存在的 Skill 不产生段");
    assertTrue(prompt.contains("REPORT_FORMAT_BODY"), "其余点名照常注入");
    assertTrue(prompt.contains("AGENT_BODY"), "AGENT.md 正文照常注入");
  }

  @Test
  @DisplayName("SKILL.md缺frontmatter围栏_按无frontmatter处理正文原样注入")
  void skillWithoutFrontmatterInjectedAsIs() throws IOException {
    writeSkill("bare", null, "BARE_BODY 无围栏的规范正文");
    writeAgent("AGENT_BODY 你是每日订单对账助手。");

    String prompt = loader.loadSystemPrompt(profileOf(List.of("bare")));

    assertTrue(prompt.contains("BARE_BODY"), "无围栏 SKILL.md 正文原样注入");
    assertFalse(prompt.contains("---"), "不残留围栏定界符");
  }

  @Test
  @DisplayName("改SKILL.md_下一次组装立即读到新内容_零缓存")
  void editSkillTakesEffectWithoutRestart() throws IOException {
    writeSkill("report-format", "name: report-format", "REPORT_FORMAT_V1");
    writeAgent("AGENT_BODY 你是每日订单对账助手。");
    Profile profile = profileOf(List.of("report-format"));
    assertTrue(loader.loadSystemPrompt(profile).contains("REPORT_FORMAT_V1"));

    writeSkill("report-format", "name: report-format", "REPORT_FORMAT_V2");

    String reloaded = loader.loadSystemPrompt(profile);
    assertTrue(reloaded.contains("REPORT_FORMAT_V2"), "改 SKILL.md 下一次组装即生效");
    assertEquals(-1, reloaded.indexOf("REPORT_FORMAT_V1"), "旧内容不再出现");
  }
}

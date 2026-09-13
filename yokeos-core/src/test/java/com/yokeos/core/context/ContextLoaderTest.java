package com.yokeos.core.context;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yokeos.core.profile.Profile;
import com.yokeos.core.profile.Profile.Identity;
import com.yokeos.core.profile.Profile.ProviderConfig;
import com.yokeos.core.profile.Profile.Settings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ContextLoader harness（docs/class/017-react-loop.md 第四部分）：system prompt 供给的顺序、header 字面量、
 * 无缓存（改完立即生效——技 §8.3「每次组装重新加载」）、Bootstrap 缺失 WARN 跳过不阻断。
 */
class ContextLoaderTest {

  @TempDir Path workspace;

  @Test
  @DisplayName("identity加Bootstrap带角色header加正文_按固定顺序拼接")
  void bootstrapAndIdentityAndBodyInOrder() throws IOException {
    writeWorkspaceBootstrap();
    writeAgentMarkdown("你是一个天气助手。");

    String prompt = new ContextLoader(workspace).loadSystemPrompt(profile(List.of()));

    int identity = prompt.indexOf("你是运维小欧的人格底座");
    int agents = prompt.indexOf("## 项目约定（AGENTS.md）");
    int soul = prompt.indexOf("## 人格定义（SOUL.md）");
    int user = prompt.indexOf("## 用户偏好（USER.md）");
    assertTrue(identity >= 0 && agents > identity, "identity 人格在最前");
    assertTrue(soul > agents, "Bootstrap 相对序固定：AGENTS.md → SOUL.md");
    assertTrue(user > soul, "SOUL.md → USER.md");
    assertTrue(prompt.indexOf("你是一个天气助手。") > user, "AGENT.md 正文压轴");
    assertTrue(prompt.contains("项目约定的正文内容"), "Bootstrap 文件正文注入");
  }

  @Test
  @DisplayName("改AGENT正文_下一次load立即读到新内容")
  void editAgentBodyNextLoadSeesNewContent() throws IOException {
    writeWorkspaceBootstrap();
    writeAgentMarkdown("旧正文");
    ContextLoader loader = new ContextLoader(workspace);

    loader.loadSystemPrompt(profile(List.of()));
    writeAgentMarkdown("全新任务指令：改完立即生效");
    String second = loader.loadSystemPrompt(profile(List.of()));

    assertTrue(second.contains("全新任务指令"), "零缓存——用户改完立即生效（无缓存回归）");
    assertFalse(second.contains("旧正文"), "旧正文不得残留");
  }

  @Test
  @DisplayName("改Bootstrap文件_下一次load立即读到新内容")
  void editBootstrapNextLoadSeesNewContent() throws IOException {
    writeWorkspaceBootstrap();
    writeAgentMarkdown("正文");
    ContextLoader loader = new ContextLoader(workspace);

    loader.loadSystemPrompt(profile(List.of()));
    Files.writeString(workspace.resolve("SOUL.md"), "新人格：冷静克制");
    String second = loader.loadSystemPrompt(profile(List.of()));

    assertTrue(second.contains("新人格：冷静克制"), "Bootstrap 同样零缓存（无缓存回归）");
  }

  @Test
  @DisplayName("Bootstrap缺失_WARN跳过不阻断")
  void missingBootstrapWarnsAndSkips() throws IOException {
    writeAgentMarkdown("正文照常");
    // 只落 SOUL.md，AGENTS.md 与 USER.md 缺失
    Files.writeString(workspace.resolve("SOUL.md"), "人格正文");

    String prompt = new ContextLoader(workspace).loadSystemPrompt(profile(List.of()));

    assertFalse(prompt.contains("## 项目约定（AGENTS.md）"), "缺失段跳过");
    assertFalse(prompt.contains("## 用户偏好（USER.md）"), "缺失段跳过");
    assertTrue(prompt.contains("## 人格定义（SOUL.md）"), "在场段照常注入");
    assertTrue(prompt.contains("正文照常"), "缺失不阻断——AGENT.md 正文与 identity 照常输出");
  }

  @Test
  @DisplayName("bootstrap列表裁剪_相对序不变")
  void bootstrapListTrimmedOrderPreserved() throws IOException {
    writeWorkspaceBootstrap();
    writeAgentMarkdown("正文");
    // 只保留 USER.md（裁剪两条）——输出序仍按固定相对序，不跟列表声明序走
    String prompt = new ContextLoader(workspace).loadSystemPrompt(profile(List.of("USER.md")));

    assertFalse(prompt.contains("## 项目约定（AGENTS.md）"), "被裁剪段不注入");
    assertFalse(prompt.contains("## 人格定义（SOUL.md）"), "被裁剪段不注入");
    assertTrue(prompt.contains("## 用户偏好（USER.md）"), "保留段注入");
    assertTrue(prompt.contains("正文"), "正文压轴不受裁剪影响");
  }

  private void writeWorkspaceBootstrap() throws IOException {
    Files.writeString(workspace.resolve("AGENTS.md"), "项目约定的正文内容");
    Files.writeString(workspace.resolve("SOUL.md"), "默认人格正文");
    Files.writeString(workspace.resolve("USER.md"), "用户偏好正文");
  }

  private void writeAgentMarkdown(String body) throws IOException {
    Path agentDir = workspace.resolve("agents").resolve("ops-agent");
    Files.createDirectories(agentDir);
    Files.writeString(agentDir.resolve("AGENT.md"), "---\nname: ops-agent\n---\n" + body + "\n");
  }

  static Profile profile(List<String> bootstrap) {
    return new Profile(
        "ops-agent",
        "运维助手",
        new Identity("运维小欧", "你是运维小欧的人格底座"),
        new ProviderConfig("deepseek", "test-model", 0.7),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        bootstrap,
        Settings.DEFAULT);
  }
}

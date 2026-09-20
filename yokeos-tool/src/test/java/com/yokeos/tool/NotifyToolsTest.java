package com.yokeos.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yokeos.core.agent.ProfileContext;
import com.yokeos.core.profile.Profile;
import com.yokeos.core.tool.ToolResult;
import com.yokeos.tool.notify.NotifyChannelAdapter;
import com.yokeos.tool.sandbox.SandboxProperties;
import com.yokeos.tool.sandbox.SandboxViolationException;
import com.yokeos.tool.sandbox.WhitelistSandbox;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 第 19 节验收 harness + 第 24 节 Sandbox 拦截回归：NotifyTools——渠道解析七态 + 域名白名单外的推送根本没发出。
 *
 * <p>既有用例的沙箱域名白名单含 {@code *.example.com}（渠道 URL 的域，真 {@link WhitelistSandbox}——教学文档拍板②）。
 * ProfileContext 是 ThreadLocal：每用例显式 set（或刻意不 set）、@AfterEach 必 clear——坑四纪律，漏 clear 下一个用例读到脏
 * Profile。
 */
class NotifyToolsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private NotifyChannelAdapter adapter;
  private NotifyTools notifyTools;

  @BeforeEach
  void setUp() {
    adapter = mock(NotifyChannelAdapter.class);
    notifyTools =
        new NotifyTools(
            Map.of("webhook", adapter),
            new WhitelistSandbox(
                new SandboxProperties(List.of(), List.of(), List.of("*.example.com"))));
  }

  @AfterEach
  void clearContext() {
    ProfileContext.clear(); // ThreadLocal 必清（坑四）
  }

  private static Profile profileWith(List<Profile.NotifyChannelConfig> channels) {
    return new Profile(
        "ops-agent", null, null, null, null, null, null, null, channels, null, null, null);
  }

  private static Profile twoChannelProfile() {
    return profileWith(
        List.of(
            new Profile.NotifyChannelConfig(
                "ops-group", "webhook", Map.of("url", "https://hooks.example.com/a")),
            new Profile.NotifyChannelConfig(
                "dev-group", "webhook", Map.of("url", "https://hooks.example.com/b"))));
  }

  private static JsonNode input(String content, String channel) {
    var node = MAPPER.createObjectNode();
    if (content != null) {
      node.put("content", content);
    }
    if (channel != null) {
      node.put("channel", channel);
    }
    return node;
  }

  @Test
  @DisplayName("notify.channels未配置_明确报错不静默失败")
  void unconfiguredChannelsFailExplicitly() {
    ProfileContext.set(profileWith(List.of()));

    ToolResult result = notifyTools.execute(input("hello", null));

    assertFalse(result.success(), "不是静默失败——Agent 不会以为发出去了");
    assertTrue(result.errorMessage().contains("notify.channels"), "报错点名未配置段（段名与 frontmatter 一致）");
    assertTrue(result.errorMessage().contains("ops-agent"), "点名哪个 Agent 没配");
    verify(adapter, never()).send(any(), anyString());
  }

  @Test
  @DisplayName("channel参数缺省_取第一个渠道")
  void channelParamDefaultTakesFirstChannel() {
    ProfileContext.set(twoChannelProfile());

    // 缺省 / 空白 / 字面量 default 三形态同语义：都取第一个渠道
    notifyTools.execute(input("m1", null));
    notifyTools.execute(input("m2", " "));
    notifyTools.execute(input("m3", "default"));

    verify(adapter, times(3))
        .send(
            argThat(t -> "https://hooks.example.com/a".equals(t.config().get("url"))), anyString());
  }

  @Test
  @DisplayName("channel显式传名_命中指定渠道")
  void explicitChannelNameSelectsNamedChannel() {
    ProfileContext.set(twoChannelProfile());

    notifyTools.execute(input("hello", "dev-group"));

    verify(adapter)
        .send(
            argThat(t -> "https://hooks.example.com/b".equals(t.config().get("url"))), eq("hello"));
  }

  @Test
  @DisplayName("channel指定名不存在_失败点名且不回退默认")
  void unknownChannelNameFailsWithoutFallback() {
    ProfileContext.set(twoChannelProfile());

    ToolResult result = notifyTools.execute(input("hello", "nope"));

    assertFalse(result.success());
    assertTrue(result.errorMessage().contains("nope"), "报错点名未命中的名字");
    verify(adapter, never()).send(any(), anyString());
  }

  @Test
  @DisplayName("无Agent上下文_失败点名")
  void missingAgentContextFailsExplicitly() {
    // 刻意不 set——模拟工具执行期拿不到 ProfileContext 的场景

    ToolResult result = notifyTools.execute(input("hello", null));

    assertFalse(result.success());
    assertTrue(result.errorMessage().contains("上下文"), "点名无 Agent 上下文");
  }

  @Test
  @DisplayName("content缺失_失败点名必填项")
  void missingContentParamFailsExplicitly() {
    ProfileContext.set(twoChannelProfile());

    ToolResult result = notifyTools.execute(input(null, "dev-group"));

    assertFalse(result.success());
    assertTrue(result.errorMessage().contains("content"), "点名缺必填参数");
  }

  @Test
  @DisplayName("域名白名单外的推送_根本没发出")
  void domainOutsideWhitelistNeverSent() {
    // 坑五回归（24 节）：真 deny-all WhitelistSandbox——enforce 先于 send，webhook 推送零发生
    NotifyChannelAdapter localAdapter = mock(NotifyChannelAdapter.class);
    NotifyTools guarded =
        new NotifyTools(
            Map.of("webhook", localAdapter),
            new WhitelistSandbox(new SandboxProperties(List.of(), List.of(), List.of())));
    ProfileContext.set(twoChannelProfile());

    SandboxViolationException ex =
        assertThrows(SandboxViolationException.class, () -> guarded.execute(input("hello", null)));

    assertTrue(ex.getMessage().contains("hooks.example.com"), "拒绝消息只显 host: " + ex.getMessage());
    assertFalse(ex.getMessage().contains("https://"), "URL 即凭证不得回显（19 节坑二）");
    verify(localAdapter, never()).send(any(), anyString());
  }

  @Test
  @DisplayName("成功路径_送达到匹配目标并返回已推送")
  void successPathSendsAndReturnsOk() {
    ProfileContext.set(twoChannelProfile());

    ToolResult result = notifyTools.execute(input("群里的各位好", "ops-group"));

    verify(adapter)
        .send(
            argThat(
                t ->
                    "webhook".equals(t.channelType())
                        && "https://hooks.example.com/a".equals(t.config().get("url"))),
            eq("群里的各位好"));
    assertTrue(result.success());
    assertEquals("已推送", result.content());
  }
}

package com.yokeos.channel.cli;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yokeos.core.agent.AgentService;
import com.yokeos.core.audit.ToolInvocationRecord;
import com.yokeos.core.profile.Profile;
import com.yokeos.core.profile.Profile.Identity;
import com.yokeos.core.profile.Profile.ProviderConfig;
import com.yokeos.core.profile.Profile.Settings;
import com.yokeos.core.profile.ProfileRegistry;
import com.yokeos.core.session.InMemorySessionManager;
import com.yokeos.core.session.Session;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 交互壳 harness（第 18 节坑三回归）：脚本化驱动多行输入，验证逐行转交打印、/quit 与 EOF、空行跳过、 --message 单条即退、/context 与 /tools
 * 本地处理不走引擎、Profile 不存在点名报错不进循环。IO 注入（StringReader 驱动输入、 ByteArrayOutputStream 收输出——D8 可测形态）。
 */
class CliChannelTest {

  private final AgentService agentService = mock(AgentService.class);
  private final InMemorySessionManager sessionManager = new InMemorySessionManager();
  private final com.yokeos.core.audit.ToolInvocationReader toolInvocationReader =
      mock(com.yokeos.core.audit.ToolInvocationReader.class);
  private final ProfileRegistry profileRegistry = mock(ProfileRegistry.class);

  private final CliChannel channel =
      new CliChannel(agentService, sessionManager, toolInvocationReader, profileRegistry);

  private static final Profile PROFILE =
      new Profile(
          "weather",
          "天气助手",
          new Identity("天气小助手", "你是天气助手"),
          new ProviderConfig("deepseek", "test-model", 0.7),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          Settings.DEFAULT);

  @AfterEach
  void cleanUp() {
    com.yokeos.core.agent.ProfileContext.clear(); // 防测试间串号
  }

  private BufferedReader in(String lines) {
    return new BufferedReader(new StringReader(lines));
  }

  private String run(String input) {
    return runAs("weather", input);
  }

  private String runAs(String profileName, String input) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    channel.run(
        profileName,
        "wang",
        in(input),
        new PrintStream(buffer, true, java.nio.charset.StandardCharsets.UTF_8));
    return buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("多行输入_逐行转交引擎并打印回复")
  void multiLine_eachForwardedAndReplyPrinted() {
    when(profileRegistry.get("weather")).thenReturn(Optional.of(PROFILE));
    when(agentService.process(any(), anyString())).thenReturn("回复一", "回复二");

    String output = run("今天天气如何\n那明天呢\n/quit\n");

    verify(agentService, times(2)).process(any(), anyString());
    assertTrue(output.contains("回复一"));
    assertTrue(output.contains("回复二"));
  }

  @Test
  @DisplayName("quit前后带空白_照常退出")
  void quitWithWhitespace_exitsCleanly() {
    when(profileRegistry.get("weather")).thenReturn(Optional.of(PROFILE));

    String output = run("  /quit  \n");

    verify(agentService, never()).process(any(), anyString());
    assertTrue(output.contains("再见"), "退出有告别语");
  }

  @Test
  @DisplayName("输入流关闭EOF_等同退出不抛堆栈")
  void eof_exitsWithoutStack() {
    when(profileRegistry.get("weather")).thenReturn(Optional.of(PROFILE));

    String output = run(""); // 流立即 EOF（null 行）

    verify(agentService, never()).process(any(), anyString());
    assertTrue(output.contains("再见"), "EOF 等同 /quit，不抛异常");
  }

  @Test
  @DisplayName("空行跳过_不转交引擎")
  void blankLine_skippedNotForwarded() {
    when(profileRegistry.get("weather")).thenReturn(Optional.of(PROFILE));
    when(agentService.process(any(), anyString())).thenReturn("好");

    run("\n   \n你好\n/quit\n");

    verify(agentService, times(1)).process(any(), anyString());
  }

  @Test
  @DisplayName("message单条模式_发一条打印即退出")
  void messageMode_singleShotThenExit() {
    when(profileRegistry.get("weather")).thenReturn(Optional.of(PROFILE));
    when(agentService.process(any(), anyString())).thenReturn("单条回复");
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    channel.runOnce(
        "weather",
        "wang",
        "你好",
        in("后续输入不该被读\n"),
        new PrintStream(buffer, true, java.nio.charset.StandardCharsets.UTF_8));

    verify(agentService, times(1)).process(any(), anyString());
    assertTrue(buffer.toString().contains("单条回复"));
  }

  @Test
  @DisplayName("context命令_打印最近消息_不转交引擎")
  void contextCommand_printsMessages_notForwarded() {
    when(profileRegistry.get("weather")).thenReturn(Optional.of(PROFILE));
    Session session = sessionManager.getOrCreate("cli", "wang", "weather");
    session.appendUser("查天气");

    String output = run("/context\n/quit\n");

    verify(agentService, never()).process(any(), anyString());
    assertTrue(output.contains("查天气"), "含会话消息");
    assertTrue(output.contains("user"), "带角色标识");
  }

  @Test
  @DisplayName("tools命令_打印调用记录_不转交引擎")
  void toolsCommand_printsInvocations_notForwarded() {
    when(profileRegistry.get("weather")).thenReturn(Optional.of(PROFILE));
    when(toolInvocationReader.findBySession("cli:wang:weather"))
        .thenReturn(
            List.of(
                new ToolInvocationRecord(
                    "http_get",
                    "{\"url\":\"https://api.open-meteo.com\"}",
                    true,
                    null,
                    321L,
                    Instant.parse("2026-09-15T01:30:00Z"))));

    String output = run("/tools\n/quit\n");

    verify(agentService, never()).process(any(), anyString());
    assertTrue(output.contains("http_get"));
    assertTrue(output.contains("321"), "带耗时毫秒");
  }

  @Test
  @DisplayName("tools命令_无记录时友好提示")
  void toolsCommand_emptyListFriendlyHint() {
    when(profileRegistry.get("weather")).thenReturn(Optional.of(PROFILE));
    when(toolInvocationReader.findBySession(anyString())).thenReturn(List.of());

    String output = run("/tools\n/quit\n");

    assertTrue(output.contains("暂无 Tool 调用记录"));
  }

  @Test
  @DisplayName("Profile不存在_点名报错_不进交互循环")
  void profileMissing_namedErrorBeforeLoop() {
    when(profileRegistry.get("ghost")).thenReturn(Optional.empty());

    var ex = assertThrows(IllegalStateException.class, () -> runAs("ghost", "你好\n/quit\n"));

    assertTrue(ex.getMessage().contains("ghost"), "点名报错含名字");
    verify(agentService, never()).process(any(), anyString());
  }
}

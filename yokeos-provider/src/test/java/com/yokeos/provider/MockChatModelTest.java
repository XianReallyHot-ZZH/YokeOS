package com.yokeos.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yokeos.core.provider.ToolCallRequest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 钉死 mock 脚本行为（第 27 节坑一；31 节随结构化回传升级）：判轮两分支（看消息序列最后一条是谁）、 tool call 参数形态与 JSON 转义。消息形态对齐 {@code
 * SpringAiProviderService.toSpringAiMessages}（SystemMessage 首位 + user / assistant(toolCalls) /
 * tool(toolCallId) 结构化）。
 */
class MockChatModelTest {

  private static final String SYSTEM_HEAD = "你是测试助手\n当前时间：2026-09-21 08:00:00";

  private static final ToolCallRequest OLD_CALL =
      new ToolCallRequest("call-old", "save_memory", "{}");

  @Test
  @DisplayName("第一轮_最近是用户消息_返回save_memory工具调用且取最后一条用户消息作事实")
  void firstRound_userLast_returnsSaveMemoryToolCall() {
    // 复用会话形态：历史里已有旧工具结果，但最后一条是新的用户消息 → 必须判为第一轮（「只判有没有工具消息」的坑）
    Prompt prompt =
        new Prompt(
            List.of(
                new SystemMessage(SYSTEM_HEAD),
                new UserMessage("旧问题"),
                AssistantMessage.builder()
                    .content("")
                    .toolCalls(
                        List.of(
                            new AssistantMessage.ToolCall(
                                OLD_CALL.id(),
                                "function",
                                OLD_CALL.name(),
                                OLD_CALL.argumentsJson())))
                    .build(),
                ToolResponseMessage.builder()
                    .responses(
                        List.of(
                            new ToolResponseMessage.ToolResponse(
                                OLD_CALL.id(), OLD_CALL.name(), "旧结果")))
                    .build(),
                new AssistantMessage("旧答复"),
                new UserMessage("记住：我在北京，怕冷")));
    ChatResponse response = new MockChatModel().call(prompt);

    var toolCalls = response.getResult().getOutput().getToolCalls();
    assertEquals(1, toolCalls.size(), "第一轮恰一个工具调用");
    assertEquals("save_memory", toolCalls.get(0).name());
    assertEquals(
        "{\"content\":\"记住：我在北京，怕冷\",\"scope\":\"archival\"}",
        toolCalls.get(0).arguments(),
        "参数取最后一条用户消息内容 + scope 固定 archival");
  }

  @Test
  @DisplayName("第二轮_工具结果在最后_直接返回终答不再调工具")
  void secondRound_toolLast_returnsFinalReply() {
    Prompt prompt =
        new Prompt(
            List.of(
                new SystemMessage(SYSTEM_HEAD),
                new UserMessage("记住：我在北京，怕冷"),
                AssistantMessage.builder()
                    .content("")
                    .toolCalls(
                        List.of(
                            new AssistantMessage.ToolCall(
                                "call-1",
                                "function",
                                "save_memory",
                                "{\"content\":\"x\",\"scope\":\"archival\"}")))
                    .build(),
                ToolResponseMessage.builder()
                    .responses(
                        List.of(
                            new ToolResponseMessage.ToolResponse("call-1", "save_memory", "已记录")))
                    .build()));
    ChatResponse response = new MockChatModel().call(prompt);

    assertEquals(
        MockChatModel.FINAL_REPLY, response.getResult().getOutput().getText(), "第二轮返回固定终答");
    assertTrue(response.getResult().getOutput().getToolCalls().isEmpty(), "第二轮不再请求工具");
  }

  @Test
  @DisplayName("特殊字符转义_引号反斜杠进JSON参数不破形态")
  void specialCharacters_escapedInJsonArguments() {
    Prompt prompt =
        new Prompt(
            List.of(new SystemMessage(SYSTEM_HEAD), new UserMessage("记住：他说\"路径是C:\\temp\"然后\n走了")));
    String arguments =
        new MockChatModel().call(prompt).getResult().getOutput().getToolCalls().get(0).arguments();
    assertTrue(arguments.contains("\\\"路径是C:\\\\temp\\\""), "引号/反斜杠必须转义（实际: " + arguments + "）");
    assertTrue(arguments.contains("走了"), "31 节结构化语义：整条用户消息作事实（旧拉平形态按行截断，不再适用）");
  }

  @Test
  @DisplayName("存量降级形态_无id工具消息转的user文本_判轮走第一轮分支")
  void legacyDegradedToolText_firstRoundBranch() {
    // 31 节降级路径：无 toolCallId 的存量 tool 消息被 SpringAiProviderService 转成 user 文本兜底——
    // 最后一条是 user → 判第一轮，事实取该兜底文本
    Prompt prompt =
        new Prompt(
            List.<Message>of(
                new SystemMessage(SYSTEM_HEAD), new UserMessage("tool[save_memory]: 已记录")));
    var toolCalls = new MockChatModel().call(prompt).getResult().getOutput().getToolCalls();
    assertEquals("save_memory", toolCalls.get(0).name());
    assertTrue(
        toolCalls.get(0).arguments().contains("已记录"),
        "兜底 user 文本作为事实（实际: " + toolCalls.get(0).arguments() + "）");
  }

  @Test
  @DisplayName("无用户消息与空消息的兜底")
  void edgeCases_fallbackFacts() {
    Prompt prompt =
        new Prompt(
            List.<Message>of(new SystemMessage(SYSTEM_HEAD), new AssistantMessage("只有助手消息")));
    ChatResponse noUser = new MockChatModel().call(prompt);
    assertEquals("", noUser.getResult().getOutput().getText(), "带 tool call 的消息 text 为空串");
    assertTrue(
        noUser.getResult().getOutput().getToolCalls().get(0).arguments().contains("（无用户消息）"),
        "无用户消息时事实兜底（实际: " + noUser.getResult().getOutput().getToolCalls().get(0).arguments() + "）");

    Prompt blankPrompt =
        new Prompt(List.<Message>of(new SystemMessage(SYSTEM_HEAD), new UserMessage("")));
    String blankArguments =
        new MockChatModel()
            .call(blankPrompt)
            .getResult()
            .getOutput()
            .getToolCalls()
            .get(0)
            .arguments();
    assertTrue(blankArguments.contains("（空消息）"), "空用户消息时事实兜底（实际: " + blankArguments + "）");
  }
}

package com.yokeos.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 钉死 mock 脚本行为（第 27 节坑一）：判轮两分支、tool call 参数形态与 JSON 转义。渲染文本形态对齐 {@code
 * PromptBuilder.appendMessage}（「user: …」/「tool[名]: …」行）。
 */
class MockChatModelTest {

  private static final String SYSTEM_HEAD = "你是测试助手\n当前时间：2026-09-21 08:00:00\n";

  @Test
  @DisplayName("第一轮_最近是用户行_返回save_memory工具调用且取最后一条用户行作事实")
  void firstRound_userLastLine_returnsSaveMemoryToolCall() {
    // 复用会话形态：历史里已有旧工具行，但最后一条是新的用户行 → 必须判为第一轮（「只判有没有工具行」的坑）
    String rendered =
        SYSTEM_HEAD
            + "user: 旧问题\n"
            + "assistant: \n"
            + "tool[save_memory]: 旧结果\n"
            + "assistant: 旧答复\n"
            + "user: 记住：我在北京，怕冷\n";
    ChatResponse response = new MockChatModel().call(new Prompt(rendered));

    var toolCalls = response.getResult().getOutput().getToolCalls();
    assertEquals(1, toolCalls.size(), "第一轮恰一个工具调用");
    assertEquals("save_memory", toolCalls.get(0).name());
    assertEquals(
        "{\"content\":\"记住：我在北京，怕冷\",\"scope\":\"archival\"}",
        toolCalls.get(0).arguments(),
        "参数取最后一条用户行内容 + scope 固定 archival");
  }

  @Test
  @DisplayName("第二轮_工具行在用户行之后_直接返回终答不再调工具")
  void secondRound_toolLineAfterUser_returnsFinalReply() {
    String rendered = SYSTEM_HEAD + "user: 记住：我在北京，怕冷\nassistant: \ntool[save_memory]: 已记录\n";
    ChatResponse response = new MockChatModel().call(new Prompt(rendered));

    assertEquals(
        MockChatModel.FINAL_REPLY, response.getResult().getOutput().getText(), "第二轮返回固定终答");
    assertTrue(response.getResult().getOutput().getToolCalls().isEmpty(), "第二轮不再请求工具");
  }

  @Test
  @DisplayName("特殊字符转义_引号反斜杠进JSON参数不破形态")
  void specialCharacters_escapedInJsonArguments() {
    // 事实取单行（lastUserLine 语义）：换行后的「走了」不在本行，不进参数
    String rendered = SYSTEM_HEAD + "user: 记住：他说\"路径是C:\\temp\"然后\n走了\n";
    String arguments =
        new MockChatModel()
            .call(new Prompt(rendered))
            .getResult()
            .getOutput()
            .getToolCalls()
            .get(0)
            .arguments();
    assertTrue(
        arguments.contains("\\\"路径是C:\\\\temp\\\"") && !arguments.contains("走了"),
        "引号/反斜杠必须转义、事实截到行尾（实际: " + arguments + "）");
  }

  @Test
  @DisplayName("Prompt消息形态_单条UserMessage经getContents拼接判轮一致")
  void promptAsUserMessage_sameBehavior() {
    // SpringAiProviderService 用 new Prompt(text, options)：整段文本包成单条 UserMessage（H3 实证），
    // getContents 返回该文本——本用例钉「判轮不依赖 Prompt 构造形态、只看渲染文本」。
    Prompt prompt = new Prompt(new UserMessage(SYSTEM_HEAD + "user: 记住：美式咖啡\n"));
    var toolCalls = new MockChatModel().call(prompt).getResult().getOutput().getToolCalls();
    assertEquals("save_memory", toolCalls.get(0).name());
    assertTrue(toolCalls.get(0).arguments().contains("美式咖啡"));
  }

  @Test
  @DisplayName("无用户消息与空消息的兜底")
  void edgeCases_fallbackFacts() {
    ChatResponse noUser = new MockChatModel().call(new Prompt(SYSTEM_HEAD + "assistant: 只有助手消息\n"));
    assertEquals("", noUser.getResult().getOutput().getText(), "带 tool call 的消息 text 为空串");
    assertTrue(
        noUser.getResult().getOutput().getToolCalls().get(0).arguments().contains("（无用户消息）"),
        "无用户行时事实兜底（实际: " + noUser.getResult().getOutput().getToolCalls().get(0).arguments() + "）");
  }
}

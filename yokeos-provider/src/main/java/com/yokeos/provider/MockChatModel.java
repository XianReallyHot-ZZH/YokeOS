package com.yokeos.provider;

import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 内置 mock provider（第 27 节）——挂在显式映射表的 {@code "mock"} 保留名下（宪法 3），不连任何真实模型、不需要 key/网络。按脚本驱动一次确定性的
 * ReAct，供无 key 的全链路自测：
 *
 * <ul>
 *   <li><b>第一轮</b>（最近一条消息是用户消息）：请求一次 {@code save_memory} 工具调用——这一步会真正写入 {@code
 *       MEMORY.md}，给全链路一个可观测的「文件写入」行为；
 *   <li><b>第二轮</b>（工具结果已回填）：不再调工具，直接返回最终答复。
 * </ul>
 *
 * <p>只有「模型」是假的：ReActLoop / ToolExecutor / Memory / Session / 审计全部走真实路径。
 *
 * <p>判轮解析渲染文本的行形态（{@code PromptBuilder} 产出：「{@code user: …}」用户行、「{@code tool[名]:
 * …}」工具行），且必须比<b>最后一条</b>是谁——复用会话的历史里早有旧工具结果，只判「有没有工具行」会把每条新消息 都误判成第二轮、永不再调工具（参照课件坑）。记忆注入（[2]
 * 段）在对话历史之前，{@code lastIndexOf} 天然取到历史里的真实用户行。
 */
public class MockChatModel implements ChatModel {

  /** 用户消息行（PromptBuilder appendMessage：role 前缀）。 */
  private static final String USER_LINE = "\nuser: ";

  /** 工具结果行（PromptBuilder appendMessage：tool[名] 前缀——本仓形态，参照为 tool: ）。 */
  private static final String TOOL_LINE = "\ntool[";

  private static final String USER_MARK = "user: ";

  /** 第二轮的固定终答（跨模块测试断言字面量，公开只读常量）。 */
  public static final String FINAL_REPLY = "好的，已经按你的要求记录并处理完成。";

  @Override
  public ChatResponse call(Prompt prompt) {
    String rendered = prompt.getContents();
    // 判轮看「最近一条消息」：工具结果行在用户行之后 → 工具已回填，第二轮收尾；否则第一轮触发 save_memory。
    if (rendered.lastIndexOf(TOOL_LINE) > rendered.lastIndexOf(USER_LINE)) {
      return single(new AssistantMessage(FINAL_REPLY));
    }
    String fact = lastUserLine(rendered);
    AssistantMessage.ToolCall toolCall =
        new AssistantMessage.ToolCall(
            "mock-call-1",
            "function",
            "save_memory",
            "{\"content\":" + jsonString(fact) + ",\"scope\":\"archival\"}");
    // 1.1.8 带 tool call 的构造只经 builder 暴露（四参构造 protected——参照 0.x 三参直构的 API 代差）
    return single(AssistantMessage.builder().content("").toolCalls(List.of(toolCall)).build());
  }

  private static ChatResponse single(AssistantMessage message) {
    return new ChatResponse(List.of(new Generation(message)));
  }

  /** 取渲染文本里最后一条用户行的内容，作为要记住的事实。 */
  private static String lastUserLine(String rendered) {
    int idx = rendered.lastIndexOf(USER_MARK);
    if (idx < 0) {
      return "（无用户消息）";
    }
    int start = idx + USER_MARK.length();
    int end = rendered.indexOf('\n', start);
    String line = end < 0 ? rendered.substring(start) : rendered.substring(start, end);
    return line.isBlank() ? "（空消息）" : line;
  }

  /** 最小 JSON 字符串转义（不引依赖；覆盖工具参数里可能出现的常规字符）。 */
  private static String jsonString(String value) {
    String escaped =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    return "\"" + escaped + "\"";
  }
}

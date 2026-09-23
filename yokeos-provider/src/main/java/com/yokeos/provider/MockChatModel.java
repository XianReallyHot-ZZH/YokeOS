package com.yokeos.provider;

import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 内置 mock provider（第 27 节；31 节随结构化回传改造升级）——挂在显式映射表的 {@code "mock"} 保留名下（宪法 3）， 不连任何真实模型、不需要
 * key/网络。按脚本驱动一次确定性的 ReAct，供无 key 的全链路自测：
 *
 * <ul>
 *   <li><b>第一轮</b>（最后一条是用户消息）：请求一次 {@code save_memory} 工具调用——这一步会真正写入 {@code
 *       MEMORY.md}，给全链路一个可观测的「文件写入」行为；
 *   <li><b>第二轮</b>（工具结果已回填）：不再调工具，直接返回最终答复。
 * </ul>
 *
 * <p>只有「模型」是假的：ReActLoop / ToolExecutor / Memory / Session / 审计全部走真实路径。
 *
 * <p>判轮按<b>消息序列的最后一条是谁</b>（31 节结构化回传：SystemMessage 首位 + user/assistant/tool 结构化消息， 27
 * 节按拉平文本行前缀判轮的写法随形态一并升级）；复用会话的历史里早有旧工具结果，必须比最后一条——
 * 只判「有没有工具消息」会把每条新消息都误判成第二轮、永不再调工具（参照课件坑）。旧存量消息降级成的 user 文本（无配对 id）同样落进「最后一条是用户消息」的分支，语义不变。
 */
public class MockChatModel implements ChatModel {

  /** 第二轮的固定终答（跨模块测试断言字面量，公开只读常量）。 */
  public static final String FINAL_REPLY = "好的，已经按你的要求记录并处理完成。";

  @Override
  public ChatResponse call(Prompt prompt) {
    List<Message> instructions = prompt.getInstructions();
    Message last = instructions.get(instructions.size() - 1);
    // 工具结果在最后 → 工具已回填，第二轮收尾
    if (last instanceof ToolResponseMessage) {
      return single(new AssistantMessage(FINAL_REPLY));
    }
    String fact = lastUserContent(instructions);
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

  /** 取消息序列里最后一条用户消息的内容，作为要记住的事实（SystemMessage 首位天然跳过）。 */
  private static String lastUserContent(List<Message> instructions) {
    for (int i = instructions.size() - 1; i >= 0; i--) {
      Message message = instructions.get(i);
      if (message instanceof UserMessage) {
        // getText() 带 @NonNull（1.1.8 契约）——按 API 契约直取不判空（17 节坑族口径）
        return message.getText().isBlank() ? "（空消息）" : message.getText();
      }
    }
    return "（无用户消息）";
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

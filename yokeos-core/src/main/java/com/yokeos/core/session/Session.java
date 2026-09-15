package com.yokeos.core.session;

import com.yokeos.core.provider.ProviderResponse;
import com.yokeos.core.tool.ToolResult;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次对话的全部状态（第 17 节内存版；session_id 三元组拼接公式与持久化归 18 节）。
 *
 * <p>累积只走 append 三兄弟，消息严格按发生序追加——事后可完整回放（坑三落点：每轮 LLM 响应与工具结果 全留痕，对外可查可审计，宪法 7）。非
 * record：累积语义要求可变列表，record 的不可变重建在此是反模式。
 */
public final class Session {

  private final String sessionId;

  private final String profileName;

  private final List<Message> messages = new ArrayList<>();

  /**
   * @param sessionId 会话标识（由 SessionManager.getOrCreate 按 channel+user+agent 三元组生成，拼接单点在
   *     SessionIds——直接 new 的调用方仅限测试自造）
   */
  public Session(String sessionId, String profileName) {
    this.sessionId = sessionId;
    this.profileName = profileName;
  }

  /**
   * 恢复构造器（18 节持久化回读）：从存储层按发生序重建历史，append 三兄弟照常可用—— 恢复后的对话在既有历史上追加，不覆盖不重排。
   *
   * @param restored 按发生序的全部历史消息（来自 messages_json 反序列化）
   */
  public Session(String sessionId, String profileName, List<Message> restored) {
    this.sessionId = sessionId;
    this.profileName = profileName;
    messages.addAll(restored);
  }

  /** 会话标识（审计关联键，ReActLoop 每轮调用随 sessionId 传递）。 */
  public String sessionId() {
    return sessionId;
  }

  /** AgentService 按它查 Profile（点名报错含名字）。 */
  public String profileName() {
    return profileName;
  }

  /** 按发生序的消息快照。 */
  public List<Message> messages() {
    return List.copyOf(messages);
  }

  /** 追加用户消息（循环入口先于一切）。 */
  public void appendUser(String content) {
    messages.add(new Message("user", content, null));
  }

  /**
   * 追加模型响应（先累积再判停——转满轮数的那几轮也全留痕）。
   *
   * @param response 本轮模型响应；text 为 null 按空串（既无文本也无工具请求的收尾边界）
   */
  public void appendAssistant(ProviderResponse response) {
    String content = response.text() == null ? "" : response.text();
    messages.add(new Message("assistant", content, null));
  }

  /** 追加工具执行结果：成功存 content、失败存错误描述——成败都进历史，模型下一轮可见失败原因。 */
  public void appendToolResult(String toolName, ToolResult result) {
    String content = result.success() ? result.content() : result.errorMessage();
    messages.add(new Message("tool", content, toolName));
  }
}

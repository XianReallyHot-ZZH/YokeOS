package com.yokeos.core.session;

import com.yokeos.core.provider.ToolCallRequest;
import java.util.List;

/**
 * 会话消息记录 (role, content, toolName, toolCalls, toolCallId)。
 *
 * <p>role 取 user / assistant / tool 三值（String 不 enum：与 Function Calling 消息形态一致，18 节 JSON
 * 序列化零转换）；toolName 仅 tool 角色非空。31 节结构化回传补两字段： toolCalls 仅 assistant 轮携带（模型提出的调用，含 协议 id）；toolCallId
 * 仅 tool 轮非空（与 assistant.tool_calls 配对）。 拉平文本时代的存量消息两字段为空——provider 侧降级处理，信息不丢。
 */
public record Message(
    String role,
    String content,
    String toolName,
    List<ToolCallRequest> toolCalls,
    String toolCallId) {

  /** 防御性拷贝：toolCalls null（含旧 JSON 反序列化缺字段）兜底为空清单。 */
  public Message {
    toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
  }

  /** 三参兼容形态（31 节前签名）：无工具调用信息的普通消息。 */
  public Message(String role, String content, String toolName) {
    this(role, content, toolName, List.of(), null);
  }
}

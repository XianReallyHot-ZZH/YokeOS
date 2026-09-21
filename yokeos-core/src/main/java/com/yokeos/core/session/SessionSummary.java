package com.yokeos.core.session;

import java.time.LocalDateTime;

/**
 * 会话摘要（第 26 节）：列表视图的元数据投影，不携带消息体——列表端点不反序列化 messages_json（data-model 不变量④）。
 *
 * <p>字段与 sessions 表元数据列一一对应（agent_name 列 ↔ agentName 字段，18 节拍板②）；status 取 {@link #STATUS_ACTIVE} /
 * {@link #STATUS_ARCHIVED} 两值，归档是标记不是终结。
 */
public record SessionSummary(
    String sessionId,
    String agentName,
    String channel,
    String userId,
    String status,
    LocalDateTime lastActiveAt) {

  /** 活跃状态（getOrCreate 未命中时的初始值）。 */
  public static final String STATUS_ACTIVE = "active";

  /** 归档状态（26 节 DELETE 端点写入；标记不终结）。 */
  public static final String STATUS_ARCHIVED = "archived";
}

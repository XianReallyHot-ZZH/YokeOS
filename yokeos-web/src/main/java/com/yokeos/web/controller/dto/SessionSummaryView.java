package com.yokeos.web.controller.dto;

import com.yokeos.core.session.SessionSummary;
import java.time.LocalDateTime;

/** 会话列表条目（第 26 节，第 19 端点）：元数据投影，不带消息体——自 {@link SessionSummary} 一一转写。 */
public record SessionSummaryView(
    String sessionId,
    String agentName,
    String channel,
    String userId,
    String status,
    LocalDateTime lastActiveAt) {

  /** 摘要转视图（字段同名直搬）。 */
  public static SessionSummaryView from(SessionSummary summary) {
    return new SessionSummaryView(
        summary.sessionId(),
        summary.agentName(),
        summary.channel(),
        summary.userId(),
        summary.status(),
        summary.lastActiveAt());
  }
}

package com.yokeos.core.session;

import java.util.List;
import java.util.Optional;

/**
 * 会话存取契约（第 17 节最小面仅 save；第 18 节按预告改造点补全三方法；第 26 节补列表与归档两方法）。
 *
 * <p>session_id 由三元组 channel+user+agent 联合唯一生成，拼接只发生在 {@link SessionIds} 一处（H4④）—— 所有入口（CLI 传
 * "cli"、Web 传 "web"、定时传 "scheduler"）只提供三元组、不自己拼字符串。
 */
public interface SessionManager {

  /**
   * 三元组唯一决定会话：同一三元组幂等返回同一条（含已恢复历史）；未命中落一条 status=active 新记录。
   *
   * <p>并发兜底：主键即三元组拼接结果，撞键按已存在处理，不产生第二条（spec Edge Case）。
   */
  Session getOrCreate(String channel, String userId, String profileName);

  /** 按标识查会话（含已恢复历史）。 */
  Optional<Session> get(String sessionId);

  /** 保存会话（累积完的历史持久化；实现自辨覆盖或追加语义）。 */
  void save(Session session);

  /**
   * 列最近会话摘要（第 26 节，GET /api/v1/sessions 数据源）：按最近活跃倒序、至多 limit 条。
   *
   * <p>摘要不带消息体——实现不得触发 messages_json 反序列化（data-model 不变量④）。
   */
  List<SessionSummary> listRecent(int limit);

  /**
   * 归档会话（第 26 节，DELETE /api/v1/sessions/{id}）：status 置 archived 并写归档时间。
   *
   * <p>归档是标记不是终结（26 节 clarify）：同三元组 getOrCreate 仍幂等返回本会话（历史保留、状态不变）， 发消息不查状态。
   *
   * @return 会话未命中返回 false（调用方据此转 404）
   */
  boolean archive(String sessionId);
}

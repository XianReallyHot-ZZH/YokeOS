package com.yokeos.core.session;

/**
 * session_id 拼接的唯一发生点（H4④：拼接全库只允许在这一处）。
 *
 * <p>格式 {@code channel:user:agent}（如 {@code cli:wang:weather}）。格式本身不对外承诺——
 * 唯一承诺是「给三元组得会话」的语义与单点拼接：两处各拼一遍、格式差一个分隔符，同一个人就会出现两条互不相认的 历史（第 18 节坑一）。两个 SessionManager 实现（JPA /
 * 内存）共用本方法，杜绝第二实现复制公式。
 */
public final class SessionIds {

  private SessionIds() {}

  /**
   * 三元组拼会话标识。
   *
   * @param channel 接入渠道（cli / web / scheduler）
   * @param userId 用户标识
   * @param agentName Agent 名（= 目录名 = Profile name）
   */
  public static String compose(String channel, String userId, String agentName) {
    return channel + ":" + userId + ":" + agentName;
  }
}

package com.yokeos.core.agent;

import com.yokeos.core.profile.Profile;
import com.yokeos.core.profile.ProfileRegistry;
import com.yokeos.core.session.Session;
import com.yokeos.core.session.SessionManager;

/**
 * 三种触发源（CLI / Web / 定时）共用的统一处理入口，一次处理的编排者（技 §4.2）：查 Profile → set ProfileContext → 跑循环 →
 * 保存会话（仅正常路径）→ finally 清 ProfileContext。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
    justification =
        "profileRegistry 等协作者是注入的单例服务，构造注入共享同一引用正是意图（25 节 AgentScheduler 同款先例）；"
            + "29 节 ProfileRegistry 增设 remove 运行时原语后 SpotBugs 对其可变性判定升级，本注解随门禁连锁显式落档")
public final class AgentService {

  private final ProfileRegistry profileRegistry;

  private final ReActLoop reActLoop;

  private final SessionManager sessionManager;

  /** 三协作者注入：注册表查 Profile、循环执行、会话保存。 */
  public AgentService(
      ProfileRegistry profileRegistry, ReActLoop reActLoop, SessionManager sessionManager) {
    this.profileRegistry = profileRegistry;
    this.reActLoop = reActLoop;
    this.sessionManager = sessionManager;
  }

  /** 处理一次用户消息：Profile 不存在点名报错（含名字）；正常路径结束后持久化会话、异常路径不保存； ProfileContext 无论成败都在 finally 清掉（坑四）。 */
  public String process(Session session, String userMessage) {
    Profile profile =
        profileRegistry
            .get(session.profileName())
            .orElseThrow(
                () ->
                    new IllegalStateException("Session 引用的 Profile 不存在: " + session.profileName()));
    ProfileContext.set(profile);
    try {
      String reply = reActLoop.run(session, userMessage, profile);
      sessionManager.save(session); // 把累积完的历史持久化（仅正常路径）
      return reply;
    } finally {
      ProfileContext.clear(); // remove：中途抛异常也清，防线程复用串号
    }
  }
}

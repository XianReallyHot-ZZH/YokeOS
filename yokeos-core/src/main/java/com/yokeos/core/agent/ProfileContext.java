package com.yokeos.core.agent;

import com.yokeos.core.profile.Profile;

/**
 * ThreadLocal 的 Agent 上下文（技 §4.2）：解决「工具执行时怎么知道当前是哪个 Agent」——YokeTool.execute 签名不带
 * Profile（改接口代价太大），入口 set、出口 clear，工具想用就取。
 *
 * <p>虚拟线程下每个请求独占线程天然不串；clear 必须放 finally 且用 remove（P3C 同款要求）——循环中途抛异常 也清，否则下一个复用线程的请求拿到别人的
 * Profile（坑四：单请求测试永不报错，只在并发复用时串号）。
 */
public final class ProfileContext {

  private static final ThreadLocal<Profile> CURRENT = new ThreadLocal<>();

  private ProfileContext() {}

  /** 处理入口设置当前 Profile（AgentService.process 唯一调用方）。 */
  public static void set(Profile profile) {
    CURRENT.set(profile);
  }

  /** 当前 Profile；未设置时为 null。工具执行期经它读取所属 Agent 的声明（19 节 notify、20 节过滤）。 */
  public static Profile current() {
    return CURRENT.get();
  }

  /** 清理：remove 而非 set(null)——彻底摘除条目，避免线程池复用时读到陈旧引用。 */
  public static void clear() {
    CURRENT.remove();
  }
}

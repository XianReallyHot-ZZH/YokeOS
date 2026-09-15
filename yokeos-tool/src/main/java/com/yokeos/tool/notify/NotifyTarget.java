package com.yokeos.tool.notify;

import java.util.Map;

/**
 * 一次推送的通知目标（技 §6.8）：渠道类型 + 一份配置，具体键含义由实现解释（webhook 档为 {@code url} 等）。
 *
 * <p>接口层不做结构性校验——缺键由实现点名报错，接口语汇零渠道特有词。
 */
public record NotifyTarget(String channelType, Map<String, String> config) {

  /** 防御副本：目标构造后配置不可变（SpotBugs EI_EXPOSE_REP 同款口径）。 */
  public NotifyTarget {
    config = Map.copyOf(config);
  }
}

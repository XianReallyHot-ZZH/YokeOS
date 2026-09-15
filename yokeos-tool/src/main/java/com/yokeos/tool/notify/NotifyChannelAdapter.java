package com.yokeos.tool.notify;

/**
 * 出站通知渠道适配接口（技 §6.8「接口先行」）：表达「把一条内容送到某个通知目标」的意图，不携带任何具体渠道特有的概念。
 *
 * <p>第一阶段唯一实现是通用 HTTP webhook；扩展阶段按 {@link NotifyTarget#channelType()} 新增实现类，不改本接口、
 * 不改调用方——接口中立性自查：换任何一家官方 SDK 实现，本签名不需要动（宪法 6「Sandbox 接口先行」同一套设计习惯）。
 */
public interface NotifyChannelAdapter {

  /**
   * 把一条内容送到通知目标。
   *
   * @param target 通知目标（渠道类型 + 配置，键含义由实现解释）
   * @param content 要推送的内容
   * @throws RuntimeException 任何失败必须上抛、绝不静默吞掉——「发出去没送到」与「没发出去」对 Agent 是同一件事（research D6）
   */
  void send(NotifyTarget target, String content);
}

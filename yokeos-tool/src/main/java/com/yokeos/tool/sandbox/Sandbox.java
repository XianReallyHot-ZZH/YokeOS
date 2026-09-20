package com.yokeos.tool.sandbox;

/**
 * 沙箱：在动作发生处守门——「确保该动作只在受控环境发生」（宪法 6、技 §6.7、specs/008 D1/D2）。
 *
 * <p>接口不携带任何一档实现特有的概念（不出现「白名单」「容器镜像」「VM 配置」字样）：第一阶段唯一实现是应用层 白名单档 {@link
 * WhitelistSandbox}（校验放行或拒绝）；未来容器/microVM 档的实现语义可以是「把动作路由进隔离环境 执行」——签名不变，语义重心由实现定义（specs/008
 * D2）。中立性校验法：拿最重的 microVM 实现反向套本签名，套得 进去才算墙立住了。
 *
 * <p>第一阶段 {@code ActionType} 四值无法分类 MCP server 语义，MCP 转发调用不过本接口（specs/008 D8：信任边界 + 审计 day one
 * 兜底，诚实标注的暴露面）；底座自身基础设施（Provider 调 LLM、审计落库、会话持久化）不在管辖。
 */
public interface Sandbox {

  /**
   * 校验一个即将发生的动作。校验失败抛 {@link SandboxViolationException}，动作不得发生（接线位保证 enforce 在方法 首行、先于任何
   * IO）；校验过程自身的异常（IO/解析失败）一律转 {@link SandboxViolationException}（fail-closed：
   * 校验完成不了就拒绝，绝不放行也不漏出异常类型）。
   *
   * @param action 动作类型 + 目标（路径/命令/URL 由 type 决定，target 是纯字符串）
   */
  void enforce(SandboxAction action);
}

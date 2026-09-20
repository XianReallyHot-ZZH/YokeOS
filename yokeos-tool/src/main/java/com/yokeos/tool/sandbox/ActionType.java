package com.yokeos.tool.sandbox;

/**
 * 动作类型四值（宪法 6、技 §6.7、specs/008 D1/D3）。文件读/写分开是接口层预留「未来按读/写分权限」的维度；第一阶段 {@link WhitelistSandbox} 把
 * {@link #FILE_READ}、{@link #FILE_WRITE} 两个 case 同路由到路径校验（同一份路径白名 单）——接口先分、实现先合。不设 TIMEOUT/RESOURCE
 * 值：超时由 shell 工具进程超时承载、资源由超长输出截断承载（张力 二裁决，specs/008 §5.2），完整配额随容器档进扩展阶段。
 */
public enum ActionType {
  /** 读文件 / 列目录。 */
  FILE_READ,

  /** 写文件（含新建）。 */
  FILE_WRITE,

  /** 执行命令——target 为 argv[0] 本身（本仓 shell 工具 argv 列表直传，无首 token 切分）。 */
  SHELL_COMMAND,

  /** 发起 HTTP 请求（含 webhook 推送与 mem0 档出站——共享同一份域名白名单）。 */
  HTTP_REQUEST
}

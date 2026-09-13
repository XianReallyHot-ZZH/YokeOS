package com.yokeos.core.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Tool 统一抽象的最小接口（宪法 5：YokeTool 统一抽象）。
 *
 * <p>三说明方法供 ToolSchemaAdapter 翻译 schema（第 16 节）；execute 是真实执行语义（第 17 节拍板② 补齐——循环要真执行了）。白名单校验归 20/24
 * 节在 ToolExecutor 的检查位接线，本接口不携带任何校验概念。
 */
public interface YokeTool {

  /** Tool 名（Function Calling 的函数名）。 */
  String getName();

  /** 给模型看的用途说明。 */
  String getDescription();

  /** 参数 JSON Schema（翻译成各家协议的依据）。 */
  String getInputSchema();

  /**
   * 执行一次工具调用。
   *
   * @param input 已解析的入参（ToolExecutor 先解析后执行——坏 JSON 不会到这里）
   * @return 成败结果（失败带原因与可重试标记，交还循环由模型决定下一步）
   */
  ToolResult execute(JsonNode input);
}

package com.yokeos.core.tool;

/**
 * Tool 统一抽象的最小接口（宪法 5：YokeTool 统一抽象）。
 *
 * <p>第 16 节只建 {@link ToolSchemaAdapter} 翻译所需的三方法最小形态（镜像参照第 16 节对 OryxTool 的最小触碰，research
 * D4）；执行语义（execute、ToolResult、白名单校验）归第 20 节扩展， 完整体系落 yokeos-tool 模块——本接口放 core 是跨模块契约的落位规则。
 */
public interface YokeTool {

  /** Tool 名（Function Calling 的函数名）。 */
  String getName();

  /** 给模型看的用途说明。 */
  String getDescription();

  /** 参数 JSON Schema（翻译成各家协议的依据）。 */
  String getInputSchema();
}

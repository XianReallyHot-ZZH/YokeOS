package com.yokeos.web.controller;

import com.yokeos.tool.ToolRegistry;
import com.yokeos.web.common.ApiResponse;
import com.yokeos.web.controller.dto.ToolView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 只读：列出注册表全量 Tool（第 26 节，GET /api/v1/tools）——含内置与 MCP 接入（ToolRegistry 是唯一真相源，research D6）。 describe
 * 与调用历史查询列扩展阶段（技 §7.3）。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification =
        "第一阶段 web API 无认证是设计决定（内网部署假设，需求 §5.10「第一阶段不做」）；认证（API Key + JWT）列扩展阶段。"
            + " toolRegistry 是装配层注入的共享单例，构造注入共享同一引用正是意图")
@RestController
@RequestMapping("/api/v1/tools")
public class ToolApiController {

  private final ToolRegistry toolRegistry;

  /** 注册表注入。 */
  public ToolApiController(ToolRegistry toolRegistry) {
    this.toolRegistry = toolRegistry;
  }

  /** 全量投影：name + description，input schema 等执行细节不外泄。 */
  @GetMapping
  public ApiResponse<List<ToolView>> list() {
    return ApiResponse.ok(
        toolRegistry.all().stream()
            .map(tool -> new ToolView(tool.getName(), tool.getDescription()))
            .toList());
  }
}

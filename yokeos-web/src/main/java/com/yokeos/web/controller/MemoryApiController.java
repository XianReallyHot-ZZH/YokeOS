package com.yokeos.web.controller;

import com.yokeos.core.memory.MemoryService;
import com.yokeos.web.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 只读：返回长期记忆全文（第 26 节，GET /api/v1/memory）——观察口径（readAll），markdown 档回 MEMORY.md 原文两分区原貌。 写入端点（append
 * / clear / search）列扩展阶段（技 §7.3）。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification = "第一阶段 web API 无认证是设计决定（内网部署假设，需求 §5.10「第一阶段不做」）；认证（API Key + JWT）列扩展阶段")
@RestController
@RequestMapping("/api/v1/memory")
public class MemoryApiController {

  private final MemoryService memoryService;

  /** 门面注入（三档后端由装配层选定，本类不感知档位）。 */
  public MemoryApiController(MemoryService memoryService) {
    this.memoryService = memoryService;
  }

  /** 全文原样进 data（String 载荷）。 */
  @GetMapping
  public ApiResponse<String> read() {
    return ApiResponse.ok(memoryService.readAll());
  }
}

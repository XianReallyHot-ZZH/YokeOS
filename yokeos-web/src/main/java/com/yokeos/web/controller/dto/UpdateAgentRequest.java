package com.yokeos.web.controller.dto;

/** PUT /api/v1/agents/{name} 请求体（第 30 节，FR-008）：覆写全文，与 create 对称。 */
public record UpdateAgentRequest(String agentMarkdown) {}

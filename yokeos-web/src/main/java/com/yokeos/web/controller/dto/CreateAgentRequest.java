package com.yokeos.web.controller.dto;

/**
 * POST /api/v1/agents 请求体（第 30 节，FR-005，拍板②）：AGENT.md 全文直给——generate 草稿 → 人改 → 提交全文的闭环形态； 带附属资源的复杂
 * Agent 走手工丢目录（本端点只写 AGENT.md）。
 */
public record CreateAgentRequest(String name, String agentMarkdown) {}

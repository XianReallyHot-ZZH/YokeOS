package com.yokeos.web.controller.dto;

/** POST /api/v1/agents/generate 响应体（第 30 节，FR-001）：草稿全文——不落盘不注册，人在环预览。 */
public record GenerateResponse(String agentMarkdown) {}

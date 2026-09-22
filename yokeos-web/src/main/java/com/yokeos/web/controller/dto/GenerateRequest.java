package com.yokeos.web.controller.dto;

/** POST /api/v1/agents/generate 请求体（第 30 节，FR-001）：一句话需求。 */
public record GenerateRequest(String sentence) {}

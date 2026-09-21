package com.yokeos.web.controller.dto;

/** 创建会话请求（第 26 节）：profile 必填（缺失 400）；userId 可选，缺省 default。 */
public record CreateSessionRequest(String profile, String userId) {}

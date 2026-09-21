package com.yokeos.web.controller.dto;

/** 发消息 / invoke 请求体（第 26 节）：content 必填，单条 ≤32KB（防呆上限）。 */
public record MessageRequest(String content) {}

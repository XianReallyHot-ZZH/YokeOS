package com.yokeos.core.provider;

/**
 * 模型提出的单个工具调用请求（契约上移，第 17 节拍板①）。
 *
 * <p>name 是 Function Calling 的函数名；argumentsJson 是参数的 JSON 文本——解析责任在 ToolExecutor （先解析后执行，坏 JSON
 * 走失败路径），本对象只承载原文。
 */
public record ToolCallRequest(String name, String argumentsJson) {}

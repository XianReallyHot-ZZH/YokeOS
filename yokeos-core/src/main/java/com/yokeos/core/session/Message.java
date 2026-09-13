package com.yokeos.core.session;

/**
 * 会话消息三元记录 (role, content, toolName)。
 *
 * <p>role 取 user / assistant / tool 三值（String 不 enum：与 Function Calling 消息形态一致，18 节 JSON
 * 序列化零转换）；toolName 仅 tool 角色非空。
 */
public record Message(String role, String content, String toolName) {}

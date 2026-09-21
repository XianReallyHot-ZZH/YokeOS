package com.yokeos.web.controller.dto;

import com.yokeos.core.session.Message;
import java.util.List;

/** 查历史响应体（第 26 节）：会话标识 + Profile 名 + 截断后的历史（最多最近 100 条，按发生序）。 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
    justification =
        "响应载荷 record：List 字段经 Jackson 序列化为 JSON 即离 JVM，进程内无二次暴露路径（MemoryServiceImpl 同款抑制先例）")
public record SessionView(String sessionId, String profileName, List<Message> messages) {}

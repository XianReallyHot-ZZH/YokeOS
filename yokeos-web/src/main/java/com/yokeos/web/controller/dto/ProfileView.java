package com.yokeos.web.controller.dto;

import java.util.List;

/** Profile 只读投影（第 26 节，GET /api/v1/profiles）：可展示字段，identity / schedules 等内部段不外泄。 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
    justification =
        "响应载荷 record：List 字段经 Jackson 序列化为 JSON 即离 JVM，进程内无二次暴露路径（MemoryServiceImpl 同款抑制先例）")
public record ProfileView(
    String name, String description, String providerName, String model, List<String> tools) {}

package com.yokeos.web.controller.dto;

import java.util.List;

/** 运行信息视图（第 26 节，GET /api/v1/info）：providers 为已配置口径（已加载 Profile 引用到的 provider 名去重排序，不探活）。 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
    justification =
        "响应载荷 record：List 字段经 Jackson 序列化为 JSON 即离 JVM，进程内无二次暴露路径（MemoryServiceImpl 同款抑制先例）")
public record InfoView(String product, String version, List<String> providers) {}

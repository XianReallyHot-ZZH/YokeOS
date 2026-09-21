package com.yokeos.web.controller.dto;

/** Tool 只读投影（第 26 节，GET /api/v1/tools）：名称 + 描述，input schema 等执行细节不外泄。 */
public record ToolView(String name, String description) {}

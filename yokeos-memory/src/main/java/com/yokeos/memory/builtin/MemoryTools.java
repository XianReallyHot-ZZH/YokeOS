package com.yokeos.memory.builtin;

import com.yokeos.core.memory.MemoryScope;
import com.yokeos.core.memory.MemoryService;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 内置记忆工具（技 §5.1，specs/006 D7）：把长期记忆暴露给 Agent——只认 {@link MemoryService} 门面， 对底层是哪档后端完全无感，换后端不碰本类。
 *
 * <p>落位 yokeos-memory 是 specs/006 裁决二：宪法 5 管 Tool 基础设施合块，本类是能力三的组成部分；注册进 ToolRegistry 与其他内置 Tool
 * 一视同仁（经 {@code registerAnnotated} 管道——schema 由 Spring AI 生成， 执行发起方永远是 ToolExecutor，宪法 2）。
 *
 * <p>写核心还是写归档由 Agent 经 scope 显式指定（契约三），缺省归档；非法值点名报错，不静默落错区。
 */
public class MemoryTools {

  private final MemoryService memoryService;

  /**
   * @param memoryService 记忆门面（装配层注入，与 PromptBuilder 共用同一实例）
   */
  public MemoryTools(MemoryService memoryService) {
    this.memoryService = memoryService;
  }

  /** 记住一件值得长期记住的事。 */
  @Tool(name = "save_memory", description = "记住一件值得长期记住的事")
  public String saveMemory(
      @ToolParam(description = "要记住的内容") String content,
      @ToolParam(description = "core 或 archival，不确定就填 archival", required = false) String scope) {
    String normalized =
        (scope == null || scope.isBlank()) ? "ARCHIVAL" : scope.toUpperCase(Locale.ROOT);
    MemoryScope target;
    try {
      target = MemoryScope.valueOf(normalized);
    } catch (IllegalArgumentException e) {
      // 非法 scope 明确报错点名，不静默落错区（坑四）——返回提示而非抛异常，让模型下一轮自行纠正
      return "无法识别的记忆分区: " + scope + "（应为 core 或 archival）";
    }
    memoryService.remember(content, target);
    return "已记住";
  }

  /** 按关键词检索长期记忆（只搜归档区；核心区本来就被全量注入）。 */
  @Tool(name = "recall_memory", description = "按关键词检索长期记忆")
  public String recallMemory(@ToolParam(description = "检索关键词") String keyword) {
    List<String> hits = memoryService.recall(keyword);
    return hits.isEmpty() ? "没有找到相关记忆" : String.join("\n", hits);
  }
}

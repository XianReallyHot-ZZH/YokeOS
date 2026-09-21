package com.yokeos.core.memory;

import com.yokeos.core.session.Session;
import java.util.List;

/**
 * 记忆统一门面（技 §5.1，跨模块契约放 core）：上层（PromptBuilder / MemoryTools）只认这三个方法， 不感知底层长期记忆是文件、本地库还是外部服务——这是第 21
 * 节评审那道「接口墙」的对上一侧（specs/006 D1）。
 *
 * <p>接口上移 core 而非留在 yokeos-memory，是因为 PromptBuilder（core）必须注入它，而 memory → core 的依赖已经存在，接口留在 memory
 * 会成环——与 16 节 ProviderService 接口上移同一个先例、同一个理由（技 §10「跨模块契约放 core」）。实现在 yokeos-memory。
 *
 * <p>会话记忆（短期）复用既有 Session 体系（specs/006 D10）：门面统一对外但不重造会话存储；上下文截断（max_history_turns）为既有行为。
 */
public interface MemoryService {

  /**
   * 拼进 prompt 的长期记忆（核心区全量 + 归档区截断后，带分区 header）。 只出长期记忆——会话历史由 PromptBuilder
   * 既有对话历史段承载，两者各拼各的（坑七：历史重复注入）。
   */
  String buildContext(Session session);

  /** {@code save_memory} 转发：记一条到指定分区（自动附日期 header，契约三：分区由 Agent 显式指定、系统不猜）。 */
  void remember(String content, MemoryScope scope);

  /** {@code recall_memory} 转发：按关键词只在归档区检索（契约四：关键词包含匹配，不做复杂化）。 */
  List<String> recall(String keyword);

  /** 只读全文视图（第 26 节，GET /api/v1/m 数据源）：观察口径，非注入口径——markdown 档回 MEMORY.md 原文两分区原貌， 结构化档按注入同口径拼装。 */
  String readAll();
}

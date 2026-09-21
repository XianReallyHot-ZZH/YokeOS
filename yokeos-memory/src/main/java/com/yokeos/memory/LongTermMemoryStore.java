package com.yokeos.memory;

import com.yokeos.core.memory.MemoryScope;
import java.util.List;

/**
 * 长期记忆的可插拔后端接口（技 §5.1，specs/006 D2）——「接口墙」的对下一侧：三档实现各写各的，换档只改 {@code yokeos.memory.backend}
 * 一行配置，门面与上层零改动。
 *
 * <p>所有实现共同遵守四条行为契约（specs/006 D3，由 MemoryStoreContractTest 对三档统一钉死）：
 *
 * <ol>
 *   <li><b>不缓存</b>——每次 {@link #load}/{@link #recallByKeyword} 重新读（文件 / 查库 / 调
 *       API），不做进程内缓存；写入后下一轮立即可见
 *   <li><b>核心区永不被截断</b>——截断只作用归档区，物理隔离（裁剪只收归档段 / LIMIT 只加在归档查询）
 *   <li><b>分区由调用方显式指定</b>——实现不猜、不改写；缺省语义在 Tool 层（缺省 ARCHIVAL）
 *   <li><b>关键词检索</b>——包含匹配（行匹配 / LIKE / REST search），不上正则、分词、语义、向量
 * </ol>
 */
public interface LongTermMemoryStore {

  /** 追加一条记忆到指定分区，自动加日期 header（技 §5.1 口径：{@code - [yyyy-MM-dd] 内容}）。 */
  void append(String content, MemoryScope scope);

  /** 返回核心区全量 + 归档区截断后的内容（核心区永远完整——契约二）。 */
  String load();

  /**
   * 只读全文视图（第 26 节，GET /api/v1/m 数据源）：markdown 档回 MEMORY.md 原文两分区原貌； 结构化档按注入同口径拼装（核心全量 + 归档截断视图）。与
   * {@link #load} 的差别：markdown 档不因注入口径裁剪归档区——观察页要看到的是「记了什么」， 不是「这轮拼进了什么」。
   */
  String readAll();

  /** 按关键词检索，只在归档区做匹配（核心区不参与——它本来就被全量注入）。 */
  List<String> recallByKeyword(String keyword);
}

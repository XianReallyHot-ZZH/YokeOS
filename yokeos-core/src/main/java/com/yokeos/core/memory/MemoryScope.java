package com.yokeos.core.memory;

/**
 * 长期记忆的两分区语义（技 §5.1，借 MemGPT 的 core memory 分层概念，specs/006 D5）：
 *
 * <ul>
 *   <li>{@link #CORE} 核心区——用户是谁、项目背景、关键偏好：全量注入、永不截断、不参与检索（本来就在场）
 *   <li>{@link #ARCHIVAL} 归档区——过程性记忆，写入缺省分区：截断保留最近、关键词检索的唯一对象
 * </ul>
 *
 * <p>两层两分区口径（specs/006 定稿总口径）：两层 = 会话 + 长期，长期内部再分核心 / 归档两分区；不照搬业界「三层」叫法。
 */
public enum MemoryScope {
  CORE,
  ARCHIVAL
}

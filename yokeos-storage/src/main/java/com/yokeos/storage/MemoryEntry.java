package com.yokeos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 长期记忆条目（第 22 节 sqlite 档存储体，技 §9.2）：scope 列承载两分区语义（CORE / ARCHIVAL—— 与 MEMORY.md 的两个 header
 * 同构，specs/006 D5「分区约定不变，存储形态随后端而变」）。
 *
 * <p>POJO + getter/setter 与 Session/LlmCall 同款（JPA 惯例）；列名蛇形映射与建表脚本逐字对齐（宪法 7 手工脚本是唯一权威）。
 */
@Entity
@Table(name = "memory_entries")
public class MemoryEntry {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private String scope;

  @Column(nullable = false)
  private String content;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  /** 主键（自增）。 */
  public Long getId() {
    return id;
  }

  /** 分区：CORE / ARCHIVAL。 */
  public String getScope() {
    return scope;
  }

  /** 分区：CORE / ARCHIVAL。 */
  public void setScope(String scope) {
    this.scope = scope;
  }

  /** 记忆内容。 */
  public String getContent() {
    return content;
  }

  /** 记忆内容。 */
  public void setContent(String content) {
    this.content = content;
  }

  /** 写入时间（排序承载「保留最近」语义）。 */
  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  /** 写入时间（排序承载「保留最近」语义）。 */
  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }
}

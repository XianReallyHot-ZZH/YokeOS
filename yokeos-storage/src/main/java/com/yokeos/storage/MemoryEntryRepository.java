package com.yokeos.storage;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * memory_entries 的仓库（第 22 节 sqlite 档）。查询口径即行为契约的 SQL 结构保证（specs/006 D3）：
 *
 * <ul>
 *   <li>核心区全量：{@code findByScope("CORE", Pageable.unpaged())}——物理上无 LIMIT（契约二：核心区永不被截断）
 *   <li>归档区截断：{@code findByScope("ARCHIVAL", PageRequest.of(0, N, DESC createdAt))}——LIMIT 只加在这里
 *   <li>检索：{@link #searchArchival}——LIKE 前转义 {@code \ % _}（契约四：关键词按字面包含匹配，不放大成通配符）
 * </ul>
 */
public interface MemoryEntryRepository extends JpaRepository<MemoryEntry, Long> {

  /** 按 scope 查询；分页参数承载 LIMIT（核心区传 unpaged 全量、归档区传取最近 N——见类注释）。 */
  List<MemoryEntry> findByScope(String scope, Pageable pageable);

  /** 只在归档区做关键词包含匹配；{@code %}/{@code _} 转义为字面量（ESCape 子句配对）。 */
  default List<MemoryEntry> searchArchival(String keyword) {
    String escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return searchArchivalByPattern("%" + escaped + "%");
  }

  /** {@link #searchArchival} 的内部形态（模式已转义拼好），不直接对外。 */
  @Query(
      "SELECT m FROM MemoryEntry m"
          + " WHERE m.scope = 'ARCHIVAL' AND m.content LIKE :pattern ESCAPE '\\'")
  List<MemoryEntry> searchArchivalByPattern(@Param("pattern") String pattern);
}

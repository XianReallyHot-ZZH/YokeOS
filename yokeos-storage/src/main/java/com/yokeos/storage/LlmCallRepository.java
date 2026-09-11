package com.yokeos.storage;

import org.springframework.data.jpa.repository.JpaRepository;

/** {@code llm_calls} 的 Spring Data 仓库；第 16 节只写不查（查询接口扩展阶段，技 §9.2）。 */
public interface LlmCallRepository extends JpaRepository<LlmCall, Long> {}

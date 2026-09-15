package com.yokeos.storage;

import org.springframework.data.jpa.repository.JpaRepository;

/** {@code sessions} 仓库（第 18 节）：主键即三元组拼接结果，幂等靠主键唯一兜底。 */
public interface SessionRepository extends JpaRepository<Session, String> {}

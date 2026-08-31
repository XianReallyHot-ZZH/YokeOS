package com.yokeos.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Skeleton-period smoke test for the reserved audit DDL (constitution 7): both day-one audit tables
 * must exist as hand-maintained scripts before section 16 starts writing to them.
 */
class StorageAuditDdlTest {

  @Test
  @DisplayName("模块冒烟：包名与测试基础设施可用")
  void packageFollowsGroupIdConvention() {
    assertEquals("com.yokeos.storage", getClass().getPackageName());
  }

  @Test
  @DisplayName("审计两表 DDL 预留位存在且幂等")
  void auditDdlSkeletonReservesBothAuditTables() throws IOException {
    String ddl = readResource("/db/schema-001-audit.sql");
    assertTrue(
        ddl.contains("CREATE TABLE IF NOT EXISTS tool_invocations"),
        "tool_invocations DDL missing");
    assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS llm_calls"), "llm_calls DDL missing");
  }

  private static String readResource(String path) throws IOException {
    try (InputStream in = StorageAuditDdlTest.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IOException("Resource not found on classpath: " + path);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}

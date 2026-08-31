package com.yokeos.web.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 信封契约：四个字段，成功与错误共用（docs/TechnicalSolution.md §7.1）。 */
class ApiResponseTest {

  @Test
  @DisplayName("ok 工厂：code=0、message=success、携带 data 与时间戳")
  void okCarriesPayloadWithSuccessCode() {
    ApiResponse<String> response = ApiResponse.ok("payload");
    assertEquals(0, response.getCode());
    assertEquals("success", response.getMessage());
    assertEquals("payload", response.getData());
    assertTrue(response.getTimestamp() > 0);
  }

  @Test
  @DisplayName("error 工厂：code=HTTP 状态值、可读 message、data 为空")
  void errorCarriesHttpStatusCodeWithoutPayload() {
    ApiResponse<Void> response = ApiResponse.error(400, "bad request");
    assertEquals(400, response.getCode());
    assertEquals("bad request", response.getMessage());
    assertNull(response.getData());
    assertTrue(response.getTimestamp() > 0);
  }
}

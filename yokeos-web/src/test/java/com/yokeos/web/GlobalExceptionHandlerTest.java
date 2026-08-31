package com.yokeos.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.yokeos.web.common.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Exception-to-envelope mappings (docs/TechnicalSolution.md §7.4): 400 / 404 / 503 / 500, with
 * sanitized outbound messages and full details only in server logs.
 */
class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  @DisplayName("非法参数映射 400，消息原文透出")
  void illegalArgumentMapsToBadRequest() {
    ResponseEntity<ApiResponse<Void>> response =
        handler.handleBadRequest(new IllegalArgumentException("invalid argument"));
    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals(400, response.getBody().getCode());
    assertEquals("invalid argument", response.getBody().getMessage());
  }

  @Test
  @DisplayName("无匹配资源映射 404，固定可读消息")
  void noResourceFoundMapsToNotFound() {
    ResponseEntity<ApiResponse<Void>> response =
        handler.handleNotFound(new NoResourceFoundException(HttpMethod.GET, "missing.txt"));
    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    assertEquals(404, response.getBody().getCode());
    assertEquals("Resource not found", response.getBody().getMessage());
  }

  @Test
  @DisplayName("下游不可用映射 503")
  void illegalStateMapsToServiceUnavailable() {
    ResponseEntity<ApiResponse<Void>> response =
        handler.handleUnavailable(new IllegalStateException("provider unreachable"));
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    assertEquals(503, response.getBody().getCode());
    assertEquals("provider unreachable", response.getBody().getMessage());
  }

  @Test
  @DisplayName("兜底映射 500，对外只给固定消息，细节留在日志")
  void catchAllMapsToInternalErrorWithoutLeakingDetails() {
    ResponseEntity<ApiResponse<Void>> response =
        handler.handleInternalError(new RuntimeException("secret internal detail"));
    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    assertEquals(500, response.getBody().getCode());
    assertEquals("Internal server error", response.getBody().getMessage());
  }
}

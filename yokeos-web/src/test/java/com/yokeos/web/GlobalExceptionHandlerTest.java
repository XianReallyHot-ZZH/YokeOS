package com.yokeos.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.yokeos.web.common.ApiResponse;
import com.yokeos.web.error.AgentTimeoutException;
import com.yokeos.web.error.ProviderUnavailableException;
import com.yokeos.web.error.ResourceNotFoundException;
import com.yokeos.web.error.SessionNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 异常到信封的映射（docs/TechnicalSolution.md §7.4）：400 / 404 / 503 / 500，对外消息净化， 完整细节只进服务端日志。 */
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

  @Test
  @DisplayName("领域资源不存在映射 404，消息含缺失名字")
  void domainNotFoundMapsToNotFoundWithName() {
    ResponseEntity<ApiResponse<Void>> sessionMissing =
        handler.handleDomainNotFound(new SessionNotFoundException("s-404"));
    assertEquals(HttpStatus.NOT_FOUND, sessionMissing.getStatusCode());
    assertEquals(404, sessionMissing.getBody().getCode());
    assertEquals("会话不存在: s-404", sessionMissing.getBody().getMessage());

    ResponseEntity<ApiResponse<Void>> agentMissing =
        handler.handleDomainNotFound(new ResourceNotFoundException("Agent 不存在: weather"));
    assertEquals(HttpStatus.NOT_FOUND, agentMissing.getStatusCode());
    assertEquals("Agent 不存在: weather", agentMissing.getBody().getMessage());
  }

  @Test
  @DisplayName("Provider 显式故障映射 503")
  void providerUnavailableMapsToServiceUnavailable() {
    ResponseEntity<ApiResponse<Void>> response =
        handler.handleProviderDown(new ProviderUnavailableException("DeepSeek 连接失败"));
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    assertEquals(503, response.getBody().getCode());
    assertEquals("DeepSeek 连接失败", response.getBody().getMessage());
  }

  @Test
  @DisplayName("Spring AI Provider 故障族映射 503（错 key 注入实证路径）")
  void aiProviderFailureMapsToServiceUnavailable() {
    ResponseEntity<ApiResponse<Void>> nonTransient =
        handler.handleAiProviderFailure(new NonTransientAiException("401 - Authentication Fails"));
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, nonTransient.getStatusCode());
    assertEquals(503, nonTransient.getBody().getCode());

    ResponseEntity<ApiResponse<Void>> transientFailure =
        handler.handleAiProviderFailure(new TransientAiException("429 - Rate limit"));
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, transientFailure.getStatusCode());
  }

  @Test
  @DisplayName("Agent 调用超时映射 504（口径占位）")
  void agentTimeoutMapsToGatewayTimeout() {
    ResponseEntity<ApiResponse<Void>> response =
        handler.handleTimeout(new AgentTimeoutException("Agent 调用超时"));
    assertEquals(HttpStatus.GATEWAY_TIMEOUT, response.getStatusCode());
    assertEquals(504, response.getBody().getCode());
    assertEquals("Agent 调用超时", response.getBody().getMessage());
  }
}

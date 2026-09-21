package com.yokeos.web;

import com.yokeos.web.common.ApiResponse;
import com.yokeos.web.error.AgentTimeoutException;
import com.yokeos.web.error.ProviderUnavailableException;
import com.yokeos.web.error.ResourceNotFoundException;
import com.yokeos.web.error.SessionNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 把未捕获异常翻译成统一的 {@link ApiResponse} 错误信封（docs/TechnicalSolution.md §7.1），客户端 永远拿到可预期的 JSON body
 * 与稳定错误码。地基只带通用映射；领域异常（资源不存在、Provider 超时……）随各自节次接入，并沿用下方 {@code sanitize} 纪律：对外消息保持可读，完整细节只进 服务端日志。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /** 400 —— 请求参数非法或格式错误。 */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiResponse<Void>> handleBadRequest(IllegalArgumentException ex) {
    LOG.warn("Bad request: {}", sanitize(ex.getMessage()));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ApiResponse.error(HttpStatus.BAD_REQUEST.value(), ex.getMessage()));
  }

  /** 404 —— 没有匹配的处理器或静态资源。 */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ApiResponse<Void>> handleNotFound(NoResourceFoundException ex) {
    LOG.warn("Not found: {}", sanitize(ex.getMessage()));
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ApiResponse.error(HttpStatus.NOT_FOUND.value(), "Resource not found"));
  }

  /** 503 —— 下游依赖（provider、tool、存储）不可用。 */
  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ApiResponse<Void>> handleUnavailable(IllegalStateException ex) {
    LOG.error("Service unavailable: {}", sanitize(ex.getMessage()));
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(ApiResponse.error(HttpStatus.SERVICE_UNAVAILABLE.value(), ex.getMessage()));
  }

  /** 404 —— 领域资源不存在（第 26 节：会话 / Agent）。消息是受控字面量（含缺失名字，调用方排查必需）， 非内部细节。 */
  @ExceptionHandler({SessionNotFoundException.class, ResourceNotFoundException.class})
  public ResponseEntity<ApiResponse<Void>> handleDomainNotFound(RuntimeException ex) {
    LOG.warn("Resource not found: {}", sanitize(ex.getMessage()));
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ApiResponse.error(HttpStatus.NOT_FOUND.value(), ex.getMessage()));
  }

  /** 503 —— Provider 显式故障（第 26 节）：与 {@code IllegalStateException}→503 并列的显式语义。 */
  @ExceptionHandler(ProviderUnavailableException.class)
  public ResponseEntity<ApiResponse<Void>> handleProviderDown(ProviderUnavailableException ex) {
    LOG.error("Provider unavailable: {}", sanitize(ex.getMessage()));
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(ApiResponse.error(HttpStatus.SERVICE_UNAVAILABLE.value(), ex.getMessage()));
  }

  /**
   * 503 —— Spring AI 的 Provider 故障族（第 26 节）：401/403 等不可重试（NonTransient）与限流/超载等可重试耗尽
   * （Transient）都属「Provider 故障」语义（技 §7.4），消息是 Provider 侧原文、非内部细节。 错 key 注入实证此路径（26 节人工项）。
   */
  @ExceptionHandler({NonTransientAiException.class, TransientAiException.class})
  public ResponseEntity<ApiResponse<Void>> handleAiProviderFailure(RuntimeException ex) {
    LOG.error("AI provider failure: {}", sanitize(ex.getMessage()));
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(ApiResponse.error(HttpStatus.SERVICE_UNAVAILABLE.value(), ex.getMessage()));
  }

  /** 504 —— Agent 调用超时（第 26 节口径占位）：真实超时由 provider 层承载，同步模型不造硬中断（research D10）。 */
  @ExceptionHandler(AgentTimeoutException.class)
  public ResponseEntity<ApiResponse<Void>> handleTimeout(AgentTimeoutException ex) {
    LOG.error("Agent invocation timeout: {}", sanitize(ex.getMessage()));
    return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
        .body(ApiResponse.error(HttpStatus.GATEWAY_TIMEOUT.value(), ex.getMessage()));
  }

  /** 500 —— 其余一切的兜底。 */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleInternalError(Exception ex) {
    LOG.error("Unhandled exception", ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ApiResponse.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal server error"));
  }

  /** 去除 CR/LF，防攻击者构造的值伪造日志行（CWE-117）。对外 body 复用同一净化值——完整异常细节 只留在服务端日志。 */
  private static String sanitize(String value) {
    if (value == null) {
      return "";
    }
    return value.replaceAll("[\r\n]", "_");
  }
}

package com.yokeos.web;

import com.yokeos.web.common.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

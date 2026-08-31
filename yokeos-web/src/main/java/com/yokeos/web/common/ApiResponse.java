package com.yokeos.web.common;

import java.time.Instant;

/**
 * Unified REST response envelope returned by every YokeOS API endpoint (docs/TechnicalSolution.md
 * §7.1): success and error share one shape, so clients always parse the same four fields.
 * Convention mirrors the reference implementation: {@code code} is 0 on success and the HTTP status
 * value on errors.
 *
 * @param <T> payload type
 */
public class ApiResponse<T> {

  private final int code;
  private final String message;
  private final T data;
  private final long timestamp;

  /** Creates an envelope with the given body code, message and payload; timestamp is set to now. */
  public ApiResponse(int code, String message, T data) {
    this.code = code;
    this.message = message;
    this.data = data;
    this.timestamp = Instant.now().toEpochMilli();
  }

  /** Builds a success response carrying the given payload. */
  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(0, "success", data);
  }

  /** Builds an error response with the HTTP status value as the body code. */
  public static <T> ApiResponse<T> error(int code, String message) {
    return new ApiResponse<>(code, message, null);
  }

  public int getCode() {
    return code;
  }

  public String getMessage() {
    return message;
  }

  public T getData() {
    return data;
  }

  public long getTimestamp() {
    return timestamp;
  }

  @Override
  public String toString() {
    return "ApiResponse{code=" + code + ", message='" + message + "', timestamp=" + timestamp + '}';
  }
}

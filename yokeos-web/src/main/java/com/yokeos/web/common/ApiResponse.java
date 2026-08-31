package com.yokeos.web.common;

import java.time.Instant;

/**
 * 统一 REST 响应信封，所有 YokeOS API 端点共用（docs/TechnicalSolution.md §7.1）：成功与错误共用
 * 同一形状，客户端永远解析同样的四个字段。约定镜像参照实现：{@code code} 成功为 0，错误为 HTTP 状态值。
 *
 * @param <T> 载荷类型
 */
public class ApiResponse<T> {

  private final int code;
  private final String message;
  private final T data;
  private final long timestamp;

  /** 按给定 body code、message、载荷创建信封；timestamp 取当前时刻。 */
  public ApiResponse(int code, String message, T data) {
    this.code = code;
    this.message = message;
    this.data = data;
    this.timestamp = Instant.now().toEpochMilli();
  }

  /** 构建成功响应，携带给定载荷。 */
  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(0, "success", data);
  }

  /** 构建错误响应，body 的 code 取 HTTP 状态值。 */
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

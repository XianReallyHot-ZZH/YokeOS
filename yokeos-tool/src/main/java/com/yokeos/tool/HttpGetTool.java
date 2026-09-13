package com.yokeos.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.yokeos.core.tool.ToolResult;
import com.yokeos.core.tool.YokeTool;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 内置 HTTP Tool 最小形态（技 §13 第 17 节行：循环要有真东西可执行）。入参 {@code {"url": ...}}，Java HttpClient 发
 * GET；响应正文超长截断（防撑爆上下文）。域名白名单归 24 节 Sandbox 接线（本节留位）；20 节扩展为 HttpTools 全量（http_post +
 * 白名单）。HttpClient 本身同步阻塞（宪法 4）。
 */
public final class HttpGetTool implements YokeTool {

  /** 响应正文截断上限（教学文档定死：约 8000 字符防撑爆上下文）。 */
  static final int MAX_BODY_CHARS = 8000;

  /** 成功状态码区间 [200, 300)。 */
  private static final int HTTP_OK_MIN = 200;

  private static final int HTTP_OK_EXCLUSIVE = 300;

  private final HttpClient httpClient;

  /** 默认构造：连接超时 10 秒的共享 HttpClient。 */
  public HttpGetTool() {
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)) // 连接超时约 10 秒（教学文档第三部分第七步）
            .build();
  }

  /** 测试可注入定制 HttpClient（超时策略差异等）。 */
  HttpGetTool(HttpClient httpClient) {
    this.httpClient = httpClient;
  }

  @Override
  public String getName() {
    return "http_get";
  }

  @Override
  public String getDescription() {
    return "发起一次 HTTP GET 请求，返回响应正文文本";
  }

  @Override
  public String getInputSchema() {
    // 按 ToolSchemaAdapter 消费格式（JSON Schema 字符串，与 16 节测试桩同构）
    return "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\","
        + "\"description\":\"目标 URL\"}},\"required\":[\"url\"]}";
  }

  @Override
  public ToolResult execute(JsonNode input) {
    // Sandbox 域名白名单校验位：24 节接线（Sandbox.enforce 失败转 error + 审计留痕）。
    String url = input.path("url").asText("");
    if (url.isBlank()) {
      return ToolResult.error("缺少必填参数 url", false);
    }
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url))
            .GET()
            .timeout(Duration.ofSeconds(10)) // 读取超时约 10 秒
            .header("User-Agent", "YokeOS/0.1")
            .build();
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < HTTP_OK_MIN || response.statusCode() >= HTTP_OK_EXCLUSIVE) {
        return ToolResult.error("HTTP " + response.statusCode() + ": " + url, false);
      }
      return ToolResult.ok(truncate(response.body()));
    } catch (IOException e) {
      return ToolResult.error("请求失败: " + e.getMessage(), true); // 网络 IO 瞬态失败可重试
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return ToolResult.error("请求被中断", false);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("URL 非法: " + e.getMessage(), false);
    }
  }

  private static String truncate(String body) {
    if (body == null || body.length() <= MAX_BODY_CHARS) {
      return body;
    }
    return body.substring(0, MAX_BODY_CHARS) + "…(截断，全文 " + body.length() + " 字符)";
  }
}

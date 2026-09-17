package com.yokeos.tool.builtin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 内置 HTTP 工具两件（http_get / http_post，技 §6.2）——17 节最小单类形态演进为成对工具（原类退役，技 §13 第 20 节行）。 JDK HttpClient
 * 同步阻塞（宪法 4，19 节拍板③同款——不引 spring-web）；响应超长截断与 17 节同口径。
 *
 * <p>失败语义（注解管道统一形态）：确定性失败（4xx/5xx/URL 非法）与网络瞬态失败均异常上抛，由 ToolExecutor 统一转 ToolResult.error
 * 落审计——参照终态同构（RestClient 异常上抛），单工具不再持可重试特殊路径。
 */
public class HttpTools {

  /** 响应正文截断上限（17 节口径：约 8000 字符防撑爆上下文）。 */
  static final int MAX_BODY_CHARS = 8000;

  /** 成功状态码区间 [200, 300)。 */
  private static final int HTTP_OK_MIN = 200;

  private static final int HTTP_OK_EXCLUSIVE = 300;

  private final HttpClient httpClient;

  /** 默认构造：连接超时 10 秒的共享 HttpClient（17 节口径）。 */
  public HttpTools() {
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  /**
   * @param httpClient 测试注入定制实例（超时策略差异等）。
   */
  HttpTools(HttpClient httpClient) {
    this.httpClient = httpClient;
  }

  /** 发起一次 HTTP GET 请求，返回响应正文文本。 */
  @Tool(name = "http_get", description = "发起一次 HTTP GET 请求，返回响应正文文本")
  public String httpGet(@ToolParam(description = "目标 URL") String url) {
    // Sandbox 检查位：24 节接 sandbox.enforce(new SandboxAction(HTTP_REQUEST, url))——域名白名单不过则请求根本不发出
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(requireUrl(url)))
            .GET()
            .timeout(Duration.ofSeconds(10))
            .header("User-Agent", "YokeOS/0.1")
            .build();
    return send(request, url);
  }

  /** 发起一次 HTTP POST 请求（JSON body），返回响应正文文本。 */
  @Tool(name = "http_post", description = "发起一次 HTTP POST 请求（JSON body），返回响应正文文本")
  public String httpPost(
      @ToolParam(description = "目标 URL") String url,
      @ToolParam(description = "JSON 请求体字符串") String body) {
    // Sandbox 检查位：24 节接 sandbox.enforce(new SandboxAction(HTTP_REQUEST, url))
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(requireUrl(url)))
            .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("User-Agent", "YokeOS/0.1")
            .build();
    return send(request, url);
  }

  private String send(HttpRequest request, String url) {
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < HTTP_OK_MIN || response.statusCode() >= HTTP_OK_EXCLUSIVE) {
        throw new IllegalStateException("HTTP " + response.statusCode() + ": " + url);
      }
      return truncate(response.body());
    } catch (IOException e) {
      throw new java.io.UncheckedIOException("请求失败: " + url, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("请求被中断: " + url, e);
    }
  }

  private static String requireUrl(String url) {
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("缺少必填参数 url");
    }
    return url;
  }

  private static String truncate(String body) {
    if (body == null || body.length() <= MAX_BODY_CHARS) {
      return body;
    }
    return body.substring(0, MAX_BODY_CHARS) + "…(截断，全文 " + body.length() + " 字符)";
  }
}

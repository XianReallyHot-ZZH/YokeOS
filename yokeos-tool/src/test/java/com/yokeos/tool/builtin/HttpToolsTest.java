package com.yokeos.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yokeos.core.tool.YokeTool;
import com.yokeos.tool.ToolRegistry;
import com.yokeos.tool.sandbox.SandboxProperties;
import com.yokeos.tool.sandbox.SandboxViolationException;
import com.yokeos.tool.sandbox.WhitelistSandbox;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 第 20 节 harness + 第 24 节 Sandbox 拦截回归：GET/POST 正常 + 4xx/5xx 报失败 + 截断防护 + 白名单外域名请求根本不发出。
 *
 * <p>JDK HttpServer 假服务（port 0 自动分配，19 节先例）——单测层不碰真实网络。既有用例的沙箱域名白名单含 localhost （真 {@link
 * WhitelistSandbox}，不是放行一切的假货——教学文档拍板②）。
 */
class HttpToolsTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private HttpServer server;

  private final AtomicReference<String> lastMethod = new AtomicReference<>();
  private final AtomicReference<String> lastBody = new AtomicReference<>();
  private final AtomicReference<String> lastContentType = new AtomicReference<>();

  private YokeTool httpGet;
  private YokeTool httpPost;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/ok",
        exchange -> {
          recordRequest(exchange);
          respond(exchange, 200, "hello from fake server");
        });
    server.createContext(
        "/echo",
        exchange -> {
          // 请求流只能读一次：读后既作断言依据又作回显体（不走 recordRequest，避免二次读空）
          lastMethod.set(exchange.getRequestMethod());
          lastContentType.set(exchange.getRequestHeaders().getFirst("Content-type"));
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          lastBody.set(body);
          respond(exchange, 200, body);
        });
    server.createContext("/notfound", exchange -> respond(exchange, 404, "gone"));
    server.createContext("/boom", exchange -> respond(exchange, 500, "server error"));
    server.createContext("/big", exchange -> respond(exchange, 200, "z".repeat(9000)));
    server.start();

    ToolRegistry registry = new ToolRegistry();
    registry.registerAnnotated(new HttpTools(whitelisting("localhost")));
    httpGet = registry.get("http_get").orElseThrow();
    httpPost = registry.get("http_post").orElseThrow();
  }

  private static WhitelistSandbox whitelisting(String... domains) {
    return new WhitelistSandbox(new SandboxProperties(List.of(), List.of(), List.of(domains)));
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
  }

  private void recordRequest(HttpExchange exchange) throws IOException {
    lastMethod.set(exchange.getRequestMethod());
    lastContentType.set(exchange.getRequestHeaders().getFirst("Content-type"));
    lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private String url(String path) {
    return "http://localhost:" + server.getAddress().getPort() + path;
  }

  @Test
  @DisplayName("http_get取回响应正文")
  void httpGetReturnsBody() throws Exception {
    assertEquals(
        "hello from fake server",
        httpGet.execute(JSON.readTree("{\"url\":\"" + url("/ok") + "\"}")).content());
  }

  @Test
  @DisplayName("http_post_body原样到达且Content-Type为JSON")
  void httpPostSendsBodyAsJson() throws Exception {
    String body = "{\"query\":\"yokeos\"}";

    String echoed =
        httpPost
            .execute(
                JSON.readTree("{\"url\":\"" + url("/echo") + "\",\"body\":" + quote(body) + "}"))
            .content();

    assertEquals(body, echoed, "假服务原样回显 body");
    assertEquals("POST", lastMethod.get());
    assertEquals(body, lastBody.get(), "body 原样到达");
    assertTrue(
        lastContentType.get().toLowerCase().contains("json"),
        "Content-Type 应为 JSON: " + lastContentType.get());
  }

  @Test
  @DisplayName("4xx与5xx都报失败并点名状态码")
  void non2xxFailsWithStatusCode() {
    IllegalStateException notFound =
        assertThrows(
            IllegalStateException.class,
            () -> httpGet.execute(JSON.readTree("{\"url\":\"" + url("/notfound") + "\"}")));
    assertTrue(notFound.getMessage().contains("404"), "4xx 点名状态码: " + notFound.getMessage());

    IllegalStateException boom =
        assertThrows(
            IllegalStateException.class,
            () -> httpGet.execute(JSON.readTree("{\"url\":\"" + url("/boom") + "\"}")));
    assertTrue(boom.getMessage().contains("500"), "5xx 点名状态码: " + boom.getMessage());
  }

  @Test
  @DisplayName("响应超长截断并注明总长")
  void oversizedBodyTruncated() throws Exception {
    String content = httpGet.execute(JSON.readTree("{\"url\":\"" + url("/big") + "\"}")).content();

    assertTrue(content.length() < 9000, "超长响应必须截断");
    assertTrue(content.contains("截断"), "截断必须注明");
  }

  @Test
  @DisplayName("白名单外域名_请求根本没发出")
  void domainOutsideWhitelistNeverSent() throws Exception {
    // 坑五回归：真 deny-all WhitelistSandbox + mock HttpClient——enforce 先于 URI 构造与请求发出，send 零调用
    HttpClient httpClient = mock(HttpClient.class);
    ToolRegistry registry = new ToolRegistry();
    registry.registerAnnotated(
        new HttpTools(
            new WhitelistSandbox(new SandboxProperties(List.of(), List.of(), List.of())),
            httpClient));
    YokeTool guardedGet = registry.get("http_get").orElseThrow();
    YokeTool guardedPost = registry.get("http_post").orElseThrow();

    assertThrows(
        SandboxViolationException.class,
        () -> guardedGet.execute(JSON.readTree("{\"url\":\"" + url("/ok") + "\"}")));
    assertThrows(
        SandboxViolationException.class,
        () ->
            guardedPost.execute(JSON.readTree("{\"url\":\"" + url("/echo") + "\",\"body\":\"\"}")));

    verify(httpClient, never()).send(any(), any());
  }

  private static String quote(String raw) {
    return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}

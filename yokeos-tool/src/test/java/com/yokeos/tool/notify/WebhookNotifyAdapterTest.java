package com.yokeos.tool.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 教学文档《第19节》验收 harness 第一批：WebhookNotifyAdapter。
 *
 * <p>假 webhook 用 JDK 内置 HttpServer（本地回环、真 HTTP，仍是单测层，research D5）——比 mock HttpClient 更硬，
 * 覆盖真实序列化与状态码路径。四态对应 US1/US2：POST 形态、换目标零代码、5xx 上抛不吞、缺 url 报错零请求。
 */
class WebhookNotifyAdapterTest {

  /** 一次收到的请求：方法 + 路径 + Content-Type + body。 */
  private record ReceivedRequest(String method, String path, String contentType, String body) {}

  private HttpServer server;
  private final List<ReceivedRequest> received = new ArrayList<>();
  private volatile int responseStatus = 200;

  private WebhookNotifyAdapter adapter;

  @BeforeEach
  void startFakeWebhook() throws IOException {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/", this::record);
    server.start();
    adapter = new WebhookNotifyAdapter();
  }

  @AfterEach
  void stopFakeWebhook() {
    server.stop(0);
  }

  private void record(HttpExchange exchange) throws IOException {
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    received.add(
        new ReceivedRequest(
            exchange.getRequestMethod(),
            exchange.getRequestURI().getPath(),
            exchange.getRequestHeaders().getFirst("Content-Type"),
            body));
    exchange.sendResponseHeaders(responseStatus, -1);
    exchange.close();
  }

  private String urlOf(String path) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + path;
  }

  private static NotifyTarget webhookTarget(String url) {
    return new NotifyTarget("webhook", Map.of("url", url));
  }

  @Test
  @DisplayName("发送后收到一次POST_body携带推送内容")
  void sendPostsJsonBodyContainingContent() {
    adapter.send(webhookTarget(urlOf("/team-hook")), "今天 28°C，建议短袖");

    assertEquals(1, received.size(), "恰好一次请求");
    ReceivedRequest request = received.get(0);
    assertEquals("POST", request.method());
    assertEquals("/team-hook", request.path());
    assertTrue(request.body().contains("今天 28°C，建议短袖"), "body 携带推送内容");
    assertTrue(request.body().contains("content"), "按约定包成 {\"content\": ...}");
    assertTrue(
        request.contentType() != null && request.contentType().startsWith("application/json"),
        "Content-Type 为 JSON");
  }

  @Test
  @DisplayName("换通知目标只改配置零代码")
  void differentTargetsGoToTheirOwnUrls() {
    adapter.send(webhookTarget(urlOf("/a-group")), "给 A 群");
    adapter.send(webhookTarget(urlOf("/b-group")), "给 B 群");

    assertEquals(2, received.size(), "两次推送两次请求");
    assertEquals("/a-group", received.get(0).path());
    assertTrue(received.get(0).body().contains("给 A 群"));
    assertEquals("/b-group", received.get(1).path());
    assertTrue(received.get(1).body().contains("给 B 群"), "目标地址来自 NotifyTarget.config 非硬编码");
  }

  @Test
  @DisplayName("webhook返回5xx_异常向上抛不静默吞掉")
  void webhookReturns5xxExceptionPropagates() {
    responseStatus = 500;

    assertThrows(
        UncheckedIOException.class,
        () -> adapter.send(webhookTarget(urlOf("/team-hook")), "hello"),
        "「发出去没送到」与「没发出去」是同一件事——绝不装成功（坑一）");
    assertEquals(1, received.size(), "确实发出过一次（失败不是没发）");
  }

  @Test
  @DisplayName("config缺url_报错点名且零请求")
  void missingUrlConfigThrowsWithoutSending() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> adapter.send(new NotifyTarget("webhook", Map.of()), "hello"));

    assertTrue(ex.getMessage().contains("url"), "报错点名缺什么");
    assertTrue(received.isEmpty(), "零请求——发起前即被拦下");
  }
}

package com.yokeos.tool.notify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 第一阶段唯一实现：通用 HTTP webhook——企业 IM 群机器人（企业微信/飞书/钉钉/Slack 等）皆经 webhook 地址接入， 一档覆盖大部分场景，不逐家接签名算法与
 * AccessToken 刷新（技 §6.8，留扩展阶段专用 Adapter）。
 *
 * <p>失败口径（research D1/D6）：JDK HttpClient 对非 2xx 不自动抛异常，必须显式检查状态码；checked IOException 包 {@link
 * UncheckedIOException} 上抛——ToolExecutor 只 catch RuntimeException，包装后才能落进既有「异常→失败结果→审计」路径；
 * 任何失败不在本层重试（重复推群风险）。body 统一 {@code {"content": ...}}——各家约定的 payload 格式差异是扩展阶段 Adapter 的事（教学文档坑三）。
 *
 * <p>凭证口径（坑二）：webhook URL 本身即凭证——不进异常消息（error_message 会落审计表）、不进日志，只从 {@link NotifyTarget#config()}
 * 取用。
 */
public final class WebhookNotifyAdapter implements NotifyChannelAdapter {

  /** 线程安全（Jackson 官方口径），静态复用免每次重建（ToolExecutor 同款）。 */
  private static final ObjectMapper JSON = new ObjectMapper();

  /** 成功状态码区间 [200, 300)（http_get 工具同款口径）。 */
  private static final int HTTP_OK_MIN = 200;

  private static final int HTTP_OK_EXCLUSIVE = 300;

  /** 单次推送请求超时（research D7：对端挂起不拖死 ReAct 同步链路）。 */
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private final HttpClient httpClient;

  /** 默认构造：连接超时 10 秒的共享 HttpClient（http_get 工具同款口径）。 */
  public WebhookNotifyAdapter() {
    this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
  }

  /** 测试可注入定制 HttpClient（超时策略差异等）。 */
  WebhookNotifyAdapter(HttpClient httpClient) {
    this.httpClient = httpClient;
  }

  @Override
  public void send(NotifyTarget target, String content) {
    String url = target.config().get("url");
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("webhook 渠道缺少 url 配置（notify.channels 条目需要 url 键）");
    }
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(bodyOf(content), StandardCharsets.UTF_8))
            .build();
    HttpResponse<Void> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
    } catch (IOException e) {
      // 连接失败/超时：消息不带 URL（坑二——error_message 落审计表），根因在堆栈
      throw new UncheckedIOException("webhook 推送失败（连接层，地址见配置不进日志）", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("webhook 推送被中断", e);
    }
    int status = response.statusCode();
    if (status < HTTP_OK_MIN || status >= HTTP_OK_EXCLUSIVE) {
      // JDK HttpClient 不自动抛非 2xx（research D1）：不检查这里就是「发错也装成功」——坑一
      throw new UncheckedIOException(new IOException("webhook 返回非 2xx: " + status));
    }
  }

  private static String bodyOf(String content) {
    try {
      return JSON.writeValueAsString(Map.of("content", content));
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException("推送内容序列化失败", e);
    }
  }
}

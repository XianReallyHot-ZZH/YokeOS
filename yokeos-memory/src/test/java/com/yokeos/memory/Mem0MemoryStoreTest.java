package com.yokeos.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yokeos.core.memory.MemoryScope;
import com.yokeos.tool.sandbox.SandboxProperties;
import com.yokeos.tool.sandbox.SandboxViolationException;
import com.yokeos.tool.sandbox.WhitelistSandbox;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * mem0 集成档的协议转换行为（教学文档拍板④：mock 定契约——本地无真实实例，REST 字段以部署的 Mem0 版本为准， 真实例人工可选）。四条跨档契约不在此测——由契约测试用
 * InMemoryMemoryStore 替身统一锚。
 */
class Mem0MemoryStoreTest {

  private final RestClient restClient = mock(RestClient.class);

  /** RETURNS_SELF：builder 链（uri/body 互相返回自身类型）自动回环，只剩 retrieve/toEntity 两处显式 stub。 */
  private final RestClient.RequestBodyUriSpec postSpec =
      mock(RestClient.RequestBodyUriSpec.class, Mockito.RETURNS_SELF);

  private final RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

  private static final String MEM0_BASE = "https://mem0.example.com";

  private Mem0MemoryStore store() {
    when(restClient.post()).thenReturn(postSpec);
    when(postSpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.toEntity(String.class)).thenReturn(ResponseEntity.ok("{\"results\":[]}"));
    return new Mem0MemoryStore(restClient, MEM0_BASE, whitelisting("mem0.example.com"));
  }

  private static WhitelistSandbox whitelisting(String domain) {
    return new WhitelistSandbox(new SandboxProperties(List.of(), List.of(), List.of(domain)));
  }

  @Test
  @DisplayName("写入请求携带分区标记")
  void appendSendsScopeMetadata() {
    Mem0MemoryStore store = store();

    store.append("用户偏好中文交流", MemoryScope.CORE);

    ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
    verify(postSpec).uri("/v1/memories/");
    verify(postSpec).body(body.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> payload = (Map<String, Object>) body.getValue();
    assertEquals("CORE", ((Map<String, Object>) payload.get("metadata")).get("scope"));
    assertTrue(payload.containsKey("messages"), "Mem0 add 的 messages 结构在场");
  }

  @Test
  @DisplayName("检索转发给Mem0的search")
  void recallForwardsToMem0Search() {
    Mem0MemoryStore store = store();
    when(responseSpec.toEntity(String.class))
        .thenReturn(ResponseEntity.ok("{\"results\":[{\"memory\":\"一条旧结论\"}]}"));

    List<String> hits = store.recallByKeyword("存储选型");
    assertEquals(List.of("一条旧结论"), hits, "命中内容解析为列表");

    verify(postSpec).uri("/v1/memories/search/");
    ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
    verify(postSpec).body(body.capture());
    @SuppressWarnings("unchecked")
    Map<String, Object> payload = (Map<String, Object>) body.getValue();
    assertEquals("存储选型", payload.get("query"), "关键词原样转发");
  }

  @Test
  @DisplayName("Mem0侧错误翻译为明确异常")
  void mem0ErrorTranslatedToExplicitException() {
    Mem0MemoryStore store = store();
    when(responseSpec.toEntity(String.class))
        .thenThrow(
            new RestClientResponseException(
                "mem0 503", 503, "Service Unavailable", null, null, null));

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> store.append("内容", MemoryScope.ARCHIVAL));

    assertTrue(error.getMessage().contains("503"), "异常含状态码");
  }

  @Test
  @DisplayName("mem0配置缺失在使用时清晰报错")
  void missingBaseUrlFailsWithExplicitMessage() {
    WhitelistSandbox denyAll =
        new WhitelistSandbox(new SandboxProperties(List.of(), List.of(), List.of()));

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> new Mem0MemoryStore("${MEM0_BASE_URL_TEST_ABSENT}", "key", denyAll));

    assertTrue(error.getMessage().contains("MEM0_BASE_URL_TEST_ABSENT"), "报错点名变量名");
    assertTrue(error.getMessage().contains("yokeos.memory.mem0"), "报错给出配置键");
  }

  @Test
  @DisplayName("白名单外主机_三种Mem0操作零出站")
  void hostOutsideWhitelistNeverSendsAnything() {
    // 坑五回归（24 节，research D8）：真 deny-all WhitelistSandbox——append/load/recallByKeyword 三方法
    // 全部先过闸再出站，拒绝后 REST 零调用（get 与 post 都 never）
    Mem0MemoryStore guarded =
        new Mem0MemoryStore(
            restClient,
            MEM0_BASE,
            new WhitelistSandbox(new SandboxProperties(List.of(), List.of(), List.of())));

    assertThrows(SandboxViolationException.class, () -> guarded.append("内容", MemoryScope.CORE));
    assertThrows(SandboxViolationException.class, guarded::load);
    assertThrows(SandboxViolationException.class, () -> guarded.recallByKeyword("关键词"));

    verify(restClient, org.mockito.Mockito.never()).post();
    verify(restClient, org.mockito.Mockito.never()).get();
  }
}

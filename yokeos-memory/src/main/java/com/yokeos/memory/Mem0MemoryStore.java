package com.yokeos.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yokeos.core.memory.MemoryScope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 长期记忆外部集成档（mem0，技 §5.1 / specs/006 D4）：接一个<b>自托管</b> Mem0 记忆层（数据不出域）， append/load/recall 翻译成 Mem0
 * 的 add/get/search——提炼、冲突消解、语义检索都交给 Mem0（契约四在此档被「升级」为语义检索， D4 明示的允许项）。本类本质是协议转换：截断与消解信任 Mem0
 * 侧分页与作用域机制。
 *
 * <p>REST 字段以部署的 Mem0 版本为准（research D3：本地核实不到真实实例，mock 定契约——换实例只改本类常量， 契约测试用 InMemoryMemoryStore
 * 替身不受影响）。
 *
 * <p>配置策略（research D6，16 节 provider 先例）：{@code yokeos.memory.mem0.base-url/api-key} 的 {@code
 * ${MEM0_*}} 占位<b>不走 Boot 绑定</b>（绑定会提前解析占位、env 缺失时启动即失败）——本类构造时解析，缺失抛含变量名的明确异常：
 * 缺省空不阻断进程启动，报错发生在切到该档使用时（FR9）。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "CT_CONSTRUCTOR_THROW",
    justification =
        "构造期校验是设计本体（FR9/research D6）：切到 mem0 档时缺配置必须在构造处清晰报错，"
            + "不静默不阻断进程启动——本类由装配层按 backend 条件构造，失败即选档错误")
public class Mem0MemoryStore implements LongTermMemoryStore {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Mem0 add/get/search 端点（以部署版本为准，research D3 mock 定契约口径）。 */
  private static final String MEMORIES_ENDPOINT = "/v1/memories/";

  private static final String SEARCH_ENDPOINT = "/v1/memories/search/";

  /** Mem0 作用域标识。本仓无 per-Agent 记忆作用域（教学文档拍板⑤），固定全局——26 节后需要拆作用域时此常量是唯一改动点。 */
  private static final String USER_ID = "yokeos";

  private static final String CORE_HEADER = "## 核心记忆";

  private static final String ARCHIVE_HEADER = "## 归档记忆";

  /** Mem0 响应里记忆数组的字段名（P3C：字面量提常量）。 */
  private static final String RESULTS_FIELD = "results";

  private static final String PLACEHOLDER_PREFIX = "${";

  private static final String PLACEHOLDER_SUFFIX = "}";

  private final RestClient restClient;

  /**
   * 生产构造：占位运行时解析，缺失清晰报错（不静默、不阻断启动——校验发生在构造本档时）。
   *
   * @param baseUrl {@code yokeos.memory.mem0.base-url} 原文（通常为 {@code ${MEM0_BASE_URL}} 占位）
   * @param apiKey {@code yokeos.memory.mem0.api-key} 原文
   */
  public Mem0MemoryStore(String baseUrl, String apiKey) {
    String resolvedBase = resolvePlaceholder("yokeos.memory.mem0.base-url", baseUrl);
    String resolvedKey = resolvePlaceholder("yokeos.memory.mem0.api-key", apiKey);
    this.restClient =
        RestClient.builder()
            .baseUrl(resolvedBase)
            .defaultHeader("Authorization", "Bearer " + resolvedKey)
            .build();
  }

  /** 测试构造：直接注入 mock 的 RestClient（mock 定契约，教学文档拍板④）。 */
  Mem0MemoryStore(RestClient restClient) {
    this.restClient = restClient;
  }

  @Override
  public void append(String content, MemoryScope scope) {
    // Sandbox 检查位：24 节接 sandbox.enforce(new SandboxAction(HTTP_REQUEST, baseUrl +
    // MEMORIES_ENDPOINT))
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("messages", List.of(Map.of("role", "user", "content", content)));
    payload.put("user_id", USER_ID);
    payload.put("metadata", Map.of("scope", scope.name())); // scope 落 metadata 供检索区分
    post(MEMORIES_ENDPOINT, payload);
  }

  @Override
  public String load() {
    JsonNode core = fetchByScope(MemoryScope.CORE);
    JsonNode archive = fetchByScope(MemoryScope.ARCHIVAL);
    return CORE_HEADER + "\n" + render(core) + "\n" + ARCHIVE_HEADER + "\n" + render(archive);
  }

  @Override
  public List<String> recallByKeyword(String keyword) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("query", keyword); // Mem0 search 语义检索（契约四的加强版，D4 允许项）
    payload.put("user_id", USER_ID);
    JsonNode results = post(SEARCH_ENDPOINT, payload);
    List<String> hits = new ArrayList<>();
    for (JsonNode item : results.path(RESULTS_FIELD)) {
      hits.add(item.path("memory").asText());
    }
    return hits;
  }

  /** GET 按 scope 过滤取记忆（截断与分页信任 Mem0 侧机制——协议转换档不做本地截断）。 */
  private JsonNode fetchByScope(MemoryScope scope) {
    String uri = MEMORIES_ENDPOINT + "?user_id=" + USER_ID + "&scope=" + scope.name();
    return readTree(exchange(() -> restClient.get().uri(uri)));
  }

  private JsonNode post(String uri, Map<String, Object> payload) {
    return readTree(exchange(() -> restClient.post().uri(uri).body(payload)));
  }

  /** 发起调用；非 2xx 由 RestClient 抛 RestClientResponseException，统一翻译成含状态码的明确异常。 */
  private String exchange(RestCall call) {
    try {
      return call.execute().retrieve().toEntity(String.class).getBody();
    } catch (RestClientResponseException e) {
      throw new IllegalStateException(
          "Mem0 调用失败: HTTP " + e.getStatusCode().value() + " " + e.getMessage(), e);
    }
  }

  private JsonNode readTree(String raw) {
    try {
      return MAPPER.readTree(raw == null ? "{}" : raw);
    } catch (Exception e) {
      throw new IllegalStateException("Mem0 响应不是合法 JSON", e);
    }
  }

  private static String render(JsonNode results) {
    StringBuilder sb = new StringBuilder();
    for (JsonNode item : results.path(RESULTS_FIELD)) {
      sb.append("- ").append(item.path("memory").asText()).append('\n');
    }
    return sb.toString();
  }

  /** ${ENV_VAR} 占位解析；空值或未解析（env 缺失）→ 含变量名与配置键的明确报错。 */
  private static String resolvePlaceholder(String configKey, String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalStateException(
          "mem0 档配置缺失: " + configKey + " 未配置（切到该档前需在 application.yaml 配置并设置环境变量）");
    }
    if (raw.startsWith(PLACEHOLDER_PREFIX) && raw.endsWith(PLACEHOLDER_SUFFIX)) {
      String varName =
          raw.substring(PLACEHOLDER_PREFIX.length(), raw.length() - PLACEHOLDER_SUFFIX.length());
      String value = System.getenv(varName);
      if (value == null || value.isBlank()) {
        throw new IllegalStateException(
            "mem0 档配置缺失: 环境变量 " + varName + " 未设置（配置键 " + configKey + "）");
      }
      return value;
    }
    return raw;
  }

  @FunctionalInterface
  private interface RestCall {

    RestClient.RequestHeadersSpec<?> execute();
  }
}

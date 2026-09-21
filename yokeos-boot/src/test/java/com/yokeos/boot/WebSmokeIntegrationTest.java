package com.yokeos.boot;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Web 层冒烟（第 26 节 T020，{@code @Tag("integration")}——真装配上下文，不依赖模型）：端点可达、/admin 托管与 SPA 回落、
 * 缓存两档、OpenAPI 覆盖、CORS。25 节 E2E 同位的 surefire 口径（env 透传 + db.dir=target）下随全量上下文起—— JPA 扫描、Bean
 * 装配、静态资源一次验完。
 */
@Tag("integration")
@AutoConfigureMockMvc
@SpringBootTest(properties = "yokeos.db.dir=target") // 自钉 db 目录：免疫同 JVM 先行测试遗留的系统属性污染
class WebSmokeIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("五个GET端点真实可达且信封code为0")
  void readOnlyEndpointsReachable() throws Exception {
    mockMvc
        .perform(get("/api/v1/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.status").value("ok"));
    mockMvc
        .perform(get("/api/v1/info"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
    mockMvc
        .perform(get("/api/v1/profiles"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
    mockMvc
        .perform(get("/api/v1/tools"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
    mockMvc
        .perform(get("/api/v1/sessions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0));
  }

  @Test
  @DisplayName("admin入口重定向到index_html且no-cache")
  void adminRootServesHtmlWithNoCache() throws Exception {
    mockMvc
        .perform(get("/admin"))
        .andExpect(status().is3xxRedirection())
        .andExpect(header().string(HttpHeaders.LOCATION, "/admin/index.html"));
    mockMvc.perform(get("/admin/")).andExpect(status().is3xxRedirection());
    mockMvc
        .perform(get("/admin/index.html"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-cache")));
  }

  @Test
  @DisplayName("admin子路由回落index_html_刷新不404")
  void spaFallbackServesIndexForUnknownRoute() throws Exception {
    mockMvc
        .perform(get("/admin/sessions"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
        .andExpect(content().string(containsString("<div id=\"app\">")));
  }

  @Test
  @DisplayName("hash资源immutable长缓存")
  void hashedAssetsAreImmutable() throws Exception {
    MvcResult root = mockMvc.perform(get("/admin/index.html")).andReturn();
    String index = root.getResponse().getContentAsString();
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("(?:src|href)=\"([^\"]*assets/[^\"]+)\"").matcher(index);
    org.junit.jupiter.api.Assertions.assertTrue(
        matcher.find(), "index.html 里应能找到 hash 资源引用（前端产物在包内）");
    mockMvc
        .perform(get(matcher.group(1)))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("immutable")));
  }

  @Test
  @DisplayName("api未命中路径返回JSON404_不被SPA回落劫持")
  void apiNotFoundStaysJsonEnvelope() throws Exception {
    mockMvc
        .perform(get("/api/v1/no-such-endpoint"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(content().string(not(containsString("<html"))));
  }

  @Test
  @DisplayName("OpenAPI文档可达且覆盖会话端点")
  void apiDocsCoverSessionEndpoint() throws Exception {
    mockMvc
        .perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/v1/sessions']").exists());
  }

  @Test
  @DisplayName("CORS全开_任意Origin放行")
  void corsAllowsAnyOrigin() throws Exception {
    mockMvc
        .perform(get("/api/v1/health").header(HttpHeaders.ORIGIN, "http://example.com"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "*"));
  }
}

package com.yokeos.web.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yokeos.core.profile.Profile;
import com.yokeos.core.profile.ProfileRegistry;
import com.yokeos.web.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 系统状态切片（第 26 节 T013）：health ok；info providers 去重排序（已配置口径，research D5）。 */
@WebMvcTest
@Import({SystemApiController.class, GlobalExceptionHandler.class})
class SystemApiControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ProfileRegistry profileRegistry;

  @Test
  @DisplayName("health返回ok")
  void healthReturnsOk() throws Exception {
    mockMvc
        .perform(get("/api/v1/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("ok"));
  }

  @Test
  @DisplayName("info的providers_去重且排序")
  void infoProvidersDedupedAndSorted() throws Exception {
    when(profileRegistry.all())
        .thenReturn(
            List.of(
                profile("kimi-agent", "kimi"),
                profile("weather", "deepseek"),
                profile("daily", "deepseek")));

    mockMvc
        .perform(get("/api/v1/info"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.product").value("yokeos"))
        .andExpect(jsonPath("$.data.providers.length()").value(2))
        .andExpect(jsonPath("$.data.providers[0]").value("deepseek"))
        .andExpect(jsonPath("$.data.providers[1]").value("kimi"));
  }

  @Test
  @DisplayName("空注册表_providers为空数组不炸")
  void emptyRegistryYieldsEmptyProviders() throws Exception {
    when(profileRegistry.all()).thenReturn(List.of());

    mockMvc
        .perform(get("/api/v1/info"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.providers.length()").value(0));
  }

  private static Profile profile(String name, String providerName) {
    return new Profile(
        name,
        null,
        null,
        new Profile.ProviderConfig(providerName, "model-x", null),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }
}

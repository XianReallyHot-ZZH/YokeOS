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

/** profiles 投影切片（第 26 节 T012）：字段对齐、provider 段缺失不炸。 */
@WebMvcTest
@Import({ProfileApiController.class, GlobalExceptionHandler.class})
class ProfileApiControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ProfileRegistry profileRegistry;

  @Test
  @DisplayName("列出全部Profile_投影字段对齐")
  void listProjectsAllFields() throws Exception {
    when(profileRegistry.all())
        .thenReturn(
            List.of(
                new Profile(
                    "weather",
                    "天气助手",
                    null,
                    new Profile.ProviderConfig("deepseek", "deepseek-chat", 0.7),
                    List.of("http_get"),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null),
                new Profile(
                    "bare", null, null, null, null, null, null, null, null, null, null, null)));

    mockMvc
        .perform(get("/api/v1/profiles"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].name").value("weather"))
        .andExpect(jsonPath("$.data[0].description").value("天气助手"))
        .andExpect(jsonPath("$.data[0].providerName").value("deepseek"))
        .andExpect(jsonPath("$.data[0].model").value("deepseek-chat"))
        .andExpect(jsonPath("$.data[0].tools[0]").value("http_get"));
  }

  @Test
  @DisplayName("provider段缺失_view为null不炸")
  void missingProviderSectionRendersNullsWithoutError() throws Exception {
    when(profileRegistry.all())
        .thenReturn(
            List.of(
                new Profile(
                    "bare", null, null, null, null, null, null, null, null, null, null, null)));

    mockMvc
        .perform(get("/api/v1/profiles"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].providerName").doesNotExist())
        .andExpect(jsonPath("$.data[0].model").doesNotExist());
  }
}

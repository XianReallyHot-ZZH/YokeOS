package com.yokeos.web.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yokeos.core.memory.MemoryService;
import com.yokeos.web.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** memory 全文切片（第 26 节 T012）：readAll 原样进 data。 */
@WebMvcTest
@Import({MemoryApiController.class, GlobalExceptionHandler.class})
class MemoryApiControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private MemoryService memoryService;

  @Test
  @DisplayName("返回长期记忆全文_readAll原样进data")
  void readReturnsFullTextAsIs() throws Exception {
    when(memoryService.readAll())
        .thenReturn("## 核心记忆\n- [2026-09-21] 用户偏好中文交流\n\n## 归档记忆\n- [2026-09-01] 项目用 Java 21");

    mockMvc
        .perform(get("/api/v1/memory"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data")
                .value("## 核心记忆\n- [2026-09-21] 用户偏好中文交流\n\n## 归档记忆\n- [2026-09-01] 项目用 Java 21"));
  }
}

package com.yokeos.web.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yokeos.web.GlobalExceptionHandler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 工作区只读浏览切片（第 30 节 T014，坑四）：真文件系统 + 真 Controller（防穿越校验在 Controller 内联， mock 无从替）； 目录穿越两形态（../
 * 变形与绝对路径）是唯一安全要点的机器断言。yokeos.root 经 @DynamicPropertySource 指向 TempDir（26 节切片属性注入同款思路，root 是动态路径故走
 * Dynamic）。
 */
@WebMvcTest
@Import({WorkspaceApiController.class, GlobalExceptionHandler.class})
class WorkspaceApiControllerTest {

  @TempDir static Path temp;

  private static final String AGENT_MD =
      "---\nname: demo\nprovider:\n  name: deepseek\n  model: deepseek-chat\n---\n正文";

  @DynamicPropertySource
  static void workspaceRoot(DynamicPropertyRegistry registry) {
    registry.add("yokeos.root", () -> temp.toString());
  }

  @BeforeAll
  static void seedWorkspace() throws IOException {
    Path agentDir = temp.resolve("agents/demo");
    Files.createDirectories(agentDir.resolve("scripts"));
    Files.writeString(agentDir.resolve("AGENT.md"), AGENT_MD);
    Files.writeString(agentDir.resolve("scripts/reconcile.py"), "print('ok')");
    Files.createDirectories(temp.resolve("archive/old-agent"));
    Files.writeString(temp.resolve("archive/old-agent/AGENT.md"), AGENT_MD);
  }

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("tree 返回 agents 与 archive 两支、Agent 目录可展开列其内文件")
  void treeReturnsAgentsAndArchiveBranches() throws Exception {
    mockMvc
        .perform(get("/api/v1/workspace/tree"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[?(@.name == 'agents')]").exists())
        .andExpect(jsonPath("$.data[?(@.name == 'archive')]").exists());
  }

  @Test
  @DisplayName("file 正常相对路径 200 返回全文")
  void fileValidPathReturnsContent() throws Exception {
    mockMvc
        .perform(get("/api/v1/workspace/file").param("path", "agents/demo/AGENT.md"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value(AGENT_MD));
  }

  @Test
  @DisplayName("file 相对穿越（../../etc/passwd）→ 400（坑四形态一）")
  void fileRelativeTraversalRejected() throws Exception {
    mockMvc
        .perform(get("/api/v1/workspace/file").param("path", "../../etc/passwd"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  @DisplayName("file 绝对路径（/etc/passwd）→ 400（坑四形态二：resolve 后不落在 root 内）")
  void fileAbsolutePathRejected() throws Exception {
    mockMvc
        .perform(get("/api/v1/workspace/file").param("path", "/etc/passwd"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  @DisplayName("file 不存在 → 404")
  void fileMissingPathNotFound() throws Exception {
    mockMvc
        .perform(get("/api/v1/workspace/file").param("path", "agents/ghost/AGENT.md"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }
}

package com.yokeos.web.controller;

import com.yokeos.web.common.ApiResponse;
import com.yokeos.web.controller.dto.FileNode;
import com.yokeos.web.error.ResourceNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作区只读浏览（第 30 节，FR-012/013，技 §11.3）：tree 列 agents/ 与 archive/ 两支目录树，file 只读返回文本内容。
 * <b>防目录穿越是本组端点唯一的安全要点</b>（坑四）：请求路径 resolve 到 root 后 normalize，断言仍落在 root 内——{@code ../}
 * 变形与绝对路径两形态都被这一道拦住（resolve 绝对路径得自身、startsWith 不成立即 400）。 本组端点零写形态（在线编辑明确不做，拍板①）。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification =
        "第一阶段 web API 无认证是设计决定（内网部署假设，需求 §5.10）；root 是启动即定的路径值，"
            + "构造注入正是意图（26 节 Controller 注解先例同款）。")
@RestController
@RequestMapping("/api/v1/workspace")
public class WorkspaceApiController {

  private final Path workspaceRoot;

  /** root 与 YokeosRuntime 的 workspace() 同源（yokeos.root 属性，缺省 .yokeos）。 */
  public WorkspaceApiController(@Value("${yokeos.root:.yokeos}") String root) {
    this.workspaceRoot = Path.of(root).toAbsolutePath().normalize();
  }

  /** 目录树（FR-012）：agents/ 与 archive/ 两支；目录不存在给空分支（工作区可能尚未初始化）。 */
  @GetMapping("/tree")
  public ApiResponse<List<FileNode>> tree() {
    return ApiResponse.ok(List.of(branch("agents", "agents"), branch("archive", "archive")));
  }

  private FileNode branch(String name, String relative) {
    Path dir = workspaceRoot.resolve(relative);
    if (!Files.isDirectory(dir)) {
      return FileNode.dir(name, relative, List.of());
    }
    try (var entries = Files.list(dir)) {
      List<FileNode> children = entries.sorted().map(p -> node(p, relative)).toList();
      return FileNode.dir(name, relative, children);
    } catch (IOException e) {
      throw new UncheckedIOException("目录枚举失败: " + relative, e);
    }
  }

  /** 递归列节点（工作区规模内全展开；path 始终相对 root——file 端点入参形态）。 */
  private FileNode node(Path path, String parentRelative) {
    String relative = parentRelative + "/" + String.valueOf(path.getFileName());
    if (Files.isDirectory(path)) {
      try (var entries = Files.list(path)) {
        List<FileNode> children = entries.sorted().map(p -> node(p, relative)).toList();
        return FileNode.dir(String.valueOf(path.getFileName()), relative, children);
      } catch (IOException e) {
        throw new UncheckedIOException("目录枚举失败: " + relative, e);
      }
    }
    return FileNode.file(String.valueOf(path.getFileName()), relative);
  }

  /** 只读读文件（FR-013）：resolve → normalize → startsWith(root) 校验，越界 400；不存在 404； 非文本（读出乱码）400 可读原因。 */
  @GetMapping("/file")
  public ApiResponse<String> file(@RequestParam("path") String path) {
    Path resolved = workspaceRoot.resolve(path).normalize();
    if (!resolved.startsWith(workspaceRoot)) {
      throw new IllegalArgumentException("路径越出工作区边界: " + path); // → 400（坑四）
    }
    if (!Files.isRegularFile(resolved)) {
      throw new ResourceNotFoundException("文件不存在: " + path); // → 404
    }
    try {
      return ApiResponse.ok(Files.readString(resolved));
    } catch (MalformedInputException e) {
      throw new IllegalArgumentException("只支持文本文件: " + path); // → 400（二进制等）
    } catch (IOException e) {
      throw new UncheckedIOException("文件读取失败: " + path, e);
    }
  }
}

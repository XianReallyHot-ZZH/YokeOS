package com.yokeos.tool.builtin;

import com.yokeos.tool.sandbox.ActionType;
import com.yokeos.tool.sandbox.Sandbox;
import com.yokeos.tool.sandbox.SandboxAction;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 内置文件工具三件（read_file / write_file / list_dir，技 §6.2）。经注解管道注册（宪法 2：只借 schema 生成）， 执行发起方是
 * ToolExecutor——方法内异常上抛，由其统一转失败结果落审计。
 *
 * <p>硬规矩：每个方法第一件事是 Sandbox 校验（24 节接线，specs/008 D7）——校验不过文件根本不碰。超长截断与 http_get 同款意识（坑五：
 * 全文回填一轮撑爆上下文）。
 */
public class FileTools {

  /** 读内容截断上限（约 8000 字符，与 HttpTools 同款口径——防超大文件撑爆上下文）。 */
  static final int MAX_CONTENT_CHARS = 8000;

  private final Sandbox sandbox;

  /**
   * @param sandbox 路径白名单校验（FILE_READ/FILE_WRITE，24 节接线——先 enforce 后 IO）。
   */
  public FileTools(Sandbox sandbox) {
    this.sandbox = sandbox;
  }

  /** 读指定路径的文本文件内容。 */
  @Tool(name = "read_file", description = "读取指定路径的文本文件内容，超长自动截断")
  public String readFile(@ToolParam(description = "要读取的文件路径") String path) {
    sandbox.enforce(new SandboxAction(ActionType.FILE_READ, path)); // 24 节接线：不过则异常上抛走既有失败审计
    Path file = Path.of(path);
    if (!Files.isRegularFile(file)) {
      throw new IllegalArgumentException("文件不存在或不是普通文件: " + path);
    }
    try {
      return truncate(Files.readString(file));
    } catch (IOException e) {
      throw new UncheckedIOException("读取文件失败: " + path, e);
    }
  }

  /** 把内容覆盖写入指定路径（父目录不存在自动创建）。 */
  @Tool(name = "write_file", description = "把内容写入指定路径的文件（覆盖写，父目录自动创建）")
  public String writeFile(
      @ToolParam(description = "要写入的文件路径") String path,
      @ToolParam(description = "要写入的内容") String content) {
    sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, path));
    try {
      Path file = Path.of(path);
      Path parent = file.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(file, content);
      return "已写入: " + path;
    } catch (IOException e) {
      throw new UncheckedIOException("写入文件失败: " + path, e);
    }
  }

  /** 列出指定目录下的条目名（排序稳定）。 */
  @Tool(name = "list_dir", description = "列出指定目录下的文件和子目录名")
  public String listDir(@ToolParam(description = "要列出的目录路径") String path) {
    sandbox.enforce(new SandboxAction(ActionType.FILE_READ, path));
    Path directory = Path.of(path);
    if (!Files.isDirectory(directory)) {
      throw new IllegalArgumentException("目录不存在: " + path);
    }
    try (Stream<Path> entries = Files.list(directory)) {
      return entries
          .map(p -> String.valueOf(p.getFileName()))
          .sorted()
          .collect(Collectors.joining("\n"));
    } catch (IOException e) {
      throw new UncheckedIOException("列目录失败: " + path, e);
    }
  }

  private static String truncate(String content) {
    if (content == null || content.length() <= MAX_CONTENT_CHARS) {
      return content;
    }
    return content.substring(0, MAX_CONTENT_CHARS) + "…(截断，全文 " + content.length() + " 字符)";
  }
}

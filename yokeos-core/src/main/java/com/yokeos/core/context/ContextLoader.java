package com.yokeos.core.context;

import com.yokeos.core.profile.Profile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * system prompt 的供给者（宪法 8：AGENT.md 归上下文层，不是 Tool）。每次组装重新读文件、零缓存——用户改完 立即生效（技
 * §8.3）。拼接顺序：identity.prompt → Bootstrap（固定相对序 AGENTS.md → SOUL.md → USER.md，每段带角色
 * header，无覆盖语义）→〔引用 Skill 正文：29 节留位〕→ AGENT.md 正文压轴。
 */
public final class ContextLoader {

  private static final Logger LOG = LoggerFactory.getLogger(ContextLoader.class);

  /**
   * Bootstrap 文件 → 角色 header 的固定相对序（技 §8.3）。LinkedHashMap 保序；Profile 的 bootstrap 列表
   * 只做裁剪选择器（裁条目不乱序），声明序不影响输出序。
   */
  private static final Map<String, String> BOOTSTRAP_ORDER = new LinkedHashMap<>();

  static {
    BOOTSTRAP_ORDER.put("AGENTS.md", "## 项目约定（AGENTS.md）");
    BOOTSTRAP_ORDER.put("SOUL.md", "## 人格定义（SOUL.md）");
    BOOTSTRAP_ORDER.put("USER.md", "## 用户偏好（USER.md）");
  }

  private final Path workspace;

  /**
   * @param workspace 工作区根（.yokeos/）：Bootstrap 在根、AGENT.md 在 agents/&lt;name&gt;/
   */
  public ContextLoader(Path workspace) {
    this.workspace = workspace;
  }

  /** 组装 system prompt：identity → Bootstrap →〔Skill 位：29 节接线〕→ AGENT.md 正文。 */
  public String loadSystemPrompt(Profile profile) {
    StringBuilder sb = new StringBuilder();
    sb.append(profile.identity().prompt()).append('\n');
    appendBootstrap(sb, bootstrapSelection(profile));
    // 引用 Skill 正文注入位：公共 Skill 库与按名引用归第 29 节，本节不读 skills/ 目录。
    sb.append(stripFrontmatter(readAgentMarkdown(profile)));
    return sb.toString();
  }

  /** 空列表 = 三件全取（缺省）；非空 = 裁剪选择器。 */
  private static List<String> bootstrapSelection(Profile profile) {
    List<String> declared = profile.bootstrap();
    return declared.isEmpty() ? List.copyOf(BOOTSTRAP_ORDER.keySet()) : declared;
  }

  private void appendBootstrap(StringBuilder sb, List<String> selection) {
    for (Map.Entry<String, String> entry : BOOTSTRAP_ORDER.entrySet()) {
      if (!selection.contains(entry.getKey())) {
        continue; // 被裁剪的条目不注入（列表只能裁剪不能乱序）
      }
      Path file = workspace.resolve(entry.getKey());
      if (!Files.exists(file)) {
        // 缺失 WARN 跳过不阻断；但读失败显式抛错——「人格悄悄丢了」是最难查的软故障
        LOG.warn("Bootstrap 文件缺失，跳过注入: {}", entry.getKey());
        continue;
      }
      try {
        sb.append(entry.getValue()).append('\n').append(Files.readString(file)).append('\n');
      } catch (IOException e) {
        throw new UncheckedIOException("读 Bootstrap 失败: " + entry.getKey(), e);
      }
    }
  }

  private String readAgentMarkdown(Profile profile) {
    Path agentMarkdown = workspace.resolve("agents").resolve(profile.name()).resolve("AGENT.md");
    try {
      return Files.readString(agentMarkdown);
    } catch (IOException e) {
      throw new UncheckedIOException("读 AGENT.md 失败: " + agentMarkdown, e);
    }
  }

  /** frontmatter 围栏标记。 */
  private static final String FRONTMATTER_FENCE = "---";

  /** frontmatter 围栏 {@code ---} 的长度（第二围栏搜索起点跳过首围栏自身）。 */
  private static final int FENCE_LENGTH = FRONTMATTER_FENCE.length();

  /** 剥离 frontmatter：首对 {@code ---} 围栏之间的内容丢弃，正文保留（无围栏则原样返回）。 */
  private static String stripFrontmatter(String raw) {
    String normalized = raw.stripLeading();
    if (!normalized.startsWith(FRONTMATTER_FENCE)) {
      return normalized;
    }
    int fenceEnd = normalized.indexOf("\n" + FRONTMATTER_FENCE, FENCE_LENGTH);
    if (fenceEnd < 0) {
      return normalized; // 围栏未闭合：按无 frontmatter 处理，内容不吞
    }
    int nextLine = normalized.indexOf('\n', fenceEnd + 1);
    return nextLine < 0 ? "" : normalized.substring(nextLine + 1);
  }
}

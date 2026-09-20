package com.yokeos.tool.sandbox;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 第一阶段唯一沙箱实现：路径/命令/域名三重白名单（技 §6.7、specs/008 D4，宪法 6）。三个 {@code check*} 均 private—— 外部只看得到 {@link
 * #enforce(SandboxAction)} 一个入口，若三个方法 public 挂上接口，接口就被这一档实现带偏了。
 *
 * <p><b>路径校验用对称真实路径解析</b>（research D2，本仓严于参照钉版树终态的 {@code normalize+startsWith} 字符串比 对）：目标与白名单根过同一个
 * {@link #resolveReal(Path)} 再比对——{@code toRealPath} 解开符号链接挡「链接在白名单内、
 * 目标在根外」的出根手法；双侧同函数防符号链接前缀目录（macOS {@code /tmp}、{@code /var/folders}）的假拒绝。新建路径
 * 回溯最近存在父目录校验（「白名单根下新建放行、借道不存在深层路径穿越仍拦」）。真实化过程的任何异常转 {@link
 * SandboxViolationException}（fail-closed：校验完成不了就拒绝）。
 *
 * <p>空白名单 = deny-all（什么都不允许），不是「不校验」（research D5）；命令比对是 argv[0] 字面精确比对（裸名与绝对路 径是两个条目，不归一——research
 * D4）；域名先解析 host（端口天然不参与、双侧小写——research D3）再通配符匹配（点号边 界：{@code *.example.com} 只命中真子域，不覆盖裸域）。
 */
public final class WhitelistSandbox implements Sandbox {

  /** 域名通配符前缀（P3C：字面量提常量）。 */
  private static final String WILDCARD_PREFIX = "*.";

  private final List<Path> allowedRoots;

  private final Set<String> allowedCommands;

  private final List<String> allowedDomainPatterns;

  /**
   * @param properties 三组白名单（路径根构造时按启动目录解析为绝对路径；域名条目双侧小写的「条目侧」在此归一）
   */
  public WhitelistSandbox(SandboxProperties properties) {
    this.allowedRoots =
        properties.allowedPaths().stream()
            .map(entry -> Path.of(entry).toAbsolutePath().normalize())
            .toList();
    this.allowedCommands = Set.copyOf(properties.allowedCommands());
    this.allowedDomainPatterns =
        properties.allowedDomains().stream()
            .map(pattern -> pattern.toLowerCase(Locale.ROOT))
            .toList();
  }

  /**
   * 四 case 全覆盖 + default 兜底（未来新增枚举值 fail-closed）。P3C 的 SwitchStatementRule 对 Java 14+ 箭头 switch 的
   * default 分支识别不了（工具代差误报——default 实际在位），按 17 节先例类级抑制并留痕。
   */
  @SuppressWarnings("PMD.SwitchStatementRule")
  @Override
  public void enforce(SandboxAction action) {
    // FILE_READ/FILE_WRITE 接口层分离（未来按读/写分权限），第一阶段同路由路径校验——接口先分、实现先合（D3）
    switch (action.type()) {
      case FILE_READ, FILE_WRITE -> checkFilePath(action.target());
      case SHELL_COMMAND -> checkShellCommand(action.target());
      case HTTP_REQUEST -> checkHttpUrl(action.target());
      default -> throw new IllegalArgumentException("未知的动作类型: " + action.type());
    }
  }

  private void checkFilePath(String rawPath) {
    Path target = resolveReal(Path.of(rawPath).toAbsolutePath().normalize());
    boolean allowed = allowedRoots.stream().anyMatch(root -> target.startsWith(resolveReal(root)));
    if (!allowed) {
      throw new SandboxViolationException("路径不在白名单内: " + rawPath);
    }
  }

  private void checkShellCommand(String argv0) {
    if (!allowedCommands.contains(argv0)) {
      throw new SandboxViolationException("命令不在白名单内: " + argv0);
    }
  }

  private void checkHttpUrl(String url) {
    URI uri;
    try {
      uri = URI.create(url);
    } catch (IllegalArgumentException e) {
      // URL 原文不回显：webhook URL 即凭证（19 节坑二），不得进 error_message 与日志（research D7）
      throw new SandboxViolationException("URL 无法解析或不含主机名，按拒绝处理", e);
    }
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new SandboxViolationException("URL 无法解析或不含主机名，按拒绝处理");
    }
    String normalizedHost = host.toLowerCase(Locale.ROOT);
    boolean allowed =
        allowedDomainPatterns.stream().anyMatch(pattern -> matchesDomain(normalizedHost, pattern));
    if (!allowed) {
      // 消息只携带 host 不携带整串 URL——URL 里可能有凭证（19 节坑二）
      throw new SandboxViolationException("域名不在白名单内: " + host);
    }
  }

  /**
   * 通配符带点号边界：{@code *.example.com} 命中 {@code api.example.com} / {@code a.b.example.com}，不命中形似域名
   * {@code evil-example.com}（{@code endsWith("example.com")} 的经典漏洞）与裸域 {@code example.com}；裸域需单独的精
   * 确条目（research D3/D4）。
   */
  private static boolean matchesDomain(String host, String pattern) {
    if (pattern.startsWith(WILDCARD_PREFIX)) {
      // 只剥 "*" 保留点号边界（".example.com"）——剥到 "example.com" 会让 evil-example.com 与裸域误命中
      return host.endsWith(pattern.substring(1));
    }
    return host.equals(pattern);
  }

  /**
   * 对称真实路径解析（research D2）：回溯到最近存在的祖先取 {@code toRealPath()}（解开符号链接），不存在的尾段原样接回
   * ——新建路径借父目录的真实形态校验。目标与白名单根都过本函数，符号链接前缀目录下两侧解析对称、不假拒绝。
   */
  private static Path resolveReal(Path path) {
    Path existing = path;
    while (existing != null && !Files.exists(existing)) {
      existing = existing.getParent();
    }
    if (existing == null) {
      // 连一个存在的祖先都没有：无法完成真实化，按拒绝处理（fail-closed）
      throw new SandboxViolationException("路径真实化失败，按拒绝处理: " + path);
    }
    try {
      return existing.toRealPath().resolve(existing.relativize(path));
    } catch (IOException e) {
      throw new SandboxViolationException("路径真实化失败，按拒绝处理: " + path, e);
    }
  }
}

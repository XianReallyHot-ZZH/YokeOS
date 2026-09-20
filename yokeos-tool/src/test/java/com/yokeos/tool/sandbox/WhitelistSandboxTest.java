package com.yokeos.tool.sandbox;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 第 24 节验收 harness：WhitelistSandboxTest——安全模块测的重点不是「放行对不对」，是「绕得过绕不过」（教学文档第四部 分）。三类校验各「允许 +
 * 拒绝」成对，再加绕过场景（{@code ../} 穿越、symlink 出根、命令变体、形似域名、query 夹带、 畸形 URL）与空白名单 deny-all。
 *
 * <p>只经 {@code enforce(SandboxAction)} 公共入口断言——三个 {@code check*} 是 private，不直测（接口中立性，specs/008
 * D1）。路径组的 @TempDir 在 macOS 落在 {@code /var/folders}（符号链接前缀），天然兼测对称真实路径解析不假拒绝（research D2）。
 */
class WhitelistSandboxTest {

  private static WhitelistSandbox sandbox(
      List<String> paths, List<String> commands, List<String> domains) {
    return new WhitelistSandbox(new SandboxProperties(paths, commands, domains));
  }

  @Nested
  @DisplayName("文件路径白名单")
  class FilePathWhitelist {

    @Test
    @DisplayName("白名单内路径_读写放行")
    void insideWhitelistAllowed(@TempDir Path allowed) throws IOException {
      WhitelistSandbox sb = sandbox(List.of(allowed.toString()), List.of(), List.of());
      Path existing = Files.createFile(allowed.resolve("report.txt"));

      assertDoesNotThrow(
          () -> sb.enforce(new SandboxAction(ActionType.FILE_READ, existing.toString())));
      assertDoesNotThrow(
          () ->
              sb.enforce(
                  new SandboxAction(
                      ActionType.FILE_WRITE, allowed.resolve("out/new.txt").toString())));
    }

    @Test
    @DisplayName("白名单外路径_拒绝")
    void outsideWhitelistRejected(@TempDir Path allowed) {
      WhitelistSandbox sb = sandbox(List.of(allowed.toString()), List.of(), List.of());

      assertThrows(
          SandboxViolationException.class,
          () -> sb.enforce(new SandboxAction(ActionType.FILE_READ, "/etc/passwd")));
    }

    @Test
    @DisplayName("相对路径穿越_爬出白名单目录_被拦")
    void relativePathTraversalIsBlocked(@TempDir Path allowed) {
      // 关键回归（坑一）：normalize 前形似落在白名单内，normalize 后爬到白名单之外——必须在标准化后判定越界
      WhitelistSandbox sb = sandbox(List.of(allowed.toString()), List.of(), List.of());
      String traversal = allowed.resolve("../../../../../../../../etc/passwd").toString();

      assertThrows(
          SandboxViolationException.class,
          () -> sb.enforce(new SandboxAction(ActionType.FILE_READ, traversal)));
    }

    @Test
    @DisplayName("符号链接出白名单根_真实路径校验拒绝")
    void symlinkEscapingRootIsRejected(@TempDir Path allowed, @TempDir Path outside)
        throws IOException {
      // 严于参照的定稿点（research D2）：链接本身在白名单内、指向白名单外——normalize+startsWith 的字符串比对会放行，
      // toRealPath 才拦得住
      Path secret = Files.createFile(outside.resolve("secret.txt"));
      Files.createSymbolicLink(allowed.resolve("innocent.txt"), secret);
      WhitelistSandbox sb = sandbox(List.of(allowed.toString()), List.of(), List.of());

      assertThrows(
          SandboxViolationException.class,
          () ->
              sb.enforce(
                  new SandboxAction(
                      ActionType.FILE_READ, allowed.resolve("innocent.txt").toString())));
    }

    @Test
    @DisplayName("白名单内新建文件_回溯父目录校验放行")
    void newFileUnderRootAllowedViaParentFallback(@TempDir Path allowed) {
      // 目标不存在：回溯最近存在父目录（白名单根本身）取真实路径，尾段原样接回——新建放行（坑一）
      WhitelistSandbox sb = sandbox(List.of(allowed.toString()), List.of(), List.of());

      assertDoesNotThrow(
          () ->
              sb.enforce(
                  new SandboxAction(
                      ActionType.FILE_WRITE, allowed.resolve("a/b/c/new.txt").toString())));
    }

    @Test
    @DisplayName("符号链接前缀目录_对称解析不假拒绝")
    void tempDirSymlinkedPrefixNoFalseRejection(@TempDir Path allowed) throws IOException {
      // macOS @TempDir 落在 /var/folders（/var → /private/var 符号链接前缀）：目标单侧 toRealPath 而根停在字符串形态
      // 必假拒绝——双侧同一 resolveReal 才放行（research D2 的对称性防线；Linux 上退化为普通放行用例，不失效）
      WhitelistSandbox sb = sandbox(List.of(allowed.toString()), List.of(), List.of());
      Path existing = Files.createDirectories(allowed.resolve("memory")).resolve("MEMORY.md");

      assertDoesNotThrow(
          () -> sb.enforce(new SandboxAction(ActionType.FILE_WRITE, existing.toString())));
    }
  }

  @Nested
  @DisplayName("Shell 命令白名单")
  class ShellCommandWhitelist {

    private final WhitelistSandbox sb = sandbox(List.of(), List.of("ls", "cat"), List.of());

    @Test
    @DisplayName("白名单内命令_首token放行")
    void whitelistedCommandPasses() {
      assertDoesNotThrow(() -> sb.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "ls")));
    }

    @Test
    @DisplayName("白名单外命令_拒绝")
    void commandOutsideWhitelistRejected() {
      assertThrows(
          SandboxViolationException.class,
          () -> sb.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "rm")));
    }

    @Test
    @DisplayName("白名单内命令的形似变体_拒绝")
    void similarVariantRejected() {
      // 关键回归（坑三）：精确比对——前缀/包含匹配会把 lsblk 之类的变体误放行
      assertThrows(
          SandboxViolationException.class,
          () -> sb.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "lsblk")));
    }

    @Test
    @DisplayName("裸名与绝对路径是不同条目_不归一")
    void bareNameAndAbsolutePathAreDistinctEntries() {
      WhitelistSandbox both = sandbox(List.of(), List.of("ls", "/bin/cat"), List.of());

      assertDoesNotThrow(() -> both.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "ls")));
      assertDoesNotThrow(
          () -> both.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "/bin/cat")));
      assertThrows(
          SandboxViolationException.class,
          () -> both.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "/bin/ls")));
    }
  }

  @Nested
  @DisplayName("HTTP 域名白名单")
  class HttpDomainWhitelist {

    private final WhitelistSandbox sb =
        sandbox(List.of(), List.of(), List.of("*.example.com", "api.deepseek.com"));

    @Test
    @DisplayName("通配符命中真子域_精确项命中裸域_放行")
    void wildcardAndExactEntriesPass() {
      assertDoesNotThrow(
          () ->
              sb.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "https://api.example.com/v1")));
      assertDoesNotThrow(
          () ->
              sb.enforce(
                  new SandboxAction(ActionType.HTTP_REQUEST, "https://a.b.example.com/deep")));
      assertDoesNotThrow(
          () ->
              sb.enforce(
                  new SandboxAction(ActionType.HTTP_REQUEST, "https://api.deepseek.com/v1")));
    }

    @Test
    @DisplayName("形似域名不得命中通配符_点号边界")
    void lookalikeDomainDoesNotMatchWildcard() {
      // 关键回归（坑四）：endsWith("example.com") 的经典漏洞——匹配必须带点号边界，裸域也不被通配覆盖
      assertThrows(
          SandboxViolationException.class,
          () ->
              sb.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "http://evil-example.com/x")));
      assertThrows(
          SandboxViolationException.class,
          () -> sb.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "http://example.com/x")));
    }

    @Test
    @DisplayName("query 夹带域名串不误放行_判定只看host")
    void queryStringDoesNotGrantPass() {
      // URL 其他部分夹带白名单域名字串不放行；host 正确时 query 夹带别站名也不影响
      assertThrows(
          SandboxViolationException.class,
          () ->
              sb.enforce(
                  new SandboxAction(
                      ActionType.HTTP_REQUEST, "https://other.com/x?url=api.example.com")));
      assertDoesNotThrow(
          () ->
              sb.enforce(
                  new SandboxAction(
                      ActionType.HTTP_REQUEST, "https://api.example.com/x?ref=evil.com")));
    }

    @Test
    @DisplayName("host大小写不敏感_端口不参与判定")
    void hostCaseAndPortInsensitive() {
      assertDoesNotThrow(
          () ->
              sb.enforce(
                  new SandboxAction(ActionType.HTTP_REQUEST, "https://API.Example.COM:8443/x")));
    }

    @Test
    @DisplayName("畸形URL无主机名_拒绝")
    void malformedUrlWithoutHostRejected() {
      assertThrows(
          SandboxViolationException.class,
          () -> sb.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "not-a-url")));
      assertThrows(
          SandboxViolationException.class,
          () -> sb.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "http://[bad")));
    }
  }

  @Nested
  @DisplayName("空白名单 = deny-all")
  class EmptyWhitelistDeniesAll {

    private final WhitelistSandbox sb = sandbox(List.of(), List.of(), List.of());

    @Test
    @DisplayName("三类白名单全空_一律拒绝而非放行")
    void emptyWhitelistRejectsEverything() {
      // 空 = 什么都不允许，不是「不校验」（research D5）——这条语义写死进配置注释与本回归
      assertThrows(
          SandboxViolationException.class,
          () -> sb.enforce(new SandboxAction(ActionType.FILE_READ, "/tmp/x")));
      assertThrows(
          SandboxViolationException.class,
          () -> sb.enforce(new SandboxAction(ActionType.SHELL_COMMAND, "ls")));
      assertThrows(
          SandboxViolationException.class,
          () -> sb.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "https://api.example.com")));
    }
  }
}

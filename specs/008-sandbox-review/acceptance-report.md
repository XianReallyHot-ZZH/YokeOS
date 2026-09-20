# 验收报告：Sandbox 设计评审（第 23 节，specs/008-sandbox-review）

> **课型**：评审课（拒绝产码）。**日期**：2026-09-20。**分支**：`specs/008-sandbox-review`。
> **可演示成果口径**（[需 §11] 第 23 节行）：评审记录：白名单沙箱设计定稿（不产码）——即 `specs/008-sandbox-review/review.md`。
> 代码课的六项证据 DoD（mvn 全绿、harness 对号等）不适用于本节；本报告按评审课等价物逐项验收。

## 一、交付物存在性核对

| 交付物 | 证据 | 结论 |
|---|---|---|
| 教学文档 `docs/class/023-sandbox-review.md` | 28,578 字节，五段式评审课形态 + 四张配图，头部拍板记录已回填（2026-09-20，用户批准「内容没问题」并要求补图） | ✅ |
| 配图 ×4 `docs/images/class-023-{1..4}.svg` | 3,127 / 4,325 / 4,387 / 3,666 字节，YokeOS 设计系统（`yk` marker 前缀、角色定色）；机检脚本四张全 OK（XML 合法、marker 引用完整、无文本越界）+ headless 截图逐张过视觉 QA（两处打磨性微调已修：图 1 chip 间距、图 4 轴标签消歧） | ✅ |
| 评审文档 `specs/008-sandbox-review/review.md` | 25,834 字节，六段结构齐全（范围方法 / 概念+系统对照 / 定稿 D1~D12 / 差异三条 / 张力裁决×3 + 开放事项 O1~O5 / 24 节 specify 素材含坑↔回归测试表） | ✅ |
| 验收报告（本文件） | — | ✅ |
| 无 spec 三件套、无代码/测试/配置/表 | 见第二节反向核对 | ✅（符合指南 §4.2 评审节形态） |

## 二、「不产码」反向核对

- `git status --short`：仅 `docs/class/023-sandbox-review.md`、`docs/images/class-023-{1..4}.svg`、`specs/008-sandbox-review/` 七项未跟踪，**零代码文件被触碰**；
- 全仓 grep 无 `interface Sandbox` / `class WhitelistSandbox`（vendors 参照库除外）——实现不存在，属第 24 节；
- 前序八处检查位留位注释原样未动（`ToolExecutor`/`FileTools`/`ShellTools`/`HttpTools`/`NotifyTools`/`MarkdownMemoryStore`/`Mem0MemoryStore`/`SqliteMemoryStore` 的「24 节」注释计数与合流时一致，文件无修改）；
- 前序交付物未动（H0 存在性检查通过：`ToolExecutor`（17）、`NotifyTools`（19）、`FileTools`/`ShellTools`/`HttpTools`/`ToolRegistry`（20）、`MemoryService` 与三档后端（22）全部在位）。

## 三、评审自查清单 12 条逐条结论（教学文档第四部分）

| # | 自查问题 | 一句话结论 | 锚点 |
|---|---|---|---|
| 1 | 跷跷板与「越底层越安全」 | 隔离越强开销越大启动越慢，所有方案在这条线上选点；应用层最弱→容器居中→microVM 更强→物理最强 | review §2.1 + 图 1 |
| 2 | 四个隔离维度 | 文件系统 / 网络 / 进程与系统调用 / 资源 | review §2.1 |
| 3 | 接口签名默写 + microVM 反套 + FILE 读写分离 | `enforce(SandboxAction{type,target})`，四值 ActionType；`enforce`/`SandboxAction` 无检查对象词，microVM 可读作「确保动作只在受控环境发生」干净套入；读写分离是接口层权限维度预留，第一阶段同路由 `checkFilePath` | D1~D3 |
| 4 | 落点 + 收口 + 拒绝不重试 | 八处动作发生处单一落点；`ToolExecutor` 只收口（异常→不可重试失败→审计）——执行器只见 name+argumentsJson 无法分类，重复 enforce 会双校验双审计；重试被拒动作无意义 | D6/D7 + 裁决 5.1 |
| 5 | MCP 为什么不 enforce、靠什么兜 | 四值 ActionType 无法分类 MCP server 任意语义；信任边界（配一个 MCP server = 信任其作者）+ 审计 day one；参照课件 Hermes 解剖（whole-process wrapping）点破的暴露面 | D8 + 差异二 |
| 6 | 三条校验规则各防什么 | 真实路径校验防符号链接/`../` 穿越（坑一）；argv[0] 精确比对防命令名变体（坑三）；host 解析+通配符防伪造后缀与 URL 夹带（坑四） | D4 + §6.3 |
| 7 | 两个升级信号 | 白名单→容器：要跑相对不可信代码或做多租户；容器→microVM：要跑完全不可信代码或规模化多租户 | D10 + 图 4 |
| 8 | 「劝阻不是关押」+ 三类系统站位 | 第一档防误操作限可用面，防不住蓄意绕过；Claude Code（可信机器防误伤）→ Hermes（可切换）→ 云解释器（陌生代码直奔 microVM），YokeOS 对标最左档——威胁模型决定选型，不是能力不足 | §2.2 + D11 |
| 9 | 纵深防御配套的第一阶段对应物 | 强隔离=缺（扩展，D10）；网络出口控制=域名白名单（应用层形态）；资源配额=Shell 超时+超长截断（缩放形态，裁决 5.2）；审计=两表 day one（完整形态）；最小权限=Profile `tools`（雏形） | §2.1 + 图 2 |
| 10 | 解释器信任边界 | 装一个带脚本的 Agent = 信任其作者——解释器入白名单即授予 YokeOS 进程用户的代码执行权；argv 直传挡 shell 语法拼接，不隔离解释器自身的文件与网络行为 | D11③ |
| 11 | 三处张力裁决 | enforce 单一落点、执行器收口（5.1）；超时资源按技归属、ActionType 不设新值（5.2）；Memory 档留位纳入覆盖面 + 配置自洽硬前提（5.3） | §5.1~5.3 |
| 12 | 坑一~六的回归测试点 | 六坑六点成表：symlink/穿越拒绝、一条审计不重试、命令变体拒绝、域名边界、拒绝后 IO 不发生、默认配置 save_memory 放行 | §6.3 |

**12/12 全部可答且有锚点。**

## 四、评审结论与移交

- **结论：通过。** [技 §6.7] 经业界对照与留位对表维持原样冻结为 D1~D12（D2 enforce 语义、D8 覆盖面原则、D9 配置自洽为评审补全），文档链零回改（三处张力以裁决留痕方式解决，未触碰原文）。
- **移交第 24 节**：六段式 specify 骨架预填（review §6.1）、取材跳转表（§6.2）、坑↔回归测试表（§6.3）、张力裁决（§5.1~5.3）、开放事项 O1~O5（§5.4，plan 阶段定稿）。
- **文档链新增引用关系**：教学文档与评审文档互相引用、配图四张随教学文档入库；CLAUDE.md / 技术方案 / 需求文档未改动。

## 五、实施偏差

1. **配图四张为用户批注后追加**：教学文档初稿无图，用户要求参照课件补图；按参照四图语义、以 YokeOS 自有设计系统重画（`docs/images/class-023-{1..4}.svg`，`class-NNN-k` 命名随 016~018 先例）。图 3 刻意**不照抄**参照的「策略对象 + ToolExecutor 单点」画法，按本项目定稿画「八处动作发生处 → enforce → 可插拔实现」（差异一的可视化立场）。
2. **拍板时机**：三处张力的裁决方向在教学文档起草阶段预填、随教学文档批准一并拍板（拍板记录③④⑤），21 节同款模式；评审文档据此留痕展开。
3. 其余按教学文档拍板记录①~⑥执行，无结构偏差。

## 六、验证命令（可复制）

```bash
# 不产码反向核对（预期：仅 docs/class/023、docs/images/class-023-*、specs/008 七项未跟踪）
git status --short

# Sandbox 实现不存在（预期：无输出——实现归第 24 节）
grep -rn 'class WhitelistSandbox\|interface Sandbox' --include='*.java' . | grep -v vendors

# 评审文档六段结构齐全（预期：6）
grep -c '^## ' specs/008-sandbox-review/review.md

# 教学文档四张配图引用解析（预期：四行 OK）
grep -o 'class-023-[0-9]\.svg' docs/class/023-sandbox-review.md | sort -u | while read f; do [ -f "docs/images/$f" ] && echo "$f OK"; done

# SVG 机检：XML 合法 + marker 引用完整（预期：四行 OK）
python3 - <<'PYEOF'
import xml.dom.minidom, glob, re
for f in sorted(glob.glob('docs/images/class-023*.svg')):
    d = xml.dom.minidom.parse(f)
    src = open(f, encoding='utf-8').read()
    ids = set(re.findall(r'marker id="([^"]+)"', src))
    refs = set(re.findall(r'url\(#([^)]+)\)', src))
    print(f, 'OK' if refs <= ids else f'MISSING {refs - ids}')
PYEOF
```

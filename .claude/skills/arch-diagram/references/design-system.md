# 手写 SVG 设计系统（tokens 与配方）

继承自参照库 `website/public/images/` 的手写 SVG 设计语言（无任何作图工具元数据的手写源码），经本仓 11 张技术方案图实践验证。三个原则：**克制**（全仓一张色表）、**语义**（颜色编码角色，不编码装饰）、**一致**（几何与排版 token 全仓统一）。写图前通读本文件，现场不发明任何 token。

## 1. 语义角色 → 颜色映射（全仓唯一映射）

| 角色 | fill | stroke（宽） | 标题文字 | 次要文字 | 典型用法 |
|------|------|-------------|---------|---------|---------|
| 上层调用方 / 入口 | `#e0e7ff` | `#6366f1` (2) | `#4338ca` | `#6366f1` | ReAct 循环、LLM、API 调用方 |
| **本章主角** | `#fef3c7` | `#f59e0b` **(2.6)** | `#b45309` | `#92400e` | 每张图唯一加粗描边的主角组件 |
| 并列实例 / 子组件 | `#dbeafe` | `#3b82f6` (2) | `#1d4ed8` | `#334155` | ChatModel 实例、内置 Tool、引擎模块 |
| 外部 / 出口 / 目标 | `#dcfce7` | `#22c55e` (2) | `#15803d` | `#166534` | 各家 LLM API、企业 IM 群、结果带 |
| 数据 / 存储 / 可插拔后端 | `#ede9fe` | `#a855f7` (2) | `#6b21a8` | `#7c3aed` | SQLite 表、Memory 三档后端、校验方法 |
| 拒绝 / 异常路径 | `#fee2e2` | `#ef4444` (2) | `#b91c1c` | `#b91c1c` | SandboxViolation、失败审计 |
| 旁注 / 配置 / 约束（虚线） | `#f8fafc` | `#94a3b8` (1.4) dash `5,3` | `#475569` | `#64748b` | application.yaml、Sandbox.enforce 旁注 |
| 中性容器 / 门槛 chip | `#f1f5f9` | `#cbd5e1` (1.4) | `#475569` | `#64748b` | 门槛标签、图例框（stroke 可用 `#e2e8f0`） |

- 箭头色随**源框角色**（amber 主角的出线用 amber），默认中性 `#94a3b8`；每色一个 marker。
- 文字中性四档：`#334155` / `#475569` / `#64748b` / `#94a3b8`（由深到浅）。
- 总览架构图的五个 band 另有一组**底色容器色**（浅一档）：blue `#eff6ff`、pink `#fdf2f8`、amber `#fffbeb`、purple `#faf5ff`、green `#f0fdf4`，band 标题用对应深色。

## 2. 几何与排版 tokens

- 根元素：`font-family="Arial, sans-serif"`；第一子元素 `<rect width=W height=H fill="#ffffff"/>`
- 圆角：主框 `rx=10`，小框/chip `rx=6~8`，总图主容器 `rx=14`
- 描边：普通 2；**主角 2.6**（全图唯一加粗）；虚线旁注 1.4~1.6
- 字号：图题 18 bold（仅总览图有图题）/ 框标题 13~16 bold / 副标题 10~12 / 微标签 8.5~9.5
- 框内文本两行制：bold 深色标题 + 浅一档副标题，`text-anchor="middle"`；内容多时 chip 内可三行（标题 + 两条微标签）
- 元素与 canvas 边缘留白 ≥ 24；相邻框间距 ≥ 24（连线拐弯需要）

## 3. marker 模板（放 `<defs>` 最前，id 前缀 `yk`）

```xml
<marker id="yks" markerWidth="10" markerHeight="10" refX="6" refY="3" orient="auto"><path d="M0,0 L6,3 L0,6 Z" fill="#94a3b8"/></marker>  <!-- 中性 -->
<!-- 同款换 fill 即得：ykam #f59e0b · ykgr #22c55e · ykbl #3b82f6 · ykpu #a855f7 · ykrd #ef4444 -->
```

引用 `marker-end="url(#yks)"`。marker 落点距目标框边 4~8px，别插进框里。

## 4. 文本宽度估算（排坐标与机检同款公式）

```
w = Σ size × (1.0 若 CJK，否则 0.56)     # em 系数
text-anchor="middle" 时：x0 = x − w/2, x1 = x + w/2
```

框宽 ≥ 最宽行 w + 16。超宽处置顺序：副标题降字号（12→10→9.5）→ 砍字 → 加宽框。

## 5. 构图配方库

1. **总览架构图**：中央主容器（amber 边 2.4, rx=14，标题 + 技术栈副标题）内叠五个分色 band（blue 触发入口 → pink 业务定义 → amber 引擎 → purple 能力 → green 存储），band = 浅底圆角容器 + 左上 11px bold 标题 + 内嵌 chip（10px bold 标题 + 8.5px 微标签）；四角虚线灰外部依赖框（LLM PROVIDERS / MCP SERVERS / NOTIFY TARGETS / USERS / BUSINESS SYSTEMS），彩色虚线协议连线（9px 文字标 API / JSON-RPC / Webhook）；底部图例框（色例一行 + 实线/虚线语义一行）。
2. **纵向链**：indigo 调用方 → amber 主角 → 干线分叉（一竖一横多竖）→ blue 实例行 → green 外部；旁注虚线 chip 从右侧水平接入主角；底部两行注脚（`#475569` 结论 + `#94a3b8` 约束）。
3. **汇流 collector**：多源各出竖线 → 同一 y 一条横线 → 单点竖线出（进下一层）。多入一出防穿框的标准件。
4. **循环回环**：`<path d="M x1,y1 L x1,ymid L x2,ymid L x2,y2" fill="none">` 圆角矩形路径 + 回环上方 13px 结论行；旁路出口（终止条件）从链首竖线下引 + 底部说明行。
5. **阶梯/分级**：同基线三框 + 每框顶部中性门槛 chip + 底部长横箭头 + 12px 选择原则注脚。
6. **结果对**：判定容器底部双出线，绿（通过）红（拒绝）两框并列，各自带 10px 后续说明。
7. **深色变体**：`-dark` 后缀与亮版成对（参照用 `-light` 成对），底色与文字整体换档后须重走自检。

## 6. 已验证的坑（每条都真实踩过）

- **堆叠框之间走直线必穿框**：下框顶边正好挡路。解法：虚线容器把同层框包住，从容器底部中心出线。
- **文本越界**：CJK 在 Arial 里按 1.0em 估不算冤枉；全拉丁的长标识符（`AgentLifecycleService.register`）也要按 0.56em 实算。
- **`transform="rotate(...)"` 文字**：机检脚本的已知误报源（估算器不认 transform），人工核横向占位即可。
- **残留调试元素**：交付前删掉 `stroke-width="0"`、多余空 line 之类写码过程的残渣。
- **defs 次序**：SVG 允许前向引用，但 defs 统一放最前，可读性好。
- **图是第二真相源**：设计改了图没改，比没有图更糟。图随文改，同一 commit。

## 7. 机检脚本（本仓已验证）

```bash
cd docs/images && python - <<'EOF'
import xml.dom.minidom, glob, re, sys
ok = True
for f in sorted(glob.glob('docs-*.svg')):
    try:
        d = xml.dom.minidom.parse(f)
        root = d.documentElement
        vb = [float(x) for x in root.getAttribute('viewBox').split()]
        src = open(f, encoding='utf-8').read()
        ids = set(re.findall(r'marker id="([^"]+)"', src))
        refs = set(re.findall(r'url\(#([^)]+)\)', src))
        missing = refs - ids
        warn = []
        for t in d.getElementsByTagName('text'):
            if t.getAttribute('transform'): continue   # rotate 误报源，人工核
            x = float(t.getAttribute('x')); size = float(t.getAttribute('font-size') or 12)
            anchor = t.getAttribute('text-anchor') or 'start'
            content = ''.join(c.data for c in t.childNodes if c.nodeType == c.TEXT_NODE)
            w = sum(size * (1.0 if ord(ch) > 0x2e80 else 0.56) for ch in content)
            x0 = x - w/2 if anchor == 'middle' else x
            if x0 < -2 or x0 + w > vb[2] + 2:
                warn.append(f'overflow [{x0:.0f},{x0+w:.0f}]/{vb[2]:.0f}: {content[:24]}')
        print(f, 'OK' if not missing and not warn else (f'MISSING {missing}' if missing else ' | '.join(warn)))
        if missing: ok = False
    except Exception as e:
        ok = False; print(f, 'PARSE FAIL', e)
sys.exit(0 if ok else 1)
EOF
```

## 8. 截图自检命令（Windows / Edge，已验证）

```bash
"/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe" \
  --headless --screenshot=out.png --window-size=<viewBox 宽>,<viewBox 高> \
  "file:///<svg 绝对路径>"
```

截完 `Read out.png` 亲自看：文字越界 / 元素重叠 / 箭头穿框 / 密度与留白，逐项改坐标重截。看过才算完成。

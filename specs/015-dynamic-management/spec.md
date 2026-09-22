# Feature Specification: 动态管理——一句话生成、上传即上线（第30节）

**Feature Branch**: `specs/015-dynamic-management`

**Created**: 2026-09-22

**Status**: Draft

**Input**: User description: "第30节需求：动态管理——一句话生成、上传即上线"（六段式组装自 `docs/class/030-dynamic-management.md` 一、二部分，拍板记录见教学文档头部）

## Clarifications

### Session 2026-09-22

- Q: 监听器拾取「已注册 Agent 的目录修改事件」时，重复注册的定时任务应如何处理才不会双跑？ → A: Option A——AgentLifecycleService.register 开头收口防重：注册前注册表已有同名 Agent 则先 unregisterProfile 旧 Profile 再走注册（一处收口、全部录入路径安全，不动 25 节 AgentScheduler 语义）。
- Q: create 端点接受的 Agent name 合法字符集应定为什么？ → A: Option A——白名单 `[a-zA-Z0-9][a-zA-Z0-9_-]*`（首字符字母数字，可含连字符下划线，长度 ≤64）；`/`、`\`、`..`、空格、中文等一律 400。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - API 建管 Agent，全程免重启 (Priority: P1)

业务系统完全通过 REST API 管理 Agent：`POST /api/v1/agents` 创建（name 冲突第一步就拒、零写入；写 Agent 目录 → 校验派生 → 注册；有定时则挂定时；注册失败回滚已写目录不留半个 Agent）、`GET /api/v1/agents` 列表、`GET /api/v1/agents/{name}` 查看含 AGENT.md 全文（编辑回填数据源）、`PUT /api/v1/agents/{name}` 覆写更新（即时生效；定时变更先注销旧句柄再注册新的）、`DELETE /api/v1/agents/{name}` 删除（按「注销定时 → 移出索引 → 目录归档 `.yokeos/archive/`」顺序，不物理删、归档重名加时间戳后缀）。创建返回 200 后不重启，Agent 立刻出现在列表里、有 cron 就到点自己跑。

**Why this priority**: 「改目录即改运行时」的主干——`.yokeos/agents/` 是唯一真相源，API 是业务系统的唯一入口；29 节运行时原语（register/remove/unregisterProfile）在此兑现价值。

**Independent Test**: 起 serve 后 curl 完整闭环：create → 不重启 GET 列表可见 → PUT 改 → invoke 可调 → DELETE → `.yokeos/archive/` 目录实证；编排顺序与回滚由单测 `InOrder`/异常注入钉死。

**Acceptance Scenarios**:

1. **Given** 注册表无 `weather-daily`，**When** `POST /api/v1/agents {name: weather-daily, agentMarkdown: 合法定义}`，**Then** 200、`.yokeos/agents/weather-daily/AGENT.md` 落盘、不重启 `GET /api/v1/agents` 即含它。
2. **Given** `weather-daily` 已存在，**When** 再次 POST 同名，**Then** 400（第一步就拒，一个字节不写，磁盘无新目录）。
3. **Given** create 的定义非法（如 provider 名不存在），**When** POST，**Then** 400 可读原因，已写目录被回滚删除、注册表与定时器零残留（不留半个 Agent）。
4. **Given** Agent 旧定义带 schedules（cron A），**When** PUT 新定义（cron B），**Then** 旧定时句柄先注销、新定时注册——cron A 不再触发、cron B 到点触发，两者不并跑。
5. **Given** Agent 存在且带定时，**When** DELETE，**Then** 按「注销定时 → 移出索引 → 归档目录」顺序执行（InOrder 可证），目录出现在 `.yokeos/archive/` 且原位置消失；历史审计记录仍可查。
6. **Given** DELETE 目标不存在，**When** DELETE，**Then** 404。
7. **Given** 归档位已有同名目录，**When** 再删同名 Agent（删了建、建了删），**Then** 新归档加时间戳后缀，历史归档不覆盖。

### User Story 2 - 一句话说出一个 Agent 草稿，人在环里把关 (Priority: P1)

运营在管理台输入一句话（如「每天早上九点查北京天气，把穿搭建议发到团队群」），系统经一次 LLM 调用产出一份规范 AGENT.md 草稿**原样返回预览**——不落盘、不注册；人过一眼、可改（尤其 cron 定时时刻与 tools 工具权限敏感项），确认后走 US1 的创建端点落盘注册。生成用 Provider/Model 走独立配置键（`yokeos.agent-generation.provider` 必填 + `.model` 可选），与具体 Agent 的 Provider 配置区分；未配置时调用端点返回 503 与配置方法提示，不静默回退；该键缺失不阻断进程启动。生成动作落 llm_calls 审计。

**Why this priority**: 需求 §5.3 动态管理三条入口的第一条；「运营不写 frontmatter」的价值主张所在，也是本节唯一的新 LLM 调用面。

**Independent Test**: 配置生成键后 POST generate，返回的草稿可被既有 Agent 定义解析器解析成合法定义；mock 断言生成全程零目录写入、零注册。

**Acceptance Scenarios**:

1. **Given** `yokeos.agent-generation.provider` 已配置，**When** `POST /api/v1/agents/generate {sentence: 一句话需求}`，**Then** 200 返回 AGENT.md 草稿全文；`.yokeos/agents/` 无新目录、注册表零变化、llm_calls 多一条（sessionId 前缀 `agent-generation`）。
2. **Given** 返回草稿，**When** 用它走 POST create，**Then** 草稿被接受创建成功（generate→create 闭环衔接）。
3. **Given** 未配置生成键，**When** POST generate，**Then** 503，消息含 `yokeos.agent-generation.provider` 配置方法；进程启动不被该键缺失阻断。
4. **Given** LLM 输出被 Markdown 代码围栏（```）包裹，**When** generate，**Then** 围栏被剥掉后校验，草稿可解析（不误报非法）。
5. **Given** LLM 输出剥围栏后仍非合法定义（缺 frontmatter 等），**When** generate，**Then** 400 可读原因（校验失败细节），不落盘不注册。
6. **Given** 请求句子为空，**When** POST generate，**Then** 400。

### User Story 3 - 丢目录即上线，文件系统直通运行时 (Priority: P1)

serve 运行中，开发者用 scp / git / 编辑器直接往 `.yokeos/agents/` 写一个完整 Agent 目录（含 `scripts/` 附属资源也行）；实时监听器（JDK WatchService 守护线程）拾取目录级新增/修改事件，走与 API 创建**同一段注册代码**校验加载，几秒内 Agent 出现在列表——不走 API 也即插即用。手工删目录对称：监听器注销定时 + 移出索引（目录已没了不归档）。单个坏目录记 WARN 跳过不拖垮监听；启动全量扫描仍走既有扫描链路，不重复登记。

**Why this priority**: 「上传即上线 = 丢目录即上线」——两条录入路径一段注册代码是 29 节「API 建的和文件建的行为一模一样」的兑现；复杂 Agent（带附属资源）的唯一录入路径。

**Independent Test**: serve 运行中 `cp -r` 一个合法 Agent 目录进 `.yokeos/agents/`，轮询断言 GET 列表几秒内出现；`handleChange` 直调单测钉「CREATE→注册、DELETE→注销、坏目录不抛不倒」。

**Acceptance Scenarios**:

1. **Given** serve 运行中、注册表无 `daily-digest`，**When** 往 `.yokeos/agents/` 拷入其目录（含 AGENT.md），**Then** 数秒内 `GET /api/v1/agents` 出现 `daily-digest`，全程无重启无 API 调用。
2. **Given** 拷入进行中（目录先建、AGENT.md 后落盘），**When** 监听器先收到目录创建事件，**Then** 首次注册失败记 WARN 跳过、监听器存活，后续事件（子目录变更）触发重注册收敛——最终 Agent 上线。
3. **Given** 拷入的目录 AGENT.md 非法，**When** 监听器拾取，**Then** 记 WARN 跳过，监听器继续服务其他目录事件（单个坏目录不拖垮监听）。
4. **Given** Agent 已注册，**When** 直接删除其目录，**Then** 数秒内列表消失（注销定时 + 移出索引），`.yokeos/archive/` 无新归档（手工删不归档）。
5. **Given** 进程启动，**When** 启动扫描执行，**Then** 既有扫描链路完成全量登记，监听器不重复登记已注册 Agent（定时不重复排）。
6. **Given** Agent 已注册且带定时（cron A），**When** 其目录被覆写修改、监听器拾取修改事件重注册（新定义 cron B），**Then** 注册段先注销旧定时再注册——cron A 不再触发、cron B 到点触发，两者不并跑（防重收口，FR-016）。

### User Story 4 - 工作区只读浏览，防目录穿越 (Priority: P2)

管理台「工作区」页像文件浏览器一样钻进工作区：`GET /api/v1/workspace/tree` 列 `.yokeos/agents/`（每个 Agent 目录可展开看 AGENT.md、scripts/、REFERENCE.md）与 `.yokeos/archive/` 两支目录树；`GET /api/v1/workspace/file?path=` 只读返回文本内容。任何路径先解析归一再校验落在 `.yokeos/` 内，越界一律 400——`../../etc/passwd`、绝对路径、符号链接变形全被拦住。

**Why this priority**: 管理台「真能管」的观察侧配套（看 Agent 目录实貌、看归档定义）；防穿越是本组端点唯一的安全要点，不可让步。

**Independent Test**: tree 返回 agents/archive 结构断言；`file?path=` 传 `../` 变形与绝对路径两形态断言 400。

**Acceptance Scenarios**:

1. **Given** 工作区有 2 个 Agent 目录与 1 个归档，**When** GET tree，**Then** 返回 agents 支（2 个可展开目录）与 archive 支（1 个），节点含名称/相对路径/目录或文件类型。
2. **Given** Agent 目录含 AGENT.md 与 scripts/x.py，**When** GET tree，**Then** 该 Agent 节点可展开列出这两个文件。
3. **Given** 请求 `file?path=agents/<name>/AGENT.md`，**When** GET file，**Then** 200 返回该文件文本内容。
4. **Given** 请求 `file?path=../../etc/passwd` 或 `file?path=/etc/passwd`，**When** GET file，**Then** 400（normalize 后越出 `.yokeos/` 根），不返回任何文件内容。
5. **Given** 请求的文件不存在，**When** GET file，**Then** 404。

### User Story 5 - 管理台从「能看」升级「真能管」 (Priority: P2)

管理台新增两页（复用既有前端工程与官网设计 token）：**Agent 管理页**——列表（走 GET /api/v1/agents）+「一句话新建」流程（输入一句话 → generate 拿草稿 → textarea 可编辑预览（重点提示改 cron / tools 敏感项）+ name 输入 → create 创建）+ 每行查看 / 编辑（回填 AGENT.md 全文 → PUT）/ 删除（删前二次确认）；**工作区页**——左树右文只读。错误提示显示后端 message（含 503 配置缺失的配置方法）。

**Why this priority**: 需求 §11 行 30 可演示成果的另一半「管理台 Agent 管理页与工作区页上线」；无前端则动态管理只对 API 用户存在。

**Independent Test**：serve 起来后浏览器走完「一句话新建 → 预览改 → 创建 → 列表见 → 编辑 → 删除」全流程，工作区页能浏览 Agent 目录与文件。

**Acceptance Scenarios**:

1. **Given** 管理台已登录打开（内网无认证），**When** 进入 Agent 管理页，**Then** 列表展示全部 Agent（name/description/provider/model/是否有定时）。
2. **Given** 一句话输入并提交，**When** 草稿返回，**Then** 预览区可编辑（含提示改 cron / tools），填 name 后创建成功列表即时出现。
3. **Given** 某行点删除，**When** 确认弹窗出现，**Then** 二次确认后才发 DELETE；取消则不发。
4. **Given** generate 返回 503（配置缺失），**When** 页面展示，**Then** 错误区显示后端 message 含配置方法提示。

### Edge Cases

- create 请求的 name 不在白名单字符集内（如 `../evil`、`a b`、`中文名`、超 64 字符）→ 400 拒绝且零写入，不落盘到预期外位置。
- PUT 覆写时新定义非法 → 400 且**旧定义不被破坏**（校验失败不落盘）。
- PUT 目标 Agent 不存在 → 404。
- Watcher 监听目录被整体删除后重建 → 监听器按平台语义尽力恢复或安静退出记日志，不抛异常拖垮进程。
- generate 时 Provider 故障（key 错误等）→ 503（Provider 侧原文消息），不吞不换码。
- llm_calls 审计在 generate 失败调用时同样落账（success=false + 失败原因）——审计 day one 纪律。
- 归档目录 `.yokeos/archive/` 不存在（首次删除）→ 归档动作按需创建，不要求 init 预建。
- workspace file 请求的是二进制文件 → 400 可读原因（本组端点只服务文本）。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST 提供 `POST /api/v1/agents/generate`：收一句话，经一次 LLM 调用产出规范 AGENT.md 草稿（frontmatter 含 name/description/identity/provider/tools/settings，有定时需求加 schedules，provider.name 用指定 provider 名），原样返回预览；MUST NOT 落盘、注册或解析成运行时对象。
- **FR-002**: generate 的 LLM 产出 MUST 先剥 Markdown 代码围栏再用既有定义解析校验；非法产出返回 400 与可读原因。
- **FR-003**: generate 动作 MUST 落 llm_calls 审计，sessionId 前缀 `agent-generation`。
- **FR-004**: 生成用 Provider/Model MUST 走独立配置键 `yokeos.agent-generation.provider`（指向已注册 provider name）与可选 `yokeos.agent-generation.model`（缺省用该 provider 默认模型）；未配置时调用端点 MUST 返回 503 与配置方法提示、MUST NOT 静默回退任何 provider；该键缺失 MUST NOT 阻断进程启动。
- **FR-005**: `POST /api/v1/agents` MUST 收 `{name, agentMarkdown}`：name 冲突第一步就拒（400、零写入）；写目录（`.yokeos/agents/<name>/AGENT.md`）→ 校验派生（非法 400）→ 注册；有 schedules 则注册定时；注册失败 MUST 回滚已写目录；200 后不重启即出现在列表。
- **FR-006**: create 的 name MUST 匹配白名单 `[a-zA-Z0-9][a-zA-Z0-9_-]*` 且长度 ≤64（首字符字母数字，可含连字符下划线）；不匹配（含 `/`、`\`、`..`、空格、中文、点等）MUST 400 拒绝且零写入。
- **FR-007**: `GET /api/v1/agents` MUST 列出全部 Agent；`GET /api/v1/agents/{name}` MUST 返回单个定义含 AGENT.md 全文；不存在 404。
- **FR-008**: `PUT /api/v1/agents/{name}` MUST 收 AGENT.md 全文覆写：校验失败 400 不落盘（旧定义不破坏）；覆写即时生效；schedules 变更 MUST 先注销旧定时句柄再注册新的；更新 MUST NOT 依赖文件监听（显式重注册）。
- **FR-009**: `DELETE /api/v1/agents/{name}` MUST 按「注销定时 → 移出索引 → 目录归档」顺序执行；整个 Agent 目录 MUST 移入 `.yokeos/archive/` 不物理删；归档重名 MUST 加时间戳后缀不覆盖历史；不存在 404。
- **FR-010**: WorkspaceWatcher（JDK WatchService，Spring 管理执行器承载的守护线程）MUST 实时监听 `.yokeos/agents/` 目录级新增/修改/删除；新增/修改走与 API 创建同一段注册代码；删除 → 注销定时 + 移出索引不归档；单个坏目录 MUST 记 WARN 跳过不拖垮监听；中断 MUST 恢复中断位安静退出；启动全量扫描 MUST 仍走既有扫描链路不重复登记。
- **FR-011**: API create 写完目录后调的注册方法、监听事件调的注册方法、启动扫描消费的运行时原语 MUST 同源（一段注册代码）。
- **FR-016**: 共用注册段 MUST 幂等防重：注册前注册表已有同名 Agent 时，MUST 先注销该 Agent 全部旧定时句柄再注册（否则修改事件重注册会让旧 cron 与新 cron 并跑且旧句柄失控）——防重收口在编排者一处，全部录入路径生效。
- **FR-012**: `GET /api/v1/workspace/tree` MUST 返回 `.yokeos/agents/` 与 `.yokeos/archive/` 两支目录树（Agent 目录可展开列其内文件）。
- **FR-013**: `GET /api/v1/workspace/file?path=` MUST 只读返回文本内容，且 MUST 防目录穿越（resolve 后 normalize 再校验落在 `.yokeos/` 内，越界 400）；文件不存在 404；MUST NOT 提供任何写端点。
- **FR-014**: 错误码 MUST 沿用既有口径：参数非法/已存在/LLM 产出非法 → 400；资源不存在 → 404；生成配置缺失与 Provider 故障 → 503；统一 ApiResponse 信封，不发明新状态码。
- **FR-015**: 管理台 MUST 新增 Agent 管理页（列表 + 一句话新建 → 预览可改 → 创建 + 查看/编辑/删除删前确认，走同一组 API）与工作区页（左树右文只读），复用既有前端工程与官网设计 token。

### Key Entities

- **AgentLifecycleService（编排者）**: 三录入（API create / 监听事件）共用的注册段 + create/update/delete/generate 编排；失败回滚与时序保证在这里。
- **AgentStore（目录管家）**: Agent 目录的写（AGENT.md 落盘）、删（create 回滚用物理删）、归档（移 `.yokeos/archive/` 重名加时间戳）三个文件操作。
- **WorkspaceWatcher（监听器）**: `.yokeos/agents/` 的 WatchService 守护线程循环；事件 → 注册/注销；坏目录容错。
- **归档目录 `.yokeos/archive/`**: 删除 Agent 的定义归档地（不物理删）；按需创建。
- **AgentView（视图值对象）**: name/description/provider/model/tools/hasSchedules + agentMarkdown 全文。
- **FileNode（树节点值对象）**: name/path/type(dir|file)/children。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: create 返回 200 后不重启，Agent 在列表端点立即可见（免重启生效，机器可证）。
- **SC-002**: serve 运行中手工拷入 Agent 目录，10 秒内在列表出现（监听实时性上限，集成测试轮询断言）。
- **SC-003**: 一句话生成的草稿经创建端点 100% 可落成可用 Agent（generate→create 闭环衔接，真模型冒烟验证）。
- **SC-004**: 删除后 Agent 定义可在归档目录找回、历史审计记录仍可查（可追溯性）。
- **SC-005**: 目录穿越攻击（`../` 变形与绝对路径）100% 被 400 拦截、零文件内容泄漏。
- **SC-006**: 管理台可走完「一句话新建 → 预览改 → 创建 → 编辑 → 删除」全流程无命令行介入。
- **SC-007**: 既有 19 端点中 11 个（26 节交付）与 invoke 行为零回退（全量测试绿）。

## Assumptions

- 前序交付物按 29 节验收报告在位：`AgentLoader.deriveProfile`（校验失败 IllegalArgumentException）、`ProfileRegistry.register/remove/exists`、`AgentScheduler.registerProfile/unregisterProfile`、`ProviderService.chat(sessionId, profile, request)`。
- `GlobalExceptionHandler` 既有映射（IllegalArgumentException→400 / ResourceNotFoundException→404 / IllegalStateException→503）覆盖本节全部错误码，零扩展。
- 生成配置键走 Spring Environment 绑定（非密钥、不含 `${ENV}` 占位）；`yokeos init` 不加 `.yokeos/archive/`（归档按需建）。
- 带附属资源的复杂 Agent 走手工丢目录路径（JSON create 只写 AGENT.md）——拍板②。
- 参照实现的窗口内演化形态（脚手架 create、generate-files、per-agent 记忆、固定会话、文件可编辑端点）不采——按技术方案 §7.2 十九端点原始设计口径（拍板①）。
- 无新增 Maven 依赖（WatchService 为 JDK 内置）；零新表（generate 落既有 llm_calls）。
- 管理台两页复用 `.claude/skills/yokeos-admin-ui/` 与既有前端工程（yokeos-web/src/main/frontend）。

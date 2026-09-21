# Feature Specification: Web Service 与管理台第一版——对外门面（第26节）

## Clarifications

### Session 2026-09-21

- Q: 会话归档后，同一三元组（channel+user+agent）再次创建会话时应该发生什么？ → A: 归档是标记不是终结——同三元组幂等返回原会话（历史保留、状态仍 archived），发消息不查状态，列表按 status 过滤；「终结后重开」若未来有真实需求走显式 unarchive 端点（扩展阶段），不做隐式复活（实现零改造，与 18 节幂等契约字面一致）。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 业务系统会话保持：建会话、连续对话、查历史、归档 (Priority: P1)

业务系统想跟某个 Agent 连续对话：先调创建会话端点拿到会话标识，随后多次向该会话发消息（每次都触发完整的 ReAct 循环——思考、调工具、续推），随时查历史回顾对话，结束时归档。发消息走与 CLI 完全相同的编排入口，会话与审计记录落在与 CLI 同一份存储里。

**Why this priority**: 会话保持是业务系统集成 Agent 的第一条常用路（技 §7.6）；「对外门面」存在的前提就是这条链路通——通了它，六个核心能力才算真正对外可用。

**Independent Test**: 用 HTTP 客户端（curl）完成 建会话 → 发消息（真 key 真模型）→ 查历史 → 归档 全链路，验证回复非空、历史完整、审计两表有账。

**Acceptance Scenarios**:

1. **Given** 已配置可用 Agent，**When** POST /api/v1/sessions（body 指定 profile），**Then** 返回统一信封内含 sessionId；同一三元组（channel=web、user、agent）重复创建幂等返回同一条。
2. **Given** 会话已存在，**When** POST /api/v1/sessions/{id}/messages（content 非空 ≤32KB），**Then** 编排入口恰好被调用一次并返回最终回复；消息为空或超 32KB 时 400。
3. **Given** 会话已存在且发过多条消息，**When** GET /api/v1/sessions/{id}，**Then** 返回最多最近 100 条历史。
4. **Given** 会话不存在，**When** 发消息 / 查历史 / 归档，**Then** 一律 404，绝不误报其他状态码。
5. **Given** 会话存在，**When** DELETE /api/v1/sessions/{id}，**Then** 归档成功（状态置 archived，列表可按 status 过滤）；再次以同三元组创建幂等返回同一会话（历史保留、状态不变），发消息不查状态——归档是标记不是终结。

### User Story 2 - 一次性无状态调用：invoke 跑完即返 (Priority: P2)

业务系统有一个 stateless 短任务：直接调 POST /api/v1/agents/{name}/invoke，带上问题，Agent 跑完一次完整循环后返回结果。不建会话、不留上下文——连续两次 invoke 互不携带历史。

**Why this priority**: 同步 invoke 是最常用的单次集成模式（技 §7.6「最常用」）；无状态语义保证调用方可安全并发、无副作用累积。

**Independent Test**: 对同一 Agent 连续 invoke 两次相同或不同问题，验证两次都得到回复、第二次的对话历史不含第一次的消息；对不存在的 Agent 名 invoke 得 404。

**Acceptance Scenarios**:

1. **Given** Agent 已加载，**When** POST /api/v1/agents/{name}/invoke，**Then** 返回该次调用的最终回复，审计两表有账。
2. **Given** Agent 名不存在，**When** invoke，**Then** 404（先查注册表再转交，不误报 503）。
3. **Given** 同一 Agent 已被 invoke 过一次，**When** 第二次 invoke，**Then** 第二次调用不携带第一次的任何历史消息。
4. **Given** 消息为空或超 32KB，**When** invoke，**Then** 400。

### User Story 3 - 信息查询与系统状态：profiles / memory / tools / health / info (Priority: P3)

业务系统或运维脚本探测实例状态：列出已加载的运行配置（Profile 投影）、读长期记忆全文、列可用 Tool、健康检查、查运行信息与 Provider 名单。全部只读。

**Why this priority**: 这五个只读端点是管理台的数据源、也是集成方对接前的探测面；单独可用但价值依附于前两个 story。

**Independent Test**: 依次请求五个端点，验证各自返回统一信封与正确数据形态（profiles 投影字段、memory 全文、tools 清单、health ok、info 的 providers 去重排序）。

**Acceptance Scenarios**:

1. **Given** 实例已加载若干 Agent，**When** GET /api/v1/profiles，**Then** 返回全部 Profile 的可展示投影（名称、描述、provider 名、model、工具清单）。
2. **Given** 长期记忆有内容，**When** GET /api/v1/memory，**Then** 返回记忆全文（含核心与归档两分区原貌）。
3. **Given** 注册表含内置与 MCP 工具，**When** GET /api/v1/tools，**Then** 全量列出名称与描述。
4. **Given** 实例运行中，**When** GET /api/v1/health，**Then** 返回 ok；**When** GET /api/v1/info，**Then** 返回运行信息 + 已加载 Profile 引用到的 provider 名单（去重排序，不做真实连通探活）。

### User Story 4 - 运营方只读管理台：/admin 五页观察 (Priority: P4)

运营方不碰命令行：浏览器打开 http://localhost:8080/admin，左侧导航五项（会话、Agent（Profile）、Tool、长期记忆、系统状态），右侧内容区渲染真实数据；界面零写入口，直接刷新任意子路由不 404。

**Why this priority**: 管理台是本节第二交付物与演示口径（需求 §11 行 26「管理台只读观察五页上线」）；它没有独立后端、只调同一组只读端点，顺带验证 API 的完备性。

**Independent Test**: 打开 /admin 逐页查看五页渲染真实数据；审查页面无任何新建/编辑/删除控件；刷新 /admin/某子路由 验证不 404。

**Acceptance Scenarios**:

1. **Given** serve 已启动且工作区有数据，**When** 访问 /admin，**Then** 五页均可渲染真实数据，空数据/加载中/错误三态有明确占位。
2. **Given** 管理台为单页应用，**When** 在子路由页按 F5 刷新，**Then** 服务端回落到入口页正常打开（不 404）。
3. **Given** 请求打错 API 路径（/api/v1/不存在），**When** 查看响应，**Then** 得到 JSON 格式 404，绝不因前端回落机制收到 HTML。
4. **Given** 管理台界面，**When** 逐页审查，**Then** 0 个写操作入口（无按钮/表单提交指向写端点）。

### Edge Cases

- 消息为空或超 32KB → 400（防呆上限，不是治理）。
- 会话 / Agent 不存在 → 404 且信封格式与成功响应一致。
- invoke 的 404/503 语义：Agent 不存在必须 404（资源缺失），只有引擎/Provider 故障才 503——不能把「名字找不到」误报成「服务不可用」。
- SPA 子路由刷新 → 服务端回落入口页；`/api/v1/**` 永不受回落影响（API 404 永远是 JSON）。
- 前端重建后浏览器持旧入口页 → 入口页必须每次向服务端校验（no-cache），否则旧壳指向已删除的旧资源、页面时好时坏；带内容 hash 的静态资源则长缓存 365 天。
- 内部异常（如数据库连接串）→ 500/503 响应体绝不包含内部细节，细节只进服务端日志。
- CLI 与 Web 是不同 channel → 同一用户对同一 Agent 在两入口各自成会话；任一入口产生的会话对另一入口可查（同一存储）。
- invoke 每次一次性会话 → sessions 数据随调用量增长属预期（审计语义保留），不做清理（扩展阶段事项）。
- 构建机无法下载 Node / npm 依赖 → 提供跳过参数保住纯 Java 构建，但本节验收必须至少一次不跳过的全量构建。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001（REST 服务随 serve 启动）**: `yokeos serve` 启动后 8080 端口可访问本节全部 11 个端点，请求处理跑在虚拟线程上（同步阻塞模型，不引入异步栈）；端点按 6 个 Controller 分组：会话管理 5、Agent 调用 1、信息查询 3、系统状态 2，统一前缀 `/api/v1`。
- **FR-002（会话管理 5 端点）**: POST /api/v1/sessions 创建（channel 固定 `web`、userId 缺省 `default`，幂等建会话返回 sessionId）；POST /api/v1/sessions/{id}/messages 发消息（走与 `yokeos chat` 完全相同的编排入口）；GET /api/v1/sessions 列最近 ≤100 条会话摘要（支持 `?status=` 过滤——第一阶段第 19 个端点，上游赢补位，管理台会话页数据源）；GET /api/v1/sessions/{id} 查历史（最多返回最近 100 条）；DELETE /api/v1/sessions/{id} 归档（未命中 404；归档是标记不是终结——同三元组再创建幂等返回原会话，发消息不查状态）。
- **FR-003（消息大小限制）**: 单条消息最大 32KB；空或超出立即 400。
- **FR-004（无状态调用）**: POST /api/v1/agents/{name}/invoke——Agent 不存在先查注册表给 404；每次调用生成一次性会话（channel `invoke`、user 每次唯一），连续两次 invoke 第二次不携带第一次历史；其余 Agent 写侧端点（generate/CRUD 共 6 个）归 29/30 节，本节不实现。
- **FR-005（信息查询 3 端点）**: GET /api/v1/profiles（运行配置投影）、GET /api/v1/memory（长期记忆全文，核心与归档两分区原貌）、GET /api/v1/tools（可用 Tool 清单含名称与描述）。
- **FR-006（系统状态 2 端点）**: GET /api/v1/health（健康检查回 ok）；GET /api/v1/info（运行信息 + Provider 名单，已配置口径：已加载 Profile 引用到的 provider 名去重排序，不做 live 探活）。
- **FR-007（统一异常出口与错误码口径）**: 所有异常经全局异常处理器转统一信封：400 参数错误、404 资源不存在、500 内部错误（响应体绝不泄漏内部细节）、503 Provider 故障、504 超时口径占位（真实超时由 provider 层承载，同步模型不造硬中断）；响应统一信封 `{code, message, data, timestamp}`，成功与错误共用。
- **FR-008（API 文档）**: OpenAPI 文档自动生成，Swagger UI 可访问，覆盖本节全部端点。
- **FR-009（管理台只读五页）**: `/admin` 单页应用，左侧导航五项（会话、Agent（Profile）、Tool、长期记忆、系统状态）+ 内容区；数据全部来自只读端点，界面零写入口；空数据/加载中/错误三态占位；响应式窄屏收导航。
- **FR-010（SPA 托管与回落）**: 构建产物由服务端托在 `/admin`，与 REST 同端口同进程；`/admin/**` 未命中真实文件的路径回落入口页（SPA 刷新不 404），`/api/v1/**` 不受回落影响（API 404 永远是 JSON）；带内容 hash 的静态资源 immutable 缓存 365 天，入口页 no-cache。
- **FR-011（构建串联）**: 前端构建绑进 Maven 构建链（frontend-maven-plugin，Node v20.18.0，generate-resources 阶段），一条 `mvn package` 命令产出含管理台的完整 fat JAR；提供 `-Dfrontend.skip=true` 跳过逃生门；`node_modules` 与构建产物进 `.gitignore`。
- **FR-012（CORS）**: 第一阶段允许所有源（调试便利），扩展阶段收敛为白名单。

**明确不做（边界）**: Agent 写侧 6 端点（generate/CRUD）与 Agent 管理页、工作区 2 端点与工作区页（29/30 节正题，本节刻意留白）；认证机制（无认证假设内网）、SSE 流式、WebSocket、RBAC、限流；/info live 探活；定时任务管理与白名单管理端点（ADR 0008 扩展位）；Profile 增删改与 Memory 写入端点（扩展清单）；管理台独立部署（nginx/CDN——开发态已按契约分离，扩展阶段选项）；60 秒硬中断超时（同步 + 虚拟线程不造，真实超时由 provider 层承载）。

### Key Entities

| 实体 | 说明 |
|------|------|
| 统一信封 | 所有响应（成功与错误）共用的结构：code / message / data / timestamp |
| 会话 | 以三元组（channel+user+agent）唯一标识；本节引入两个新 channel 取值：`web`（会话保持）与 `invoke`（一次性调用） |
| 会话摘要 | 列表视图值对象：会话标识、Agent 名、channel、user、状态（active/archived）、最近活跃时间——不携带消息体 |
| Provider 名单 | 已配置口径的状态视图：已加载 Profile 引用到的 provider 名，去重排序，不含真实连通结果 |
| 管理台 | 托在 `/admin` 的只读单页应用：五页各调一个只读端点，无独立后端 |
| 错误码口径 | 400 参数 / 404 资源不存在 / 500 内部（不泄漏）/ 503 Provider 故障 / 504 超时占位 |

## Success Criteria *(mandatory)*

### Measurable Outcomes

1. 本节 11 个端点 100% 按契约可用：任一请求（成功或失败）都返回统一信封；参数/资源类错误 100% 命中约定状态码（400/404），零裸响应、零错误码漂移。
2. 正常发消息与 invoke 100% 走与 CLI 相同的编排入口且恰好一次（Controller 层零业务逻辑夹带）；两入口产生的会话与审计记录 100% 落在同一存储，任一入口可查。
3. 连续两次 invoke，第二次携带第一次历史的消息数为 0（无状态语义）。
4. 内部异常发生时，响应体泄漏内部细节（连接串、路径、堆栈）的次数为 0。
5. 管理台五页 100% 渲染真实数据、写操作入口数为 0；SPA 子路由刷新 404 次数为 0；API 未命中路径返回 HTML 的次数为 0（永远 JSON）。
6. 一条命令（不带前端跳过参数）完成含管理台的完整构建，产物开箱即含五页。
7. 自动化验收全绿（验收 harness 承载）；人工项：真 key 发消息全链路且审计两表有账、断 Provider 得 503、并发冒烟虚拟线程无异常。

## Assumptions

- `yokeos serve` 命令、运行时装配、虚拟线程与 8080 端口已由 18/25 节交付，本节只做 REST 接线。
- `sessions` 表已含 status/archived_at/last_active_at 列（18 节建表即有），本节无表结构变更；会话管理契约补「最近会话列表」「归档」两方法与「会话摘要」值对象是本节声明的改造点（参照实现同款先例）。
- 长期记忆门面补「读全文」方法：markdown 档返回原文（两分区原貌），sqlite 档按注入同口径拼装，mem0 档与上下文构建同源。
- 无认证假设内网部署；CORS 全开是第一阶段调试便利，扩展阶段收敛。
- 管理台前端与官网同栈（Vue 3 + Vite），视觉风格钉官网设计 token，生成与复用经项目内 skill（yokeos-admin-ui）；构建期由 frontend-maven-plugin 下载 Node v20.18.0，CI/新机首跑耗时属预期。
- invoke 一次性会话使 sessions 数据随调用量增长，属审计语义保留的预期行为，清理策略列扩展阶段。
- 504/503 等故障状态的真实触发依赖 provider 层超时与错误配置（17 节已收紧）；本节不构造硬中断机制。

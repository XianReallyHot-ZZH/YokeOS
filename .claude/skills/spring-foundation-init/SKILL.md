---
name: spring-foundation-init
description: 一次性搭建 JDK 21 + Spring Boot 3.x Maven 多模块企业级单体的工程地基——模块骨架、统一响应体与全局异常、结构化日志、Actuator + Prometheus、springdoc OpenAPI、格式与编码规约门禁（Spotless + 阿里 P3C + Checkstyle）、安全扫描（SpotBugs + Find Security Bugs + PMD + OWASP Dependency-Check）、CI 与 pre-commit、每模块冒烟测试。触发：初始化项目、搭工程骨架、起脚手架、加开发规范、加日志监控、加代码安全检查、加 CI。业务能力开发、可视化编排、前端资产都不归本 skill。
---

# Spring 多模块单体工程地基（spring-foundation-init）

把「工程地基」一次性、标准化地装好——业务逻辑一行不写。本 skill 是**流程与配置骨架**，不含任何具体项目的专属事实：版本号、模块清单、契约类永远来自项目自身的文档（覆盖层），运行时读取。

## 什么时候用

- 新仓库起工程骨架，或空仓库第一次搭多模块单体时
- 给已有项目**补齐单项**：只加日志 / 只加监控 / 只加门禁 / 只加 CI 时（按步骤选取执行）
- 任何 JDK 21 + Spring Boot 3.x 的企业级单体，想一次到位装好地基时

## 不做什么（边界）

- 不实现任何业务能力——那是业务模块，走项目自己的规格流程逐个开发
- 不硬编码任何密钥 / token / API key——一律 `${ENV_VAR}` 占位
- **只新增，不替换**——重跑安全：已存在的业务代码与配置不动，只新增基础设施
- 项目专属事实（版本、模块命名、契约类、建表清单）不写死在本 skill——见第 0 步覆盖层

## 第 0 步：项目事实覆盖层

**先读后动，事实外置**。读项目的权威文档（通常是指南型 CLAUDE.md 与技术方案），按以下五类确认骨架逐类提取；读到的向用户**复述确认**，缺失的才询问，与本 skill 或项目文档矛盾的停下报告：

1. **运行时**：JDK 版本、parent/BOM、各依赖版本号。事实源没有的组件 → 实施时最新稳定版，**先锁定再开发**；不引入 milestone 仓库（除非项目明确要求）
2. **HTTP 层**：Web 栈（默认 Spring MVC + 虚拟线程）、端口、API 前缀约定
3. **持久化**：数据源与方言、建表策略（演进**禁 `ddl-auto=update`**，手工 DDL）、是否有 day-one 必须预留的建表脚本（如审计表）
4. **契约类**：项目技术规格中约定的核心接口/值对象（逐字照规格；规格没写的**不发明**，询问）
5. **质量门禁**：格式/规约/安全扫描的组合与严格度、CI 平台与触发分支

LLM 相关 starter 常带 eager 自动装配：识别后在配置层显式 exclude（写明这是哪条设计决策的执法点），否则启动即索要 API key。

---

## 步骤（按顺序执行；每步完成后即可提交，粒度按项目惯例）

### 1. Maven 多模块骨架

父 `pom.xml`（packaging=pom）+ 按覆盖层模块清单建子模块。指定一个**启动模块**（聚合其余模块，打 fat JAR）。模块间依赖方向遵守项目规格；跨模块契约放核心模块，下游实现，禁止循环依赖。

### 2. 版本管理（父 pom `dependencyManagement` / `pluginManagement`）

Spring Boot BOM（作 parent）+ 项目规格指定的其余 BOM/依赖统一在此管理；内部模块版本引用 `${project.version}`。

### 3. 契约类（核心模块）

按覆盖层第 4 类逐字落地项目技术规格约定的接口与值对象。形态惯例：接口只含行为方法；结果对象用 `record` + `ok` / `error` 静态工厂。规格没约定契约类的项目，此步跳过。

### 4. 统一响应体 + 全局异常（Web 模块）

- `ApiResponse<T>`：`code` / `message` / `data` / `timestamp` 四字段，成功与错误共用信封，`ok` / `error` 静态工厂
- `GlobalExceptionHandler`（`@RestControllerAdvice`）：`IllegalArgumentException`→400、`NoResourceFoundException`→404、下游不可用→503、兜底→500；对外消息做 sanitize，服务端记完整日志
- REST 约定：资源名词复数、`/api/v1` 前缀、合理状态码

### 5. 日志（结构化，双 profile）

`logback-spring.xml`：开发 profile 彩色控制台 pattern（含 `traceId` MDC 占位）；生产 profile 单行 JSON（`LogstashEncoder`，`customFields` 带应用名）。统一 SLF4J，**禁 `System.out`**。依赖：`logstash-logback-encoder`。

### 6. 监控 + HTTP + OpenAPI

```yaml
spring:
  threads.virtual.enabled: true        # JDK 21 虚拟线程
management:
  endpoints.web.exposure.include: health,info,prometheus,metrics
  endpoint.health.probes.enabled: true
  metrics.tags.application: <app-name>
```

依赖：`spring-boot-starter-web`、`spring-boot-starter-actuator`、`micrometer-registry-prometheus`、`springdoc-openapi-starter-webmvc-ui`（`/swagger-ui.html` + `/v3/api-docs`）。

### 7. 建表脚本预留位（如覆盖层要求）

`src/main/resources/db/` 下放 day-one 表的 DDL 骨架（只放脚本，不在地基接业务写入）。数据源指向项目指定存储。

### 8. 配置加载纪律

`${ENV_VAR}` 占位从环境变量解析；启动时校验必填项与格式，缺失或非法给**清晰报错，不静默失败**。加载器放命令行/启动模块，单列一个类，不散落各处。

### 9. 开发规范门禁（两层互补 + 兜底）

**分工原则：Google 管「长什么样」（格式，能自动修），阿里管「怎么写才对」（规约），Checkstyle 兜底。风格冲突以 google-java-format 为准——因为它能自动修，省争论。**

- Spotless + google-java-format（GOOGLE 风格，`removeUnusedImports` / `importOrder`，`check` 挂 verify）
- PMD + 阿里 P3C rulesets（命名/并发/异常/集合/OOP 等）
- Checkstyle `google_checks.xml`（validate 阶段，仅 error 级）+ 根目录 `.editorconfig`

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-pmd-plugin</artifactId>
  <configuration>
    <!-- P3C 基于 PMD 6.x：语法级解析按 17 配置，代码避免 18+ 新语法
         （record patterns / pattern switch；record 与 sealed 不受影响） -->
    <targetJdk>17</targetJdk>
    <rulesets>
      <ruleset>rulesets/java/ali-naming.xml</ruleset>
      <ruleset>rulesets/java/ali-concurrent.xml</ruleset>
      <ruleset>rulesets/java/ali-exception.xml</ruleset>
      <!-- 其余 ali-* rulesets 按需补齐 -->
    </rulesets>
  </configuration>
  <dependencies>
    <!-- PMD 6.x 自带 ASM 解析不了新字节码，升到 9.7+ -->
    <dependency><groupId>org.ow2.asm</groupId><artifactId>asm</artifactId><version>9.7</version></dependency>
    <dependency><groupId>com.alibaba.p3c</groupId><artifactId>p3c-pmd</artifactId><version>${p3c.version}</version></dependency>
  </dependencies>
</plugin>
```

### 10. 安全扫描

- SpotBugs（effort Max / threshold Low）+ **Find Security Bugs** 插件（OWASP Top 10 覆盖）
- **OWASP Dependency-Check**：`failBuildOnCVSS 8`；**本地默认 skip**（NVD 下载慢），CI 独立 job 用 `-Dowasp.skip=false` 开启，`NVD_API_KEY` 走 secret（缺失也能跑，只是慢）

### 11. CI + pre-commit

- pre-commit（本地）：`spotless:apply` 自动格式化 → `spotless:check` 把关，两段式，`.java` 触发
- CI 双 job：**job1** `mvn -B clean verify`（全门禁主线）；**job2** 独立 dependency-check（`-DskipTests -Dowasp.skip=false`）。任一失败即红，禁止合并。触发分支按项目惯例（主分支 + 工作分支模式）

### 12. 冒烟测试

每个模块至少一个可运行测试（骨架期允许最小断言）；启动模块放 context 加载测试。保证 `verify` 真的执行测试，不是空转。

---

## 坑与对策表（跨项目有效；执行中发现新坑，当场回写本表并随任务提交）

| 坑 | 对策 |
|----|------|
| google-java-format 在 JDK 17+ 反射 javac 被拒 | `.mvn/jvm.config` 加 `--add-exports jdk.compiler/...` 五件（api/file/parser/tree/util） |
| P3C 只兼容 PMD 6.x（语法级最高 Java 19） | `targetJdk 17` + 插件依赖升 ASM ≥9.7；代码避免 record patterns / pattern switch |
| LLM starter eager 自动装配启动索要 API key | `spring.autoconfigure.exclude` 显式排除，注释写明是哪条决策的执法点 |
| starter 传递拉进注册中心客户端，健康指示器永远 DOWN | `management.health.<client>.enabled: false` 显式关闭 |
| fat JAR mainClass 指到应用类，CLI 进不了 jar | `spring-boot-maven-plugin` 的 `mainClass` 指向**真实用户入口**（CLI 主类） |
| 表结构演进依赖 `ddl-auto=update` | SQLite 等嵌入式库 ALTER 支持弱；手工 DDL 脚本 |
| OWASP 拖垮本地构建 | 本地 skip + CI 独立 job 分离（快慢分离） |
| skill 与实际实现漂移 | 执行后回写：本表 + 配置片段以实际跑通的为准 |

---

## 验收（红绿 ×2，逐项贴证据）

- [ ] `mvn clean verify` 本地全绿
- [ ] fat JAR 可运行：`java -jar <boot>/target/*.jar` 打印版本/帮助
- [ ] 服务起得来：`/actuator/health` UP、`/actuator/prometheus` **有指标**、`/swagger-ui.html` 可打开
- [ ] **格式门禁红**：故意不规范 → spotless/checkstyle 报错
- [ ] **安全门禁红**：故意引一个有已知 CVE 的旧依赖 → dependency-check 报警
- [ ] 恢复后全绿

## Definition of Done

- [ ] 覆盖层模块清单全部建好，`mvn clean package` 出 fat JAR
- [ ] 结构化日志双 profile，无 `System.out`
- [ ] 虚拟线程开启；actuator 三端点可访问且有指标
- [ ] `ApiResponse` + `GlobalExceptionHandler` 就位，swagger 可开
- [ ] Spotless / P3C / Checkstyle / `.editorconfig` 全部生效
- [ ] SpotBugs + FindSecBugs + PMD + OWASP 接入 `mvn verify`
- [ ] pre-commit + CI 跑通，任一失败阻断
- [ ] 敏感配置全 `${ENV_VAR}` 占位；契约类与项目规格逐字一致
- [ ] 每模块至少一个冒烟测试；（如覆盖层要求）建表脚本预留位就位
- [ ] 门禁红绿 ×2 验证过，新发现的坑已回写坑表

---

## 与项目宪法 / CI / 规格流程的分工

- **本 skill**：把地基「装上」（一次性、可复用、跨模块）
- **项目宪法**：把硬约束「钉死」（让 AI 每次都遵守）
- **CI + pre-commit**：把检查「强制执行」（机器把关，不靠自觉）
- **规格流程**：地基起好后，业务能力按项目规格逐个开发

> 本 skill 给的是流程与配置骨架，不锁死具体版本与插件坐标，以实施时官方文档为准；项目专属事实永远来自项目自身文档（第 0 步覆盖层），执行中学到的坑回写「坑与对策表」。

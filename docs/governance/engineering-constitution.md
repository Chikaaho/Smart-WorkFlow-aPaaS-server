# Smart-WorkFlow 后端工程宪法

> 本文件是 Smart-WorkFlow 后端的**工程宪法**。本目录会话角色为**执行（后端）**，
> 所有代码生成必须遵守本文件与根目录 `system.md`（角色制）。违反任一硬约束的产物视为不合格，需返工。
> 本文件只管「规范与硬约束」，**不记进度**——当前状态只见工作区 `knowledge/current-status.md`。
> 前端工程由相邻的 `Smart-WorkFlow-Web/` executor sublayer 独立治理；后端执行会话不得读取或操作该子层。功能 ID 见 `功能清单.md`。

---

## 0. 项目定位

### 0.0 角色定位：执行层（硬约束）

本仓入口的角色门禁、Executor 权限、任务闭环、终态与重复失败纪律，唯一分别见工作区根 `system.md` §0.2 与 `roles/executor.md`；本工程宪法不复制或改写这些公共规则，只定义后端工程专属约束。

### 0.1 本仓库范围（硬约束）

**本仓库（`Smart-WorkFlow`）只做后端，不碰前端。**

- ❌ **禁止** 读取、修改、构建、运行或分析 `Smart-WorkFlow-Web/` 中的任何前端代码（Vue、TypeScript、Vite 等）
- ❌ **禁止** 执行前端命令（`pnpm`、`npm`、`vite`、`vitest` 等）
- ❌ **禁止** 向前端仓库提交或推送代码
- ✅ 只关注后端代码：Java 21 / Spring Boot 3.4 / MyBatis-Plus / Flowable
- ✅ 只在后端目录下执行命令（`mvn` 系命令）
- ✅ **编译命令必须限制最大内存**：`mvn` 系命令一律带 `MAVEN_OPTS="-Xmx2g"`，上限 2G，禁止无限制内存直接编译

涉及前后端联动时，本仓只实现和验证后端部分；跨仓任务的角色与范围授权只引用根 `system.md` 与 `roles/executor.md`，本文件不另定义。

---

低代码 OA 平台，内嵌 AI Agent 能力的多模块 Java 后端。
模块化单体（modular monolith），`-api`/`-biz` 拆分以支持未来微服务抽取。

**技术栈**：Java 21 · Spring Boot 3.4 · MyBatis-Plus · Flowable ·
dynamic-datasource-spring-boot3-starter · Flyway · jsqlparser · Quartz（单节点）

---

## 1. 模块结构与依赖方向（硬约束）

四层层级，依赖只能自上而下：

```
sw-dependencies (BOM)
  └─ sw-framework  : sw-common, sw-security
       └─ sw-basic : storage, notify, job, iot, knowledge, agent
            └─ sw-biz : system, form, bpm (api/engine/process), openapi
                 └─ sw-bootstrap (启动 + Flyway + 自检)
```

> 注：原 `sw-biz-lowcode` 已整体重命名为 `sw-biz-form`（模块、包名、配置开关、
> 表前缀、AutoConfiguration 全部对齐到 form，不保留 lowcode 残留）。
> 重命名清单见附录 A。

**依赖铁律：**

- **业务模块永不依赖另一模块的 `-biz` 实现。** 只能依赖目标模块的 `-api`。
- `bpm` 依赖 `form`（不可反向）。bpm-process 通过
  `@AutoConfiguration(after = FormAutoConfiguration.class)` 在运行时门控，
  若 `sw.form.enabled` 非 true，fail-fast 抛 `IllegalStateException`。
- 每个 `sw-biz-*` 拆 `-api`（契约/DTO/SPI 接口）与 `-biz`（实现），
  以便未来抽取微服务时最小重构。

**跨模块通信（强制模式）：**

- **无返回值交互** → Spring 事件，从 `-api` 发布。
  事件必须 `@Async` + `@TransactionalEventListener(phase = AFTER_COMMIT)`。
  统一经 `DomainEventPublisher` 薄封装发布，保持机制可替换。
- **有返回值交互** → Facade 接口，定义于 `-api`，实现于 `-biz`，经 Spring 注入调用。
- ❌ **禁止**用全限定类名字符串选择实现（绕过 Spring 容器，破坏
  `@Transactional`/AOP，引入安全风险）。需多实现分发时，用
  `Map<String, Interface>` 以业务语义字符串为 key，或纯接口契约 + Spring 注入。

---

## 2. 模块职责边界（硬约束）

- **system** — 身份/组织/RBAC/字典/参数（共享内核）。**拥有**字典数据
  （dict type / dict item / code / label / 值域），经 `DictFacade`（定义于
  `sw-biz-system-api`）对外暴露。
- **form** — 仅承载**表单与控件库**。不拥有任何业务主数据。
  - 字典**控件**（下拉框：绑定哪个 dict type、单/多选、渲染）归 form；
    字典**数据**归 system。form 的字典控件经 `DictFacade` 消费 system 的字典数据，
    **禁止** form 直接访问 `sys_dict` 表。
- **bpm** — 流程引擎（engine 闭源核心/防腐）+ 流程业务（process）+ 契约（api）。外部数据源执行引擎（详见第 7 节）。
- **openapi** — 开放接口层。

---

## 3. 表命名规则（硬约束）

仅约束**主业务库**自建表。扩展库只做 CRUD、不建表、不拥有 schema，不受约束。

**原则：前缀 = 模块短名，一对一映射。唯一例外是 system 用 `sys_`。**

| 前缀 | 范围 | 模块 |
|------|------|------|
| `sys_` | 身份/组织/RBAC/字典/参数（共享内核） | sw-biz-system |
| `sw_form_` | 表单元数据、动态宽表、控件库 | sw-biz-form |
| `sw_bpm_` | 自建流程业务表、外部数据源执行审计表（含 `sw_bpm_ext_` 外部数据源子域，非独立模块） | sw-bpm |
| `sw_openapi_` | 应用、密钥、调用日志 | sw-biz-openapi |
| `sw_job_` | 任务定义、调度日志 | sw-basic-job |
| `sw_notify_` | 站内信、模板、发送记录 | sw-basic-notify |
| `sw_storage_` | 文件元数据 | sw-basic-storage |
| `sw_iot_` | 设备、产品、属性、上下行记录 | sw-basic-iot |
| `sw_knowledge_` | 知识库、文档、向量元数据 | sw-basic-knowledge |
| `sw_agent_` | 会话、消息、工具调用 | sw-basic-agent |
| `ACT_*` | Flowable 引擎自带表 | 框架自有，原样保留，**不纳入规则** |

**配套：**

- 关联表归单一模块，不跨模块共享 FK（如 `sys_user_role`、`sys_role_menu` 在 system 内）。
- ❌ **禁止自创前缀。** 新表前缀必须落在上表枚举内，否则视为违规。
- 现状 `sys_*` 核心表已就位，新增 system 表一律延续 `sys_`，**不得**改用 `system_`。
- 集群阶段才会引入的 `QRTZ_*` 同属框架自带表，届时原样保留，不纳入规则。

---

## 4. 低代码表单存储模型（硬约束）

### 4.1 存储模型：动态宽表

- 表单提交数据采用**动态宽表**：一个表单 = 一张物理表，**一行 = 一次提交**。
  不用单 JSON 列，不用 EAV。理由：查询/报表/导出/流程取值能力上限最高（原生 SQL + 索引）。
- 动态宽表是**「数据」不是「schema」**，**不归 Flyway 管**（见 6.2 例外）。
  环境迁移靠表单/应用/流程的导入导出功能（后续设计）。

### 4.2 物理表命名

| 用途 | 表名 |
|------|------|
| 主表单动态宽表 | `sw_form_{nanoId}` |
| 表格子表 | `sw_form_table_{nanoId}` |
| 配置表（全表单共用一张） | `sw_form_config`（form_id + table_name 唯一 key + parent_table + 样式 definition jsonb） |
| 快照表（全表单共用一张） | `sw_form_snapshot`（form_id + version + definition jsonb） |

- **nanoId 规则**：首位强制小写字母 `[a-z]`，其余 `[a-z0-9]`，总长 ≤ 12。
  ❌ 不含大写（PG 折叠大小写 → 撞表）、不含 `-`、不以数字/下划线开头。
- ❌ 用户填写的"逻辑表名/字段名"**绝不能直接拼进 DDL**。物理表名由系统生成
  （`sw_form_` 前缀 + 受约束 nanoId）；字段名须过严格白名单
  （`[a-z_][a-z0-9_]*`、长度限、保留字黑名单、不得撞 `sys_`/`sw_`/`ACT_`）。
  这是 SQL 注入红线（执行口径见第 5 节）。

### 4.3 动态宽表系统列（DynamicTableManager.SYSTEM_COLUMNS）

每张动态宽表建表时由 DynamicTableManager 统一注入以下系统列，**不可缺**：

| 列 | 类型 | 语义 |
|---|---|---|
| `id` | VARCHAR(36) | 主键，UUID v4（字符串） |
| `tenant_id` | BIGINT NOT NULL DEFAULT 0 | 租户隔离（裸 SQL 手写过滤，见第 5 节） |
| `deleted` | SMALLINT NOT NULL DEFAULT 0 | 逻辑删除，1=已删 0=未删（裸 SQL 手写） |
| 审计列 | — | create_time / create_by / update_time / update_by |
| `version` | — | 乐观锁（编辑期使用） |
| `parent_record_id` | VARCHAR(36) | **仅子表**，指回父表记录 id，应用层维护，不建 DB 级 FK |

- **最小暴露**：对外查询响应（列表）**不回** `deleted` / `tenant_id` / `version`
  （噪音 + 轻泄漏，无客户端价值）；`id` + 审计列 + 业务列正常回。
  ❗注意：「不投影」≠「不过滤」——`deleted` / `tenant_id` 仍是 WHERE 条件，只是不进 SELECT 列。

### 4.4 关系原语：统一「宽表 + 外键」，两档行为

表格控件与表单间关联**底层是同一个原语**（宽表 + 一个外键列），由 FieldType 分支驱动
（不立 `relation_type` 枚举），仅行为分两档：

| 档 | 语义 | 删除 | 共享 | 渲染 | 子表命名 |
|---|---|---|---|---|---|
| `TABLE` | 主表单内嵌子表（明细行） | **CASCADE**（删主→连带软删明细） | 独占，不可被他表引用，不递归 | 内嵌，子表也有自己样式元数据 | `sw_form_table_{nanoId}` |
| `REFERENCE` | 独立表单间引用 | **RESTRICT**（有子引用则禁删父） | 可被多表引用 | 用目标表单自己的 definition 渲染 | 目标表单平级 `sw_form_{nanoId}` |

- 一对多的外键**永远落在「多」的一端（子表）**，子表存 `parent_record_id` 指回父；
  父表不持有指向多个子的列。
- 建表逻辑（DynamicTableManager）对两档**完全一致**；差异只在删除、渲染、命名三处。
- **REFERENCE 关系存放**：关系待在**引用方的 definition JSON**里（REFERENCE 字段的
  `targetFormId`），**不开 fk 列**。物理上引用方业务表有一列 `ref_{name}_id` 存被引记录 id。
- **`targetFormId` 口径（钉死）**：REFERENCE 字段 definition 里的 `targetFormId` 存的是
  **formKey**（业务标识，如 `dept_form`），**不是** form_id（UUID）。
  反查路径：`targetFormId → FormDefService.getFormDefByKey(targetFormId) → 物理表名`。

### 4.5 删除语义（硬约束）

记录删除一律**软删**（`UPDATE ... SET deleted=1`），不物理 DELETE。同一 `@Transactional` 内按序：

1. **RESTRICT 反查（删父前拦）**：扫 `sw_form_config`，找出 definition 里 REFERENCE.`targetFormId`
   指向本表单的全部引用方 →（引用方物理表, `ref_{name}_id` 列名）。对每个引用方
   `SELECT 1 FROM 引用方表 WHERE ref_xxx_id=? AND deleted=0 AND tenant_id=? LIMIT 1`，
   任一命中 → 抛 RESTRICT 错误码，整事务回滚。
   - 反查 `deleted=0`：已软删的引用不算有效引用（漏则误拦合法删除）。
   - 反查 `tenant_id=?`：仅同租户参与判定（漏则跨租户串数据）。
   - `refTable == 主表` 时追加 `AND id != ?` 排除自引用。
2. **CASCADE 软删子表**：对每张 TABLE 子表
   `UPDATE 子表 SET deleted=1 WHERE parent_record_id=? AND deleted=0 AND tenant_id=?`。
3. **软删主记录**：`UPDATE 主表 SET deleted=1 WHERE id=? AND deleted=0 AND tenant_id=?`。
- **幂等口径**：删不存在/已删一律返成功（REST DELETE 目标态语义），不抛错。
- 高并发引用方插入竞态（TOCTOU）当前用单事务 + `WHERE deleted=0` 兜，引用计数/锁为后续。

### 4.6 字段类型 / 状态机 / 快照样式分离

- **字段类型**：启用 8 类（TEXT/RICH_TEXT/NUMBER/DATE/BOOL/DICT/REFERENCE/TABLE）；
  其余为占位类型 `enabled=false` 无行为。RADIO 不立类型 = DICT + renderAs。
- **definition 唯一字段真源**：发布读该表单已存的 `config.definition.fields` 派生建表，
  不另收 fieldSpecs。definition 形状见 `FormDefinitionSchema`（落 `-api`，type 用 String 字面量
  保 `-api` 不依赖 `-biz`）。
- **草稿 → 发布（硬约束）**：草稿态**只动元数据**（definition / 快照），**物理表零接触**，
  草稿保存路径禁出现任何 DDL。发布动作 = 校验表名/字段名 → 建表/加列 → 冻结。
  **发布后表名、字段名不可修改**（改类型/删字段/改名永久封死）；唯二例外「加列/改长度」
  作为接缝、v2 接通（v1 不开）。
- **样式与数据分离**：样式 definition 存 `sw_form_config.definition`(jsonb)；
  版本快照存 `sw_form_snapshot.definition`(jsonb)；业务数据在动态宽表。均存主库，不引 Mongo。

---

## 5. 数据层与多租户（硬约束）

- **BaseEntity 仅适用于主业务库。** 其 MyBatis-Plus 拦截器只作用于主库；
  扩展库实体**禁止**继承 BaseEntity。
- `TenantLineHandler` 必须**数据源感知**，在非主数据源上跳过租户注入。
- 多租户为**列级隔离**。
- 跨切面基础设施（多租户、BaseEntity、数据权限、Security 过滤链、字典服务）
  必须先于业务代码就位，避免昂贵的回填式改造。
- `DataScope` 枚举置于 `sw-common`，消除 sw-common 与 sw-security 间的脆弱字符串映射。

### 5.1 动态宽表裸 SQL 红线（硬约束 · 反复踩，固化）

动态宽表经裸 `JdbcTemplate` 读写，**MyBatis-Plus 拦截器全部失效**：

- **`@TableLogic` 不生效** → 逻辑删除必须**手写** `WHERE deleted=0`；删除必须手写
  `UPDATE ... SET deleted=1`。
- **`TenantLineHandler` 不生效** → 租户隔离必须**手写** `WHERE tenant_id=?`
  （取自 `LoginUserHolder.get().getTenantId()`）。
- 查询 / 删除 / 更新，**每一条裸 SQL 都须同时手写 `deleted` + `tenant_id` 两个条件**，
  缺一即数据隔离/逻辑删除漏洞。
- **注入红线**：列名一律过 `ColumnValidation.physicalColumnName(name, type)` 白名单单出口
  （REFERENCE → `ref_{name}_id`，TABLE → 抛异常，其余原值）+ `validateColumnName()`；
  表名过 `TABLE_NAME_PATTERN` 正则；所有值一律 `PreparedStatement ?` 参数化绑定。
  **绝不**裸拼列名/表名/值进 SQL。
- 分页手写 `SELECT COUNT(*)` + `LIMIT ? OFFSET ?`（IPage 拦截器不作用于裸 JdbcTemplate）；
  `LIMIT/OFFSET` 须 PG + H2 双通；查询 size 设硬上限（现 200）。
- 若勘察发现动态宽表缺 `deleted` / `tenant_id` 列 → **停，报缺口**，不静默放过、不自编租户语义。

### 5.2 错误码区间登记（form 模块 · 新码须落未占区间顺延）

| 区间 | 用途 | 备注 |
|---|---|---|
| 1000-1099 | 通用 | |
| 1100-1199 | 状态机 | |
| 1200-1208 | 发布 | 1204 FIELD_TYPE_UNKNOWN / 1205 DISABLED / 1206 ATTR_MISSING / 1207 NESTED_TABLE / 1208 DEFINITION_INVALID |
| 1300-1399 | 渲染 | 1300 CONFIG_NOT_FOUND（已占） |
| 1400-1404 | 提交 | 1400 未知字段 / 1401 必填 / 1402 类型 / 1403 字典值域 / 1404 提交路径 |
| 1500-1504 | 查询 | 1500 表单不存在/未发布 / 1501 字段未知 / 1502 不可筛 / 1503 op×type / 1504 op 不支持 |
| 1505 | 删除 | DELETE_RESTRICT_REFERENCED |

> 1506 曾分配（DELETE_RECORD_NOT_EXIST）后**摘除**——删除幂等口径 A 下无路径抛它。
> 新增错误码统一按现有 `code + 默认 message` 模式；全局异常 + i18n 落地后统一进消息表。

---

## 6. Schema 治理（Flyway）

### 6.1 通则

- **单数据源**，`{vendor}` 目录结构，PostgreSQL 为首个部署目标。
- ❌ 无 Liquibase，❌ 无 Hibernate DDL。
- 一个环境 = 一种固定数据库类型。CRUD 可移植性靠约定；DDL 可移植性靠
  per-vendor Flyway 脚本。扩展库只做 CRUD，永不拥有 schema。
- `prod-update` 目录存生产 DML 补丁（原生方言）；失败**故意**阻断启动，留待开发者修复。
- 开发期以 **H2 作为 SQL 正确性代理**（跳过中间件验证）。
- **已应用迁移不原地改**，schema 变更走前向迁移；PG + H2 双份**逐字节一致**。

### 6.2 Flyway 例外：动态宽表

- 动态宽表（`sw_form_{nanoId}` / `sw_form_table_{nanoId}`）是运行时由
  DynamicTableManager 按 vendor 方言建立的**数据载体**，**不纳入 Flyway**。
- 这是对 "无 Hibernate DDL / DDL 全归 Flyway" 的**唯一显式例外**，有意为之。
- 固定元数据表（`sw_form_config`、`sw_form_snapshot` 及其它 `sys_*`/`sw_*` 表）
  仍全部归 Flyway。
- 动态宽表的环境迁移靠表单导入导出，不靠 Flyway 脚本。

---

## 7. 多数据源

- **编码期**：`@DS` 注解系统（经 dynamic-datasource-spring-boot3-starter）用于扩展库。
- **运行期**：动态 SQL 平台**仅限** `sw-bpm-engine`，只读（SELECT-only）仓库查询，
  附带加密、行数限制、超时、完整审计日志。

---

## 8. 安全 / 异常处理

- **过滤器层异常需区分**：令牌校验失败（401） vs 基础设施故障（500/503），
  不得把所有异常统一降级。
- ❌ **禁止远程代码执行类功能。** 不接受"用户上传代码包 → 主进程编译运行"。
  用户可配置的是**数据**（流程 key、表单数据、cron），不是**代码**。
  需要用户自部署任务逻辑时，只走进程外（执行器模型或 HTTP/webhook 触发）。
- ❌ 用户输入的表名/字段名禁止直接拼 DDL（见 4.2 / 5.1），须过白名单 + 参数化。

---

## 9. 编码规范（硬约束）

### 9.1 全限定类名（FQCN）

- ❌ **禁止用 FQCN 字符串选择实现 / 反射绕 Spring 容器**（见第 1 节，破坏
  `@Transactional`/AOP，引安全风险）。
- 日常编码**用 import + 短类名**，不在代码里写全限定名；仅在**同名冲突**等必要场景才用 FQCN。

### 9.2 工具类：优先成熟开源库

- 需要工具能力时，**优先选社区活跃、易维护的成熟开源库**，不自造轮子、不引冷门库。
- **JSON**：不强制统一某一库。Spring MVC 出入参**沿用 Jackson**（框架默认，勿改，
  含既有 `JacksonLongToStringConfig`、record 序列化）；业务内部手动 JSON 操作**按需选用**，
  惯用 **fastjson2**。**关键约束**：同一序列化场景内**不混用**两个 JSON 库，口径一致
  （Long→String、日期格式、null 处理）。
- **HTTP**（业务侧主动发请求，如 webhook / 第三方集成）：用 **hutool `HttpRequest`**。
- 引入任何第三方工具库仍受**层级铁律**与 **BOM 版本管理**（`sw-dependencies`）约束，
  不因「是工具」就随意加依赖或绕过模块边界。

---

## 10. 定时任务（sw-basic-job）

- **技术选型**：Quartz，**单节点**（RAMJobStore，当前无 `QRTZ_*` 表）。
- 后台维护抽象 `JobHandler` 入口；实现类填 bean 名 + cron 表达式即可设置并启用。
- **任务类型经 `job_type` 枚举分流，共用同一套调度基础设施：**
  - `BEAN`：`bean_name` + `params`，走 JobHandler 入口。
  - `FLOW`：`flow_def_key` + `form_data`(JSON)，**定时发起流程**。
- **FLOW 任务必须经与手动表单提交相同的校验路径**（字段合法性、必填、字典值域）。
  定时触发与手动提交**汇入同一个校验+发起方法**，不得各写一份，
  不得因"是定时任务配的"而跳过 FormSubmitService 校验。
- **job 不依赖 workflow 的 `-biz`，也不硬编流程逻辑。**
  到点发领域事件（如 `ScheduledFlowTriggerEvent`），workflow 监听并复用
  `FormSubmittedEventListener` 同一个流程发起入口。
- **集群升级路径（现在预留，后续做）：**
  - 调度入口抽象，不把 RAMJobStore 写死在业务码，将来切 JDBC JobStore 只动配置。
  - 任务执行逻辑保持**幂等**，尤其 FLOW 任务以 `job_id + 触发时间` 做去重键。

**`sw_job_` 表（单节点版）：**

- `sw_job_info` — `job_type`(BEAN/FLOW)、cron、状态、并发/misfire 策略；
  BEAN 存 `bean_name`+`params`，FLOW 存 `flow_def_key`+`form_data`(JSON)。
- `sw_job_log` — job_id、起止时间、状态、耗时、结果/异常、触发方式。

---

## 11. 工程执行与分级验证

角色、授权、任务闭环、terminal 和回执生命周期只引用根 `system.md`、`roles/executor.md` 与机器契约；本节只定义后端工程动作。

- 实现前读取真实签名、装配关系与现有测试边界；prompt 与仓库事实冲突时以事实为准并记录偏差。
- 重型命令前先按工作区 `knowledge/shared-constraints.md` §6 检查前端进程，保持前后端编译互斥。
- **S**：运行能够直接覆盖局部改动的最小充分检查；不改变业务逻辑的文案/配置修正不强制全工程测试。
- **M**：先执行 `MAVEN_OPTS="-Xmx2g" mvn -q compile`，再运行受影响模块或目标测试；影响装配、公共契约或全局配置时升级验证范围。
- **L/XL 完整校验门**：`MAVEN_OPTS="-Xmx2g" mvn -q compile` → `MAVEN_OPTS="-Xmx2g" mvn -q test`，使用全工程计数并确认基线无漂移。
- ❌ **反模式**：禁止用 `spring-boot:run` 作阻塞进程做启动校验
  （导致超时循环 + 端口冲突级联）。改用后台进程 + 日志轮询 + 干净进程终止 + 确定性结果码。
- 工程验证按等级记录实际改动文件和验证结果；L/XL 在正式回执中记录完整校验门结果/计数和易错点反向断言。原始日志、完整命令输出、截图、采样及其他证据附件只在本机留存，不提交到 Git；工作区只跟踪任务与回执文档。回执记录结果摘要和本地证据位置，不嵌入原始附件，也不得用 `git add -f` 绕过忽略规则。

---

## 12. 开发哲学：Walking Skeleton

- 优先打通一条端到端的薄切片，而非任一模块的横向铺满。
- 里程碑：`登录 → 简单表单 → 单节点审批 → 通知` 端到端跑通，**之后**才横向扩展任一模块。
- 关系原语两档（TABLE + REFERENCE）各跑一条最小路径，验 CASCADE 与 RESTRICT 两种删除语义。

---

## 13. 信息分层铁律（与根 system.md §0.4 一致）

信息分层、当前状态与 memory 摘要规则唯一见根 `system.md` §0.4/§0.8；本工程宪法不保留镜像正文。

---

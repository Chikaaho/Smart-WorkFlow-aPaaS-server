# Smart-WorkFlow-aPaaS-server

<img src="docs/images/ch-apaas-logo.png" alt="CH-aPaaS Logo" width="180" />

Smart-WorkFlow-aPaaS-server 是 **CH-aPaaS** 的后端 API 服务（Java 21 / Spring Boot），承载组织权限、表单、流程、审批、通知以及设备与智能能力的组合基础。CH-aPaaS 是用于搭建企业业务应用的平台；办公审批（OA）是已经落地的一类使用场景，而不是项目的全部定义。

- 当前正式版本：**0.4.0**（2026-11-09，多数据库兼容）
- 配套前端：[Smart-WorkFlow-aPaaS-Web](https://github.com/Chikaaho/Smart-WorkFlow-aPaaS-Web) **0.3.6**
- 支持数据库：PostgreSQL / MySQL / MariaDB / Oracle / SQL Server / openGauss / DM8 / KingbaseES / H2

## 谁可以使用、可以做什么

- **普通员工**：从工作台找到可发起的事项，填写或恢复业务草稿，提交申请、跟踪进度、处理待办并查看结果；支持企业微信、飞书、钉钉 SSO 登录。
- **审批人**：处理待办并填写审批意见，接收抄送与催办提醒。
- **业务管理员**：在管理后台维护事项与分类、表单、流程，以及通知规则、渠道配置与订阅偏好，处理通知发送失败并重发。
- **平台管理员**：管理用户、角色、菜单与租户权限边界，装配通知渠道与 SSO Provider。

为什么要使用它：把业务事项配置为表单与流程之后，使用者即可在统一的工作台发起和办理，不需要为每个业务流程重复开发页面、权限、表单和审批链。

## 当前版本：0.4.0

0.4.0 让同一套迁移脚本和 SQL 种子可以在九种数据库上完成空库启动、迁移、种子初始化和业务冒烟；业务功能与 0.3.2 相同。

| 项目 | 内容 |
| --- | --- |
| tag | `0.4.0` → `a4f6c278`（annotated tag） |
| Release | [CH-aPaaS Server 0.4.0](https://github.com/Chikaaho/Smart-WorkFlow-aPaaS-server/releases/tag/0.4.0)（id `438918154`） |
| 制品 | `bootstrap-0.4.0.jar`，236,245,457 B，sha256 `188be963796af0583f2e59653cfc19cd482856e6fc4d0ecd22f102223b5e620b` |
| CI | main run `42588179160` success |
| 数据库迁移 | Flyway 终点 **V0.4.0**：新增 `V0.4.0__knowledge_vector_persistence.sql`（知识库向量持久化）和 `R__0.4.0_rename_bpm_opinion_comment.sql`（意见草稿表 `comment` 列改名为 `opinion_comment`）；已发布迁移的校验和不变 |
| 部署 | UAT 运行 `build.version=0.4.0`，health UP |

### 从旧版本升级

1. 停服前备份数据库（PostgreSQL 用 `pg_dump -Fc`），并保留旧 jar。
2. 换上 `bootstrap-0.4.0.jar` 后启动，Flyway 自动补齐迁移：从 0.3.x 升级会应用上表的两条新迁移，从更早版本升级会沿迁移链依次应用。
3. 启动时 `DatabaseCompatibilityGuard` 会先检查库级前提（字符集、排序规则、兼容模式等），不满足就在迁移前停止。非 PostgreSQL 数据库请先按 [多数据库部署说明](docs/deployment/multi-database.md) 建库。
4. 生产默认日志级别为 INFO。

### 回退到 0.3.2

0.4.0 已经对数据库应用了新迁移（含列改名），0.3.2 的 jar 不能直接运行在升级后的库上：只换 jar 会因 Flyway 校验失败（`Detected applied migration not resolved locally`）而无法启动。回退必须按以下顺序：

1. 停服；
2. 恢复升级前的数据库备份（UAT 为 `smart_workflow_20261109_pre040.dump`）；
3. 换回 0.3.2 的 jar；
4. 启动。

完整步骤见工作区运维手册 [`production-ops.md`](https://github.com/Chikaaho/Smart-WorkFlow-Agent-Workspace/blob/develop-sw/docs/ops/production-ops.md) §8.1、§9.1。

## 多数据库支持

| 数据库 | 版本 | 验证方式 | 建库前提（摘要） |
| --- | --- | --- | --- |
| H2 | 测试/开发模式 | 既有回归 | 保持现状 |
| PostgreSQL | 14.24（UAT）/ 17.5+（CI） | 实测 | UTF8；UAT 迁移校验和为准 |
| MySQL | 5.7 / 8.0 | 实测 | `utf8mb4` + `utf8mb4_bin`；`explicit_defaults_for_timestamp=ON` |
| MariaDB | 10.6+ | 实测 | `utf8mb4_bin`；`explicit_defaults_for_timestamp=ON` |
| Oracle | 21c XE | 实测 | `AL32UTF8`；空串=NULL 语义 |
| SQL Server | 2019+ / 2022 | 实测 | `Chinese_PRC_CS_AS`；终态基线 |
| openGauss | 5.0 | 实测 | `UTF8` + `DBCOMPATIBILITY 'PG'` |
| DM8 | — | Owner 实库验证 | `CASE_SENSITIVE=Y`、`LENGTH_IN_CHAR=1` |
| KingbaseES | V8 | Owner 实库验证 | `database_mode=pg`（参数名随版本） |

完整建库参数、JDBC 示例、向量存储与 SQL Server 基线说明见 [`docs/deployment/multi-database.md`](docs/deployment/multi-database.md)。

## 当前可体验的业务闭环

以下能力在当前版本 0.4.0 中均可使用。以办公审批（OA）为主线：

> 用户从工作台找到可发起事项，填写或恢复业务草稿，提交申请；审批人处理待办并填写意见；发起人随后查看状态和结果，相关人员收到抄送，管理员在后台维护事项、表单、流程和通知记录。

围绕这条旅程，后端提供以下运行支撑：

- **工作台与常用事项**：待办、我发起的、抄送、常用事项的个人布局持久化，支持显示/隐藏、排序与恢复默认。
- **流程中心与分类查找**：普通用户按分类和关键词查找本人可发起的事项，选择事项即进入关联表单；管理员维护分类、表单关联与发布状态，历史实例绑定保持兼容。
- **表单能力**：多选、附件、图片、说明文字等控件，支持默认值、条件显示（显隐联动）；草稿可保存与恢复，提交校验与服务端一致，隐藏字段不进入正式提交载荷。
- **个人办理**：我发起的、我的待办、我的已办、抄送我的与催办；催办只针对仍在办理中的实例，重复催办受冷却控制。
- **流程编排与运营**：自研流程设计器（审批人选择、会签、分支与条件）、动态并行编排、流程模板中心、监控/干预与基础分析、批量审批与流程交接，以及流程专用跨系统接入（外部应用发起/查询/签名回调/重试/防重放）。
- **审批意见驱动流转**：意见表单字段写入流程变量，会签意见按字段类型聚合，条件分支和参与人可以直接取用意见；流转记录和审批详情逐项显示意见内容。
- **Agent 与知识**：模型供应商与模型配置、图编排执行（异步受理、持久回查、取消与重启恢复、流式输出）、知识库 RAG；IoT 事件规则可以启动已发布的 Agent 图做研判，研判失败自动升级通知负责人。
- **组织与权限底座**：用户、部门、角色、菜单与数据访问范围的后台维护，支撑前后台分层的入口与操作权限。
- **普通业务入口与管理后台**：前后台分层，普通用户与管理员的入口和权限各自独立；无权深链、管理接口和跨用户对象访问会被拒绝。
- **租户安全与三方 SSO**：租户归属贯穿表单、流程、通知等对象并 fail closed；企业微信、飞书、钉钉 SSO 的服务端授权发起、回调换票、state 限时一次性绑定与稳定主体绑定已就位。
- **统一通知**：站内信、短信、飞书卡片、钉钉、企业微信与邮件六类渠道共用一套投递权威；模板按事件与渠道版本化，通知规则与订阅偏好可配置，收件箱支持真分页与未读数，发送记录脱敏并支持有权限的重发；通知失败不影响审批状态。

身份、组织、角色与数据权限贯穿以上能力，附件等对象访问遵守各自权限边界。外部渠道与三方 SSO 的生产 Adapter 已实现，未装配即不可启用；邮件渠道已用真实凭据发送成功，其余渠道与 SSO 的真实凭据链路尚未验证。

## 三个示例场景（已完成）

三条示例流程在 0.3.0—0.3.6 落地并达到 MVP，在 UAT 上用真实业务数据跑通了“表单 → 流程 → 审批 → 业务动作 → Agent/IoT 联动”的完整链路。

### 场景一：多部门灾备演练（A · 应急演练）

分公司发起跨部门灾备演练计划。流程依次经过申请单位负责人、技术委员会会签、分管副总，再由各参与部门分管领导并行审批，最后经总公司会签后交现场指挥。审批通过后，系统按表单里的预定时间登记预约，到点通过 MQTT 下发机房断电、火警告警等演练指令。

它展示表单、多人审批、动态并行、会签、抄送、定时任务与设备控制的组合价值。

### 场景二：校园出入口流量异常（B · 值班协同）

校园某个出入口出现异常流量后，Agent 先检索近期校内活动和已有知识，再结合公开信息判断原因：有合理解释的记录日志并建立 P1 工单，找不到原因的建立 P0 工单并告警负责人。人工侧由安保复核，核实事实后分流到督办、上报或现场处置。告警目前走站内信和邮件，短信、电话渠道尚未接入。

它展示事件接入、流程与知识检索、外部信息判断、工单分级和多渠道告警的组合价值。

### 场景三：MES 温度智能判断（C · MES 异常研判）

厂房设备常温约为 75℃，80℃ 为告警阈值，85℃ 必须停机。Agent 结合业务人员配置的知识和样本研判温度序列：短时快速升温判为异常并建立工单，缓慢升温且未到 85℃ 识别为可能的正常操作并记录，超过 85℃ 进入紧急停机流程。工单经班组长确认、原因分类后进入校准、维修或工艺调整，处置完成再走复产审批。

它展示实时数据、业务知识、趋势判断、分级处置和流程联动的组合价值。

交付登记与验收链路见工作区 [`knowledge/features/0.3.2-opinion-display.md`](https://github.com/Chikaaho/Smart-WorkFlow-Agent-Workspace/blob/develop-sw/knowledge/features/0.3.2-opinion-display.md)。

## 版本历史

| 版本 | 内容 | tag / Release |
| --- | --- | --- |
| 0.4.0 | 多数据库兼容（见上文） | `0.4.0`→`a4f6c278` / `438918154` |
| 0.3.2 | 意见展示与回显：审批详情逐项显示中文标签和值，会签记录可查看每人意见，部门字段回显名称；无新迁移（Flyway V0.2.9） | `0.3.2`→`de8ec017` / `437820040` |
| 0.3.1 | 审批意见驱动流转：意见字段写入流程变量，触发器注入本节点意见，会签意见按字段类型聚合；本版前端未随版 | `0.3.1`→`452178d8` / `437557325` |
| 0.3.0 | 三示例流程首版：应用“运营与风险管理工作台”（3 张业务表单、3 条流程）与 4 类缺陷修复。**Owner 2026-11-07 审核后退回**：意见过于简单，没有用审批意见、流程变量和脚本来驱动分支与参与人；由 0.3.1—0.3.6 返修，0.3.0 的 tag、Release 和部署记录保持原样 | `0.3.0`→`a0ac3a1a`（同版本覆盖）/ `436832133` |
| 0.2.4 | IoT 事件规则启动 Agent 研判、失败自动升级、Agent 内部工具、渠道凭据密文存储；Flyway V0.2.9 | `0.2.4`→`41511bd4` |
| 0.2.3 | Agent 执行生命周期：异步受理、持久回查、取消与重启恢复、三协议流式输出；Flyway V0.2.7 | `0.2.3`→`9b86ff0d` |
| 0.2.2 | Agent 完善（模型供应商与模型、graph 快照、知识库 RAG）与应用整包导出导入 | `0.2.2`→`0bf4c938` |
| 0.2.1 | 缺陷修复批次 B021-001～029 | `0.2.1`→`5c33eab3` |
| 0.2.0 | 全局 UI 与关联功能完善；数据库基线收敛为单条 `V0.2.0` | `0.2.0`→`39dcedb7` |

0.1.x 及更早版本见仓库 [tags](https://github.com/Chikaaho/Smart-WorkFlow-aPaaS-server/tags) 与工作区 [`CHANGELOG.md`](https://github.com/Chikaaho/Smart-WorkFlow-Agent-Workspace/blob/develop-sw/CHANGELOG.md)。

## 项目入口

| 入口 | 说明 |
| --- | --- |
| [Smart-WorkFlow-aPaaS-server](https://github.com/Chikaaho/Smart-WorkFlow-aPaaS-server) | 后端仓库（本仓库） |
| [Smart-WorkFlow-aPaaS-Web](https://github.com/Chikaaho/Smart-WorkFlow-aPaaS-Web) | 前端仓库 |
| [Smart-WorkFlow-Agent-Workspace](https://github.com/Chikaaho/Smart-WorkFlow-Agent-Workspace/tree/develop-sw) | 项目规划与知识中心（`develop-sw` 分支） |
| [后端工程文档](docs/governance/engineering-constitution.md) | 工程规范、模块结构与验证入口等开发者资料汇总 |
| [多数据库部署说明](docs/deployment/multi-database.md) | 九种数据库的建库前提、JDBC 示例与差异说明 |

从使用者视角了解界面与操作体验，见配套前端 [Smart-WorkFlow-aPaaS-Web](https://github.com/Chikaaho/Smart-WorkFlow-aPaaS-Web)。

# 错误码与 errorKey 兼容目录（P61 阶段 A）

> 权威来源：本目录的机器可读事实来自各模块 `ErrorCode` 枚举源码，由回归测试
> `ErrorCodeCatalogTest` 守护；本文件是人工可读的兼容与弃用登记。
> 角色、授权与终态不在此定义，分别见工作区 `system.md`、`roles/executor.md` 与 `.codex/governance/terminal-contract.json`。

## 1. 契约分层

| 层 | 字段 | 稳定性 | 用途 |
|---|---|---|---|
| 旧调用方兼容层 | `code`（整数） | 0.1.0 冻结，本轮不重编号 | 既有消费者按数值处理；**数值不唯一**，不得用于跨模块分流 |
| 新机器契约 | `errorKey`（字符串） | 全局唯一、跨语言稳定 | Web / 新调用方 / Mock / 自动化按语义分流 |
| 人类可读层 | `msg` | 可随语言与受众变化 | 面向当前受众的安全结论与恢复建议；不得作为机器判据 |
| 定位关联层 | `eventRef` | 每请求唯一 | 用户可报出，与服务端访问/诊断日志关联 |

**硬规则**

1. `errorKey` 全局唯一。新增错误必须登记唯一键，命名 `<domain>.<semantic_name>`（小写 + 下划线）。
2. `code` 允许在历史原因下重复（见 §3），但**新错误不得复用已登记冲突值**。
3. 任何业务分支不得依赖 `msg` 文案、其子串或完整文本；一律使用 `errorKey`、状态字段或结构化判据。
4. 公共 `msg` 不承载原始诊断（Java 字段名/类名/SQL/JDBC/栈/租户标识/密钥片段/第三方原文）。
   诊断经 `eventRef` 关联的服务端日志定位。
5. 成功响应不出现 `errorKey`/`eventRef`，保持与 0.1.0 相同的字节形状。

## 2. 全量登记（127 常量 / 5 枚举）

| 命名空间 | 常量 | code | errorKey | 当前 msg（zh-CN 默认） |
|---|---|---|---|---|
| common | `SYSTEM_ERROR` | 500 | `common.system_error` | 系统异常 |
| common | `PARAM_ERROR` | 400 | `common.param_error` | 参数错误 |
| common | `UNAUTHORIZED` | 401 | `common.unauthenticated` | 未认证 |
| common | `FORBIDDEN` | 403 | `common.forbidden` | 无权限 |
| common | `NOT_FOUND` | 404 | `common.not_found` | 资源不存在 |
| auth | `CAPTCHA_ERROR` | 2101 | `auth.captcha_mismatch` | 验证码错误 |
| auth | `CAPTCHA_EXPIRED` | 2102 | `auth.captcha_expired` | 验证码已过期 |
| auth | `CLIENT_TIME_ABNORMAL` | 2103 | `auth.client_time_abnormal` | 机器时间异常 |
| auth | `PASSWORD_ERROR` | 2104 | `auth.credential_invalid` | 密码错误 |
| form | `FORM_NOT_FOUND` | 1000 | `form.not_found` | 表单不存在 |
| form | `FORM_KEY_DUPLICATE` | 1001 | `form.key_duplicate` | 表单标识已存在 |
| form | `FORM_NAME_DUPLICATE` | 1002 | `form.name_duplicate` | 表单名称已存在 |
| form | `FORM_ALREADY_PUBLISHED` | 1100 | `form.already_published` | 表单已发布，不能修改 |
| form | `FORM_ALREADY_DRAFT` | 1101 | `form.already_draft` | 表单处于草稿态，不能执行此操作 |
| form | `FORM_NOT_PUBLISHED` | 1102 | `form.not_published` | 表单未发布，不能提交数据 |
| form | `FORM_DISABLED` | 1103 | `form.disabled` | 表单已停用，不能填报或提交 |
| form | `FORM_DELETE_RESTRICTED` | 1104 | `form.delete_restricted` | 表单存在有效引用，不能删除 |
| form | `FIELD_EDIT_DENIED` | 1105 | `form.field_edit_denied` | 当前身份无该字段编辑权限 |
| form | `FIELD_VIEW_DENIED` | 1106 | `form.field_view_denied` | 当前身份无该字段查看权限 |
| form | `INVALID_COLUMN_NAME` | 1200 | `form.invalid_column_name` | 字段名不合法 |
| form | `DUPLICATE_COLUMN` | 1201 | `form.duplicate_column` | 字段名重复 |
| form | `TABLE_ALREADY_EXISTS` | 1202 | `form.table_already_exists` | 动态宽表已存在 |
| form | `PUBLISH_FAILED` | 1203 | `form.publish_failed` | 表单发布失败 |
| form | `FIELD_TYPE_UNKNOWN` | 1204 | `form.field_type_unknown` | 字段类型未知 |
| form | `FIELD_TYPE_DISABLED` | 1205 | `form.field_type_disabled` | 字段类型暂不允许发布 |
| form | `FIELD_ATTR_MISSING` | 1206 | `form.field_attr_missing` | 字段缺少必要属性 |
| form | `FIELD_NESTED_TABLE` | 1207 | `form.field_nested_table` | 表格字段不能嵌套 |
| form | `DEFINITION_INVALID` | 1208 | `form.definition_invalid` | 表单定义配置异常 |
| form | `FORMULA_INVALID` | 1209 | `form.formula_invalid` | 公式表达式非法 |
| form | `FORMULA_CYCLE` | 1210 | `form.formula_cycle` | 公式存在循环依赖 |
| form | `FORMULA_UNKNOWN_FIELD` | 1211 | `form.formula_unknown_field` | 公式引用了未定义字段 |
| form | `EXT_QUERY_NOT_FOUND` | 1212 | `form.ext_query_not_found` | 外部数据源查询契约不存在 |
| form | `EXT_QUERY_DISABLED` | 1213 | `form.ext_query_disabled` | 外部数据源查询契约已停用 |
| form | `EXT_OUTPUT_MISMATCH` | 1214 | `form.ext_output_mismatch` | 外部数据源输出与契约不匹配 |
| form | `EXT_OBJECT_NOT_FOUND` | 1215 | `form.ext_object_not_found` | 外部数据对象不存在或不可见 |
| form | `LIST_CONFIG_INVALID` | 1216 | `form.list_config_invalid` | 列表展示配置非法 |
| form | `REFERENCE_OBJECT_NOT_FOUND` | 1217 | `form.reference_object_not_found` | 引用记录不存在或不可见 |
| form | `ATTACHMENT_FILE_NOT_FOUND` | 1218 | `form.attachment_file_not_found` | 附件/图片文件不存在或不可见 |
| form | `EXT_RESULT_LIMIT_EXCEEDED` | 1219 | `form.ext_result_limit_exceeded` | 外部数据源结果超过行数上限 |
| form | `CONFIG_NOT_FOUND` | 1300 | `form.config_not_found` | 表单配置未找到 |
| form | `SNAPSHOT_NOT_FOUND` | 1301 | `form.snapshot_not_found` | 表单版本快照不存在 |
| form | `SUBMIT_FIELD_UNKNOWN` | 1400 | `form.submit_field_unknown` | 提交了未定义的字段 |
| form | `SUBMIT_FIELD_REQUIRED` | 1401 | `form.submit_field_required` | 必填字段缺失 |
| form | `SUBMIT_FIELD_TYPE_MISMATCH` | 1402 | `form.submit_field_type_mismatch` | 字段类型不匹配 |
| form | `SUBMIT_DICT_INVALID` | 1403 | `form.submit_dict_invalid` | 字典值不在允许范围内 |
| form | `SUBMIT_FAILED` | 1499 | `form.submit_failed` | 表单提交失败 |
| form | `SUBMIT_DEFINITION_INVALID` | 1404 | `form.submit_definition_invalid` | 表单定义配置异常 |
| form | `QUERY_FORM_NOT_EXIST` | 1500 | `form.query_form_not_exist` | 表单不存在或未发布 |
| form | `QUERY_FILTER_FIELD_UNKNOWN` | 1501 | `form.query_filter_field_unknown` | 过滤字段不在表单定义中 |
| form | `QUERY_FILTER_FIELD_NOT_FILTERABLE` | 1502 | `form.query_filter_field_not_filterable` | 该字段类型不支持筛选 |
| form | `QUERY_FILTER_OP_TYPE_MISMATCH` | 1503 | `form.query_filter_op_type_mismatch` | 过滤操作符与字段类型不匹配 |
| form | `QUERY_FILTER_OP_NOT_SUPPORTED` | 1504 | `form.query_filter_op_not_supported` | 该过滤操作符 v1 暂不支持 |
| form | `DELETE_RESTRICT_REFERENCED` | 1505 | `form.delete_restrict_referenced` | 记录被其他表单引用，不能删除 |
| form | `DELETE_RECORD_NOT_EXIST` | 1506 | `form.delete_record_not_exist` | 记录不存在或已删除 |
| form | `RECORD_NOT_FOUND` | 1507 | `form.record_not_found` | 记录不存在或已删除 |
| form | `VERSION_CONFLICT` | 1508 | `form.version_conflict` | 数据版本冲突，请刷新后重试 |
| bpm | `GRAPH_MISSING_START` | 2000 | `bpm.graph_missing_start` | 图缺少开始节点 |
| bpm | `GRAPH_MULTIPLE_START` | 2001 | `bpm.graph_multiple_start` | 图存在多个开始节点 |
| bpm | `GRAPH_MISSING_END` | 2002 | `bpm.graph_missing_end` | 图缺少结束节点 |
| bpm | `GRAPH_MULTIPLE_END` | 2003 | `bpm.graph_multiple_end` | 图存在多个结束节点 |
| bpm | `GRAPH_NODE_EDGE_CARDINALITY` | 2004 | `bpm.graph_node_edge_cardinality` | 节点入/出边基数违规 |
| bpm | `GRAPH_ORPHAN_NODE` | 2005 | `bpm.graph_orphan_node` | 存在孤儿/不可达节点 |
| bpm | `GRAPH_EDGE_TARGET_NOT_FOUND` | 2006 | `bpm.graph_edge_target_not_found` | 边指向不存在的节点 |
| bpm | `GRAPH_ILLEGAL_EDGE` | 2007 | `bpm.graph_illegal_edge` | 非法边（自环或重复边） |
| bpm | `GRAPH_UNKNOWN_NODE_TYPE` | 2008 | `bpm.graph_unknown_node_type` | 未注册的节点类型 |
| bpm | `GRAPH_FORM_NOT_FOUND` | 2009 | `bpm.graph_form_not_found` | 绑定表单不存在 |
| bpm | `PROCESS_DEF_NOT_FOUND` | 2010 | `bpm.process_def_not_found` | 流程定义不存在 |
| bpm | `FORM_NOT_PUBLISHED` | 2100 | `bpm.bound_form_not_published` | 绑定表单未发布，请先发布表单 |
| bpm | `PROCESS_KEY_FROZEN` | 2101 | `bpm.process_key_frozen` | 流程定义已有发布版本，process_key 不可变更 |
| bpm | `TRANSLATION_FAILED` | 2102 | `bpm.translation_failed` | 图翻译为 BPMN 失败 |
| bpm | `DEPLOYMENT_FAILED` | 2103 | `bpm.deployment_failed` | BPMN 部署失败 |
| bpm | `PROCESS_NOT_PUBLISHED` | 2104 | `bpm.process_not_published` | 流程未发布，无法获取 BPMN XML |
| bpm | `PROCESS_DEF_PUBLISHED` | 2105 | `bpm.process_def_published` | 流程定义已发布，不可修改 |
| bpm | `NODE_CONFIG_INVALID` | 2106 | `bpm.node_config_invalid` | 节点配置不合法 |
| bpm | `NODE_CAPABILITY_MISSING` | 2107 | `bpm.node_capability_missing` | 节点缺少必要能力 |
| bpm | `NODE_REGISTRATION_INVALID` | 2108 | `bpm.node_registration_invalid` | 节点注册契约非法 |
| bpm | `APPROVER_RESOLVE_EMPTY` | 2200 | `bpm.approver_resolve_empty` | 审批人解析结果为空 |
| bpm | `APPROVER_TYPE_NOT_IMPLEMENTED` | 2201 | `bpm.approver_type_not_implemented` | 审批人类型未实现 |
| bpm | `APPROVER_CONFIG_MISSING` | 2202 | `bpm.approver_config_missing` | 审批人配置缺失 |
| bpm | `APPROVER_TENANT_ID_MISSING` | 2203 | `bpm.approver_tenant_id_missing` | 流程变量中缺少 tenantId，无法构建审批人上下文 |
| bpm | `PARTICIPANT_CONFIG_INVALID` | 2300 | `bpm.participant_config_invalid` | 参与人配置不合法 |
| bpm | `PARTICIPANT_TYPE_NOT_IMPLEMENTED` | 2301 | `bpm.participant_type_not_implemented` | 参与人策略未实现 |
| bpm | `PARTICIPANT_RESOLVE_EMPTY` | 2302 | `bpm.participant_resolve_empty` | 参与人解析结果为空 |
| bpm | `PARTICIPANT_ADAPTER_NOT_FOUND` | 2303 | `bpm.participant_adapter_not_found` | 参与人适配器不存在 |
| bpm | `APPROVAL_ACTION_INVALID` | 2304 | `bpm.approval_action_invalid` | 审批动作不合法 |
| bpm | `APPROVAL_ALREADY_HANDLED` | 2305 | `bpm.approval_already_handled` | 节点已被处理 |
| bpm | `APPROVAL_RETURN_TARGET_INVALID` | 2306 | `bpm.approval_return_target_invalid` | 退回目标节点不合法 |
| bpm | `APPROVAL_OPINION_INVALID` | 2307 | `bpm.approval_opinion_invalid` | 审批意见不合法 |
| bpm | `APPROVAL_OPINION_REQUIRED` | 2308 | `bpm.approval_opinion_required` | 审批意见不能为空 |
| bpm | `COUNTER_CONFIG_INVALID` | 2309 | `bpm.counter_config_invalid` | 会签结算配置不合法 |
| bpm | `BRANCH_CONFIG_INVALID` | 2310 | `bpm.branch_config_invalid` | 分支配置不合法 |
| bpm | `BRANCH_EVALUATION_FAILED` | 2311 | `bpm.branch_evaluation_failed` | 分支条件求值失败 |
| bpm | `NODE_DELIVERY_FAILED` | 2312 | `bpm.node_delivery_failed` | 节点投递失败 |
| bpm | `INSTANCE_FAILED` | 2313 | `bpm.instance_failed` | 流程实例已失败，不可继续审批 |
| bpm | `INSTANCE_INITIATOR_INVALID` | 2314 | `bpm.instance_initiator_invalid` | 流程发起人无效、已停用或不属于当前租户 |
| bpm | `ACTION_NOT_ALLOWED` | 2400 | `bpm.action_not_allowed` | 当前状态不允许该动作 |
| bpm | `ACTION_SELF_INVALID` | 2401 | `bpm.action_self_invalid` | 不能转办/委托/授权给本人 |
| bpm | `AUTHORIZATION_INVALID` | 2402 | `bpm.authorization_invalid` | 代理授权不合法（冲突、循环、失效或越租户） |
| bpm | `WITHDRAW_NOT_PERMITTED` | 2403 | `bpm.withdraw_not_permitted` | 当前状态不可撤回 |
| bpm | `INVALIDATE_NOT_PERMITTED` | 2404 | `bpm.invalidate_not_permitted` | 当前主体无权废弃该实例 |
| bpm | `ADD_SIGN_INVALID` | 2405 | `bpm.add_sign_invalid` | 加签/补签请求不合法 |
| bpm | `SIGN_RECORD_NOT_FOUND` | 2406 | `bpm.sign_record_not_found` | 加签/补签记录不存在 |
| bpm | `DELEGATE_RELATION_INVALID` | 2407 | `bpm.delegate_relation_invalid` | 委托关系不合法 |
| bpm | `COMMUNICATION_INVALID` | 2408 | `bpm.communication_invalid` | 沟通请求不合法 |
| bpm | `CONSENSUS_VETOED` | 2409 | `bpm.consensus_vetoed` | 会签被一票否决 |
| bpm | `DEADLINE_NOT_FOUND` | 2410 | `bpm.deadline_not_found` | 时限配置不存在或已失效 |
| bpm | `AUTO_ACTION_INVALID` | 2411 | `bpm.auto_action_invalid` | 受控自动动作配置不合法 |
| bpm | `NODE_FUNCTION_NOT_FOUND` | 2412 | `bpm.node_function_not_found` | 节点函数不存在 |
| bpm | `NODE_FUNCTION_INVALID_OUTPUT` | 2413 | `bpm.node_function_invalid_output` | 节点函数输出不合法 |
| bpm | `NODE_FUNCTION_FAILED` | 2414 | `bpm.node_function_failed` | 节点函数执行失败 |
| bpm | `NODE_FUNCTION_TIMEOUT` | 2415 | `bpm.node_function_timeout` | 节点函数执行超时 |
| bpm | `OPINION_FORM_GONE` | 2415 | `bpm.opinion_form_gone` | 意见表单不可用（未发布或版本缺失） |
| bpm | `VERSION_STATE_INVALID` | 2416 | `bpm.version_state_invalid` | 发布版本状态不允许该操作 |
| bpm | `OPINION_FORM_COMPONENT_UNAVAILABLE` | 2417 | `bpm.opinion_form_component_unavailable` | 审批意见表单组件不可用 |
| bpm | `DYNAMIC_BRANCH_EMPTY` | 2418 | `bpm.dynamic_branch_empty` | 动态并行来源集合为空且未配置受控放行策略 |
| bpm | `DYNAMIC_BRANCH_LEADER_MISSING` | 2419 | `bpm.dynamic_branch_leader_missing` | 动态并行存在失效部门或负责人缺失，且未配置受控跳过策略 |
| bpm | `DYNAMIC_BRANCH_LIMIT_EXCEEDED` | 2420 | `bpm.dynamic_branch_limit_exceeded` | 动态并行分支数超过安全上限 |
| openapi | `APP_NOT_FOUND` | 3000 | `openapi.app_not_found` | 开放应用不存在 |
| openapi | `APP_DISABLED` | 3001 | `openapi.app_disabled` | 开放应用已停用 |
| openapi | `SIGN_INVALID` | 3002 | `openapi.signature_invalid` | 签名校验失败 |
| openapi | `TIMESTAMP_EXPIRED` | 3003 | `openapi.timestamp_out_of_window` | 请求时间戳超出允许窗口 |
| openapi | `NONCE_REUSED` | 3004 | `openapi.nonce_reused` | 随机串已使用（防重放拒绝） |
| openapi | `SCOPE_DENIED` | 3005 | `openapi.scope_denied` | 应用未被授权该操作范围 |
| openapi | `IDEMPOTENCY_CONFLICT` | 3006 | `openapi.idempotency_conflict` | 幂等键已绑定其他业务对象 |
| openapi | `PROCESS_NOT_VISIBLE` | 3007 | `openapi.process_not_visible` | 流程实例不存在或不属于应用授权范围 |
| openapi | `CALLBACK_FAILED` | 3008 | `openapi.callback_failed` | 回调投递失败 |
| openapi | `TENANT_INVALID` | 3009 | `openapi.tenant_invalid` | 应用所属租户不可用 |

## 3. 数值冲突登记（已弃用数值）

以下数值在 0.1.0 已存在跨语义重复。P61 **不重编号**（避免静默破坏既有消费者），
改由 `errorKey` 消歧；这些数值登记为**已弃用**：

| 冲突数值 | errorKey A | 所属枚举 | errorKey B | 所属枚举 |
|---|---|---|---|---|
| 2101 | `auth.captcha_mismatch` | `AuthErrorCode` | `bpm.process_key_frozen` | `BpmErrorCode` |
| 2102 | `auth.captcha_expired` | `AuthErrorCode` | `bpm.translation_failed` | `BpmErrorCode` |
| 2103 | `auth.client_time_abnormal` | `AuthErrorCode` | `bpm.deployment_failed` | `BpmErrorCode` |
| 2104 | `auth.credential_invalid` | `AuthErrorCode` | `bpm.process_not_published` | `BpmErrorCode` |
| 2415 | `bpm.node_function_timeout` | `BpmErrorCode` | `bpm.opinion_form_gone` | `BpmErrorCode` |

**消费规则**

- 仅凭上述数值无法判定所属模块。Web、Mock 与自动化**不得**再按 2101-2104 选择业务文案；
  缺少 `errorKey` 时必须使用安全通用兜底，不猜测模块。
- 若将来需要移除数值歧义，另行立项并做消费者盘点与版本化迁移（本轮非目标）。

## 4. 保留既有通用数值码的语义键

以下语义在 0.1.0 已以 `CommonErrorCode.PARAM_ERROR(400)` 的通用码外显。P61 保留其数值不动，
仅登记稳定 `errorKey`，使调用方可按语义分流而不再按文案子串判断：

| errorKey | 保留数值 | 语义 | 常量位置 |
|---|---|---|---|
| `bpm.draft_no_active_binding` | 400 | 表单当前无有效流程绑定（可保存草稿，正式提交拒绝） | `BpmErrorCode.DRAFT_NO_ACTIVE_BINDING` |
| `bpm.draft_binding_ambiguous` | 400 | 表单存在多个有效绑定或绑定无效（管理配置错误） | `BpmErrorCode.DRAFT_BINDING_AMBIGUOUS` |
| `bpm.draft_not_editable` | 400 | 草稿当前状态不允许编辑 | `BpmErrorCode.DRAFT_NOT_EDITABLE` |
| `bpm.draft_binding_changed` | 400 | 草稿快照绑定的流程已被管理员更新，需用户确认后重试 | `BpmErrorCode.DRAFT_BINDING_CHANGED` |

## 5. 第三方判据来源登记

机器分支只能依赖结构化字段或稳定的协议格式。当前各处判据来源如下：

| 位置 | 判据来源 | 说明 |
|---|---|---|
| `AgentOrchestrationServiceImpl.isQuotaExceededException` | 结构化 `RestClientResponseException.getStatusCode()` 优先；无结构化状态码时锚定解析消息**开头**的状态码 | Spring AI 1.0.4 的 `NonTransientAiException` 不暴露状态码，消息格式固定为 `"<status> - <body>"`。锚定解析只接受以状态码开头的消息，响应体内部出现数字串不构成匹配。升级 Spring AI 时应复核并优先切换到结构化 API |
| `GraalJsRunner` | 本类持有的 `ResourceLimits.onLimit` 标志 | 语句上限由本类设置，触发信号同源；与异常描述文本无关 |
| `IotConnectionService` / `MqttBrokerManager` | Paho `MqttException.getReasonCode()` | 认证失败由协议 reason code 判定 |
| `MqttBrokerManager.classify` 兜底分支 | cause 链的类型判定（`UnknownHostException` / `SSLException` / `ConnectException` / `SocketTimeoutException`），必要时用异常**类名**兜底 | 类名是类型标识，不是可本地化的描述文本 |

## 6. 新增错误码流程

1. 在对应模块 `ErrorCode` 枚举追加常量，显式给出 `code` 与 `errorKey`。
2. `errorKey` 必须满足命名规范且全局唯一（回归测试强制）。
3. 不得使用 §3 已登记的冲突数值。
4. 若数值确有语义重复的必要（历史兼容），必须在本文件登记并说明理由。
5. 新增外显文案必须同时满足 §1 的 `msg` 边界规则。

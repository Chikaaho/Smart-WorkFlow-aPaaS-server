package com.sw.ck.bpm.api.exception;

import com.sw.ck.common.exception.ErrorCode;
import lombok.Getter;

/**
 * BPM 模块错误码（2000-2999 区间）。
 *
 * <p>覆盖图校验、翻译发布、审批人解析、共享参与人与审批、动作/会签/时限/函数与动态并行编排。</p>
 *
 * <p><b>P61 数值歧义登记</b>：2101-2104 与认证模块 {@code AuthErrorCode} 同值不同义，
 * 2415 在本枚举内被 {@link #NODE_FUNCTION_TIMEOUT} 与 {@link #OPINION_FORM_GONE} 双占用。
 * 两者均为已登记冲突值（见 {@code docs/governance/error-code-catalog.md}），
 * 由 {@link #getErrorKey()} 消歧；<b>新增错误不得继续复用这些数值</b>。</p>
 */
@Getter
public enum BpmErrorCode implements ErrorCode {

    // ==================== 图校验（2000-2009） ====================
    GRAPH_MISSING_START(2000, "bpm.graph_missing_start", "流程图缺少开始节点，请检查流程设计"),
    GRAPH_MULTIPLE_START(2001, "bpm.graph_multiple_start", "流程图存在多个开始节点，请只保留一个开始节点"),
    GRAPH_MISSING_END(2002, "bpm.graph_missing_end", "流程图缺少结束节点，请检查流程设计"),
    GRAPH_MULTIPLE_END(2003, "bpm.graph_multiple_end", "流程图存在多个结束节点，请只保留一个结束节点"),
    GRAPH_NODE_EDGE_CARDINALITY(2004, "bpm.graph_node_edge_cardinality", "节点连线数量不符合规则，请检查该节点的入口与出口"),
    GRAPH_ORPHAN_NODE(2005, "bpm.graph_orphan_node", "存在未与流程连接的节点，请连接或删除该节点"),
    GRAPH_EDGE_TARGET_NOT_FOUND(2006, "bpm.graph_edge_target_not_found", "存在指向已不存在节点的连线，请调整连线后重试"),
    GRAPH_ILLEGAL_EDGE(2007, "bpm.graph_illegal_edge", "连线不合法：不能连接节点自身，也不能重复连线"),
    GRAPH_UNKNOWN_NODE_TYPE(2008, "bpm.graph_unknown_node_type", "流程中存在无法识别的节点类型，请删除后重新添加"),
    GRAPH_FORM_NOT_FOUND(2009, "bpm.graph_form_not_found", "绑定表单不存在"),

    // ==================== 通用（20xx） ====================
    PROCESS_DEF_NOT_FOUND(2010, "bpm.process_def_not_found", "流程定义不存在"),

    // ==================== 翻译/发布（21xx；2101-2104 与 AuthErrorCode 同值，已登记弃用） ====================
    FORM_NOT_PUBLISHED(2100, "bpm.bound_form_not_published", "绑定表单未发布，请先发布表单"),
    PROCESS_KEY_FROZEN(2101, "bpm.process_key_frozen", "流程标识在首次发布后不可变更，如需调整请新建流程定义"),
    TRANSLATION_FAILED(2102, "bpm.translation_failed", "流程发布失败：流程图结构校验未通过，请检查节点与连线"),
    DEPLOYMENT_FAILED(2103, "bpm.deployment_failed", "流程发布未成功，请稍后重试；若持续出现请联系管理员"),
    PROCESS_NOT_PUBLISHED(2104, "bpm.process_not_published", "该流程尚未发布，暂无流程图可查看"),
    PROCESS_DEF_PUBLISHED(2105, "bpm.process_def_published", "流程定义已发布，不可修改"),
    NODE_CONFIG_INVALID(2106, "bpm.node_config_invalid", "节点配置不合法"),
    NODE_CAPABILITY_MISSING(2107, "bpm.node_capability_missing", "节点配置不完整，请在流程设计中补全后重试"),
    NODE_REGISTRATION_INVALID(2108, "bpm.node_registration_invalid", "节点配置不完整，请在流程设计中补全后重试"),

    // ==================== 审批人解析（22xx） ====================
    APPROVER_RESOLVE_EMPTY(2200, "bpm.approver_resolve_empty", "未能确定审批人，请检查该节点的审批人配置"),
    APPROVER_TYPE_NOT_IMPLEMENTED(2201, "bpm.approver_type_not_implemented", "该审批人类型暂不可用，请更换审批人来源后重试"),
    APPROVER_CONFIG_MISSING(2202, "bpm.approver_config_missing", "审批人配置缺失"),
    APPROVER_TENANT_ID_MISSING(2203, "bpm.approver_tenant_id_missing", "流程发起信息不完整，无法确定审批人，请重新发起或联系管理员"),

    // ==================== P58 共享参与人与审批（23xx） ====================
    PARTICIPANT_CONFIG_INVALID(2300, "bpm.participant_config_invalid", "参与人配置不合法"),
    PARTICIPANT_TYPE_NOT_IMPLEMENTED(2301, "bpm.participant_type_not_implemented", "该参与人类型暂不可用，请更换参与人来源后重试"),
    PARTICIPANT_RESOLVE_EMPTY(2302, "bpm.participant_resolve_empty", "未能确定参与人，请检查该节点的参与人配置"),
    PARTICIPANT_ADAPTER_NOT_FOUND(2303, "bpm.participant_adapter_not_found", "参与人来源不可用，请重新选择参与人"),
    APPROVAL_ACTION_INVALID(2304, "bpm.approval_action_invalid", "审批动作不合法"),
    APPROVAL_ALREADY_HANDLED(2305, "bpm.approval_already_handled", "节点已被处理"),
    APPROVAL_RETURN_TARGET_INVALID(2306, "bpm.approval_return_target_invalid", "退回目标节点不合法"),
    APPROVAL_OPINION_INVALID(2307, "bpm.approval_opinion_invalid", "审批意见不合法"),
    APPROVAL_OPINION_REQUIRED(2308, "bpm.approval_opinion_required", "审批意见不能为空"),
    COUNTER_CONFIG_INVALID(2309, "bpm.counter_config_invalid", "会签结算配置不合法"),
    BRANCH_CONFIG_INVALID(2310, "bpm.branch_config_invalid", "分支配置不合法"),
    BRANCH_EVALUATION_FAILED(2311, "bpm.branch_evaluation_failed", "分支条件求值失败"),
    NODE_DELIVERY_FAILED(2312, "bpm.node_delivery_failed", "节点投递失败"),
    INSTANCE_FAILED(2313, "bpm.instance_failed", "流程实例已失败，不可继续审批"),
    INSTANCE_INITIATOR_INVALID(2314, "bpm.instance_initiator_invalid", "流程发起人已不可用或不属于当前企业，请重新发起或联系管理员"),

    // ==================== I3 动作/会签/时限/函数（24xx；2415 为枚举内重复值，已登记弃用） ====================
    ACTION_NOT_ALLOWED(2400, "bpm.action_not_allowed", "当前状态不允许该动作"),
    ACTION_SELF_INVALID(2401, "bpm.action_self_invalid", "不能转办/委托/授权给本人"),
    AUTHORIZATION_INVALID(2402, "bpm.authorization_invalid", "代理授权设置存在冲突、循环、已过期或超出范围，请调整后重新设置"),
    WITHDRAW_NOT_PERMITTED(2403, "bpm.withdraw_not_permitted", "当前状态不可撤回"),
    INVALIDATE_NOT_PERMITTED(2404, "bpm.invalidate_not_permitted", "当前主体无权废弃该实例"),
    ADD_SIGN_INVALID(2405, "bpm.add_sign_invalid", "加签/补签请求不合法"),
    SIGN_RECORD_NOT_FOUND(2406, "bpm.sign_record_not_found", "加签/补签记录不存在"),
    DELEGATE_RELATION_INVALID(2407, "bpm.delegate_relation_invalid", "委托关系不合法"),
    COMMUNICATION_INVALID(2408, "bpm.communication_invalid", "沟通请求不合法"),
    CONSENSUS_VETOED(2409, "bpm.consensus_vetoed", "会签被一票否决"),
    DEADLINE_NOT_FOUND(2410, "bpm.deadline_not_found", "时限配置不存在或已失效"),
    AUTO_ACTION_INVALID(2411, "bpm.auto_action_invalid", "受控自动动作配置不合法"),
    NODE_FUNCTION_NOT_FOUND(2412, "bpm.node_function_not_found", "节点函数不存在"),
    NODE_FUNCTION_INVALID_OUTPUT(2413, "bpm.node_function_invalid_output", "节点函数输出不合法"),
    NODE_FUNCTION_FAILED(2414, "bpm.node_function_failed", "节点函数执行失败"),
    NODE_FUNCTION_TIMEOUT(2415, "bpm.node_function_timeout", "节点函数执行超时"),
    OPINION_FORM_GONE(2415, "bpm.opinion_form_gone", "意见表单不可用（未发布或版本缺失）"),
    VERSION_STATE_INVALID(2416, "bpm.version_state_invalid", "发布版本状态不允许该操作"),
    OPINION_FORM_COMPONENT_UNAVAILABLE(2417, "bpm.opinion_form_component_unavailable", "审批意见表单组件不可用"),

    // ==================== I4 动态并行编排（242x） ====================
    DYNAMIC_BRANCH_EMPTY(2418, "bpm.dynamic_branch_empty", "动态并行分支没有可用来源，且未配置兜底策略，请检查并行节点的来源配置"),
    DYNAMIC_BRANCH_LEADER_MISSING(2419, "bpm.dynamic_branch_leader_missing", "动态并行分支的来源存在已停用部门或缺少负责人，且未配置跳过策略，请检查部门与负责人配置"),
    DYNAMIC_BRANCH_LIMIT_EXCEEDED(2420, "bpm.dynamic_branch_limit_exceeded", "动态并行分支数超过安全上限"),
    ;

    // ==================== 保留既有通用数值码的语义键 ====================
    // 以下语义在 0.1.0 已以 CommonErrorCode.PARAM_ERROR(400) 的通用码外显，
    // P61 不重编号：仅为其登记稳定 errorKey，供调用方按语义分流而非按文案子串判断。
    // 登记见 docs/governance/error-code-catalog.md「保留通用数值码的语义键」。

    /** 表单当前没有有效流程绑定（可保存草稿，正式提交拒绝）。 */
    public static final String DRAFT_NO_ACTIVE_BINDING = "bpm.draft_no_active_binding";

    /** 表单存在多个有效绑定或绑定无效（管理配置错误，必须显式失败）。 */
    public static final String DRAFT_BINDING_AMBIGUOUS = "bpm.draft_binding_ambiguous";

    /** 草稿当前状态不允许编辑。 */
    public static final String DRAFT_NOT_EDITABLE = "bpm.draft_not_editable";

    /** 草稿快照绑定的流程已被管理员更新，需用户确认后重试。 */
    public static final String DRAFT_BINDING_CHANGED = "bpm.draft_binding_changed";

    private final int code;
    private final String errorKey;
    private final String message;

    BpmErrorCode(int code, String errorKey, String message) {
        this.code = code;
        this.errorKey = errorKey;
        this.message = message;
    }
}

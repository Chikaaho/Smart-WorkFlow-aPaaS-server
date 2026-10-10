package com.sw.ck.bpm.api.participant;

import java.util.List;
import java.util.Map;

/** 统一人员型节点参与人策略标识。配置值属于产品契约，不是 Bean/class 名称。 */
public final class ParticipantStrategy {

    public static final String FIXED_USER = "FIXED_USER";
    public static final String ROLE = "ROLE";
    /** 部门负责人策略：value 为部门 ID 集合，解析有效部门的有效负责人（I1 组织权威）。 */
    public static final String DEPT_LEADER = "DEPT_LEADER";
    /** 岗位策略：value 为岗位编码集合，解析启用岗位的有效任职用户。 */
    public static final String POST = "POST";
    /** 部门+岗位组合策略：value 为 {deptId, postCode}，解析指定部门内担任指定岗位的有效用户。 */
    public static final String DEPT_POST = "DEPT_POST";
    public static final String EXPRESSION = "EXPRESSION";
    public static final String ADAPTER = "ADAPTER";
    /**
     * 表单字段策略（P63）：value 为 {objectType: USER|DEPT, scope: MAIN|TABLE,
     * field: 主字段名, tableField: 表格字段名, column: 表格列名}。
     * USER=所选人员审批；DEPT=服务端按权威组织关系解析各部门唯一负责人。
     */
    public static final String FORM_FIELD = "FORM_FIELD";
    /**
     * 节点表单人员聚合策略（P64 阶段Ⅱ A07）：value 为 {nodeKey, formField, round}。
     * 读取指定节点指定轮次全部有效最终提交的节点表单人员字段，按用户 ID 并集去重，
     * 生成下一轮会签参与者；round 缺省 CURRENT（当前轮），PREVIOUS=当前轮-1。
     */
    public static final String NODE_FORM_AGGREGATE = "NODE_FORM_AGGREGATE";

    /** 设计/发布校验共用的合法策略白名单（单一权威，翻译器不得另建目录）。 */
    public static final List<String> ALL = List.of(FIXED_USER, ROLE, DEPT_LEADER, POST, DEPT_POST,
            EXPRESSION, ADAPTER, FORM_FIELD, NODE_FORM_AGGREGATE);

    /**
     * NODE_FORM_AGGREGATE value 形状校验（设计/发布/运行共用的单一权威）。
     *
     * @return present = 面向用户的错误说明；empty = 形状合法
     */
    public static java.util.Optional<String> nodeFormAggregateConfigError(Object value) {
        if (!(value instanceof Map<?, ?> mapping)) {
            return java.util.Optional.of("NODE_FORM_AGGREGATE 必须配置 {nodeKey, formField[, round]}");
        }
        if (text(mapping.get("nodeKey")) == null) {
            return java.util.Optional.of("NODE_FORM_AGGREGATE.nodeKey 必须配置来源节点 key");
        }
        if (text(mapping.get("formField")) == null) {
            return java.util.Optional.of("NODE_FORM_AGGREGATE.formField 必须配置人员字段名");
        }
        String round = text(mapping.get("round"));
        if (round != null && !"CURRENT".equalsIgnoreCase(round) && !"PREVIOUS".equalsIgnoreCase(round)) {
            return java.util.Optional.of("NODE_FORM_AGGREGATE.round 只能是 CURRENT 或 PREVIOUS");
        }
        return java.util.Optional.empty();
    }

    /**
     * FORM_FIELD value 形状校验（设计/发布/运行共用的单一权威）。
     *
     * @return present = 面向用户的错误说明；empty = 形状合法
     */
    public static java.util.Optional<String> formFieldConfigError(Object value) {
        if (!(value instanceof Map<?, ?> mapping)) {
            return java.util.Optional.of("FORM_FIELD 必须配置 {objectType, scope, field[, tableField, column]}");
        }
        String objectType = text(mapping.get("objectType"));
        if (!"USER".equalsIgnoreCase(objectType) && !"DEPT".equalsIgnoreCase(objectType)) {
            return java.util.Optional.of("FORM_FIELD.objectType 只能是 USER 或 DEPT");
        }
        String scope = text(mapping.get("scope"));
        if (!"MAIN".equalsIgnoreCase(scope) && !"TABLE".equalsIgnoreCase(scope)) {
            return java.util.Optional.of("FORM_FIELD.scope 只能是 MAIN 或 TABLE");
        }
        if (text(mapping.get("field")) == null) {
            return java.util.Optional.of("FORM_FIELD.field 必须配置来源字段名");
        }
        if ("TABLE".equalsIgnoreCase(scope)) {
            if (text(mapping.get("tableField")) == null || text(mapping.get("column")) == null) {
                return java.util.Optional.of("FORM_FIELD scope=TABLE 时必须配置 tableField 与 column");
            }
        }
        return java.util.Optional.empty();
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private ParticipantStrategy() {
    }
}

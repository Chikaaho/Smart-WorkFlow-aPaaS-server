package com.sw.ck.bpm.api.participant;

import java.util.List;

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

    /** 设计/发布校验共用的合法策略白名单（单一权威，翻译器不得另建目录）。 */
    public static final List<String> ALL = List.of(FIXED_USER, ROLE, DEPT_LEADER, POST, DEPT_POST,
            EXPRESSION, ADAPTER);

    private ParticipantStrategy() {
    }
}

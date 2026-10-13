package com.sw.ck.form.api.facade;

import java.io.Serializable;
import java.util.Map;
import java.util.Optional;

/**
 * P64 阶段Ⅱ（A06 隔离与准确回写）表单数据受控回写 Facade（form-api 定义，form-biz 实现）。
 * <p>
 * 供流程命令消费者（bpm-process 子流程回写）按<strong>稳定来源行身份 + 版本</strong>把允许字段
 * 写回父表单记录（主表字段或 TABLE 子表行列），乐观版本冲突不覆盖、可诊断：
 * <ul>
 *   <li>列名一律过动态宽表白名单（{@code DynamicTableSql#requireColumn}）+ 参数化绑定；</li>
 *   <li>每条 SQL 手写 {@code deleted=0 AND tenant_id=?}；子表行 UPDATE 强制
 *       {@code parent_record_id} 防越权动他人子行；</li>
 *   <li>期望版本不匹配返回 {@code VERSION_CONFLICT}（挂起等待处置），不覆盖任何现有值。</li>
 * </ul>
 * 允许字段由调用方（编排域冻结映射）事先收敛；本门面只做目标合法性与版本守卫。
 * </p>
 */
public interface FormDataWritebackFacade {

    /**
     * 行级/主记录受控回写。
     *
     * @return present = 处理结果（WRITTEN / VERSION_CONFLICT / NOT_FOUND）；
     *         empty = 请求上下文缺失（租户/表单/记录标识为空，或表单不存在）
     */
    Optional<WritebackResult> applyWriteback(WritebackRequest request);

    /**
     * 读取当前版本（派发冻结基准；只读）。
     *
     * @param tableField 表格字段名（null = 主记录版本）
     * @param rowId      稳定行 ID（tableField 非空时必须）
     * @return present = 当前版本；empty = 表单/记录/行不存在或上下文缺失
     */
    Optional<Long> readVersion(Long tenantId, String formKey, String recordId,
                               String tableField, String rowId);

    /**
     * 回写请求：tableField 为空 = 主表字段回写（recordId 行）；非空 = TABLE 子表行回写。
     *
     * @param tenantId           租户 ID（必须）
     * @param formKey            父表单业务标识（必须）
     * @param recordId           父主记录 ID（必须）
     * @param tableField         父表单 TABLE 字段名（行级回写时必须）
     * @param rowId              稳定来源行 ID（行级回写时必须）
     * @param expectedRowVersion 期望行/记录版本（null = 不做版本守卫，仅身份守卫）
     * @param fields             允许回写字段（列名 → 值；必须非空）
     * @param actorId            审计操作人（命令上下文为流程发起人或系统账号）
     */
    record WritebackRequest(Long tenantId, String formKey, String recordId, String tableField,
                            String rowId, Long expectedRowVersion, Map<String, Object> fields,
                            Long actorId) implements Serializable {
    }

    /**
     * 回写结果。
     *
     * @param status     WRITTEN / VERSION_CONFLICT / NOT_FOUND
     * @param rowVersion 写后版本（WRITTEN 时为当前版本；冲突时为当前权威版本）
     */
    record WritebackResult(String status, Long rowVersion) implements Serializable {

        public static final String WRITTEN = "WRITTEN";
        public static final String VERSION_CONFLICT = "VERSION_CONFLICT";
        public static final String NOT_FOUND = "NOT_FOUND";
    }
}

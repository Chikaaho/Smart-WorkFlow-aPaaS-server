package com.sw.ck.form.api.port;

import java.util.Optional;

/**
 * 表单记录流程参与访问 SPI（form-api 定义，bpm-process 实现）。
 * <p>
 * 供表单读取链在数据范围（create_by 归属）遮蔽后判定"合法办理人"通道：
 * 记录被某流程实例引用（businessKey + formKey）时，实例发起人与
 * 实例内任务的办理人/候选参与人可读取该记录——仅限其参与的实例行，
 * 不放大表单级数据范围，不授予其他行可见性。
 * </p>
 * <p>
 * 模块内部调用边界统一返回非空 {@link Optional}：empty 表达查询上下文缺失
 * （租户/用户/记录标识不全，无法判定），present 表达判定结果；调用方对 empty
 * 按不可访问处理（fail-closed）。
 * </p>
 */
public interface FormRecordAccessPort {

    /**
     * 判定用户是否可按流程参与关系访问该表单记录。
     *
     * @param tenantId 租户 ID
     * @param userId   用户 ID
     * @param formKey  表单键（可空 = 不限表单，仅按记录归属）
     * @param recordId 表单记录 ID（= 流程实例 businessKey）
     * @return present = 判定结果（true：用户为引用该记录的实例发起人，或持有/持有过
     *         实例任务，或为节点参与人快照成员；false：不满足任一条件）；
     *         empty = 查询上下文缺失（tenantId/userId/recordId 任一为空），判定未执行
     */
    Optional<Boolean> isProcessParticipant(Long tenantId, Long userId, String formKey, String recordId);
}

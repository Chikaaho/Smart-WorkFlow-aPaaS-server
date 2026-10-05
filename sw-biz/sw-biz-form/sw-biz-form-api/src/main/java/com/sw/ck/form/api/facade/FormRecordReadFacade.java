package com.sw.ck.form.api.facade;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 表单记录读取 Facade（form-api 定义，form-biz 实现；P63 跨模块读接缝）。
 * <p>
 * 供流程引擎/调度等受信服务端上下文按显式租户读取实例表单数据（主字段 + 表格行），
 * 用于参与人解析、动态并行来源等运行时取值。与用户侧详情查询的差异：
 * 不做字段查看权限投影与数据范围（调用方为受信服务端上下文，对象可见性由调用方既有授权承接）；
 * 表格行保留稳定行 {@code id}（来源行追溯，不以行序号代替对象身份）。
 * </p>
 */
public interface FormRecordReadFacade {

    /**
     * 按租户读取已发布表单的一条记录全量数据。
     *
     * @param tenantId 租户（必填；裸 SQL 显式租户条件）
     * @param formKey  表单业务标识
     * @param recordId 主表记录 UUID
     * @return present = 记录数据（fields：主字段名 → 解码值（多选=ID 列表，日期=ISO 文本）；
     *         tables：TABLE 字段名 → 行列表（每行含 {@code id} 稳定行标识 + 子字段解码值））；
     *         empty = 记录不存在或已删除（表单不存在/未发布抛明确异常）
     */
    Optional<FormRecordData> findRecord(Long tenantId, String formKey, String recordId);

    /** 表单记录读取结果（字段值已按定义解码）。 */
    record FormRecordData(String formKey, String recordId,
                          Map<String, Object> fields,
                          Map<String, List<Map<String, Object>>> tables) {
    }
}

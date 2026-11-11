package com.sw.ck.form.api.print;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * 服务端打印（1.0.0 F2）表单侧数据门面。
 * <p>
 * 供 bpm 打印 PDF 服务取数；实现必须走<b>有权限过滤</b>的读取链路
 * （字段级隐藏照样生效），不得暴露无过滤的内部读取（F2-4）。
 * 版本解析（F2-5）：能按提交时间定位到发布快照时用提交时版本；定位不到回退当前版本，
 * 由 {@code FormPrintData.versionPinned=false} 标记，调用方在页脚注明。
 * </p>
 */
public interface FormPrintFacade {

    /**
     * 单条记录打印数据（含子表行、表单元数据与打印用定义）。
     *
     * @param formKey     表单业务标识
     * @param recordId    主表记录 UUID
     * @param submittedAt 实例提交时间（可空；空=无法做提交时版本定位）
     * @return 打印数据；empty=表单/记录不存在或无权读取
     */
    Optional<FormPrintData> recordDetail(String formKey, String recordId,
                                         LocalDateTime submittedAt);

    /**
     * 打印数据载荷。
     */
    record FormPrintData(
            /** 主记录字段值 + 各 TABLE 子表行（key=字段逻辑名） */
            Map<String, Object> record,
            /** 表单名 */
            String formName,
            /** 实际用于打印的定义 JSON（提交时快照或当前定义） */
            String printDefinitionJson,
            /** 实际用于打印的定义版本 */
            Integer printVersion,
            /** true=提交时版本（快照命中）；false=定位不到，按当前版本打印 */
            boolean versionPinned) {
    }
}

package com.sw.ck.bpm.process.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sw.ck.bpm.api.vo.BpmTaskVO;
import com.sw.ck.bpm.process.entity.ApprovalActionRecord;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.CopyRecord;
import lombok.Data;

import java.util.List;
import com.sw.ck.common.value.DynamicData;

/**
 * 抄送详情：抄送记录 + 实例摘要 + 只读表单快照 + 审批进度/流转记录。
 * <p>
 * 实例缺失时 progress/formData/history 保持省略（与原 Map 契约一致）；
 * {@code instance} 始终回显（可为 null）。
 * </p>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MyCopyDetailVO {

    private CopyRecord copy;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private BpmInstance instance;

    private DynamicData formData;

    private List<BpmTaskVO> progress;

    private List<ApprovalActionRecord> history;
}

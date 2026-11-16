package com.sw.ck.bpm.process.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sw.ck.bpm.api.dto.BpmTaskDTO;
import com.sw.ck.bpm.process.entity.ApprovalActionRecord;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.CopyRecord;
import lombok.Data;

import java.util.List;

/**
 * 抄送详情：抄送记录 + 实例摘要 + 只读表单快照 + 审批进度/流转记录。
 * <p>
 * 实例缺失时 progress/formData/history 保持省略（与原 Map 契约一致）；
 * {@code instance} 始终回显（可为 null）。
 * </p>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MyCopyDetailDTO {

    private CopyRecord copy;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private BpmInstance instance;

    private Object formData;

    private List<BpmTaskDTO> progress;

    private List<ApprovalActionRecord> history;
}

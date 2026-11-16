package com.sw.ck.bpm.process.vo;

import com.sw.ck.bpm.process.entity.BpmInstance;
import lombok.Data;

import java.util.List;
import com.sw.ck.common.value.DynamicData;

/**
 * 「我发起的」实例详情。
 * <p>
 * 顶层字段与历史 Map 契约保持一致：{@code instance} 仍为实例实体快照，
 * {@code progress} 为当前活动任务，{@code history} 为流转记录，
 * {@code formData} 为引擎历史变量中的表单快照（可为 null）。
 * </p>
 */
@Data
public class MyInstanceDetailVO {

    private BpmInstance instance;

    private String processName;

    private String formKey;

    private String businessKey;

    private String status;

    private List<MyInstanceProgressNodeVO> progress;

    private List<ApprovalHistoryItemVO> history;

    private DynamicData formData;
}

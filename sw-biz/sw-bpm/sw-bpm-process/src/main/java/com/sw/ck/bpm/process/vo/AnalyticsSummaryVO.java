package com.sw.ck.bpm.process.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;
import com.sw.ck.common.value.DynamicData;

/**
 * 流程运营分析汇总。
 * <p>
 * 固定指标为 DTO 字段；{@code handlerWorkload}/{@code nodeStats} 的 key 分别为
 * 办理人 ID 与节点 key，是动态字典，保留 Map 字段以维持原 JSON 形状。
 * 计数保持 long/int、百分比保持 Double（无可比基期为 null），沿用原序列化口径。
 * </p>
 */
@Data
public class AnalyticsSummaryVO {

    private long launched;

    private long completed;

    private long running;

    private long rejected;

    /** 办理人 ID → 审批动作数。 */
    private DynamicData handlerWorkload;

    private long returnedCount;

    private long rejectedActionCount;

    private long overdueCount;

    /** 节点 key → 停留统计。 */
    private Map<String, NodeStatVO> nodeStats;

    private double avgDurationMs;

    private long p50DurationMs;

    private long p90DurationMs;

    private int durationSample;

    private List<DailyTrendVO> dailyTrend;

    private long overdue;

    /** 与上期相比的启动量变化百分比；无基期为 null。 */
    private Double launchTrendPercent;

    /** 与上期相比的平均时长变化百分比；无基期为 null。 */
    private Double durationTrendPercent;

    private long overdueTrendDelta;

    /** 节点停留统计。 */
    @Data
    public static class NodeStatVO {
        private int count;
        private double avgStayMs;
        private long p90StayMs;
    }

    /** 近 30 天按天聚合的发起量。 */
    @Data
    public static class DailyTrendVO {
        private String date;
        private long count;
    }
}

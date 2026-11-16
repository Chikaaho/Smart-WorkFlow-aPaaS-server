// package com.sw.ck.bpm.process.service;

// import com.sw.ck.bpm.api.facade.BpmRuntimeFacade;
// import com.sw.ck.bpm.api.facade.BpmTaskFacade;
// import com.sw.ck.bpm.process.vo.AnalyticsSummaryVO;
// import com.sw.ck.bpm.process.entity.ApprovalActionRecord;
// import com.sw.ck.bpm.process.entity.BpmTaskDeadline;
// import com.sw.ck.bpm.process.mapper.ApprovalActionRecordMapper;
// import com.sw.ck.bpm.process.mapper.BpmInstanceInterventionMapper;
// import com.sw.ck.bpm.process.mapper.BpmMonitorMapper;
// import com.sw.ck.bpm.process.mapper.BpmTaskDeadlineMapper;
// import com.sw.ck.bpm.process.mapper.DynamicBranchSnapshotMapper;
// import com.sw.ck.bpm.process.service.impl.BpmMonitorServiceImpl;
// import com.sw.ck.common.datascope.DataScopeFilter;
// import com.sw.ck.common.datascope.DataScopeType;
// import com.sw.ck.common.datascope.DeptScopeProvider;
// import com.sw.ck.common.security.LoginContextProvider;

// import org.junit.jupiter.api.BeforeEach;
// import org.junit.jupiter.api.Test;
// import org.mockito.ArgumentMatchers;

// import java.time.LocalDate;
// import java.time.LocalDateTime;
// import java.util.List;
// import java.util.Map;
// import java.util.Optional;

// import static org.junit.jupiter.api.Assertions.assertEquals;
// import static org.junit.jupiter.api.Assertions.assertTrue;
// import static org.mockito.ArgumentMatchers.any;
// import static org.mockito.ArgumentMatchers.anyList;
// import static org.mockito.ArgumentMatchers.isNull;
// import static org.mockito.Mockito.mock;
// import static org.mockito.Mockito.when;

// /**
//  * 门户业务运行概览（7:2）dailyTrend 聚合口径：近 30 天按天计数、MM-dd 日期标签，
//  * 复用 monitorCount 的定义/数据范围口径（趋势缺口修复，direction-blue-primary-020 消费批次）。
//  */
// class BpmMonitorServiceImplTest {

//     private BpmMonitorMapper monitorMapper;
//     private ApprovalActionRecordMapper actionRecordMapper;
//     private BpmTaskDeadlineMapper deadlineMapper;
//     private BpmRuntimeFacade bpmRuntimeFacade;
//     private LoginContextProvider loginContextProvider;
//     private BpmMonitorServiceImpl service;

//     @BeforeEach
//     void setUp() {
//         monitorMapper = mock(BpmMonitorMapper.class);
//         actionRecordMapper = mock(ApprovalActionRecordMapper.class);
//         deadlineMapper = mock(BpmTaskDeadlineMapper.class);
//         bpmRuntimeFacade = mock(BpmRuntimeFacade.class);
//         loginContextProvider = mock(LoginContextProvider.class);
//         when(loginContextProvider.isSuperAdmin()).thenReturn(false);
//         when(loginContextProvider.getDataScopeType()).thenReturn(DataScopeType.ALL);
//         when(actionRecordMapper.selectList(any())).thenReturn(List.<ApprovalActionRecord>of());
//         when(deadlineMapper.selectList(any())).thenReturn(List.<BpmTaskDeadline>of());
//         when(bpmRuntimeFacade.queryHistoricActivities(any())).thenReturn(Optional.empty());
//         when(monitorMapper.monitorList(any(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(),
//                 ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt()))
//                 .thenReturn(List.of());
//         service = new BpmMonitorServiceImpl(monitorMapper,
//                 mock(BpmInstanceService.class),
//                 mock(BpmInstanceInterventionMapper.class),
//                 mock(DynamicBranchSnapshotMapper.class),
//                 actionRecordMapper,
//                 deadlineMapper,
//                 mock(BpmTaskFacade.class),
//                 bpmRuntimeFacade,
//                 loginContextProvider,
//                 mock(DeptScopeProvider.class),
//                 mock(BpmProcessDefService.class));
//     }

//     @Test
//     void dailyTrendCoversThirtyDaysWithMMddLabels() {
//         LocalDateTime now = LocalDateTime.now();
//         when(monitorMapper.monitorCount(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any()))
//                 .thenReturn(42L);
//         when(monitorMapper.monitorCount(isNull(), isNull(), isNull(),
//                 ArgumentMatchers.notNull(), isNull(), isNull(), isNull(), any()))
//                 .thenReturn(17L);
//         when(monitorMapper.monitorCount(isNull(), isNull(), isNull(), isNull(),
//                 ArgumentMatchers.notNull(), ArgumentMatchers.notNull(), isNull(), any()))
//                 .thenAnswer(invocation -> {
//                     LocalDateTime from = invocation.getArgument(4);
//                     return (long) from.getDayOfMonth();
//                 });

//         AnalyticsSummaryVO summary = service.analyticsSummary(null, null, null);

//         List<AnalyticsSummaryVO.DailyTrendDTO> trend = summary.getDailyTrend();
//         assertTrue(trend != null, "趋势应为列表: " + trend);
//         assertEquals(30, trend.size(), "趋势应覆盖近 30 天: " + trend);
//         AnalyticsSummaryVO.DailyTrendDTO first = trend.get(0);
//         AnalyticsSummaryVO.DailyTrendDTO last = trend.get(trend.size() - 1);
//         assertEquals(String.format("%02d-%02d", now.minusDays(29).getMonthValue(), now.minusDays(29).getDayOfMonth()),
//                 first.getDate());
//         assertEquals(String.format("%02d-%02d", now.getMonthValue(), now.getDayOfMonth()), last.getDate());
//         assertEquals((long) now.minusDays(29).getDayOfMonth(), first.getCount());
//         assertEquals((long) now.getDayOfMonth(), last.getCount());
//     }

//     @Test
//     void dailyTrendRespectsExplicitWindowAndDefKey() {
//         LocalDateTime from = LocalDate.of(2026, 1, 10).atStartOfDay();
//         LocalDateTime to = LocalDate.of(2026, 1, 12).atTime(23, 59, 59);
//         when(monitorMapper.monitorCount(ArgumentMatchers.eq("probe_def"), isNull(), isNull(), isNull(),
//                 any(), any(), isNull(), any(DataScopeFilter.class)))
//                 .thenReturn(3L);

//         AnalyticsSummaryVO summary = service.analyticsSummary("probe_def", from, to);

//         List<AnalyticsSummaryVO.DailyTrendDTO> trend = summary.getDailyTrend();
//         assertEquals(3, trend.size());
//         assertEquals("01-10", trend.get(0).getDate());
//         assertEquals("01-12", trend.get(2).getDate());
//         assertEquals(3L, trend.get(1).getCount());
//     }
// }

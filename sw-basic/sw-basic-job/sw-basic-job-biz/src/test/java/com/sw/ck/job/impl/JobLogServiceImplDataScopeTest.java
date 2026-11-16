// package com.sw.ck.job.impl;

// import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
// import com.sw.ck.common.datascope.DataScopeType;
// import com.sw.ck.common.datascope.DataScopeFilter;
// import com.sw.ck.common.datascope.DeptScopeProvider;
// import com.sw.ck.common.page.PageParam;
// import com.sw.ck.common.security.LoginContextProvider;
// import com.sw.ck.job.entity.JobLog;
// import com.sw.ck.job.mapper.JobLogMapper;
// import com.sw.ck.job.service.impl.JobLogServiceImpl;
// import org.junit.jupiter.api.BeforeEach;
// import org.junit.jupiter.api.DisplayName;
// import org.junit.jupiter.api.Test;
// import org.junit.jupiter.api.extension.ExtendWith;
// import org.mockito.ArgumentCaptor;
// import org.mockito.Mock;
// import org.mockito.junit.jupiter.MockitoExtension;
// import org.springframework.test.util.ReflectionTestUtils;

// import java.util.List;

// import static org.assertj.core.api.Assertions.assertThat;
// import static org.mockito.ArgumentMatchers.any;
// import static org.mockito.ArgumentMatchers.eq;
// import static org.mockito.Mockito.verify;
// import static org.mockito.Mockito.when;

// /**
//  * {@link JobLogServiceImpl#page} 数据范围传参验证（纯 Mockito 单测）。
//  * <p>
//  * sw_job_log 无 dept_id 列，等效条件在自定义 Mapper 方法
//  * {@link JobLogMapper#selectJobLogPage} 内实现——本测试验证 Service 正确解析
//  * 数据范围并调用带范围的自定义 Mapper 方法、传参正确（jobId + scope）。
//  * </p>
//  */
// @ExtendWith(MockitoExtension.class)
// @DisplayName("JobLogServiceImpl 数据范围传参测试")
// class JobLogServiceImplDataScopeTest {

//     @Mock
//     private JobLogMapper jobLogMapper;

//     @Mock
//     private LoginContextProvider loginContextProvider;

//     @Mock
//     private DeptScopeProvider deptScopeProvider;

//     private JobLogServiceImpl service;

//     @BeforeEach
//     void setUp() {
//         service = new JobLogServiceImpl(loginContextProvider, deptScopeProvider);
//         // ServiceImpl.baseMapper 为父类 protected 字段，显式注入 mock
//         ReflectionTestUtils.setField(service, "baseMapper", jobLogMapper);
//     }

//     private PageParam pageParam() {
//         PageParam param = new PageParam();
//         param.setPageNum(1);
//         param.setPageSize(50);
//         return param;
//     }

//     private void stubDeptScope() {
//         when(loginContextProvider.isSuperAdmin()).thenReturn(false);
//         when(loginContextProvider.getDataScopeType()).thenReturn(DataScopeType.DEPT);
//         when(loginContextProvider.getDeptId()).thenReturn(5L);
//         Page<JobLog> empty = new Page<>(1, 50);
//         empty.setRecords(List.of());
//         empty.setTotal(0L);
//         when(jobLogMapper.selectJobLogPage(any(Page.class), eq(9L), any(DataScopeFilter.class)))
//                 .thenReturn(empty);
//     }

//     @Test
//     @DisplayName("DEPT 档 → 自定义 Mapper 方法收到 jobId 与 scope.deptIds=[5]")
//     void deptScope_shouldPassJobIdAndDeptIds() {
//         stubDeptScope();

//         service.page(pageParam(), 9L);

//         ArgumentCaptor<DataScopeFilter> captor = ArgumentCaptor.forClass(DataScopeFilter.class);
//         verify(jobLogMapper).selectJobLogPage(any(Page.class), eq(9L), captor.capture());
//         assertThat(captor.getValue().getDeptIds()).containsExactly(5L);
//         assertThat(captor.getValue().getUserId()).isNull();
//     }

//     @Test
//     @DisplayName("SELF 档 → scope.userId=7")
//     void selfScope_shouldPassUserId() {
//         when(loginContextProvider.isSuperAdmin()).thenReturn(false);
//         when(loginContextProvider.getDataScopeType()).thenReturn(DataScopeType.SELF);
//         when(loginContextProvider.getUserId()).thenReturn(7L);
//         Page<JobLog> empty = new Page<>(1, 50);
//         empty.setRecords(List.of());
//         empty.setTotal(0L);
//         when(jobLogMapper.selectJobLogPage(any(Page.class), eq(9L), any(DataScopeFilter.class)))
//                 .thenReturn(empty);

//         service.page(pageParam(), 9L);

//         ArgumentCaptor<DataScopeFilter> captor = ArgumentCaptor.forClass(DataScopeFilter.class);
//         verify(jobLogMapper).selectJobLogPage(any(Page.class), eq(9L), captor.capture());
//         assertThat(captor.getValue().getUserId()).isEqualTo(7L);
//         assertThat(captor.getValue().getDeptIds()).isNull();
//     }

//     @Test
//     @DisplayName("ALL 档 → scope 两个条件字段均为空（不限制）")
//     void allScope_shouldPassNoScope() {
//         when(loginContextProvider.isSuperAdmin()).thenReturn(false);
//         when(loginContextProvider.getDataScopeType()).thenReturn(DataScopeType.ALL);
//         Page<JobLog> empty = new Page<>(1, 50);
//         empty.setRecords(List.of());
//         empty.setTotal(0L);
//         when(jobLogMapper.selectJobLogPage(any(Page.class), eq(9L), any(DataScopeFilter.class)))
//                 .thenReturn(empty);

//         service.page(pageParam(), 9L);

//         ArgumentCaptor<DataScopeFilter> captor = ArgumentCaptor.forClass(DataScopeFilter.class);
//         verify(jobLogMapper).selectJobLogPage(any(Page.class), eq(9L), captor.capture());
//         assertThat(captor.getValue().getUserId()).isNull();
//         assertThat(captor.getValue().getDeptIds()).isNull();
//         assertThat(captor.getValue().isAlwaysFalse()).isFalse();
//     }
// }

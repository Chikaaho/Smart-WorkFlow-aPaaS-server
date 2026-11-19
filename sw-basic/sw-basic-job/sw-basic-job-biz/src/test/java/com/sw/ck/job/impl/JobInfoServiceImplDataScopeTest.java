// package com.sw.ck.job.impl;

// import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

// import com.sw.ck.common.datascope.DataScopeType;
// import com.sw.ck.common.datascope.DataScopeFilter;
// import com.sw.ck.common.datascope.DeptScopeProvider;
// import com.sw.ck.common.page.PageParam;
// import com.sw.ck.common.page.PageResult;
// import com.sw.ck.common.security.LoginContextProvider;
// import com.sw.ck.job.entity.JobInfo;
// import com.sw.ck.job.mapper.JobInfoMapper;
// import com.sw.ck.job.service.impl.JobInfoServiceImpl;

// import org.junit.jupiter.api.BeforeEach;
// import org.junit.jupiter.api.DisplayName;
// import org.junit.jupiter.api.Test;
// import org.junit.jupiter.api.extension.ExtendWith;
// import org.mockito.ArgumentCaptor;
// import org.mockito.ArgumentMatchers;
// import org.mockito.Mock;
// import org.mockito.Mockito;
// import org.mockito.junit.jupiter.MockitoExtension;
// import org.springframework.test.util.ReflectionTestUtils;

// import java.util.List;
// import java.util.Set;

// import static org.assertj.core.api.Assertions.assertThat;
// import static org.mockito.ArgumentMatchers.any;
// import static org.mockito.ArgumentMatchers.isNull;
// import static org.mockito.Mockito.verify;
// import static org.mockito.Mockito.when;

// /**
//  * {@link JobInfoServiceImpl#page} 数据范围传参验证（纯 Mockito 单测）。
//  * <p>
//  * sw_job_info 无 dept_id 列，等效条件在自定义 Mapper 方法
//  * {@link JobInfoMapper#selectJobInfoPage} 内实现——本测试验证 Service 正确解析
//  * 数据范围（经 {@link DataScopeFilter#resolve} 既有 SPI）并调用带范围的
//  * 自定义 Mapper 方法、传参正确（scope + 业务过滤条件）。
//  * </p>
//  */
// @ExtendWith(MockitoExtension.class)
// @DisplayName("JobInfoServiceImpl 数据范围传参测试")
// class JobInfoServiceImplDataScopeTest {

//     @Mock
//     private JobInfoMapper jobInfoMapper;

//     @Mock
//     private LoginContextProvider loginContextProvider;

//     @Mock
//     private DeptScopeProvider deptScopeProvider;

//     private JobInfoServiceImpl service;

//     @BeforeEach
//     void setUp() {
//         service = new JobInfoServiceImpl(loginContextProvider, deptScopeProvider);
//         // ServiceImpl.baseMapper 为父类 protected 字段，显式注入 mock
//         ReflectionTestUtils.setField(service, "baseMapper", jobInfoMapper);
//     }

//     private PageParam pageParam() {
//         PageParam param = new PageParam();
//         param.setPageNum(1);
//         param.setPageSize(50);
//         return param;
//     }

//     private void stubScope(DataScopeType type, Long userId, Long deptId, Set<Long> customIds) {
//         when(loginContextProvider.isSuperAdmin()).thenReturn(false);
//         when(loginContextProvider.getDataScopeType()).thenReturn(type);
//         // resolve 按 scope 类型只取部分字段，未消费的 stub 用 lenient 避免 strict 模式误报
//         Mockito.lenient().when(loginContextProvider.getUserId()).thenReturn(userId);
//         Mockito.lenient().when(loginContextProvider.getDeptId()).thenReturn(deptId);
//         Mockito.lenient().when(loginContextProvider.getCustomDeptIds()).thenReturn(customIds);
//     }

//     private void stubEmptyPage() {
//         Page<JobInfo> empty = new Page<>(1, 50);
//         empty.setRecords(List.of());
//         empty.setTotal(0L);
//         when(jobInfoMapper.selectJobInfoPage(any(Page.class), isNull(), isNull(), isNull(),
//                 any(DataScopeFilter.class))).thenReturn(empty);
//     }

//     @Test
//     @DisplayName("DEPT 档 → 自定义 Mapper 方法收到 scope.deptIds=[5]")
//     void deptScope_shouldPassDeptIds() {
//         stubScope(DataScopeType.DEPT, 1L, 5L, Set.of());
//         stubEmptyPage();

//         service.page(pageParam(), null);

//         ArgumentCaptor<DataScopeFilter> captor = ArgumentCaptor.forClass(DataScopeFilter.class);
//         verify(jobInfoMapper).selectJobInfoPage(any(Page.class), isNull(), isNull(), isNull(), captor.capture());
//         assertThat(captor.getValue().getDeptIds()).containsExactly(5L);
//         assertThat(captor.getValue().getUserId()).isNull();
//         assertThat(captor.getValue().isAlwaysFalse()).isFalse();
//     }

//     @Test
//     @DisplayName("DEPT_AND_CHILD 档 → deptIds = 本部门 + 子部门")
//     void deptAndChildScope_shouldPassDeptAndChildren() {
//         stubScope(DataScopeType.DEPT_AND_CHILD, 1L, 11L, Set.of());
//         when(deptScopeProvider.listChildDeptIds(11L)).thenReturn(List.of(111L, 112L));
//         stubEmptyPage();

//         service.page(pageParam(), null);

//         ArgumentCaptor<DataScopeFilter> captor = ArgumentCaptor.forClass(DataScopeFilter.class);
//         verify(jobInfoMapper).selectJobInfoPage(any(Page.class), isNull(), isNull(), isNull(), captor.capture());
//         assertThat(captor.getValue().getDeptIds()).containsExactlyInAnyOrder(11L, 111L, 112L);
//     }

//     @Test
//     @DisplayName("SELF 档 → scope.userId=7（等效 create_by=7 条件）")
//     void selfScope_shouldPassUserId() {
//         stubScope(DataScopeType.SELF, 7L, 11L, Set.of());
//         stubEmptyPage();

//         service.page(pageParam(), null);

//         ArgumentCaptor<DataScopeFilter> captor = ArgumentCaptor.forClass(DataScopeFilter.class);
//         verify(jobInfoMapper).selectJobInfoPage(any(Page.class), isNull(), isNull(), isNull(), captor.capture());
//         assertThat(captor.getValue().getUserId()).isEqualTo(7L);
//         assertThat(captor.getValue().getDeptIds()).isNull();
//     }

//     @Test
//     @DisplayName("CUSTOM 空关联 → deptIds 空列表（SQL 侧恒假）")
//     void customEmpty_shouldPassEmptyDeptIds() {
//         stubScope(DataScopeType.CUSTOM, 1L, 11L, Set.of());
//         stubEmptyPage();

//         service.page(pageParam(), null);

//         ArgumentCaptor<DataScopeFilter> captor = ArgumentCaptor.forClass(DataScopeFilter.class);
//         verify(jobInfoMapper).selectJobInfoPage(any(Page.class), isNull(), isNull(), isNull(), captor.capture());
//         assertThat(captor.getValue().getDeptIds()).isEmpty();
//     }

//     @Test
//     @DisplayName("ALL 档 → 不限制（scope 两个条件字段均为空）")
//     void allScope_shouldPassNoScope() {
//         stubScope(DataScopeType.ALL, 1L, 11L, Set.of());
//         stubEmptyPage();

//         service.page(pageParam(), null);

//         ArgumentCaptor<DataScopeFilter> captor = ArgumentCaptor.forClass(DataScopeFilter.class);
//         verify(jobInfoMapper).selectJobInfoPage(any(Page.class), isNull(), isNull(), isNull(), captor.capture());
//         assertThat(captor.getValue().getUserId()).isNull();
//         assertThat(captor.getValue().getDeptIds()).isNull();
//         assertThat(captor.getValue().isAlwaysFalse()).isFalse();
//     }

//     @Test
//     @DisplayName("超管 → 短路不限制")
//     void superAdmin_shouldPassNoScope() {
//         when(loginContextProvider.isSuperAdmin()).thenReturn(true);
//         stubEmptyPage();

//         service.page(pageParam(), null);

//         ArgumentCaptor<DataScopeFilter> captor = ArgumentCaptor.forClass(DataScopeFilter.class);
//         verify(jobInfoMapper).selectJobInfoPage(any(Page.class), isNull(), isNull(), isNull(), captor.capture());
//         assertThat(captor.getValue().getUserId()).isNull();
//         assertThat(captor.getValue().getDeptIds()).isNull();
//     }

//     @Test
//     @DisplayName("业务过滤条件透传：jobName/jobType/status 原样传给 Mapper")
//     void businessFilters_shouldBePassedToMapper() {
//         stubScope(DataScopeType.ALL, 1L, 11L, Set.of());
//         Page<JobInfo> empty = new Page<>(1, 50);
//         empty.setRecords(List.of());
//         empty.setTotal(0L);
//         when(jobInfoMapper.selectJobInfoPage(any(Page.class), ArgumentMatchers.eq("n1"),
//                 ArgumentMatchers.eq("BEAN"), ArgumentMatchers.eq("NORMAL"),
//                 any(DataScopeFilter.class))).thenReturn(empty);

//         JobInfo query = new JobInfo();
//         query.setJobName("n1");
//         query.setJobType("BEAN");
//         query.setStatus("NORMAL");
//         PageResult<JobInfo> result = service.page(pageParam(), query);

//         assertThat(result.getTotal()).isZero();
//     }

//     @Test
//     @DisplayName("空串/空白过滤条件归一化为 null（与原 wrapper 判空等价）")
//     void blankFilters_shouldBeNormalizedToNull() {
//         stubScope(DataScopeType.ALL, 1L, 11L, Set.of());
//         stubEmptyPage();

//         JobInfo query = new JobInfo();
//         query.setJobName("   ");
//         query.setJobType("");
//         service.page(pageParam(), query);

//         verify(jobInfoMapper).selectJobInfoPage(any(Page.class), isNull(), isNull(), isNull(),
//                 any(DataScopeFilter.class));
//     }
// }

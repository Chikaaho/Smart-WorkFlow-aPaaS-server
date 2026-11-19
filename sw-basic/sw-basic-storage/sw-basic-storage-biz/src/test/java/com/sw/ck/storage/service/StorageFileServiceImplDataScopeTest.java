// package com.sw.ck.storage.service;

// import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
// import com.sw.ck.common.datascope.DataScopeType;
// import com.sw.ck.common.datascope.DataScopeFilter;
// import com.sw.ck.common.datascope.DeptScopeProvider;
// import com.sw.ck.common.security.LoginContextProvider;
// import com.sw.ck.storage.entity.StorageFile;
// import com.sw.ck.storage.mapper.StorageFileMapper;
// import com.sw.ck.storage.service.impl.StorageFileServiceImpl;
// import org.junit.jupiter.api.BeforeEach;
// import org.junit.jupiter.api.DisplayName;
// import org.junit.jupiter.api.Test;
// import org.junit.jupiter.api.extension.ExtendWith;
// import org.mockito.ArgumentCaptor;
// import org.mockito.Mock;
// import org.mockito.junit.jupiter.MockitoExtension;
// import org.springframework.test.util.ReflectionTestUtils;

// import java.util.List;
// import java.util.Set;

// import static org.assertj.core.api.Assertions.assertThat;
// import static org.mockito.ArgumentMatchers.any;
// import static org.mockito.Mockito.verify;
// import static org.mockito.Mockito.when;

// /**
//  * {@link StorageFileServiceImpl#pageFiles} 数据范围传参验证（纯 Mockito 单测）。
//  * <p>
//  * sw_storage_file 无 dept_id 列，等效条件在自定义 Mapper 方法
//  * {@link StorageFileMapper#selectStorageFilePage} 内实现——本测试验证 Service 正确解析
//  * 数据范围并调用带范围的自定义 Mapper 方法、传参正确（分页参数 + scope）。
//  * </p>
//  */
// @ExtendWith(MockitoExtension.class)
// @DisplayName("StorageFileServiceImpl 数据范围传参测试")
// class StorageFileServiceImplDataScopeTest {

//     @Mock
//     private StorageFileMapper storageFileMapper;

//     @Mock
//     private LoginContextProvider loginContextProvider;

//     @Mock
//     private DeptScopeProvider deptScopeProvider;

//     private StorageFileServiceImpl service;

//     @BeforeEach
//     void setUp() {
//         service = new StorageFileServiceImpl(loginContextProvider, deptScopeProvider);
//         // ServiceImpl.baseMapper 为父类 protected 字段，显式注入 mock
//         ReflectionTestUtils.setField(service, "baseMapper", storageFileMapper);
//     }

//     private void stubPage() {
//         Page<StorageFile> empty = new Page<>(2, 30);
//         empty.setRecords(List.of());
//         empty.setTotal(0L);
//         when(storageFileMapper.selectStorageFilePage(any(Page.class), any(DataScopeFilter.class)))
//                 .thenReturn(empty);
//     }

//     @Test
//     @DisplayName("CUSTOM 档 → 自定义 Mapper 方法收到 scope.deptIds=关联部门并集")
//     void customScope_shouldPassUnionDeptIds() {
//         stubPage();
//         when(loginContextProvider.isSuperAdmin()).thenReturn(false);
//         when(loginContextProvider.getDataScopeType()).thenReturn(DataScopeType.CUSTOM);
//         when(loginContextProvider.getCustomDeptIds()).thenReturn(Set.of(10L, 20L));

//         service.pageFiles(2, 30);

//         ArgumentCaptor<DataScopeFilter> captor = ArgumentCaptor.forClass(DataScopeFilter.class);
//         verify(storageFileMapper).selectStorageFilePage(any(Page.class), captor.capture());
//         assertThat(captor.getValue().getDeptIds()).containsExactlyInAnyOrder(10L, 20L);
//     }

//     @Test
//     @DisplayName("SELF 档 → scope.userId=7（等效 create_by=7 条件）")
//     void selfScope_shouldPassUserId() {
//         stubPage();
//         when(loginContextProvider.isSuperAdmin()).thenReturn(false);
//         when(loginContextProvider.getDataScopeType()).thenReturn(DataScopeType.SELF);
//         when(loginContextProvider.getUserId()).thenReturn(7L);

//         service.pageFiles(1, 20);

//         ArgumentCaptor<DataScopeFilter> captor = ArgumentCaptor.forClass(DataScopeFilter.class);
//         verify(storageFileMapper).selectStorageFilePage(any(Page.class), captor.capture());
//         assertThat(captor.getValue().getUserId()).isEqualTo(7L);
//         assertThat(captor.getValue().getDeptIds()).isNull();
//     }

//     @Test
//     @DisplayName("ALL 档 → scope 两个条件字段均为空（不限制）")
//     void allScope_shouldPassNoScope() {
//         stubPage();
//         when(loginContextProvider.isSuperAdmin()).thenReturn(false);
//         when(loginContextProvider.getDataScopeType()).thenReturn(DataScopeType.ALL);

//         service.pageFiles(1, 20);

//         ArgumentCaptor<DataScopeFilter> captor = ArgumentCaptor.forClass(DataScopeFilter.class);
//         verify(storageFileMapper).selectStorageFilePage(any(Page.class), captor.capture());
//         assertThat(captor.getValue().getUserId()).isNull();
//         assertThat(captor.getValue().getDeptIds()).isNull();
//         assertThat(captor.getValue().isAlwaysFalse()).isFalse();
//     }

//     @Test
//     @DisplayName("分页参数透传：pageFiles(2,30) → Mapper 收到 Page(current=2, size=30)")
//     void pageParams_shouldBePassedToMapper() {
//         stubPage();
//         when(loginContextProvider.isSuperAdmin()).thenReturn(false);
//         when(loginContextProvider.getDataScopeType()).thenReturn(DataScopeType.ALL);

//         service.pageFiles(2, 30);

//         ArgumentCaptor<Page<StorageFile>> pageCaptor = ArgumentCaptor.forClass(Page.class);
//         verify(storageFileMapper).selectStorageFilePage(pageCaptor.capture(), any(DataScopeFilter.class));
//         assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(2);
//         assertThat(pageCaptor.getValue().getSize()).isEqualTo(30);
//     }
// }

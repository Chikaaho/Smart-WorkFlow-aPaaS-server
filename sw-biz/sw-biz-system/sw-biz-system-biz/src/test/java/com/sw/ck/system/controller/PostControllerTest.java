// package com.sw.ck.system.controller;

// import com.sw.ck.common.page.PageParam;
// import com.sw.ck.common.page.PageResult;
// import com.sw.ck.common.response.R;
// import com.sw.ck.system.entity.SysPost;
// import com.sw.ck.system.service.SysPostService;
// import org.junit.jupiter.api.DisplayName;
// import org.junit.jupiter.api.Test;

// import java.util.List;

// import static org.assertj.core.api.Assertions.assertThat;
// import static org.mockito.ArgumentMatchers.*;
// import static org.mockito.Mockito.*;

// /**
//  * {@link PostController} 单元测试。
//  */
// @DisplayName("岗位管理控制器测试")
// class PostControllerTest {

//     private final SysPostService sysPostService = mock(SysPostService.class);
//     private final PostController controller = new PostController();

//     @Test
//     @DisplayName("分页查询 → 返回 PageResult")
//     void page_shouldReturnPageResult() {
//         SysPost post = new SysPost();
//         post.setId(1L);
//         post.setCode("CEO");
//         post.setName("首席执行官");

//         PageResult<SysPost> mockPage = new PageResult<>();
//         mockPage.setRecords(List.of(post));
//         mockPage.setTotal(1L);
//         mockPage.setPageNum(1L);
//         mockPage.setPageSize(10L);

//         when(sysPostService.page(any(PageParam.class), any())).thenReturn(mockPage);

//         R<PageResult<SysPost>> result = controller.page(1, 10, null);

//         assertThat(result.getCode()).isZero();
//         assertThat(result.getData().getRecords()).hasSize(1);
//         assertThat(result.getData().getRecords().get(0).getCode()).isEqualTo("CEO");
//     }

//     @Test
//     @DisplayName("分页 query=null → 不抛异常")
//     void page_withNullQuery_shouldNotThrow() {
//         when(sysPostService.page(any(PageParam.class), eq(null))).thenReturn(new PageResult<>());

//         R<PageResult<SysPost>> result = controller.page(1, 10, null);

//         assertThat(result.getCode()).isZero();
//     }

//     @Test
//     @DisplayName("GET /{id} → 返回岗位详情")
//     void get_shouldReturnPost() {
//         SysPost post = new SysPost();
//         post.setId(1L);
//         post.setCode("CEO");
//         post.setName("首席执行官");

//         when(sysPostService.getById(1L)).thenReturn(post);

//         R<SysPost> result = controller.get(1L);

//         assertThat(result.getCode()).isZero();
//         assertThat(result.getData().getCode()).isEqualTo("CEO");
//     }

//     @Test
//     @DisplayName("创建岗位 → 返回新 ID")
//     void create_shouldReturnId() {
//         SysPost post = new SysPost();
//         post.setCode("CTO");
//         post.setName("首席技术官");
//         post.setSort(10);
//         post.setStatus(1);

//         when(sysPostService.create(post)).thenReturn(300L);

//         R<Long> result = controller.create(post);

//         assertThat(result.getCode()).isZero();
//         assertThat(result.getData()).isEqualTo(300L);
//         verify(sysPostService).create(post);
//     }

//     @Test
//     @DisplayName("更新岗位 → 返回 R.ok()")
//     void update_shouldReturnOk() {
//         SysPost post = new SysPost();
//         post.setId(1L);
//         post.setCode("CEO");
//         post.setName("首席执行官改名");

//         doNothing().when(sysPostService).update(post);

//         R<Void> result = controller.update(post);

//         assertThat(result.getCode()).isZero();
//         verify(sysPostService).update(post);
//     }

//     @Test
//     @DisplayName("DELETE /{id} → 返回 R.ok()")
//     void delete_shouldReturnOk() {
//         doNothing().when(sysPostService).delete(1L);

//         R<Void> result = controller.delete(1L);

//         assertThat(result.getCode()).isZero();
//         verify(sysPostService).delete(1L);
//     }
// }

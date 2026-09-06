package com.sw.ck.bpm.process.service.impl;

import com.sw.ck.bpm.process.dto.CategorySaveReq;
import com.sw.ck.bpm.process.entity.BpmCategory;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.mapper.BpmCategoryMapper;
import com.sw.ck.bpm.process.mapper.BpmProcessDefMapper;
import com.sw.ck.common.exception.BaseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流程分类服务单测（v0.0.2 流程中心）。
 * 覆盖：重名拒绝、空名拒绝、删除约束（有事项归属须先解除）、无归属可删、幂等删除。
 */
@ExtendWith(MockitoExtension.class)
class BpmCategoryServiceTest {

    @Mock
    private BpmCategoryMapper categoryMapper;
    @Mock
    private BpmProcessDefMapper processDefMapper;

    private BpmCategoryServiceImpl categoryService;

    @BeforeEach
    void setUp() {
        categoryService = new BpmCategoryServiceImpl(categoryMapper, processDefMapper);
    }

    @Test
    @DisplayName("创建：重名 → 拒绝")
    void createDuplicateNameRejected() {
        BpmCategory existing = new BpmCategory();
        existing.setId(1L);
        existing.setName("行政办公");
        when(categoryMapper.selectList(any())).thenReturn(List.of(existing));
        assertThatThrownBy(() -> categoryService.createCategory(req("行政办公")))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("已存在");
        verify(categoryMapper, never()).insert(any(BpmCategory.class));
    }

    @Test
    @DisplayName("创建：空名/空白名 → 拒绝")
    void createBlankNameRejected() {
        assertThatThrownBy(() -> categoryService.createCategory(req("  ")))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    @DisplayName("删除：有事项归属 → 拒绝并提示先解除")
    void deleteWithItemsRejected() {
        BpmCategory category = new BpmCategory();
        category.setId(5L);
        category.setName("行政办公");
        when(categoryMapper.selectById(5L)).thenReturn(category);
        when(processDefMapper.selectCount(any())).thenReturn(2L);

        assertThatThrownBy(() -> categoryService.deleteCategory(5L))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("先解除归属");
        verify(categoryMapper, never()).deleteById(5L);
    }

    @Test
    @DisplayName("删除：无归属可删；不存在时幂等成功")
    void deleteOkAndIdempotent() {
        BpmCategory category = new BpmCategory();
        category.setId(5L);
        when(categoryMapper.selectById(5L)).thenReturn(category);
        when(processDefMapper.selectCount(any())).thenReturn(0L);
        categoryService.deleteCategory(5L);
        verify(categoryMapper).deleteById(5L);

        when(categoryMapper.selectById(6L)).thenReturn(null);
        categoryService.deleteCategory(6L);
        verify(categoryMapper, never()).deleteById(6L);
    }

    private CategorySaveReq req(String name) {
        CategorySaveReq r = new CategorySaveReq();
        r.setName(name);
        r.setSortNo(1);
        return r;
    }
}

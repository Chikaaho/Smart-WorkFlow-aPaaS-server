package com.sw.ck.bpm.process.service;

import com.sw.ck.bpm.process.entity.BpmProcessFavorite;
import com.sw.ck.bpm.process.mapper.BpmProcessFavoriteMapper;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 收藏写路径回归（V012-BUG-009 复开批次 15）：
 * uk_sw_bpm_process_favorite 为物理唯一键，取消收藏必须物理删除；
 * 取消后重新收藏（unfavorite → favorite 回环）不得因软删键位残留撞唯一约束。
 */
@DisplayName("收藏物理删除与重新收藏回环")
@ExtendWith(MockitoExtension.class)
class BpmProcessFavoriteServiceTest {

    @Mock
    private BpmProcessFavoriteMapper favoriteMapper;

    private BpmProcessFavoriteService newService() {
        return new BpmProcessFavoriteService(favoriteMapper, null, null);
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    private void login() {
        LoginUser user = new LoginUser();
        user.setUserId(1L);
        user.setTenantId(0L);
        LoginUserHolder.set(user);
    }

    @Test
    @DisplayName("取消收藏走物理删除（软删行仍占用物理唯一键位）")
    void unfavorite_shouldDeletePhysically() {
        login();
        BpmProcessFavoriteService service = newService();

        service.unfavorite("bpm_abc");

        verify(favoriteMapper).deletePhysically(1L, "bpm_abc");
        verify(favoriteMapper, never()).delete(any());
    }

    @Test
    @DisplayName("未收藏时先物理清键位再插入（unfavorite→favorite 回环不撞唯一约束）")
    void favorite_afterUnfavorite_shouldCleanupResidueThenInsert() {
        login();
        when(favoriteMapper.selectOne(any())).thenReturn(null);
        BpmProcessFavoriteService service = newService();

        service.favorite("bpm_abc", "测试1");

        verify(favoriteMapper).deletePhysically(1L, "bpm_abc");
        ArgumentCaptor<BpmProcessFavorite> captor = ArgumentCaptor.forClass(BpmProcessFavorite.class);
        verify(favoriteMapper).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(1L);
        assertThat(captor.getValue().getProcessKey()).isEqualTo("bpm_abc");
        assertThat(captor.getValue().getProcessName()).isEqualTo("测试1");
    }

    @Test
    @DisplayName("已收藏时刷新名称与时间，不再插入")
    void favorite_existing_shouldUpdateOnly() {
        login();
        BpmProcessFavorite existing = new BpmProcessFavorite();
        existing.setUserId(1L);
        existing.setProcessKey("bpm_abc");
        existing.setProcessName("旧名");
        when(favoriteMapper.selectOne(any())).thenReturn(existing);
        BpmProcessFavoriteService service = newService();

        service.favorite("bpm_abc", "新名");

        verify(favoriteMapper, never()).deletePhysically(eq(1L), eq("bpm_abc"));
        verify(favoriteMapper, never()).insert(any(BpmProcessFavorite.class));
        assertThat(existing.getProcessName()).isEqualTo("新名");
        verify(favoriteMapper).updateById(existing);
    }
}

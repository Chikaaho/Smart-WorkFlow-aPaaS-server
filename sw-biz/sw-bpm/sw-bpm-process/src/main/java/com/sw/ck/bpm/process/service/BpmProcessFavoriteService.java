package com.sw.ck.bpm.process.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.bpm.process.dto.FavoriteItemDTO;
import com.sw.ck.bpm.process.entity.BpmInstance;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.entity.BpmProcessFavorite;
import com.sw.ck.bpm.process.mapper.BpmInstanceMapper;
import com.sw.ck.bpm.process.mapper.BpmProcessDefMapper;
import com.sw.ck.bpm.process.mapper.BpmProcessFavoriteMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.security.holder.LoginUserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 流程收藏 / 常用 / 最近使用（V012-BUG-009）。
 * <p>
 * 口径（方向 §3-009 授权下的执行口径，随回执提交规划确认）：
 * <ul>
 *   <li>常用流程 = 租户内发起次数总量最多的前 N 个流程（Owner 原文「发起流程数
 *       总量最多」，聚合口径为未删除实例总数，全部发起人）；</li>
 *   <li>最近使用 = 本人最近一次发起时间倒序的前 N 个流程（复用既有「我发起的」
 *       契约：本人发起实例的时间倒序去重，不含办理他人单据）。</li>
 * </ul>
 * 两者均只统计未删除实例；收藏数据用户级租户内隔离。
 * </p>
 */
@Service
public class BpmProcessFavoriteService {

    private final BpmProcessFavoriteMapper favoriteMapper;
    private final BpmInstanceMapper instanceMapper;
    private final BpmProcessDefMapper processDefMapper;

    public BpmProcessFavoriteService(BpmProcessFavoriteMapper favoriteMapper,
                                     BpmInstanceMapper instanceMapper,
                                     BpmProcessDefMapper processDefMapper) {
        this.favoriteMapper = favoriteMapper;
        this.instanceMapper = instanceMapper;
        this.processDefMapper = processDefMapper;
    }

    /** 当前用户收藏列表（最近收藏在前 → 列表置顶语义）。 */
    public List<FavoriteItemDTO> listMyFavorites() {
        return favoriteMapper.selectList(Wrappers.lambdaQuery(BpmProcessFavorite.class)
                        .eq(BpmProcessFavorite::getUserId, currentUserId())
                        .orderByDesc(BpmProcessFavorite::getUpdateTime))
                .stream()
                .map(f -> FavoriteItemDTO.builder()
                        .processKey(f.getProcessKey())
                        .name(f.getProcessName())
                        .build())
                .toList();
    }

    /** 收藏（幂等；已收藏则刷新名称与时间）。 */
    @Transactional(rollbackFor = Exception.class)
    public void favorite(String processKey, String processName) {
        BpmProcessFavorite existing = favoriteMapper.selectOne(
                Wrappers.lambdaQuery(BpmProcessFavorite.class)
                        .eq(BpmProcessFavorite::getUserId, currentUserId())
                        .eq(BpmProcessFavorite::getProcessKey, processKey));
        if (existing == null) {
            BpmProcessFavorite row = new BpmProcessFavorite();
            row.setUserId(currentUserId());
            row.setProcessKey(processKey);
            row.setProcessName(processName);
            row.setCreateTime(LocalDateTime.now());
            row.setUpdateTime(LocalDateTime.now());
            favoriteMapper.insert(row);
        } else {
            existing.setProcessName(processName);
            existing.setUpdateTime(LocalDateTime.now());
            favoriteMapper.updateById(existing);
        }
    }

    /** 取消收藏（幂等：不存在视为已取消）。 */
    @Transactional(rollbackFor = Exception.class)
    public void unfavorite(String processKey) {
        favoriteMapper.delete(Wrappers.lambdaQuery(BpmProcessFavorite.class)
                .eq(BpmProcessFavorite::getUserId, currentUserId())
                .eq(BpmProcessFavorite::getProcessKey, processKey));
    }

    /** 常用流程：租户内发起次数总量 TopN。 */
    public List<FavoriteItemDTO> frequentlyStarted(int limit) {
        List<Map<String, Object>> rows = instanceMapper.selectMaps(
                Wrappers.query(BpmInstance.class)
                        .select("process_def_key as processKey", "count(*) as cnt")
                        .groupBy("process_def_key")
                        .orderByDesc("cnt")
                        .orderByAsc("process_def_key")
                        .last("limit " + capped(limit)));
        return toItems(rows.stream().map(r -> String.valueOf(r.get("processKey"))).toList());
    }

    /** 最近使用：本人最近发起时间倒序 TopN。 */
    public List<FavoriteItemDTO> recentlyUsed(int limit) {
        List<Map<String, Object>> rows = instanceMapper.selectMaps(
                Wrappers.query(BpmInstance.class)
                        .select("process_def_key as processKey", "max(create_time) as lastTime")
                        .eq("initiator_id", currentUserId())
                        .groupBy("process_def_key")
                        .orderByDesc("lastTime")
                        .last("limit " + capped(limit)));
        return toItems(rows.stream().map(r -> String.valueOf(r.get("processKey"))).toList());
    }

    private int capped(int limit) {
        return Math.max(1, Math.min(limit, 20));
    }

    /** 名称解析：优先流程定义名；定义已删除时回退收藏快照名/键名。 */
    private List<FavoriteItemDTO> toItems(List<String> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        Map<String, String> names = new HashMap<>();
        for (BpmProcessDef def : processDefMapper.selectList(
                Wrappers.lambdaQuery(BpmProcessDef.class).in(BpmProcessDef::getProcessKey, keys))) {
            names.put(def.getProcessKey(), def.getName());
        }
        return keys.stream()
                .map(key -> FavoriteItemDTO.builder()
                        .processKey(key)
                        .name(names.getOrDefault(key, key))
                        .build())
                .toList();
    }

    private Long currentUserId() {
        var user = LoginUserHolder.get();
        if (user == null || user.getUserId() == null) {
            throw new BaseException(CommonErrorCode.FORBIDDEN.getCode(), "未登录");
        }
        return user.getUserId();
    }
}

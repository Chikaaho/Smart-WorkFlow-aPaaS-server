package com.sw.ck.bpm.process.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sw.ck.bpm.process.entity.BpmProcessFavorite;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BpmProcessFavoriteMapper extends BaseMapper<BpmProcessFavorite> {

    /**
     * 物理删除收藏行（绕过 @TableLogic 软删）。
     * <p>
     * uk_sw_bpm_process_favorite(tenant_id, user_id, process_key) 为物理唯一键，
     * 软删行仍占用键位；取消收藏必须物理删除，否则同一流程无法再次收藏。
     * 租户隔离由多租户拦截器追加 tenant_id 条件。
     * </p>
     */
    @Delete("DELETE FROM sw_bpm_process_favorite WHERE user_id = #{userId} AND process_key = #{processKey}")
    int deletePhysically(@Param("userId") Long userId, @Param("processKey") String processKey);
}

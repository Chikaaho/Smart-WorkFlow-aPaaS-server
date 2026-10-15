package com.sw.ck.form.mapper;

import com.sw.ck.common.mapper.BaseMapperX;
import com.sw.ck.form.entity.FormAppUserEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 应用指定可见用户 Mapper。
 */
@Mapper
public interface FormAppUserMapper extends BaseMapperX<FormAppUserEntity> {

    /**
     * 物理删除应用的指定用户集合（可见范围重配专用）。
     * <p>
     * 关联行是纯配置关系，不走逻辑删除：唯一键 (tenant_id, app_id, user_id)
     * 若重配时仅做软删，历史已删行会让「移除后再加入」撞唯一键。
     * 租户条件由公共租户拦截器注入。
     * </p>
     */
    @Delete("DELETE FROM sw_form_app_user WHERE app_id = #{appId}")
    int deletePhysicallyByAppId(@Param("appId") String appId);
}

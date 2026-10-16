package com.sw.ck.form.mapper;

import com.sw.ck.common.mapper.BaseMapperX;
import com.sw.ck.form.entity.FormAppScopeEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 应用可见范围维度 Mapper（0.1.7 #1）。
 */
@Mapper
public interface FormAppScopeMapper extends BaseMapperX<FormAppScopeEntity> {

    /**
     * 物理删除应用的可见范围维度集合（重配专用）。
     * <p>
     * 与 sw_form_app_user 同一口径：关联行是纯配置关系，重配走物理删除重建，
     * 避免软删历史行影响判定。租户条件由公共租户拦截器注入。
     * </p>
     */
    @Delete("DELETE FROM sw_form_app_scope WHERE app_id = #{appId}")
    int deletePhysicallyByAppId(@Param("appId") String appId);
}

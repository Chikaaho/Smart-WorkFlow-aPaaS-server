package com.sw.ck.system.mapper;

import com.sw.ck.common.mapper.BaseMapperX;
import com.sw.ck.system.entity.NotifySubjectBinding;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 通知 Provider 主体映射 Mapper（I6 G5a-I / V91）。
 * <p>全部查询显式手写 tenant_id 条件（工程宪法 §5：每一条裸 SQL 同时手写
 * deleted + tenant_id）。</p>
 */
@Mapper
public interface NotifySubjectBindingMapper extends BaseMapperX<NotifySubjectBinding> {

    /** 当前租户内该用户对 Provider 的 ACTIVE 绑定（同租户权威，唯一索引兜底）。 */
    @Select("SELECT * FROM sw_notify_subject_binding WHERE tenant_id = #{tenantId} "
            + "AND user_id = #{userId} AND provider = #{provider} "
            + "AND bind_status = 'ACTIVE' AND deleted = 0 LIMIT 1")
    NotifySubjectBinding selectActive(@Param("tenantId") Long tenantId,
                                      @Param("userId") Long userId,
                                      @Param("provider") String provider);
}

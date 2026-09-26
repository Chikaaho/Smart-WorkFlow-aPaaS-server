package com.sw.ck.bootstrap.verify;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 多数据源 + 租户隔离验证 Mapper。
 * <p>
 * 注意：Mapper 上不加 @DS——Mapper 级切源由 dynamic-datasource 的 MyBatis Plugin 处理，
 * 该 Plugin 在 MyBatis-Plus 拦截器链之后才切换上下文，导致 TenantLineHandler 拿不到正确的 DS。
 * 正确的做法是在 Service 层用 @DS（Spring AOP，先切源再进 MyBatis）。
 * 本验证在 CommandLineRunner 中手动 push/poll DS 模拟 Service 层切源。
 * </p>
 */
@Mapper
public interface VerifyMapper {

    /** 主库查询：sys_tenant 表（默认 DS = master，TenantLineHandler 应追加 tenant_id） */
    @Select("SELECT id, name, code, tenant_id FROM sys_tenant")
    List<Map<String, Object>> selectFromMaster();

    /** 扩展库查询：verify_iot 表（手动 push("iot") 后调用，TenantLineHandler 应跳过） */
    @Select("SELECT id, name FROM verify_iot")
    List<Map<String, Object>> selectFromIot();
}

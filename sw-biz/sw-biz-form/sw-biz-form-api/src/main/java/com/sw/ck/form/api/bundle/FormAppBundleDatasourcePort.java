package com.sw.ck.form.api.bundle;

import java.util.Optional;

/**
 * 应用导入导出的外部数据源端口（0.2.2 F2，D022-13）。
 * <p>
 * 导入时外部数据源按名称匹配：名称命中当前租户的数据源则复用其 id，
 * 不携带连接信息与密码。数据源归 bpm 模块所有，经本端口（form-api 声明、
 * sw-bpm-engine 提供 Bean）交互；未装配（sw.bpm 关闭）视为无匹配。
 * </p>
 */
public interface FormAppBundleDatasourcePort {

    /** 外部数据源按名称匹配（当前租户；多个同名取 id 最小，不匹配返回 empty）。 */
    Optional<Long> findDatasourceIdByName(String name);

    /** 导出时按 id 读取数据源只读名称（当前租户；仅名称出包用于导入匹配，连接信息与密码不参与）。 */
    Optional<String> findDatasourceNameById(Long id);
}

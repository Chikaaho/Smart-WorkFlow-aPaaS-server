package com.sw.ck.system.controller;

import com.sw.ck.common.datascope.DataScope;
import com.sw.ck.system.mapper.SysUserMapper;
import com.sw.ck.system.service.UserPageQuery;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/** 不依赖 Mockito 的用户关联契约测试，保证关键安全边界不会随重构丢失。 */
class UserAssociationContractTest {
    @Test
    void createAndUpdateMustBeTransactional() throws NoSuchMethodException {
        assertThat(UserController.class.getMethod("create", UserController.UserFormRequest.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(UserController.class.getMethod("update", UserController.UserFormRequest.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }

    @Test
    void associationQueryMustRemainDataScoped() throws NoSuchMethodException {
        DataScope scope = SysUserMapper.class.getMethod("selectUserPageByQuery", Page.class,
                UserPageQuery.class).getAnnotation(DataScope.class);
        assertThat(scope).isNotNull();
        assertThat(scope.deptAlias()).isEqualTo("u");
        assertThat(scope.userAlias()).isEqualTo("u");
    }
}

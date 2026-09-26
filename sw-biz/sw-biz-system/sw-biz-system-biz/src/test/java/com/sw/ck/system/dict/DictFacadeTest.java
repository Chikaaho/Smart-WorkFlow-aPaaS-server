package com.sw.ck.system.dict;

import com.sw.ck.common.datascope.DataScopeType;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.api.dict.DictFacade;
import com.sw.ck.system.api.dict.DictItemDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.mapper.MapperScannerConfigurer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DictFacade 最小验证测试。
 * <p>
 * 在显式设置 super tenant（tenant_id=0）租户上下文的条件下，
 * 验证各方法的正确性。若未设置租户上下文，{@code TenantLineHandler}
 * 会因无 tenant_id 而添加 {@code WHERE tenant_id = '0'}（兜底值），
 * 在仅有 tenant_id=0 种子数据的测试库中仍然正常返回——这本身是预期行为
 * （超级租户始终可访问），但验证仍显式设置上下文以确保可追溯。
 * </p>
 *
 * <p>覆盖的方法：</p>
 * <ul>
 *   <li>{@link DictFacade#listByType(String)}</li>
 *   <li>{@link DictFacade#isValidCode(String, String)}</li>
 * </ul>
 */
@SpringBootTest(
        classes = DictFacadeTest.TestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
class DictFacadeTest {

    @Autowired
    private DictFacade dictFacade;

    @BeforeEach
    void setUp() {
        // 显式设置 super tenant 上下文，使 TenantLineHandler 注入 tenant_id=0
        LoginUser user = new LoginUser();
        user.setUserId(1L);
        user.setTenantId(0L);
        LoginUserHolder.set(user);
    }

    @AfterEach
    void tearDown() {
        LoginUserHolder.clear();
    }

    // ==================== listByType ====================

    @Test
    void listByType_should_return_all_items_sorted() {
        // sys_common_status: 2 项，按 sort 排序 → "正常"(0/0)、"停用"(1/1)
        Optional<List<DictItemDTO>> result = dictFacade.listByType("sys_common_status");

        assertThat(result)
                .as("listByType('sys_common_status') 应 present 2 条有效字典项")
                .isPresent();
        List<DictItemDTO> items = result.orElseThrow();
        assertThat(items)
                .as("listByType('sys_common_status') 应返回 2 条有效字典项")
                .hasSize(2);
        assertThat(items)
                .extracting(DictItemDTO::getCode)
                .containsExactly("0", "1");
        assertThat(items)
                .extracting(DictItemDTO::getLabel)
                .containsExactly("正常", "停用");
    }

    @Test
    void listByType_should_return_empty_for_unknown_dict_type() {
        Optional<List<DictItemDTO>> result = dictFacade.listByType("nonexistent_dict_type");

        // 类型不存在属于合法零匹配：以 present 空集合表达，而非 empty
        assertThat(result)
                .as("不存在的 dictType 应以 present 空列表表达")
                .isPresent();
        assertThat(result.orElseThrow())
                .as("不存在的 dictType 应返回空列表")
                .isEmpty();
    }

    // ==================== isValidCode ====================

    @Test
    void isValidCode_should_return_true_for_existing_value() {
        // sys_yes_no 中存在 dict_value = '1'（"是"）
        boolean result = dictFacade.isValidCode("sys_yes_no", "1").orElseThrow();

        assertThat(result)
                .as("isValidCode('sys_yes_no', '1') 应为 true")
                .isTrue();
    }

    @Test
    void isValidCode_should_return_false_for_nonexistent_value() {
        boolean result = dictFacade.isValidCode("sys_yes_no", "不存在的值").orElseThrow();

        assertThat(result)
                .as("isValidCode('sys_yes_no', '不存在的值') 应为 false")
                .isFalse();
    }

    // ==================== 测试上下文配置 ====================

    /**
     * 最小化 Spring 上下文：仅加载字典模块必需的 Bean + MyBatis-Plus 基础设施。
     * <ul>
     *   <li>通过 {@code MapperScannerConfigurer} 扫描 {@code com.sw.ck.system.mapper}</li>
     *   <li>字典相关的业务 Bean 通过自动组件扫描在 {@code com.sw.ck.system.service.impl} 下载入</li>
     *   <li>通过 {@code @EnableAutoConfiguration} 加载 MyBatis-Plus、DataSource 等基础设置</li>
     * </ul>
     * 不相关的模块（AuthController、SysUserService、SystemAutoConfiguration 等）在
     * {@code application-test.yml} 的 {@code spring.autoconfigure.exclude} 中排除。
     */
    @Configuration
    @EnableAutoConfiguration
    @ComponentScan("com.sw.ck.system.service.impl")
    static class TestConfig {

        /**
         * 手动注册 Mapper 扫描器：扫描 {@code com.sw.ck.system.mapper} 下的所有 MyBatis-Plus 映射器。
         * MapperScannerConfigurer 是 BeanDefinitionRegistryPostProcessor，
         * 在常规 bean 实例化之前执行，确保 Mapper 代理可被自动注入。
         */
        @Bean
        public static MapperScannerConfigurer mapperScannerConfigurer() {
            MapperScannerConfigurer configurer = new MapperScannerConfigurer();
            configurer.setBasePackage("com.sw.ck.system.mapper");
            return configurer;
        }

        /**
         * 自定义 LoginContextProvider：从 {@link LoginUserHolder} 读取租户上下文，
         * 使 {@code TenantLineHandler} 能感知测试中显式设置的 tenant_id。
         * 由于 {@code SecurityAutoConfiguration} 已被排除，此 Bean 会替代
         * {@link com.sw.ck.common.security.DefaultLoginContextProvider} 生效。
         */
        @Bean
        public LoginContextProvider testLoginContextProvider() {
            return new LoginContextProvider() {
                @Override
                public Long getUserId() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null ? user.getUserId() : null;
                }

                @Override
                public Long getTenantId() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null ? user.getTenantId() : null;
                }

                @Override
                public Long getDeptId() {
                    LoginUser user = LoginUserHolder.get();
                    return user != null ? user.getDeptId() : null;
                }

                @Override
                public DataScopeType getDataScopeType() {
                    return DataScopeType.ALL;
                }

                @Override
                public Set<Long> getCustomDeptIds() {
                    return Set.of();
                }

                @Override
                public boolean isSuperAdmin() {
                    return true;
                }
            };
        }

        /**
         * PasswordEncoder（被 {@link com.sw.ck.system.service.impl.SysUserServiceImpl} 依赖）。
         * 因 {@code SystemAutoConfiguration} 已被排除，在此显式提供。
         */
        @Bean
        public PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(10);
        }
    }

    // ==================== §4.4 两层语义：上下文缺失 vs 合法零匹配 ====================

    @Test
    void listByType_should_return_empty_optional_when_dict_type_missing() {
        assertThat(dictFacade.listByType(" ")).as("字典类型空白：上下文缺失 → empty").isEmpty();
        assertThat(dictFacade.listByType(null)).as("字典类型为空：上下文缺失 → empty").isEmpty();
    }

    @Test
    void isValidCode_should_return_empty_optional_when_context_missing() {
        assertThat(dictFacade.isValidCode(" ", "1")).as("字典类型空白：无法判定 → empty").isEmpty();
        assertThat(dictFacade.isValidCode("sys_yes_no", " ")).as("字典值为空：无法判定 → empty").isEmpty();
        assertThat(dictFacade.isValidCode(null, null)).as("两者皆空：无法判定 → empty").isEmpty();
    }
}

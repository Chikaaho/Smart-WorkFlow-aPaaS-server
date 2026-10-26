package com.sw.ck.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sw.ck.agent.dto.AgentToolExternalConfigDTO;
import com.sw.ck.agent.dto.AgentToolExternalConfigQuery;
import com.sw.ck.agent.dto.AgentToolInternalConfigDTO;
import com.sw.ck.agent.dto.AgentToolInternalConfigQuery;
import com.sw.ck.agent.entity.tool.AgentToolExternalConfig;
import com.sw.ck.agent.entity.tool.AgentToolInternalConfig;
import com.sw.ck.agent.mapper.tool.AgentToolExternalConfigMapper;
import com.sw.ck.agent.mapper.tool.AgentToolInternalConfigMapper;
import com.sw.ck.agent.service.AgentToolConfigService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.service.BaseServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 工具沙箱配置管理 Service 实现（M07 Step3）。
 * <p>
 * 内部工具以 {@code BaseServiceImpl<AgentToolInternalConfigMapper, ...>} 为基座，
 * 外部工具经注入的 {@link AgentToolExternalConfigMapper} 操作（双表同 Service 的
 * 最小化组合方式）。本类不含 testConnection 端点（方案 §7.2：外部工具连通性测试留后续）。
 * </p>
 * <p>
 * 参数校验为 Service 层手动校验（DTO 无 bean-validation 注解，模块类路径无
 * jakarta.validation-api，沿用 Step1/Step2 惯例）。
 * </p>
 */
@Service
public class AgentToolConfigServiceImpl
        extends BaseServiceImpl<AgentToolInternalConfigMapper, AgentToolInternalConfig>
        implements AgentToolConfigService {

    /** 外部工具 HTTP 方法白名单 */
    private static final Set<String> SUPPORTED_HTTP_METHODS = Set.of("GET", "POST", "PUT");

    /** 外部工具副作用分类白名单；空值表示未分类，保留既有行可读取。 */
    private static final Set<String> SUPPORTED_EFFECT_SCOPES = Set.of("QUERY", "WRITE");

    /** 外部工具默认超时（秒），与 V20 表结构 DEFAULT 30 一致 */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final AgentToolExternalConfigMapper externalMapper;

    public AgentToolConfigServiceImpl(AgentToolExternalConfigMapper externalMapper) {
        this.externalMapper = externalMapper;
    }

    // ==================== 内部工具 ====================

    @Override
    public PageResult<AgentToolInternalConfigDTO> pageInternalTools(AgentToolInternalConfigQuery query) {
        LambdaQueryWrapper<AgentToolInternalConfig> wrapper = Wrappers.lambdaQuery();
        if (query.getNameKeyword() != null && !query.getNameKeyword().isBlank()) {
            wrapper.like(AgentToolInternalConfig::getName, query.getNameKeyword());
        }
        // enabled 条件用数字字面量（Boolean 参数与 SMALLINT 列比较在 H2/PG 下不稳定，
        // M07 Step4 现场实证 H2 90110，见 step-4-execution 回执 §7）
        wrapper.eq(query.getEnabled() != null, AgentToolInternalConfig::getEnabled,
                Boolean.TRUE.equals(query.getEnabled()) ? 1 : 0);
        wrapper.orderByDesc(AgentToolInternalConfig::getId);
        Page<AgentToolInternalConfig> page = page(new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        return PageResult.of(page.convert(this::toInternalDTO));
    }

    @Override
    public AgentToolInternalConfigDTO getInternalTool(Long id) {
        return toInternalDTO(requireInternalEntity(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createInternalTool(AgentToolInternalConfigDTO dto) {
        validateInternal(dto);
        AgentToolInternalConfig entity = toInternalEntity(dto);
        save(entity);
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateInternalTool(Long id, AgentToolInternalConfigDTO dto) {
        requireInternalEntity(id);
        validateInternal(dto);
        AgentToolInternalConfig entity = toInternalEntity(dto);
        entity.setId(id);
        updateById(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteInternalTool(Long id) {
        // 逻辑删除（@TableLogic），幂等：不存在也直接返回成功
        removeById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void toggleInternalTool(Long id, boolean enabled) {
        AgentToolInternalConfig entity = requireInternalEntity(id);
        entity.setEnabled(enabled);
        updateById(entity);
    }

    @Override
    public List<AgentToolInternalConfig> listEnabledInternalTools(Long tenantId) {
        return baseMapper.selectList(
                Wrappers.<AgentToolInternalConfig>lambdaQuery()
                        .eq(AgentToolInternalConfig::getEnabled, 1)
                        .eq(tenantId != null, AgentToolInternalConfig::getTenantId, tenantId)
                        .orderByDesc(AgentToolInternalConfig::getId));
    }

    // ==================== 外部工具 ====================

    @Override
    public PageResult<AgentToolExternalConfigDTO> pageExternalTools(AgentToolExternalConfigQuery query) {
        LambdaQueryWrapper<AgentToolExternalConfig> wrapper = Wrappers.lambdaQuery();
        if (query.getNameKeyword() != null && !query.getNameKeyword().isBlank()) {
            wrapper.like(AgentToolExternalConfig::getName, query.getNameKeyword());
        }
        // enabled 条件用数字字面量（Boolean 参数与 SMALLINT 列比较在 H2/PG 下不稳定，
        // M07 Step4 现场实证 H2 90110，见 step-4-execution 回执 §7）
        wrapper.eq(query.getEnabled() != null, AgentToolExternalConfig::getEnabled,
                Boolean.TRUE.equals(query.getEnabled()) ? 1 : 0);
        wrapper.orderByDesc(AgentToolExternalConfig::getId);
        Page<AgentToolExternalConfig> page = externalMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wrapper);
        return PageResult.of(page.convert(this::toExternalDTO));
    }

    @Override
    public AgentToolExternalConfigDTO getExternalTool(Long id) {
        return toExternalDTO(requireExternalEntity(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createExternalTool(AgentToolExternalConfigDTO dto) {
        validateExternal(dto);
        AgentToolExternalConfig entity = toExternalEntity(dto);
        externalMapper.insert(entity);
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateExternalTool(Long id, AgentToolExternalConfigDTO dto) {
        requireExternalEntity(id);
        validateExternal(dto);
        AgentToolExternalConfig entity = toExternalEntity(dto);
        entity.setId(id);
        externalMapper.updateById(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteExternalTool(Long id) {
        // 逻辑删除，幂等
        externalMapper.deleteById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void toggleExternalTool(Long id, boolean enabled) {
        AgentToolExternalConfig entity = requireExternalEntity(id);
        entity.setEnabled(enabled);
        externalMapper.updateById(entity);
    }

    @Override
    public List<AgentToolExternalConfig> listEnabledExternalTools(Long tenantId) {
        return externalMapper.selectList(
                Wrappers.<AgentToolExternalConfig>lambdaQuery()
                        .eq(AgentToolExternalConfig::getEnabled, 1)
                        .eq(tenantId != null, AgentToolExternalConfig::getTenantId, tenantId)
                        .orderByDesc(AgentToolExternalConfig::getId));
    }

    // ==================== 内部辅助 ====================

    private void validateInternal(AgentToolInternalConfigDTO dto) {
        if (dto.getName() == null || dto.getName().isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "工具名不能为空");
        }
        if (dto.getDescription() == null || dto.getDescription().isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "工具描述不能为空");
        }
        if (dto.getBeanName() == null || dto.getBeanName().isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "beanName 不能为空");
        }
        if (dto.getMethodName() == null || dto.getMethodName().isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "methodName 不能为空");
        }
    }

    private void validateExternal(AgentToolExternalConfigDTO dto) {
        if (dto.getName() == null || dto.getName().isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "工具名不能为空");
        }
        if (dto.getDescription() == null || dto.getDescription().isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "工具描述不能为空");
        }
        if (dto.getUrl() == null || dto.getUrl().isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "url 不能为空");
        }
        if (dto.getHttpMethod() != null) {
            String upper = dto.getHttpMethod().toUpperCase(Locale.ROOT);
            if (!SUPPORTED_HTTP_METHODS.contains(upper)) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR,
                        "HTTP 方法不支持（仅 GET/POST/PUT）: " + dto.getHttpMethod());
            }
        }
        if (dto.getEffectScope() != null && !dto.getEffectScope().isBlank()) {
            String upper = dto.getEffectScope().trim().toUpperCase(Locale.ROOT);
            if (!SUPPORTED_EFFECT_SCOPES.contains(upper)) {
                throw new BaseException(CommonErrorCode.PARAM_ERROR,
                        "副作用分类不支持（仅 QUERY/WRITE）: " + dto.getEffectScope());
            }
        }
    }

    private AgentToolInternalConfig requireInternalEntity(Long id) {
        if (id == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "id 不能为空");
        }
        AgentToolInternalConfig entity = baseMapper.selectById(id);
        if (entity == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND);
        }
        return entity;
    }

    private AgentToolExternalConfig requireExternalEntity(Long id) {
        if (id == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "id 不能为空");
        }
        AgentToolExternalConfig entity = externalMapper.selectById(id);
        if (entity == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND);
        }
        return entity;
    }

    private AgentToolInternalConfig toInternalEntity(AgentToolInternalConfigDTO dto) {
        AgentToolInternalConfig entity = new AgentToolInternalConfig();
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setInputSchema(dto.getInputSchema());
        entity.setBeanName(dto.getBeanName());
        entity.setMethodName(dto.getMethodName());
        entity.setEnabled(dto.getEnabled() == null || dto.getEnabled());
        entity.setRemark(dto.getRemark());
        return entity;
    }

    private AgentToolInternalConfigDTO toInternalDTO(AgentToolInternalConfig entity) {
        AgentToolInternalConfigDTO dto = new AgentToolInternalConfigDTO();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setInputSchema(entity.getInputSchema());
        dto.setBeanName(entity.getBeanName());
        dto.setMethodName(entity.getMethodName());
        dto.setEnabled(entity.getEnabled());
        dto.setRemark(entity.getRemark());
        dto.setCreateTime(entity.getCreateTime());
        dto.setUpdateTime(entity.getUpdateTime());
        return dto;
    }

    private AgentToolExternalConfig toExternalEntity(AgentToolExternalConfigDTO dto) {
        AgentToolExternalConfig entity = new AgentToolExternalConfig();
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        entity.setInputSchema(dto.getInputSchema());
        entity.setUrl(dto.getUrl());
        entity.setHttpMethod(dto.getHttpMethod() == null ? "POST" : dto.getHttpMethod().toUpperCase(Locale.ROOT));
        entity.setEffectScope(dto.getEffectScope() == null || dto.getEffectScope().isBlank()
                ? null
                : dto.getEffectScope().trim().toUpperCase(Locale.ROOT));
        entity.setTimeoutSeconds(dto.getTimeoutSeconds() == null
                ? DEFAULT_TIMEOUT_SECONDS
                : Math.max(1, dto.getTimeoutSeconds()));
        entity.setEnabled(dto.getEnabled() == null || dto.getEnabled());
        entity.setRemark(dto.getRemark());
        return entity;
    }

    private AgentToolExternalConfigDTO toExternalDTO(AgentToolExternalConfig entity) {
        AgentToolExternalConfigDTO dto = new AgentToolExternalConfigDTO();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setInputSchema(entity.getInputSchema());
        dto.setUrl(entity.getUrl());
        dto.setHttpMethod(entity.getHttpMethod());
        dto.setEffectScope(entity.getEffectScope());
        dto.setTimeoutSeconds(entity.getTimeoutSeconds());
        dto.setEnabled(entity.getEnabled());
        dto.setRemark(entity.getRemark());
        dto.setCreateTime(entity.getCreateTime());
        dto.setUpdateTime(entity.getUpdateTime());
        return dto;
    }
}

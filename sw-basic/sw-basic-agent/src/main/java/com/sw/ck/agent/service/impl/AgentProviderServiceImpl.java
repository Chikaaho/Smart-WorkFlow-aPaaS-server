package com.sw.ck.agent.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sw.ck.agent.dto.AgentProviderDTO;
import com.sw.ck.agent.dto.AgentProviderSaveReqDTO;
import com.sw.ck.agent.entity.AgentProvider;
import com.sw.ck.agent.mapper.AgentProviderMapper;
import com.sw.ck.agent.orchestration.AgentChatModelAssembler;
import com.sw.ck.agent.service.AgentProviderService;
import com.sw.ck.common.crypto.AesGcmCipher;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.service.BaseServiceImpl;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 供应商管理 Service 实现（F1 阶段 1，D022-02）。
 * <p>
 * 加密调用点唯一化：{@code AesGcmCipher.encrypt/decrypt/mask} 只在本类（模型侧在
 * AgentModelRuntimeService / AgentModelManageServiceImpl）内调用；明文 Key 仅存在局部变量。
 * </p>
 */
@Service
public class AgentProviderServiceImpl
        extends BaseServiceImpl<AgentProviderMapper, AgentProvider>
        implements AgentProviderService {

    private final AesGcmCipher cipher;

    public AgentProviderServiceImpl(AesGcmCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public PageResult<AgentProviderDTO> pageProviders(PageParam pageParam, String nameKeyword) {
        String keyword = nameKeyword != null && !nameKeyword.isBlank() ? nameKeyword : null;
        IPage<AgentProvider> page = baseMapper.selectPage(
                new Page<>(pageParam.getPageNum(), pageParam.getPageSize()),
                Wrappers.<AgentProvider>lambdaQuery()
                        .like(keyword != null, AgentProvider::getName, keyword)
                        .orderByDesc(AgentProvider::getId));
        return PageResult.of(page.convert(this::toDTO));
    }

    @Override
    public List<AgentProviderDTO> listEnabled() {
        return baseMapper.selectList(
                        Wrappers.<AgentProvider>lambdaQuery()
                                .eq(AgentProvider::getEnabled, 1)
                                .orderByAsc(AgentProvider::getId))
                .stream().map(this::toDTO).toList();
    }

    @Override
    public AgentProviderDTO getById(Long id) {
        AgentProviderDTO dto = toDTO(requireEntity(id));
        if (dto != null && dto.getApiKeyMasked() == null) {
            AgentProvider entity = requireEntity(id);
            if (entity.getApiKeyCipher() != null && !entity.getApiKeyCipher().isEmpty()) {
                String plain = cipher.decrypt(entity.getApiKeyCipher());
                dto.setApiKeyMasked(AesGcmCipher.mask(plain));
                plain = null;
            }
        }
        return dto;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(AgentProviderSaveReqDTO req) {
        validateFormat(req.getApiFormat());
        checkNameUnique(req.getName(), null);
        AgentProvider entity = toEntity(req);
        if (req.getApiKey() != null && !req.getApiKey().isEmpty()) {
            entity.setApiKeyCipher(cipher.encrypt(req.getApiKey()));
        }
        try {
            save(entity);
        } catch (DuplicateKeyException e) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "供应商名称已存在：" + req.getName());
        }
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, AgentProviderSaveReqDTO req) {
        AgentProvider existing = requireEntity(id);
        validateFormat(req.getApiFormat());
        checkNameUnique(req.getName(), id);
        AgentProvider entity = toEntity(req);
        entity.setId(id);
        if (req.getApiKey() != null && !req.getApiKey().isEmpty()) {
            entity.setApiKeyCipher(cipher.encrypt(req.getApiKey()));
        } else {
            // apiKey 为空 → 不覆盖旧密文（唯一允许的"部分更新"字段）
            entity.setApiKeyCipher(existing.getApiKeyCipher());
        }
        try {
            updateById(entity);
        } catch (DuplicateKeyException e) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "供应商名称已存在：" + req.getName());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        removeById(id);
    }

    private void validateFormat(String apiFormat) {
        if (apiFormat == null || !AgentChatModelAssembler.SUPPORTED_FORMATS.contains(apiFormat)) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR,
                    "不支持的 API 格式：" + apiFormat + "（仅支持 OPENAI_CHAT/OPENAI_RESPONSES/ANTHROPIC）");
        }
    }

    private void checkNameUnique(String name, Long excludeId) {
        if (name == null || name.isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "供应商名称不能为空");
        }
        boolean exists = lambdaQuery()
                .eq(AgentProvider::getName, name)
                .ne(excludeId != null, AgentProvider::getId, excludeId)
                .exists();
        if (exists) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "供应商名称已存在：" + name);
        }
    }

    private AgentProvider requireEntity(Long id) {
        if (id == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "id 不能为空");
        }
        AgentProvider entity = baseMapper.selectById(id);
        if (entity == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND);
        }
        return entity;
    }

    private AgentProvider toEntity(AgentProviderSaveReqDTO req) {
        AgentProvider entity = new AgentProvider();
        entity.setName(req.getName());
        entity.setApiFormat(req.getApiFormat());
        entity.setBaseUrl(req.getBaseUrl());
        entity.setEnabled(req.getEnabled());
        entity.setRemark(req.getRemark());
        return entity;
    }

    private AgentProviderDTO toDTO(AgentProvider entity) {
        AgentProviderDTO dto = new AgentProviderDTO();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setApiFormat(entity.getApiFormat());
        dto.setBaseUrl(entity.getBaseUrl());
        dto.setEnabled(entity.getEnabled());
        dto.setRemark(entity.getRemark());
        dto.setCreateTime(entity.getCreateTime());
        dto.setUpdateTime(entity.getUpdateTime());
        return dto;
    }
}

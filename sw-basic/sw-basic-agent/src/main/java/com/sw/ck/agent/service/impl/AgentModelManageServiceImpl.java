package com.sw.ck.agent.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sw.ck.agent.dto.AgentModelDTO;
import com.sw.ck.agent.dto.AgentModelSaveReqDTO;
import com.sw.ck.agent.dto.AgentModelTestConnectionRespDTO;
import com.sw.ck.agent.entity.AgentModel;
import com.sw.ck.agent.entity.AgentProvider;
import com.sw.ck.agent.mapper.AgentModelMapper;
import com.sw.ck.agent.mapper.AgentProviderMapper;
import com.sw.ck.agent.orchestration.AgentModelRuntimeService;
import com.sw.ck.agent.service.AgentModelService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.service.BaseServiceImpl;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * 模型管理 Service 实现（F1 阶段 1，D022-02/05）。
 * <p>
 * 连通性测试（阶段 1.5）：对所选模型发起一次<b>真实的最小调用</b>（maxOutputTokens=1、
 * 入参 "ping"）——只有模型真实回包才判成功；鉴权失败（401/403）/ 模型不存在（404）/
 * 超时分别给出可读原因。异常文本不透传（防供应商错误体回显敏感信息），密钥永不出现在
 * 响应或日志。
 * </p>
 */
@Service
public class AgentModelManageServiceImpl
        extends BaseServiceImpl<AgentModelMapper, AgentModel>
        implements AgentModelService {

    private final AgentProviderMapper providerMapper;
    private final AgentModelRuntimeService modelRuntimeService;

    public AgentModelManageServiceImpl(AgentProviderMapper providerMapper,
                                       AgentModelRuntimeService modelRuntimeService) {
        this.providerMapper = providerMapper;
        this.modelRuntimeService = modelRuntimeService;
    }

    @Override
    public PageResult<AgentModelDTO> pageModels(PageParam pageParam, Long providerId, String nameKeyword) {
        String keyword = nameKeyword != null && !nameKeyword.isBlank() ? nameKeyword : null;
        IPage<AgentModel> page = baseMapper.selectPage(
                new Page<>(pageParam.getPageNum(), pageParam.getPageSize()),
                Wrappers.<AgentModel>lambdaQuery()
                        .eq(providerId != null, AgentModel::getProviderId, providerId)
                        .like(keyword != null, AgentModel::getName, keyword)
                        .orderByDesc(AgentModel::getId));
        return PageResult.of(page.convert(this::toDTO));
    }

    @Override
    public List<AgentModelDTO> listEnabled(String capability) {
        List<AgentModel> models = baseMapper.selectList(
                Wrappers.<AgentModel>lambdaQuery()
                        .eq(AgentModel::getEnabled, 1)
                        .orderByAsc(AgentModel::getId));
        return models.stream()
                .filter(m -> capability == null
                        || m.getCapabilities() == null
                        || m.getCapabilities().contains(capability))
                .map(this::toDTO)
                .toList();
    }

    @Override
    public AgentModelDTO getById(Long id) {
        return toDTO(requireEntity(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(AgentModelSaveReqDTO req) {
        requireProvider(req.getProviderId());
        checkNameUnique(req.getName(), null);
        AgentModel entity = toEntity(req);
        try {
            save(entity);
        } catch (DuplicateKeyException e) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "模型名称已存在：" + req.getName());
        }
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, AgentModelSaveReqDTO req) {
        AgentModel existing = requireEntity(id);
        requireProvider(req.getProviderId());
        checkNameUnique(req.getName(), id);
        AgentModel entity = toEntity(req);
        entity.setId(id);
        // 携带旧 version：乐观锁插件据此自增——单例复用按修订号重建（D022-04），
        // 改采样参数/超时/启停必须让下一次 acquire 装配新配置（改参数热生效，无需重启）
        entity.setVersion(existing.getVersion());
        try {
            boolean updated = updateById(entity);
            if (!updated) {
                // version 已被并发修改（0 行命中）→ 显式冲突，避免静默丢失更新
                throw new BaseException(CommonErrorCode.PARAM_ERROR, "模型配置已被修改，请刷新后重试");
            }
        } catch (DuplicateKeyException e) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "模型名称已存在：" + req.getName());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        removeById(id);
    }

    @Override
    public AgentModelTestConnectionRespDTO testConnection(Long id) {
        // acquire 会做存在性 + 模型/供应商启停校验，并按修订号复用单例（真实装配）
        AgentModelRuntimeService.ModelInstance instance = modelRuntimeService.acquire(id);
        long start = System.currentTimeMillis();
        AgentModelTestConnectionRespDTO resp = new AgentModelTestConnectionRespDTO();
        try {
            ChatResponse response = instance.chatModel().chat(ChatRequest.builder()
                    .messages(UserMessage.from("ping"))
                    .maxOutputTokens(1)
                    .build());
            boolean hasContent = response.aiMessage() != null
                    && (response.aiMessage().text() != null
                        || response.aiMessage().hasToolExecutionRequests()
                        || (response.finishReason() != null));
            resp.setSuccess(true);
            resp.setMessage(hasContent ? "模型调用成功" : "模型已响应（无文本内容）");
        } catch (Exception e) {
            resp.setSuccess(false);
            resp.setMessage(readableFailure(e));
        }
        resp.setLatencyMs(System.currentTimeMillis() - start);
        return resp;
    }

    /** 失败原因分类（不透传原始错误文本，杜绝密钥/内部信息回显） */
    private String readableFailure(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof HttpException http) {
                if (http.statusCode() == 401 || http.statusCode() == 403) {
                    return "鉴权失败（HTTP " + http.statusCode() + "）：请检查 API Key 或权限";
                }
                if (http.statusCode() == 404) {
                    return "模型不存在（HTTP 404）：请检查模型名与地址";
                }
                return "供应商返回错误（HTTP " + http.statusCode() + "）";
            }
            if (cur instanceof TimeoutException) {
                return "请求超时：请检查地址与超时配置";
            }
            if (cur instanceof java.io.IOException
                    || cur.getCause() instanceof java.io.IOException) {
                return "网络不可达：请检查服务地址";
            }
            cur = cur.getCause();
        }
        return "调用失败：" + t.getClass().getSimpleName();
    }

    private void requireProvider(Long providerId) {
        if (providerId == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "providerId 不能为空");
        }
        AgentProvider provider = providerMapper.selectById(providerId);
        if (provider == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND, "供应商不存在: " + providerId);
        }
    }

    private void checkNameUnique(String name, Long excludeId) {
        if (name == null || name.isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "模型名称不能为空");
        }
        boolean exists = lambdaQuery()
                .eq(AgentModel::getName, name)
                .ne(excludeId != null, AgentModel::getId, excludeId)
                .exists();
        if (exists) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "模型名称已存在：" + name);
        }
    }

    private AgentModel requireEntity(Long id) {
        if (id == null) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR, "id 不能为空");
        }
        AgentModel entity = baseMapper.selectById(id);
        if (entity == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND);
        }
        return entity;
    }

    private AgentModel toEntity(AgentModelSaveReqDTO req) {
        AgentModel entity = new AgentModel();
        entity.setProviderId(req.getProviderId());
        entity.setName(req.getName());
        entity.setModelName(req.getModelName());
        entity.setCapabilities(req.getCapabilities() == null || req.getCapabilities().isBlank()
                ? "CHAT" : req.getCapabilities());
        entity.setTemperature(req.getTemperature());
        entity.setMaxTokens(req.getMaxTokens());
        entity.setTopP(req.getTopP());
        entity.setTimeoutSeconds(req.getTimeoutSeconds());
        entity.setRetryCount(req.getRetryCount());
        entity.setEnabled(req.getEnabled());
        entity.setRemark(req.getRemark());
        entity.setGroupKey(req.getGroupKey());
        entity.setSort(req.getSort());
        entity.setQuotaCooldownSeconds(req.getQuotaCooldownSeconds());
        return entity;
    }

    private AgentModelDTO toDTO(AgentModel entity) {
        AgentModelDTO dto = new AgentModelDTO();
        dto.setId(entity.getId());
        dto.setProviderId(entity.getProviderId());
        dto.setName(entity.getName());
        dto.setModelName(entity.getModelName());
        dto.setCapabilities(entity.getCapabilities());
        dto.setTemperature(entity.getTemperature());
        dto.setMaxTokens(entity.getMaxTokens());
        dto.setTopP(entity.getTopP());
        dto.setTimeoutSeconds(entity.getTimeoutSeconds());
        dto.setRetryCount(entity.getRetryCount());
        dto.setEnabled(entity.getEnabled());
        dto.setGroupKey(entity.getGroupKey());
        dto.setSort(entity.getSort());
        dto.setLockedUntil(entity.getLockedUntil());
        dto.setQuotaCooldownSeconds(entity.getQuotaCooldownSeconds());
        dto.setRemark(entity.getRemark());
        dto.setCreateTime(entity.getCreateTime());
        dto.setUpdateTime(entity.getUpdateTime());
        if (entity.getProviderId() != null) {
            AgentProvider provider = providerMapper.selectById(entity.getProviderId());
            if (provider != null) {
                dto.setProviderName(provider.getName());
                dto.setProviderApiFormat(provider.getApiFormat());
            }
        }
        return dto;
    }
}

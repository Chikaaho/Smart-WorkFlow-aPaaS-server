package com.sw.ck.bpm.process.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.entity.BpmProcessTemplate;
import com.sw.ck.bpm.process.mapper.BpmProcessTemplateMapper;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.bpm.process.service.BpmProcessTemplateService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 流程模板中心实现（I4 §3.2）。
 * <p>
 * 分级授权：GLOBAL 模板所有管理员可见；DEPT 模板仅同部门管理员可见，
 * 无权身份在列表即不可见，取单条/复制/修改均拒绝（服务端拒绝，不靠前端隐藏）。
 * 复制创建定义走正式定义创建链（createDef + saveDraftGraph），模板本身零改写。
 * </p>
 */
@Service
public class BpmProcessTemplateServiceImpl implements BpmProcessTemplateService {

    private static final Logger log = LoggerFactory.getLogger(BpmProcessTemplateServiceImpl.class);

    private final BpmProcessTemplateMapper mapper;
    private final BpmProcessDefService bpmProcessDefService;

    public BpmProcessTemplateServiceImpl(BpmProcessTemplateMapper mapper,
                                         BpmProcessDefService bpmProcessDefService) {
        this.mapper = mapper;
        this.bpmProcessDefService = bpmProcessDefService;
    }

    @Override
    public PageResult<BpmProcessTemplate> page(PageParam pageParam, String keyword,
                                               String category, String status) {
        LambdaQueryWrapper<BpmProcessTemplate> qw = Wrappers.<BpmProcessTemplate>lambdaQuery()
                .and(keyword != null && !keyword.isBlank(), wrapper -> wrapper
                        .like(BpmProcessTemplate::getName, keyword)
                        .or().like(BpmProcessTemplate::getDescription, keyword))
                .eq(category != null && !category.isBlank(), BpmProcessTemplate::getCategory, category)
                .eq(status != null && !status.isBlank(), BpmProcessTemplate::getStatus, status)
                .orderByDesc(BpmProcessTemplate::getId);
        // 数据范围：非全局身份只看 GLOBAL 模板 + 本部门 DEPT 模板
        LoginUser user = LoginUserHolder.get();
        if (user != null && !user.isSuperAdmin()) {
            qw.and(wrapper -> wrapper
                    .eq(BpmProcessTemplate::getScopeType, "GLOBAL")
                    .or(scope -> scope.eq(BpmProcessTemplate::getScopeType, "DEPT")
                            .eq(BpmProcessTemplate::getScopeDeptId, user.getDeptId())));
        }
        List<BpmProcessTemplate> all = mapper.selectList(qw);
        long total = all.size();
        long size = Math.max(pageParam.getPageSize(), 1);
        long current = Math.max(pageParam.getPageNum(), 1);
        int from = (int) Math.min((current - 1) * size, total);
        int to = (int) Math.min(from + size, total);
        PageResult<BpmProcessTemplate> result = new PageResult<>();
        result.setRecords(all.subList(from, to));
        result.setTotal(total);
        result.setPageNum(current);
        result.setPageSize(size);
        return result;
    }

    @Override
    public BpmProcessTemplate get(Long id) {
        BpmProcessTemplate template = mapper.selectById(id);
        if (template == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "流程模板不存在");
        }
        assertVisible(template);
        return template;
    }

    @Override
    @Transactional
    public BpmProcessTemplate create(String name, String category, String description, String formKey,
                                     String graphJson, String scopeType, Long scopeDeptId,
                                     Long sourceDefId, Integer sourceDefVersion) {
        requireManager();
        BpmProcessTemplate template = new BpmProcessTemplate();
        template.setName(name);
        template.setCategory(category);
        template.setDescription(description);
        template.setFormKey(formKey);
        template.setGraphJson(graphJson);
        template.setTemplateVersion(1);
        template.setStatus("ENABLED");
        template.setScopeType("DEPT".equalsIgnoreCase(scopeType) ? "DEPT" : "GLOBAL");
        template.setScopeDeptId("DEPT".equalsIgnoreCase(scopeType) ? scopeDeptId : null);
        template.setSourceDefId(sourceDefId);
        template.setSourceDefVersion(sourceDefVersion);
        template.setCreateTime(LocalDateTime.now());
        template.setUpdateTime(LocalDateTime.now());
        mapper.insert(template);
        log.info("流程模板已创建: id={}, name={}, scope={}", template.getId(), name, template.getScopeType());
        return template;
    }

    @Override
    @Transactional
    public BpmProcessTemplate update(Long id, String name, String category, String description,
                                     String graphJson, String scopeType, Long scopeDeptId) {
        BpmProcessTemplate template = get(id);
        requireManager();
        if (name != null && !name.isBlank()) {
            template.setName(name);
        }
        if (category != null) {
            template.setCategory(category);
        }
        if (description != null) {
            template.setDescription(description);
        }
        if (graphJson != null && !graphJson.isBlank()) {
            template.setGraphJson(graphJson);
            template.setTemplateVersion(template.getTemplateVersion() == null
                    ? 2 : template.getTemplateVersion() + 1);
        }
        if (scopeType != null && !scopeType.isBlank()) {
            template.setScopeType("DEPT".equalsIgnoreCase(scopeType) ? "DEPT" : "GLOBAL");
            template.setScopeDeptId("DEPT".equalsIgnoreCase(template.getScopeType())
                    ? scopeDeptId : null);
        }
        template.setUpdateTime(LocalDateTime.now());
        mapper.updateById(template);
        return template;
    }

    @Override
    @Transactional
    public BpmProcessTemplate changeStatus(Long id, boolean enabled) {
        BpmProcessTemplate template = get(id);
        requireManager();
        template.setStatus(enabled ? "ENABLED" : "DISABLED");
        template.setUpdateTime(LocalDateTime.now());
        mapper.updateById(template);
        return template;
    }

    @Override
    @Transactional
    public BpmProcessDef copyToDefinition(Long templateId, String name) {
        BpmProcessTemplate template = get(templateId);
        requireManager();
        if (!"ENABLED".equals(template.getStatus())) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "模板已停用，不可复制创建定义");
        }
        // 受控来源：走正式定义创建链；模板本身零改写
        BpmProcessDef def = bpmProcessDefService.createDef(name, template.getFormKey());
        if (template.getGraphJson() != null && !template.getGraphJson().isBlank()) {
            bpmProcessDefService.saveDraftGraph(def.getId(), template.getGraphJson());
        }
        bpmProcessDefService.markTemplateSource(def.getId(), template.getId(),
                template.getTemplateVersion());
        log.info("流程模板已复制为定义: templateId={}, templateVersion={}, defId={}",
                templateId, template.getTemplateVersion(), def.getId());
        return bpmProcessDefService.getDef(def.getId());
    }

    @Override
    @Transactional
    public void delete(Long id) {
        BpmProcessTemplate template = get(id);
        requireManager();
        mapper.deleteById(template.getId());
        log.info("流程模板已删除: id={}", id);
    }

    /** 服务端可见性拒绝：无权身份零可见零写入（前端隐藏不替代）。 */
    private void assertVisible(BpmProcessTemplate template) {
        LoginUser user = LoginUserHolder.get();
        if (user == null || user.isSuperAdmin()) {
            return;
        }
        if ("DEPT".equals(template.getScopeType())
                && (user.getDeptId() == null || !user.getDeptId().equals(template.getScopeDeptId()))) {
            throw new BaseException(CommonErrorCode.FORBIDDEN);
        }
    }

    private void requireManager() {
        LoginUser user = LoginUserHolder.get();
        if (user == null) {
            throw new BaseException(CommonErrorCode.UNAUTHORIZED);
        }
    }
}

package com.sw.ck.bpm.process.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.bpm.process.dto.CatalogItemDTO;
import com.sw.ck.bpm.process.entity.BpmCategory;
import com.sw.ck.bpm.process.entity.BpmFormBinding;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.mapper.BpmCategoryMapper;
import com.sw.ck.bpm.process.mapper.BpmProcessDefMapper;
import com.sw.ck.bpm.process.service.BpmCatalogService;
import com.sw.ck.bpm.process.service.BpmFormBindingService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.form.FormDefinitionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 流程中心事项目录服务实现。 */
@Service
public class BpmCatalogServiceImpl implements BpmCatalogService {

    private static final Logger log = LoggerFactory.getLogger(BpmCatalogServiceImpl.class);

    /** 扫描上限：租户内目录规模小，超出视为异常规模，拒绝而非全量加载。 */
    private static final long SCAN_LIMIT = 500L;

    private static final long UNCATEGORIZED = 0L;

    private final BpmProcessDefMapper processDefMapper;
    private final BpmCategoryMapper categoryMapper;
    private final BpmFormBindingService bindingService;
    private final FormDefinitionService formDefinitionService;

    public BpmCatalogServiceImpl(BpmProcessDefMapper processDefMapper,
                                 BpmCategoryMapper categoryMapper,
                                 BpmFormBindingService bindingService,
                                 FormDefinitionService formDefinitionService) {
        this.processDefMapper = processDefMapper;
        this.categoryMapper = categoryMapper;
        this.bindingService = bindingService;
        this.formDefinitionService = formDefinitionService;
    }

    @Override
    public PageResult<CatalogItemDTO> listPortalItems(String keyword, Long categoryId, PageParam pageParam) {
        List<CatalogItemDTO> visible = loadDefs(null).stream()
                .map(this::toPortalItem)
                .filter(Objects::nonNull)
                .filter(item -> matchCategory(item, categoryId))
                .filter(item -> matchKeyword(item, keyword))
                .toList();
        return paginate(visible, pageParam);
    }

    @Override
    public Map<Long, Long> portalCategoryCounts() {
        // 未分类（categoryId=null）归入 key 0 统计，保证「未分类」页签计数与卡片一致
        return loadDefs(null).stream()
                .map(this::toPortalItem)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(
                        item -> item.getCategoryId() == null ? UNCATEGORIZED : item.getCategoryId(),
                        Collectors.counting()));
    }

    @Override
    public java.util.List<com.sw.ck.bpm.process.entity.BpmCategory> portalCategories() {
        return categoryMapper.selectList(
                Wrappers.<com.sw.ck.bpm.process.entity.BpmCategory>lambdaQuery()
                        .orderByAsc(com.sw.ck.bpm.process.entity.BpmCategory::getSortNo));
    }

    @Override
    public PageResult<CatalogItemDTO> listAdminItems(String keyword, Long categoryId, PageParam pageParam) {
        List<CatalogItemDTO> all = loadDefs(null).stream()
                .map(this::toAdminItem)
                .filter(item -> matchCategory(item, categoryId))
                .filter(item -> matchKeyword(item, keyword))
                .toList();
        return paginate(all, pageParam);
    }

    @Override
    public void assignCategory(String processKey, Long categoryId) {
        BpmProcessDef def = requireDef(processKey);
        if (categoryId != null) {
            BpmCategory category = categoryMapper.selectById(categoryId);
            if (category == null) {
                throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "分类不存在");
            }
        }
        // categoryId 可为 null（移入未分类）：updateById 忽略 null 字段，必须显式 set
        processDefMapper.update(null,
                Wrappers.<BpmProcessDef>lambdaUpdate()
                        .set(BpmProcessDef::getCategoryId, categoryId)
                        .eq(BpmProcessDef::getProcessKey, processKey));
        log.info("事项分类已调整: processKey={}, categoryId={}", processKey, categoryId);
    }

    @Override
    public CatalogItemDTO getPortalItem(String processKey) {
        CatalogItemDTO item = toPortalItem(requireDef(processKey));
        if (item == null) {
            // 不向无权用户泄漏定义存在性
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "事项不存在或不可见");
        }
        return item;
    }

    // ==================== 内部方法 ====================

    private List<BpmProcessDef> loadDefs(String status) {
        List<BpmProcessDef> defs = processDefMapper.selectList(
                Wrappers.<BpmProcessDef>lambdaQuery()
                        .eq(status != null, BpmProcessDef::getStatus, status)
                        .orderByAsc(BpmProcessDef::getName));
        if (defs.size() > SCAN_LIMIT) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                    "流程定义数量超过目录扫描上限 " + SCAN_LIMIT);
        }
        return defs;
    }

    /** 普通视角投影：不可见（未发布/无绑定/表单未发布/表单不可见）一律返回 null，不泄漏存在性。 */
    private CatalogItemDTO toPortalItem(BpmProcessDef def) {
        BpmFormBinding binding = findActiveBinding(def.getProcessKey());
        if (binding == null) {
            return null;
        }
        FormDefDTO form = formDefinitionService.getFormDef(def.getFormKey());
        if (form == null || !"PUBLISHED".equals(form.getStatus())) {
            return null;
        }
        if (!formDefinitionService.canCurrentUserInitiate(def.getFormKey())) {
            return null;
        }
        return CatalogItemDTO.builder()
                .itemKey(def.getProcessKey())
                .name(def.getName())
                .formKey(def.getFormKey())
                .categoryId(def.getCategoryId())
                .status(def.getStatus())
                .formPublished(true)
                .bindingActive(true)
                .build();
    }

    /** 管理视角投影：全量展示（含 DRAFT/未绑定/表单未发布），逐项独立检查由操作端点承担。 */
    private CatalogItemDTO toAdminItem(BpmProcessDef def) {
        BpmFormBinding binding = findActiveBinding(def.getProcessKey());
        FormDefDTO form = formDefinitionService.getFormDef(def.getFormKey());
        return CatalogItemDTO.builder()
                .itemKey(def.getProcessKey())
                .name(def.getName())
                .formKey(def.getFormKey())
                .categoryId(def.getCategoryId())
                .status(def.getStatus())
                .formPublished(form != null && "PUBLISHED".equals(form.getStatus()))
                .bindingActive(binding != null)
                .build();
    }

    private BpmProcessDef requireDef(String processKey) {
        BpmProcessDef def = processDefMapper.selectOne(
                Wrappers.<BpmProcessDef>lambdaQuery().eq(BpmProcessDef::getProcessKey, processKey));
        if (def == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "事项不存在");
        }
        return def;
    }

    private boolean matchCategory(CatalogItemDTO item, Long categoryId) {
        if (categoryId == null) {
            return true;
        }
        if (categoryId == UNCATEGORIZED) {
            return item.getCategoryId() == null;
        }
        return categoryId.equals(item.getCategoryId());
    }

    private boolean matchKeyword(CatalogItemDTO item, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String kw = keyword.trim().toLowerCase();
        return (item.getName() != null && item.getName().toLowerCase().contains(kw))
                || (item.getFormKey() != null && item.getFormKey().toLowerCase().contains(kw));
    }

    private BpmFormBinding findActiveBinding(String processDefKey) {
        return bindingService.lambdaQuery()
                .eq(BpmFormBinding::getProcessDefKey, processDefKey)
                .eq(BpmFormBinding::getActive, Boolean.TRUE)
                .last("LIMIT 1")
                .one();
    }

    private PageResult<CatalogItemDTO> paginate(List<CatalogItemDTO> items, PageParam pageParam) {
        int from = (int) Math.min((pageParam.getPageNum() - 1) * pageParam.getPageSize(), items.size());
        int to = (int) Math.min(from + pageParam.getPageSize(), items.size());
        PageResult<CatalogItemDTO> result = new PageResult<>();
        result.setRecords(items.subList(from, to));
        result.setTotal(items.size());
        result.setPageNum(pageParam.getPageNum());
        result.setPageSize(pageParam.getPageSize());
        return result;
    }
}

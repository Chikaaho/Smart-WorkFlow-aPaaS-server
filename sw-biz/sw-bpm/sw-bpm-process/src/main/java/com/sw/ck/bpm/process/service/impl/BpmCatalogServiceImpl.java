package com.sw.ck.bpm.process.service.impl;

import org.springframework.beans.factory.annotation.Autowired;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import com.sw.ck.bpm.process.vo.CatalogItemVO;
import com.sw.ck.bpm.process.vo.CategoryCountsVO;
import com.sw.ck.bpm.process.vo.CategoryVO;
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
import com.sw.ck.form.api.vo.FormDefVO;
import com.sw.ck.form.api.form.FormDefinitionService;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.extern.log4j.Log4j2;
/** 流程中心事项目录服务实现。 */
@Service
@Log4j2
public class BpmCatalogServiceImpl implements BpmCatalogService {

    /** 扫描上限：租户内目录规模小，超出视为异常规模，拒绝而非全量加载。 */
    private static final long SCAN_LIMIT = 500L;

    private static final long UNCATEGORIZED = 0L;

    @Autowired
    private BpmProcessDefMapper processDefMapper;
    @Autowired
    private BpmCategoryMapper categoryMapper;
    @Autowired
    private BpmFormBindingService bindingService;
    @Autowired
    private FormDefinitionService formDefinitionService;

    @Override
    public PageResult<CatalogItemVO> listPortalItems(String keyword, Long categoryId, PageParam pageParam) {
        List<CatalogItemVO> visible = loadDefs(null).stream()
                .map(this::toPortalItem)
                .filter(Objects::nonNull)
                .filter(item -> matchCategory(item, categoryId))
                .filter(item -> matchKeyword(item, keyword))
                .toList();
        return paginate(visible, pageParam);
    }

    @Override
    public CategoryCountsVO portalCategoryCounts() {
        // 未分类（categoryId=null）归入 key 0 统计，保证「未分类」页签计数与卡片一致
        return new CategoryCountsVO(loadDefs(null).stream()
                .map(this::toPortalItem)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(
                        item -> item.getCategoryId() == null ? UNCATEGORIZED : item.getCategoryId(),
                        Collectors.counting())));
    }

    @Override
    public List<CategoryVO> portalCategories() {
        return categoryMapper.selectList(
                        Wrappers.<BpmCategory>lambdaQuery()
                                .orderByAsc(BpmCategory::getSortNo))
                .stream()
                .map(category -> CategoryVO.builder()
                        .id(category.getId())
                        .name(category.getName())
                        .sortNo(category.getSortNo())
                        .build())
                .toList();
    }

    @Override
    public PageResult<CatalogItemVO> listAdminItems(String keyword, Long categoryId, PageParam pageParam) {
        List<CatalogItemVO> all = loadDefs(null).stream()
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
    public CatalogItemVO getPortalItem(String processKey) {
        CatalogItemVO item = toPortalItem(requireDef(processKey));
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
    private CatalogItemVO toPortalItem(BpmProcessDef def) {
        BpmFormBinding binding = findActiveBinding(def.getProcessKey());
        if (binding == null) {
            return null;
        }
        // empty = 该 formKey 无表单定义（原 null 返回路径）：不可见，返回 null 不泄漏存在性
        Optional<FormDefVO> formLookup = formDefinitionService.getFormDef(def.getFormKey());
        if (formLookup.isEmpty() || !"PUBLISHED".equals(formLookup.get().getStatus())) {
            return null;
        }
        // empty = formKey 空白缺少判定目标：与不可发起同判不可见（fail closed）
        Optional<Boolean> initiateDecision =
                formDefinitionService.canCurrentUserInitiate(def.getFormKey());
        if (!(initiateDecision.isPresent() && initiateDecision.get())) {
            return null;
        }
        return CatalogItemVO.builder()
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
    private CatalogItemVO toAdminItem(BpmProcessDef def) {
        BpmFormBinding binding = findActiveBinding(def.getProcessKey());
        // empty = 该 formKey 无表单定义（原 null 返回路径）：视为未发布
        Optional<FormDefVO> formLookup = formDefinitionService.getFormDef(def.getFormKey());
        boolean formPublished = formLookup.isPresent()
                && "PUBLISHED".equals(formLookup.get().getStatus());
        return CatalogItemVO.builder()
                .itemKey(def.getProcessKey())
                .name(def.getName())
                .formKey(def.getFormKey())
                .categoryId(def.getCategoryId())
                .status(def.getStatus())
                .formPublished(formPublished)
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

    private boolean matchCategory(CatalogItemVO item, Long categoryId) {
        if (categoryId == null) {
            return true;
        }
        if (categoryId == UNCATEGORIZED) {
            return item.getCategoryId() == null;
        }
        return categoryId.equals(item.getCategoryId());
    }

    private boolean matchKeyword(CatalogItemVO item, String keyword) {
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

    private PageResult<CatalogItemVO> paginate(List<CatalogItemVO> items, PageParam pageParam) {
        int from = (int) Math.min((pageParam.getPageNum() - 1) * pageParam.getPageSize(), items.size());
        int to = (int) Math.min(from + pageParam.getPageSize(), items.size());
        PageResult<CatalogItemVO> result = new PageResult<>();
        result.setRecords(items.subList(from, to));
        result.setTotal(items.size());
        result.setPageNum(pageParam.getPageNum());
        result.setPageSize(pageParam.getPageSize());
        return result;
    }
}

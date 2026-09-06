package com.sw.ck.bpm.process.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.bpm.process.dto.CategoryDTO;
import com.sw.ck.bpm.process.dto.CategorySaveReq;
import com.sw.ck.bpm.process.entity.BpmCategory;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.mapper.BpmCategoryMapper;
import com.sw.ck.bpm.process.mapper.BpmProcessDefMapper;
import com.sw.ck.bpm.process.service.BpmCategoryService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 流程分类服务实现。 */
@Service
public class BpmCategoryServiceImpl implements BpmCategoryService {

    private static final Logger log = LoggerFactory.getLogger(BpmCategoryServiceImpl.class);

    private final BpmCategoryMapper categoryMapper;
    private final BpmProcessDefMapper processDefMapper;

    public BpmCategoryServiceImpl(BpmCategoryMapper categoryMapper,
                                  BpmProcessDefMapper processDefMapper) {
        this.categoryMapper = categoryMapper;
        this.processDefMapper = processDefMapper;
    }

    @Override
    public List<CategoryDTO> listCategories() {
        List<BpmCategory> categories = categoryMapper.selectList(
                Wrappers.<BpmCategory>lambdaQuery()
                        .orderByAsc(BpmCategory::getSortNo)
                        .orderByAsc(BpmCategory::getCreateTime));
        Map<Long, Long> counts = processDefMapper.selectList(
                        Wrappers.<BpmProcessDef>lambdaQuery().select(BpmProcessDef::getId, BpmProcessDef::getCategoryId))
                .stream()
                .filter(def -> def.getCategoryId() != null)
                .collect(Collectors.groupingBy(BpmProcessDef::getCategoryId, Collectors.counting()));
        return categories.stream()
                .map(c -> CategoryDTO.builder()
                        .id(c.getId())
                        .name(c.getName())
                        .sortNo(c.getSortNo())
                        .itemCount(counts.getOrDefault(c.getId(), 0L))
                        .build())
                .toList();
    }

    @Override
    @Transactional
    public BpmCategory createCategory(CategorySaveReq req) {
        String name = requireValidName(req);
        requireNameAvailable(name, null);
        BpmCategory category = new BpmCategory();
        category.setName(name);
        category.setSortNo(req.getSortNo() == null ? 0 : req.getSortNo());
        categoryMapper.insert(category);
        log.info("分类已创建: id={}, name={}", category.getId(), name);
        return category;
    }

    @Override
    @Transactional
    public BpmCategory updateCategory(Long id, CategorySaveReq req) {
        BpmCategory category = getExisting(id);
        String name = requireValidName(req);
        requireNameAvailable(name, id);
        category.setName(name);
        if (req.getSortNo() != null) {
            category.setSortNo(req.getSortNo());
        }
        categoryMapper.updateById(category);
        log.info("分类已更新: id={}, name={}, sortNo={}", id, category.getName(), category.getSortNo());
        return category;
    }

    @Override
    @Transactional
    public void deleteCategory(Long id) {
        BpmCategory category = categoryMapper.selectById(id);
        if (category == null) {
            // 幂等：目标态为已删除
            return;
        }
        Long referenced = processDefMapper.selectCount(
                Wrappers.<BpmProcessDef>lambdaQuery().eq(BpmProcessDef::getCategoryId, id));
        if (referenced != null && referenced > 0) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(),
                    "分类下仍有 " + referenced + " 个事项，须先解除归属再删除");
        }
        categoryMapper.deleteById(id);
        log.info("分类已删除: id={}", id);
    }

    // ==================== 内部方法 ====================

    private BpmCategory getExisting(Long id) {
        BpmCategory category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BaseException(CommonErrorCode.NOT_FOUND.getCode(), "分类不存在");
        }
        return category;
    }

    private String requireValidName(CategorySaveReq req) {
        if (req == null || req.getName() == null || req.getName().isBlank()) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "分类名称不能为空");
        }
        String name = req.getName().trim();
        if (name.length() > 100) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "分类名称不能超过 100 字");
        }
        return name;
    }

    private void requireNameAvailable(String name, Long excludeId) {
        List<BpmCategory> same = categoryMapper.selectList(
                Wrappers.<BpmCategory>lambdaQuery().eq(BpmCategory::getName, name));
        boolean duplicated = same.stream().anyMatch(c -> !Objects.equals(c.getId(), excludeId));
        if (duplicated) {
            throw new BaseException(CommonErrorCode.PARAM_ERROR.getCode(), "分类名称已存在: " + name);
        }
    }
}

package com.sw.ck.bpm.process.service.impl;

import org.springframework.beans.factory.annotation.Autowired;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.bpm.process.vo.CategoryVO;
import com.sw.ck.bpm.process.dto.CategorySaveReq;
import com.sw.ck.bpm.process.entity.BpmCategory;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.mapper.BpmCategoryMapper;
import com.sw.ck.bpm.process.mapper.BpmProcessDefMapper;
import com.sw.ck.bpm.process.service.BpmCategoryService;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.common.exception.CommonErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import lombok.extern.log4j.Log4j2;
/** 流程分类服务实现。 */
@Service
@Log4j2
public class BpmCategoryServiceImpl implements BpmCategoryService {

    @Autowired
    private BpmCategoryMapper categoryMapper;
    @Autowired
    private BpmProcessDefMapper processDefMapper;

    @Override
    public List<CategoryVO> listCategories() {
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
                .map(c -> CategoryVO.builder()
                        .id(c.getId())
                        .name(c.getName())
                        .sortNo(c.getSortNo())
                        .itemCount(counts.getOrDefault(c.getId(), 0L))
                        .build())
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
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
    @Transactional(rollbackFor = Exception.class)
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
    @Transactional(rollbackFor = Exception.class)
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

package com.sw.ck.bpm.process.bundle;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.bpm.process.entity.BpmCategory;
import com.sw.ck.bpm.process.entity.BpmProcessDef;
import com.sw.ck.bpm.process.mapper.BpmCategoryMapper;
import com.sw.ck.bpm.process.mapper.BpmProcessDefMapper;
import com.sw.ck.form.api.bundle.FormAppBundleProcessPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link FormAppBundleProcessPort} 实现（0.2.2 F2）：应用整包导出/导入的流程侧读写。
 * <p>
 * 只操作当前登录租户的数据（租户条件由全局 TenantLine 拦截器注入）：
 * 导出按主表单 formKey 加载 def 行并回填分类名；导入写入一律为草稿
 * （{@code status=DRAFT}、版本 1，不落部署/发布字段，D022-14）。
 * </p>
 */
@Component
public class FormAppBundleProcessAdapter implements FormAppBundleProcessPort {

    private static final Logger log = LoggerFactory.getLogger(FormAppBundleProcessAdapter.class);

    private final BpmProcessDefMapper processDefMapper;
    private final BpmCategoryMapper categoryMapper;

    public FormAppBundleProcessAdapter(BpmProcessDefMapper processDefMapper, BpmCategoryMapper categoryMapper) {
        this.processDefMapper = processDefMapper;
        this.categoryMapper = categoryMapper;
    }

    @Override
    public Optional<List<BundleProcessExport>> loadProcessesByMainFormKeys(Collection<String> formKeys) {
        if (formKeys == null || formKeys.isEmpty()) {
            return Optional.empty();
        }
        List<BpmProcessDef> defs = processDefMapper.selectList(Wrappers.<BpmProcessDef>lambdaQuery()
                .in(BpmProcessDef::getFormKey, formKeys)
                .orderByAsc(BpmProcessDef::getId));
        if (defs.isEmpty()) {
            return Optional.of(Collections.emptyList());
        }
        Map<Long, String> categoryNames = defs.stream()
                .map(BpmProcessDef::getCategoryId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toMap(Function.identity(), this::categoryName, (a, b) -> a));
        return Optional.of(defs.stream()
                .map(def -> new BundleProcessExport(def.getId(), def.getProcessKey(), def.getName(),
                        def.getFormKey(), def.getThemeRule(), def.getIotAccessEnabled(),
                        def.getIotDeviceActionJson(),
                        def.getCategoryId() == null ? null : categoryNames.get(def.getCategoryId()),
                        def.getGraphJson()))
                .collect(Collectors.toList()));
    }

    private String categoryName(Long categoryId) {
        BpmCategory category = categoryMapper.selectById(categoryId);
        return category == null ? null : category.getName();
    }

    @Override
    public Optional<Long> insertDraftProcess(BundleProcessImport row) {
        if (row == null) {
            return Optional.empty();
        }
        BpmProcessDef def = new BpmProcessDef();
        def.setProcessKey(row.processKey());
        def.setName(row.name());
        def.setFormKey(row.formKey());
        def.setDefVersion(1);
        def.setStatus("DRAFT");
        def.setGraphJson(row.graphJson());
        def.setThemeRule(row.themeRule());
        def.setCategoryId(row.categoryId());
        def.setIotAccessEnabled(Boolean.TRUE.equals(row.iotAccessEnabled()));
        def.setIotDeviceActionJson(row.iotDeviceActionJson());
        processDefMapper.insert(def);
        log.info("Bundle import: draft process inserted id={}, processKey={}, formKey={}",
                def.getId(), def.getProcessKey(), def.getFormKey());
        return Optional.of(def.getId());
    }

    @Override
    public Optional<Long> findCategoryIdByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return categoryMapper.selectList(Wrappers.<BpmCategory>lambdaQuery()
                        .eq(BpmCategory::getName, name.trim())
                        .orderByAsc(BpmCategory::getId))
                .stream()
                .findFirst()
                .map(BpmCategory::getId);
    }
}

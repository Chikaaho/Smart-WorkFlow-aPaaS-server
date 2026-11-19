package com.sw.ck.form.service;

import com.sw.ck.form.bundle.FormAppBundleDocument;
import com.sw.ck.form.bundle.FormBundleImportReport;
import com.sw.ck.form.entity.FormDefEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 单次导入的可变上下文（映射表、报告、保留 key 集与残留校验素材）。
 */
final class ImportContext {
    final FormAppBundleDocument document;
    final TargetFacts facts;
    final FormBundleImportReport report = FormBundleImportReport.builder()
            .packageName("")
            .createdDirs(new ArrayList<>())
            .createdForms(new ArrayList<>())
            .createdOpinionForms(new ArrayList<>())
            .createdProcesses(new ArrayList<>())
            .createdTxnActions(new ArrayList<>())
            .createdExtQueries(new ArrayList<>())
            .createdDictTypes(new ArrayList<>())
            .reusedDictTypes(new ArrayList<>())
            .matchedDatasources(new ArrayList<>())
            .matchedRoles(new ArrayList<>())
            .matchedPosts(new ArrayList<>())
            .matchedCategories(new ArrayList<>())
            .regeneratedFormKeys(new ArrayList<>())
            .regeneratedQueryKeys(new ArrayList<>())
            .clearedRefs(new ArrayList<>())
            .build();
    /** 包内保留的新 key（意见表单 + 业务表单），保证写入互不冲突 */
    final Set<String> reservedKeys = new LinkedHashSet<>();
    /** 意见表单 oldKey → finalKey（冲突项为用户确认值） */
    final Map<String, String> opinionKeyMap = new LinkedHashMap<>();
    /** 意见表单 oldId → 新表单 id */
    final Map<String, String> opinionIdMap = new LinkedHashMap<>();
    /** 业务表单 oldKey → finalKey */
    final Map<String, String> formKeyMap = new LinkedHashMap<>();
    /** 业务表单 oldId → 新表单 id */
    final Map<String, String> formIdMap = new LinkedHashMap<>();
    /** 交易动作 oldId → 新 id */
    final Map<String, String> txnActionIdMap = new LinkedHashMap<>();
    /** 业务表单 oldKey → 新表单（流程改写需要新 formVersion/id） */
    final Map<String, FormDefEntity> importedFormByKey = new LinkedHashMap<>();
    /** ext query oldKey → finalKey（冲突重生成） */
    final Map<String, String> queryKeyMap = new LinkedHashMap<>();
    /** oldProcessKey → 新 processKey */
    final Map<String, String> processKeyMap = new LinkedHashMap<>();
    /** oldProcessKey → 改写后的图文档 JSON（残留校验以改写结果为准） */
    final Map<String, String> rewrittenGraphs = new LinkedHashMap<>();
    /** 本次导入已插入的 (queryKey@version)，避免跨表单重复插入违反唯一约束 */
    final Set<String> insertedQueryVersions = new LinkedHashSet<>();
    /**
     * oldQueryKey → 匹配到的数据源 id。数据源是扩展查询契约的强依赖
     * （sw_form_ext_query.datasource_id NOT NULL）：未匹配的契约行不落库，
     * dsBinding 一并置空并记入报告（D022-13）。
     */
    final Map<String, Long> datasourceIdByOldQueryKey = new LinkedHashMap<>();
    /** oldQueryKey → 包内记录的数据源名称（报告与改写展示用） */
    final Map<String, String> datasourceNameByOldQueryKey = new LinkedHashMap<>();
    String fileName;

    ImportContext(FormAppBundleDocument document, TargetFacts facts) {
        this.document = document;
        this.facts = facts;
        this.report.setPackageName(document.getPackageName());
    }

    Set<String> allFinalFormKeys() {
        Set<String> keys = new LinkedHashSet<>(formKeyMap.values());
        keys.addAll(opinionKeyMap.values());
        return keys;
    }
}

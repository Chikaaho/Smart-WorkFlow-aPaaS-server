package com.sw.ck.form.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.bundle.FormAppBundleProcessPort;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.bundle.FormAppBundleDocument;
import com.sw.ck.form.bundle.FormBundleImportReport;
import com.sw.ck.form.entity.FormAppDirEntity;
import com.sw.ck.form.entity.FormAppEntity;
import com.sw.ck.form.entity.FormAppImportRecordEntity;
import com.sw.ck.form.entity.FormAppScopeEntity;
import com.sw.ck.form.entity.FormConfigEntity;
import com.sw.ck.form.entity.FormDefEntity;
import com.sw.ck.form.entity.FormExtQueryEntity;
import com.sw.ck.form.entity.FormIdGenerator;
import com.sw.ck.form.entity.FormListConfigEntity;
import com.sw.ck.form.mapper.FormAppDirMapper;
import com.sw.ck.form.mapper.FormAppImportRecordMapper;
import com.sw.ck.form.mapper.FormAppMapper;
import com.sw.ck.form.mapper.FormAppScopeMapper;
import com.sw.ck.form.mapper.FormConfigMapper;
import com.sw.ck.form.mapper.FormDefMapper;
import com.sw.ck.form.mapper.FormExtQueryMapper;
import com.sw.ck.form.mapper.FormListConfigMapper;
import com.sw.ck.form.txn.entity.TxnActionEntity;
import com.sw.ck.form.txn.mapper.TxnActionMapper;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.api.dict.DictFacade;
import com.sw.ck.system.api.dict.DictItemDTO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import lombok.extern.log4j.Log4j2;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 应用整包确认导入的写入编排（D022-11～15）：一律新建、从不覆盖已有对象。
 * 应用重名自动加后缀；业务表单 key 冲突自动重新生成；包内引用（表单之间、
 * 流程与表单、流程与意见表单、流程与交易动作）全部按新 id、新 key 改写
 * （改写细节见 {@link FormAppBundleRewriter}，残留校验见
 * {@link FormAppBundleResidueVerifier}）；审批意见表单冲突逐个由用户确认新 key；
 * 外部引用按 D022-13 匹配或置空并记入报告；导入后表单、流程（含交易动作）
 * 全部为草稿；最后写入导入记录。事务边界在门面
 * {@link FormAppBundleService#confirmImport} 上（经 Spring 代理，单事务整体回滚）。
 */
@Service
@Log4j2
class FormAppBundleImporter {

    @Autowired
    private FormAppMapper formAppMapper;
    @Autowired
    private FormAppDirMapper formAppDirMapper;
    @Autowired
    private FormAppScopeMapper formAppScopeMapper;
    @Autowired
    private FormDefMapper formDefMapper;
    @Autowired
    private FormConfigMapper formConfigMapper;
    @Autowired
    private FormListConfigMapper formListConfigMapper;
    @Autowired
    private FormExtQueryMapper formExtQueryMapper;
    @Autowired
    private TxnActionMapper txnActionMapper;
    @Autowired
    private FormAppImportRecordMapper importRecordMapper;
    @Autowired
    private FormIdGenerator idGenerator;
    @Autowired
    private ObjectProvider<FormAppBundleProcessPort> processPort;
    // 字典创建走 DictFacade（模块边界铁律：form 禁止直查 sys_dict_*）。
    // 经 ObjectProvider 防御模块未装配。
    @Autowired
    private ObjectProvider<DictFacade> dictFacade;
    @Autowired
    private FormBundleSupport support;
    @Autowired
    private FormAppBundlePrecheckService precheckService;
    @Autowired
    private FormAppBundleRewriter rewriter;
    @Autowired
    private FormAppBundleResidueVerifier residueVerifier;

    /**
     * 确认导入：一律新建，从不覆盖已有对象（D022-11）。
     *
     * @param opinionKeysJson 意见表单冲突解决 JSON：[{"formKey":"包内原 key","newKey":"用户确认的新 key"}]；
     *                        仅冲突项必须提供，非冲突项保持原 key（D022-12）
     */
    FormBundleImportReport confirmImport(byte[] content, String opinionKeysJson) {
        FormAppBundleDocument document = precheckService.parseBundle(content);
        TargetFacts facts = precheckService.collectTargetFacts(document);
        Map<String, String> opinionResolutions = support.parseOpinionResolutions(opinionKeysJson);

        ImportContext ctx = new ImportContext(document, facts);
        resolveOpinionFinalKeys(ctx, opinionResolutions);

        // —— 应用与目录（重名自动加后缀，D022-11） ——
        String appName = resolveAppName(document.getApp().getName().trim(), facts);
        FormAppEntity app = new FormAppEntity();
        app.setId(idGenerator.generate());
        app.setName(appName);
        app.setIcon(document.getApp().getIcon());
        app.setColor(document.getApp().getColor());
        app.setSortNo(document.getApp().getSortNo() == null ? 0 : document.getApp().getSortNo());
        app.setVisibleScopeType(document.getApp().getVisibleScopeType() == null
                ? "ALL" : document.getApp().getVisibleScopeType());
        app.setStatus(document.getApp().getStatus() == null ? FormAppMembershipValidator.STATUS_ENABLED : document.getApp().getStatus());
        app.setSource("MANUAL");
        formAppMapper.insert(app);
        ctx.report.setAppId(app.getId());
        ctx.report.setAppName(appName);
        ctx.report.setFinalAppName(appName);

        Map<String, String> dirIdMap = new LinkedHashMap<>();
        for (FormAppBundleDocument.DirBundle dir : document.getDirs()) {
            FormAppDirEntity entity = new FormAppDirEntity();
            entity.setId(idGenerator.generate());
            entity.setAppId(app.getId());
            entity.setName(dir.getName());
            entity.setSortNo(dir.getSortNo() == null ? 0 : dir.getSortNo());
            formAppDirMapper.insert(entity);
            dirIdMap.put(dir.getId(), entity.getId());
            ctx.report.getCreatedDirs().add(FormBundleImportReport.CreatedMapping.builder()
                    .oldId(dir.getId()).newId(entity.getId()).name(dir.getName()).build());
        }

        // 可见范围主体（D022-13）：USER/DEPT 置空记报告；ROLE/POST 编码匹配；TENANT 保留
        insertImportedScopes(app.getId(), document, facts, ctx);

        // —— 扩展查询契约 key 解析（先于表单定义改写） ——
        resolveExtQueryFinalKeys(ctx);

        // —— 业务表单（key 冲突自动重新生成，包内引用按新 key 改写，D022-11） ——
        importBusinessForms(ctx, app.getId(), dirIdMap);

        // —— 审批意见表单（冲突项使用用户确认的新 key，D022-12） ——
        importOpinionForms(ctx);

        // —— 字典类型（同编码复用/缺失新建/占用记报告，D022-13） ——
        importDictTypes(ctx);

        // —— 流程（graph_json 引用改写 + 一律草稿，D022-14） ——
        importProcesses(ctx);

        // —— 残留校验：包内不允许残留源 id/key（方向 §风险方向） ——
        residueVerifier.verifyNoSourceResidue(ctx);

        // —— 导入报告与记录（同一事务，失败整体回滚，D022-15） ——
        ctx.report.setImportedAt(LocalDateTime.now().toString());
        LoginUser user = LoginUserHolder.get();
        ctx.report.setOperatorName(user == null ? null : user.getUsername());

        FormAppImportRecordEntity record = new FormAppImportRecordEntity();
        record.setId(idGenerator.generate());
        record.setPackageName(document.getPackageName());
        record.setFileName(ctx.fileName);
        record.setAppId(app.getId());
        record.setAppName(appName);
        record.setResult("SUCCESS");
        record.setReportJson(support.writeJson(ctx.report));
        importRecordMapper.insert(record);

        log.info("Bundle imported: appId={}, appName={}, forms={}, processes={}, opinionForms={}, clearedRefs={}",
                app.getId(), appName, ctx.report.getCreatedForms().size(),
                ctx.report.getCreatedProcesses().size(),
                ctx.report.getCreatedOpinionForms().size(),
                ctx.report.getClearedRefs().size());
        return ctx.report;
    }

    private void resolveOpinionFinalKeys(ImportContext ctx, Map<String, String> resolutions) {
        for (FormAppBundleDocument.OpinionFormBundle opinion : ctx.document.getOpinionForms()) {
            String oldKey = opinion.getFormKey();
            if (!ctx.facts.opinionKeyTaken(oldKey)) {
                ctx.opinionKeyMap.put(oldKey, oldKey);
                ctx.reservedKeys.add(oldKey);
                continue;
            }
            String newKey = resolutions.get(oldKey);
            if (newKey == null || newKey.isBlank()) {
                // F2-G1：errorKey + 冲突项标识随拒绝结果返回（P61 R3 目录参数），
                // 前端据此把提示映射到对应输入项，而不是只弹全局 toast
                throw new BaseException(FormErrorCode.BUNDLE_OPINION_KEY_REQUIRED,
                        new Object[]{oldKey},
                        "审批意见表单“" + oldKey + "”在目标租户已存在同名标识，须提供新标识后才能确认导入");
            }
            newKey = newKey.trim();
            if (ctx.facts.existingFormKeys().contains(newKey) || ctx.reservedKeys.contains(newKey)) {
                throw new BaseException(FormErrorCode.BUNDLE_OPINION_KEY_CONFLICT,
                        new Object[]{newKey},
                        "审批意见表单新标识“" + newKey + "”在目标租户已存在，请修改后重试");
            }
            ctx.opinionKeyMap.put(oldKey, newKey);
            ctx.reservedKeys.add(newKey);
        }
    }

    /** 应用重名自动加后缀：-导入、-导入2、-导入3…（D022-11）。 */
    private String resolveAppName(String baseName, TargetFacts facts) {
        if (!facts.appNameTaken()) {
            return baseName;
        }
        String candidate = baseName + "-导入";
        int n = 2;
        while (formAppMapper.selectCount(Wrappers.<FormAppEntity>lambdaQuery()
                .eq(FormAppEntity::getName, candidate)) > 0) {
            candidate = baseName + "-导入" + n;
            n++;
        }
        return candidate;
    }

    private void insertImportedScopes(String appId, FormAppBundleDocument document,
                                      TargetFacts facts, ImportContext ctx) {
        List<FormAppBundleDocument.ScopeBundle> scopes = document.getApp().getScopes();
        if (scopes == null) {
            return;
        }
        for (FormAppBundleDocument.ScopeBundle scope : scopes) {
            String type = scope.getScopeType() == null ? "" : scope.getScopeType().trim();
            String value = scope.getScopeId() == null ? "" : scope.getScopeId().trim();
            switch (type) {
                case FormAppScopeTypes.USER, FormAppScopeTypes.DEPT -> ctx.report.getClearedRefs().add(
                        FormBundleImportReport.ClearedRef.builder()
                                .kind(type)
                                .originalValue(value)
                                .location("应用 可见范围")
                                .build());
                case FormAppScopeTypes.ROLE -> {
                    if (Boolean.TRUE.equals(facts.roleCodes().get(value))) {
                        insertScope(appId, type, value);
                        ctx.report.getMatchedRoles().add(support.reused(value, value, null));
                    } else {
                        ctx.report.getClearedRefs().add(support.cleared(FormAppScopeTypes.ROLE, value, "应用 可见范围"));
                    }
                }
                case FormAppScopeTypes.POST -> {
                    if (Boolean.TRUE.equals(facts.postCodes().get(value))) {
                        insertScope(appId, type, value);
                        ctx.report.getMatchedPosts().add(support.reused(value, value, null));
                    } else {
                        ctx.report.getClearedRefs().add(support.cleared(FormAppScopeTypes.POST, value, "应用 可见范围"));
                    }
                }
                case FormAppScopeTypes.TENANT -> insertScope(appId, type, value);
                default -> log.warn("未知可见范围维度，跳过: type={}", type);
            }
        }
    }

    private void insertScope(String appId, String scopeType, String scopeId) {
        FormAppScopeEntity entity = new FormAppScopeEntity();
        entity.setId(idGenerator.generate());
        entity.setAppId(appId);
        entity.setScopeType(scopeType);
        entity.setScopeId(scopeId);
        formAppScopeMapper.insert(entity);
    }

    /** 扩展查询契约：目标租户已有同 (key,version) 时重新生成 key（同步改写 dsBinding）。 */
    private void resolveExtQueryFinalKeys(ImportContext ctx) {
        Set<String> referenced = new LinkedHashSet<>();
        for (FormAppBundleDocument.FormBundle form : ctx.document.getForms()) {
            support.collectDsBindingKeys(support.parseObjectOrEmpty(form.getConfigDefinition()), referenced);
        }
        for (String oldKey : referenced) {
            String probe = oldKey + "@" + firstVersionOf(ctx.document, oldKey);
            if (ctx.facts.existingQueryKeyVersions().contains(probe)) {
                String newKey = uniqueKey(oldKey, ctx.facts.existingFormKeys(), ctx.reservedKeys,
                        k -> existingQueryKeyTaken(k, ctx));
                ctx.queryKeyMap.put(oldKey, newKey);
                ctx.reservedKeys.add(newKey);
                ctx.report.getRegeneratedQueryKeys().add(FormBundleImportReport.CreatedMapping.builder()
                        .oldKey(oldKey).newKey(newKey).build());
            } else {
                ctx.queryKeyMap.put(oldKey, oldKey);
                ctx.reservedKeys.add(oldKey);
            }
        }
    }

    private boolean existingQueryKeyTaken(String key, ImportContext ctx) {
        return formExtQueryMapper.selectCount(Wrappers.<FormExtQueryEntity>lambdaQuery()
                .eq(FormExtQueryEntity::getQueryKey, key)) > 0;
    }

    private Integer firstVersionOf(FormAppBundleDocument document, String queryKey) {
        for (FormAppBundleDocument.FormBundle form : document.getForms()) {
            if (form.getExtQueries() == null) {
                continue;
            }
            for (FormAppBundleDocument.ExtQueryBundle q : form.getExtQueries()) {
                if (queryKey.equals(q.getQueryKey()) && q.getQueryVersion() != null) {
                    return q.getQueryVersion();
                }
            }
        }
        return null;
    }

    private void importBusinessForms(ImportContext ctx, String appId, Map<String, String> dirIdMap) {
        // 第一遍：解析全部业务表单 finalKey。表单之间存在互引用（REFERENCE/触发器），
        // 引用改写必须等 key 映射完整后进行，否则先导入方会把包内引用误判为外部
        for (FormAppBundleDocument.FormBundle formBundle : ctx.document.getForms()) {
            String oldKey = formBundle.getFormKey();
            String finalKey;
            if (!ctx.facts.existingFormKeys().contains(oldKey) && !ctx.reservedKeys.contains(oldKey)) {
                finalKey = oldKey;
            } else {
                finalKey = uniqueKey(oldKey, ctx.facts.existingFormKeys(), ctx.reservedKeys, null);
                ctx.report.getRegeneratedFormKeys().add(FormBundleImportReport.CreatedMapping.builder()
                        .oldKey(oldKey).newKey(finalKey).name(formBundle.getName()).build());
            }
            ctx.formKeyMap.put(oldKey, finalKey);
            ctx.reservedKeys.add(finalKey);
        }
        for (FormAppBundleDocument.FormBundle formBundle : ctx.document.getForms()) {
            String oldKey = formBundle.getFormKey();
            String finalKey = ctx.formKeyMap.get(oldKey);

            FormDefEntity def = new FormDefEntity();
            def.setId(idGenerator.generate());
            def.setFormKey(finalKey);
            def.setName(formBundle.getName());
            def.setLogicalTableName(formBundle.getLogicalTableName());
            def.setDescription(formBundle.getDescription());
            def.setStatus(FormAppBundleService.STATUS_DRAFT);
            def.setFormType(FormAppBundleService.FORM_TYPE_BUSINESS);
            def.setAppId(appId);
            def.setDirId(formBundle.getDirId() == null ? null : dirIdMap.get(formBundle.getDirId()));
            def.setFormVersion(1);
            def.setPhysicalTableName(null);
            def.setSubTableMapping(null);
            // 发起可见范围（D022-13）：指定用户 id 置空（可见范围回到本租户全部用户）
            def.setVisibilityScope(rewriter.clearedVisibilityScope(formBundle.getVisibilityScope(),
                    formBundle.getName(), ctx));
            formDefMapper.insert(def);
            ctx.formIdMap.put(formBundle.getId(), def.getId());
            ctx.importedFormByKey.put(oldKey, def);
            ctx.report.getCreatedForms().add(FormBundleImportReport.CreatedMapping.builder()
                    .oldKey(oldKey).newKey(finalKey).oldId(formBundle.getId()).newId(def.getId())
                    .name(formBundle.getName()).build());

            // 数据源按名称匹配（每个 oldQueryKey 一次）：未匹配的契约行不落库（见 rewriteField）
            if (formBundle.getExtQueries() != null) {
                for (FormAppBundleDocument.ExtQueryBundle q : formBundle.getExtQueries()) {
                    if (q.getQueryKey() == null || ctx.datasourceIdByOldQueryKey.containsKey(q.getQueryKey())) {
                        continue;
                    }
                    Long datasourceId = q.getDatasourceName() == null || q.getDatasourceName().isBlank()
                            ? null : support.findDatasourceIdByName(q.getDatasourceName()).orElse(null);
                    ctx.datasourceIdByOldQueryKey.put(q.getQueryKey(), datasourceId);
                    ctx.datasourceNameByOldQueryKey.put(q.getQueryKey(),
                            q.getDatasourceName() == null ? "" : q.getDatasourceName());
                }
            }

            // 主 config 行（definition 引用改写后写入；子表行发布时派生，D022-14 草稿不建）
            Map<String, Object> definition = support.parseObjectOrEmpty(formBundle.getConfigDefinition());
            rewriter.rewriteDefinition(definition, ctx, formBundle.getName());
            insertConfigRow(def.getId(), support.writeJson(definition));

            // 列表配置
            if (formBundle.getListConfig() != null && !formBundle.getListConfig().isBlank()) {
                FormListConfigEntity listConfig = new FormListConfigEntity();
                listConfig.setId(idGenerator.generate());
                listConfig.setFormId(def.getId());
                listConfig.setConfigJson(formBundle.getListConfig());
                formListConfigMapper.insert(listConfig);
            }

            // 扩展查询契约行（数据源按名称匹配，D022-13）
            importExtQueries(ctx, formBundle, def);

            // 交易动作（草稿导入，节点 actionId 经 txnActionIdMap 改写）
            importTxnActions(ctx, formBundle, def);
        }
    }

    private void importExtQueries(ImportContext ctx, FormAppBundleDocument.FormBundle formBundle,
                                  FormDefEntity def) {
        if (formBundle.getExtQueries() == null) {
            return;
        }
        for (FormAppBundleDocument.ExtQueryBundle q : formBundle.getExtQueries()) {
            String finalKey = ctx.queryKeyMap.getOrDefault(q.getQueryKey(), q.getQueryKey());
            // 同一 (key,version) 可能被多个表单引用：只落一行（租户级唯一约束）
            Integer queryVersion = q.getQueryVersion() == null ? 1 : q.getQueryVersion();
            if (!ctx.insertedQueryVersions.add(finalKey + "@" + queryVersion)) {
                continue;
            }
            // 数据源未匹配：契约行不落库（datasource_id NOT NULL），dsBinding 已在字段改写时置空
            Long datasourceId = ctx.datasourceIdByOldQueryKey.get(q.getQueryKey());
            if (datasourceId == null) {
                continue;
            }
            if (datasourceId != null) {
                ctx.report.getMatchedDatasources().add(support.reused(q.getDatasourceName(),
                        q.getDatasourceName(), datasourceId));
            }
            FormExtQueryEntity entity = new FormExtQueryEntity();
            entity.setId(idGenerator.generate());
            entity.setDatasourceId(datasourceId);
            entity.setQueryKey(finalKey);
            entity.setQueryVersion(queryVersion);
            entity.setSqlText(q.getSqlText());
            entity.setOutputFields(q.getOutputFields());
            entity.setEnabled(q.getEnabled() == null ? 1 : q.getEnabled());
            formExtQueryMapper.insert(entity);
            ctx.report.getCreatedExtQueries().add(FormBundleImportReport.CreatedQuery.builder()
                    .formKey(ctx.formKeyMap.get(formBundle.getFormKey()))
                    .oldQueryKey(q.getQueryKey())
                    .newQueryKey(finalKey)
                    .queryVersion(queryVersion)
                    .datasourceName(q.getDatasourceName())
                    .datasourceId(datasourceId)
                    .build());
        }
    }

    private void importTxnActions(ImportContext ctx, FormAppBundleDocument.FormBundle formBundle,
                                  FormDefEntity def) {
        if (formBundle.getTxnActions() == null) {
            return;
        }
        for (FormAppBundleDocument.TxnActionBundle action : formBundle.getTxnActions()) {
            TxnActionEntity entity = new TxnActionEntity();
            entity.setId(idGenerator.generate());
            entity.setFormId(def.getId());
            entity.setActionKey(action.getActionKey());
            entity.setName(action.getName());
            entity.setActionType(action.getActionType());
            entity.setStatus(FormAppBundleService.STATUS_DRAFT);
            entity.setCurrentVersion(null);
            entity.setConfigJson(action.getConfigJson());
            entity.setDescription(action.getDescription());
            txnActionMapper.insert(entity);
            // TXN_ACTION 节点以源动作 UUID 引用：oldId → 新 id 映射供图改写
            ctx.txnActionIdMap.put(action.getId(), entity.getId());
            ctx.report.getCreatedTxnActions().add(FormBundleImportReport.CreatedTxnAction.builder()
                    .formKey(ctx.formKeyMap.get(formBundle.getFormKey()))
                    .actionKey(action.getActionKey())
                    .name(action.getName())
                    .newActionId(entity.getId())
                    .build());
        }
    }

    private void importOpinionForms(ImportContext ctx) {
        for (FormAppBundleDocument.OpinionFormBundle opinion : ctx.document.getOpinionForms()) {
            String finalKey = ctx.opinionKeyMap.get(opinion.getFormKey());
            FormDefEntity def = new FormDefEntity();
            def.setId(idGenerator.generate());
            def.setFormKey(finalKey);
            def.setName(opinion.getName());
            def.setDescription(opinion.getDescription());
            def.setStatus(FormAppBundleService.STATUS_DRAFT);
            def.setFormType(FormAppBundleService.FORM_TYPE_OPINION);
            def.setAppId(null);
            def.setDirId(null);
            def.setFormVersion(1);
            formDefMapper.insert(def);
            ctx.opinionIdMap.put(opinion.getId(), def.getId());

            Map<String, Object> definition = support.parseObjectOrEmpty(opinion.getConfigDefinition());
            rewriter.rewriteDefinition(definition, ctx, opinion.getName());
            insertConfigRow(def.getId(), support.writeJson(definition));

            ctx.report.getCreatedOpinionForms().add(FormBundleImportReport.CreatedMapping.builder()
                    .oldKey(opinion.getFormKey()).newKey(finalKey)
                    .oldId(opinion.getId()).newId(def.getId())
                    .name(opinion.getName()).build());
        }
    }

    private void importDictTypes(ImportContext ctx) {
        DictFacade facade = dictFacade.getIfAvailable();
        for (FormAppBundleDocument.DictTypeBundle type : ctx.document.getDictTypes()) {
            String action = ctx.facts.dictActions().getOrDefault(type.getCode(), "CREATE");
            if ("REUSE".equals(action)) {
                ctx.report.getReusedDictTypes().add(support.reused(type.getCode(), type.getName(), null));
                continue;
            }
            if ("OCCUPIED".equals(action)) {
                // 编码被其他租户占用：不中断导入，计入报告（D022-13）
                ctx.report.getClearedRefs().add(support.cleared("DICT_OCCUPIED", type.getCode(),
                        "字典类型 " + type.getCode()));
                continue;
            }
            if (facade == null) {
                // 预检按 CREATE 继续，但 system 模块未装配无法创建：计入清除引用清单
                ctx.report.getClearedRefs().add(support.cleared("DICT_UNAVAILABLE", type.getCode(),
                        "字典类型 " + type.getCode() + "（系统字典服务未装配，未创建）"));
                continue;
            }
            List<DictItemDTO> items = toItemDTOs(type, ctx);
            Boolean created = facade
                    .createDictTypeForImport(type.getCode(), type.getName(), type.getStatus(),
                            type.getDescription(), items)
                    .orElse(false);
            if (!created) {
                // 预检与确认之间无服务端状态：写入前复核发现被并发占用（撞全局唯一
                // 索引 uk_sys_dict_type_code），不中断导入，计入报告（D022-13）
                ctx.report.getClearedRefs().add(support.cleared("DICT_OCCUPIED", type.getCode(),
                        "字典类型 " + type.getCode()));
            } else {
                // 新建字典类型按对象计入报告：编码、名称、字典项数（F2-G4）
                ctx.report.getCreatedDictTypes().add(FormBundleImportReport.CreatedDictType.builder()
                        .code(type.getCode()).name(type.getName()).itemCount(items.size()).build());
            }
        }
    }

    /**
     * 包内字典项 → Facade 导入 DTO（dictValue 归位为 code，类型编码归位 dictType）。
     * <p>
     * 缺少 dictValue 的字典项无法落库：跳过并计入清除引用清单，报告中的字典项数
     * 只统计实际导入的项（0.3.0 修复——此前会静默丢弃却按包内条数上报）。
     * </p>
     */
    private List<DictItemDTO> toItemDTOs(FormAppBundleDocument.DictTypeBundle type, ImportContext ctx) {
        if (type.getItems() == null) {
            return List.of();
        }
        List<DictItemDTO> items = new ArrayList<>();
        for (FormAppBundleDocument.DictItemBundle item : type.getItems()) {
            String value = item.getDictValue();
            if (value == null || value.isBlank()) {
                ctx.report.getClearedRefs().add(support.cleared("DICT_ITEM", String.valueOf(item.getLabel()),
                        "字典类型 " + type.getCode() + "（字典项缺少 dictValue，未导入）"));
                continue;
            }
            items.add(DictItemDTO.builder()
                    .dictType(type.getCode())
                    .code(value)
                    .label(item.getLabel())
                    .sort(item.getSort())
                    .status(item.getStatus())
                    .isDefault(item.getIsDefault())
                    .cssClass(item.getCssClass())
                    .listClass(item.getListClass())
                    .description(item.getDescription())
                    .build());
        }
        return items;
    }

    private void importProcesses(ImportContext ctx) {
        FormAppBundleProcessPort port = processPort.getIfAvailable();
        for (FormAppBundleDocument.ProcessBundle process : ctx.document.getProcesses()) {
            String newProcessKey = "bpm_" + support.randomHex(16);
            ctx.processKeyMap.put(process.getProcessKey(), newProcessKey);
        }
        for (FormAppBundleDocument.ProcessBundle process : ctx.document.getProcesses()) {
            String oldFormKey = process.getFormKey();
            FormDefEntity mainForm = ctx.importedFormByKey.get(oldFormKey);
            if (mainForm == null) {
                throw new BaseException(FormErrorCode.BUNDLE_INVALID.getCode(),
                        "流程“" + process.getName() + "”的主表单不在包内，导入包不完整");
            }
            Map<String, Object> graph = support.parseObjectOrNull(process.getGraphJson());
            if (graph == null) {
                throw new BaseException(FormErrorCode.BUNDLE_INVALID.getCode(),
                        "流程“" + process.getName() + "”的图文档无效");
            }
            rewriter.rewriteProcessGraph(graph, process, ctx);
            String rewrittenGraph = support.writeJson(graph);
            ctx.rewrittenGraphs.put(process.getProcessKey(), rewrittenGraph);
            String rewrittenIot = rewriter.rewriteIotDeviceAction(process.getIotDeviceActionJson(),
                    process.getName(), ctx);

            Long categoryId = null;
            if (process.getCategoryName() != null && !process.getCategoryName().isBlank()) {
                categoryId = support.findCategoryIdByName(process.getCategoryName()).orElse(null);
                if (categoryId != null) {
                    // 同一分类被多条流程命中时按对象去重，报告只列一次（F2-G4）
                    Long matchedCategoryId = categoryId;
                    boolean categoryAlreadyMatched = ctx.report.getMatchedCategories().stream()
                            .anyMatch(c -> matchedCategoryId.equals(c.getId()));
                    if (!categoryAlreadyMatched) {
                        ctx.report.getMatchedCategories().add(support.reused(process.getCategoryName(),
                                process.getCategoryName(), categoryId));
                    }
                } else {
                    ctx.report.getClearedRefs().add(support.cleared("CATEGORY", process.getCategoryName(),
                            "流程 " + process.getName() + " · 分类"));
                }
            }

            String newProcessKey = ctx.processKeyMap.get(process.getProcessKey());
            Long newDefId = port == null ? null
                    : port.insertDraftProcess(
                            new FormAppBundleProcessPort.BundleProcessImport(
                                    newProcessKey, process.getName(), mainForm.getFormKey(),
                                    process.getThemeRule(), process.getIotAccessEnabled(), rewrittenIot,
                                    categoryId, rewrittenGraph))
                            .orElse(null);
            if (newDefId == null) {
                throw new BaseException(FormErrorCode.BUNDLE_IMPORT_FAILED.getCode(),
                        "流程模块未启用，无法导入流程“" + process.getName() + "”");
            }
            ctx.report.getCreatedProcesses().add(FormBundleImportReport.CreatedProcess.builder()
                    .name(process.getName())
                    .oldProcessKey(process.getProcessKey())
                    .newProcessKey(newProcessKey)
                    .newDefId(newDefId)
                    .formKey(mainForm.getFormKey())
                    .build());
        }
    }

    private String insertConfigRow(String formId, String definition) {
        FormConfigEntity config = new FormConfigEntity();
        config.setId(idGenerator.generate());
        config.setFormId(formId);
        config.setTableName(null);
        config.setParentTable(null);
        config.setDefinition(definition == null ? "{}" : definition);
        formConfigMapper.insert(config);
        return config.getId();
    }

    private String uniqueKey(String baseKey, Set<String> existing, Set<String> reserved,
                             Predicate<String> extraProbe) {
        String candidate = baseKey + "_" + support.randomHex(6);
        while (existing.contains(candidate) || reserved.contains(candidate)
                || (extraProbe != null && extraProbe.test(candidate))) {
            candidate = baseKey + "_" + support.randomHex(6);
        }
        return candidate;
    }

    // ==================================================================================
    // 导入记录
    // ==================================================================================

    List<FormAppImportRecordEntity> listImportRecords() {
        return importRecordMapper.selectList(Wrappers.<FormAppImportRecordEntity>lambdaQuery()
                .orderByDesc(FormAppImportRecordEntity::getCreateTime)
                .orderByDesc(FormAppImportRecordEntity::getId));
    }

    FormAppImportRecordEntity getImportRecord(String id) {
        FormAppImportRecordEntity record = importRecordMapper.selectById(id);
        if (record == null) {
            throw new BaseException(FormErrorCode.BUNDLE_RECORD_NOT_FOUND);
        }
        return record;
    }
}

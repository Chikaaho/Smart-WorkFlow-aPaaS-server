package com.sw.ck.form.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.bundle.FormAppBundleDatasourcePort;
import com.sw.ck.form.api.bundle.FormAppBundleProcessPort;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.bundle.FormAppBundleDocument;
import com.sw.ck.form.bundle.FormBundleImportReport;
import com.sw.ck.form.bundle.FormBundlePreviewResult;
import com.sw.ck.form.entity.FormAppDirEntity;
import com.sw.ck.form.entity.FormAppEntity;
import com.sw.ck.form.entity.FormAppImportRecordEntity;
import com.sw.ck.form.entity.FormAppScopeEntity;
import com.sw.ck.form.entity.FormConfigEntity;
import com.sw.ck.form.entity.FormDefEntity;
import com.sw.ck.form.entity.FormIdGenerator;
import com.sw.ck.form.entity.FormListConfigEntity;
import com.sw.ck.form.entity.FormExtQueryEntity;
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
import com.sw.ck.system.api.dict.DictTypeDetailDTO;
import com.sw.ck.system.api.user.UserQueryFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 应用整包导入导出服务（0.2.2 F2，D022-10～15）。
 *
 * <h3>导出（D022-10）</h3>
 * 以应用为单位导出各对象当前版本的定义：应用（含可见范围主体）、目录、业务表单
 * （定义/列表配置/扩展查询契约/交易动作）、主表单属于该应用的流程（当前 graph_json）、
 * 流程节点引用的审批意见表单、包内用到的字典类型与字典项。不含业务记录、子表数据、
 * 附件文件、表单历史快照与流程历史版本；不出现任何密钥、密码或数据源连接串。
 *
 * <h3>导入（D022-11～15）</h3>
 * 两步导入：先预检（对象清单、意见表单冲突、外部引用匹配），确认后单事务写入，
 * 失败整体回滚。一律新建、从不覆盖已有对象：应用重名自动加后缀；业务表单 key
 * 冲突自动重新生成；包内引用（表单之间、流程与表单、流程与意见表单、流程与交易动作）
 * 全部按新 id、新 key 改写；审批意见表单冲突逐个由用户确认新 key；外部引用按
 * D022-13 匹配或置空并记入报告。导入后表单、流程（含交易动作）全部为草稿。
 *
 * <h3>残留校验</h3>
 * 写入完成前对全部改写后的 JSON 做逐项校验：源环境表单 id、交易动作 id、流程 key
 * 不允许残留在任何包内文档中；key 类引用按改写位置复核。任一残留即抛错回滚。
 */
@Service
public class FormAppBundleService {

    private static final Logger log = LoggerFactory.getLogger(FormAppBundleService.class);

    /** 导入包大小上限（容器 multipart 上限 6MB，业务层结构化拒绝） */
    public static final int MAX_BUNDLE_FILE_BYTES = 5 * 1024 * 1024;

    private static final String FORM_TYPE_BUSINESS = "BUSINESS";
    private static final String FORM_TYPE_OPINION = "APPROVAL_COMMENT";
    private static final String STATUS_DRAFT = "DRAFT";

    private final FormAppMapper formAppMapper;
    private final FormAppDirMapper formAppDirMapper;
    private final FormAppScopeMapper formAppScopeMapper;
    private final FormDefMapper formDefMapper;
    private final FormConfigMapper formConfigMapper;
    private final FormListConfigMapper formListConfigMapper;
    private final FormExtQueryMapper formExtQueryMapper;
    private final TxnActionMapper txnActionMapper;
    private final FormAppImportRecordMapper importRecordMapper;
    private final FormIdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<FormAppBundleProcessPort> processPort;
    private final ObjectProvider<FormAppBundleDatasourcePort> datasourcePort;
    // 字典读写与占用探测走 DictFacade（模块边界铁律：form 禁止直查 sys_dict_*）；
    // 角色/岗位存在性走 UserQueryFacade。经 ObjectProvider 防御模块未装配。
    private final ObjectProvider<DictFacade> dictFacade;
    private final ObjectProvider<UserQueryFacade> userQueryFacade;

    public FormAppBundleService(FormAppMapper formAppMapper,
                                FormAppDirMapper formAppDirMapper,
                                FormAppScopeMapper formAppScopeMapper,
                                FormDefMapper formDefMapper,
                                FormConfigMapper formConfigMapper,
                                FormListConfigMapper formListConfigMapper,
                                FormExtQueryMapper formExtQueryMapper,
                                TxnActionMapper txnActionMapper,
                                FormAppImportRecordMapper importRecordMapper,
                                FormIdGenerator idGenerator,
                                ObjectMapper objectMapper,
                                ObjectProvider<FormAppBundleProcessPort> processPort,
                                ObjectProvider<FormAppBundleDatasourcePort> datasourcePort,
                                ObjectProvider<DictFacade> dictFacade,
                                ObjectProvider<UserQueryFacade> userQueryFacade) {
        this.formAppMapper = formAppMapper;
        this.formAppDirMapper = formAppDirMapper;
        this.formAppScopeMapper = formAppScopeMapper;
        this.formDefMapper = formDefMapper;
        this.formConfigMapper = formConfigMapper;
        this.formListConfigMapper = formListConfigMapper;
        this.formExtQueryMapper = formExtQueryMapper;
        this.txnActionMapper = txnActionMapper;
        this.importRecordMapper = importRecordMapper;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.processPort = processPort;
        this.datasourcePort = datasourcePort;
        this.dictFacade = dictFacade;
        this.userQueryFacade = userQueryFacade;
    }

    // ==================================================================================
    // 导出
    // ==================================================================================

    /** 导出结果：文件名 + 包字节 + 结构化文档（报告/预检复用）。 */
    public record ExportedBundle(String fileName, byte[] content, FormAppBundleDocument document) {
    }

    /**
     * 导出应用整包。只读当前租户（超管旁路时为当前选择租户，租户条件由全局拦截器注入）。
     */
    public ExportedBundle exportAppBundle(String appId) {
        if (appId == null || appId.isBlank()) {
            throw new BaseException(FormErrorCode.APP_NOT_FOUND.getCode(), "应用不存在或不可见");
        }
        FormAppEntity app = formAppMapper.selectById(appId.trim());
        if (app == null) {
            throw new BaseException(FormErrorCode.APP_NOT_FOUND);
        }
        List<FormAppDirEntity> dirs = formAppDirMapper.selectList(Wrappers.<FormAppDirEntity>lambdaQuery()
                .eq(FormAppDirEntity::getAppId, app.getId())
                .orderByAsc(FormAppDirEntity::getSortNo)
                .orderByAsc(FormAppDirEntity::getId));
        List<FormDefEntity> businessForms = formDefMapper.selectList(Wrappers.<FormDefEntity>lambdaQuery()
                .eq(FormDefEntity::getAppId, app.getId())
                .eq(FormDefEntity::getFormType, FORM_TYPE_BUSINESS)
                .orderByAsc(FormDefEntity::getCreateTime));

        List<FormAppBundleDocument.FormBundle> formBundles = new ArrayList<>();
        for (FormDefEntity form : businessForms) {
            formBundles.add(exportForm(form));
        }

        // 流程：主表单属于该应用的流程定义（当前 graph_json）
        Set<String> businessKeys = businessForms.stream().map(FormDefEntity::getFormKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<FormAppBundleDocument.ProcessBundle> processBundles = new ArrayList<>();
        FormAppBundleProcessPort port = processPort.getIfAvailable();
        if (port != null) {
            for (FormAppBundleProcessPort.BundleProcessExport p : port.loadProcessesByMainFormKeys(businessKeys)
                    .orElse(List.of())) {
                processBundles.add(FormAppBundleDocument.ProcessBundle.builder()
                        .processKey(p.processKey())
                        .name(p.name())
                        .formKey(p.formKey())
                        .themeRule(p.themeRule())
                        .iotAccessEnabled(p.iotAccessEnabled())
                        .iotDeviceActionJson(p.iotDeviceActionJson())
                        .categoryName(p.categoryName())
                        .graphJson(p.graphJson())
                        .build());
            }
        }

        // 审批意见表单：流程节点引用的（nodeForm.formKey / opinionForm.formId）
        List<FormAppBundleDocument.OpinionFormBundle> opinionBundles =
                exportOpinionForms(processBundles);

        // 字典类型与字典项：包内表单引用到的 dictType
        List<FormAppBundleDocument.DictTypeBundle> dictBundles =
                exportDictTypes(formBundles, opinionBundles);

        FormAppBundleDocument.AppBundle appBundle = FormAppBundleDocument.AppBundle.builder()
                .name(app.getName())
                .icon(app.getIcon())
                .color(app.getColor())
                .sortNo(app.getSortNo())
                .visibleScopeType(app.getVisibleScopeType())
                .status(app.getStatus())
                .scopes(exportAppScopes(app.getId()))
                .build();

        FormAppBundleDocument document = FormAppBundleDocument.builder()
                .bundleFormatVersion(FormAppBundleDocument.FORMAT_VERSION)
                .systemVersion(resolveSystemVersion())
                .exportedAt(LocalDateTime.now().toString())
                .packageName(app.getName())
                .app(appBundle)
                .dirs(dirs.stream().map(d -> FormAppBundleDocument.DirBundle.builder()
                        .id(d.getId()).name(d.getName()).sortNo(d.getSortNo()).build())
                        .collect(Collectors.toList()))
                .forms(formBundles)
                .opinionForms(opinionBundles)
                .processes(processBundles)
                .dictTypes(dictBundles)
                .build();

        byte[] content = serializeDocument(document);
        String fileName = sanitizeFileName(app.getName()) + "_bundle.json";
        log.info("Bundle exported: appId={}, forms={}, processes={}, opinionForms={}, bytes={}",
                app.getId(), formBundles.size(), processBundles.size(), opinionBundles.size(), content.length);
        return new ExportedBundle(fileName, content, document);
    }

    private FormAppBundleDocument.FormBundle exportForm(FormDefEntity form) {
        FormConfigEntity config = formConfigMapper.selectOne(Wrappers.<FormConfigEntity>lambdaQuery()
                .eq(FormConfigEntity::getFormId, form.getId())
                .isNull(FormConfigEntity::getParentTable)
                .last("LIMIT 1"));
        FormListConfigEntity listConfig = formListConfigMapper.selectOne(Wrappers.<FormListConfigEntity>lambdaQuery()
                .eq(FormListConfigEntity::getFormId, form.getId())
                .last("LIMIT 1"));

        // 扩展查询契约：本表单 definition 中 dsBinding 引用到的 (queryKey, version)，按 key@version 去重
        Map<String, Object> definition = parseObjectOrEmpty(config == null ? null : config.getDefinition());
        Set<String> referencedKeys = new LinkedHashSet<>();
        collectDsBindingKeys(definition, referencedKeys);
        List<FormExtQueryEntity> extQueries = referencedKeys.isEmpty() ? List.of()
                : formExtQueryMapper.selectList(Wrappers.<FormExtQueryEntity>lambdaQuery()
                        .in(FormExtQueryEntity::getQueryKey, referencedKeys));
        Set<String> seenQueryVersions = new HashSet<>();
        List<FormAppBundleDocument.ExtQueryBundle> extBundles = extQueries.stream()
                .filter(q -> seenQueryVersions.add(q.getQueryKey() + "@" + q.getQueryVersion()))
                .map(q -> FormAppBundleDocument.ExtQueryBundle.builder()
                        .datasourceName(datasourceNameOf(q.getDatasourceId()))
                        .queryKey(q.getQueryKey())
                        .queryVersion(q.getQueryVersion())
                        .sqlText(q.getSqlText())
                        .outputFields(q.getOutputFields())
                        .enabled(q.getEnabled())
                        .build())
                .collect(Collectors.toList());

        // 交易动作定义（草稿/发布态当前版本；版本历史不导出）
        List<TxnActionEntity> actions = txnActionMapper.selectList(Wrappers.<TxnActionEntity>lambdaQuery()
                .eq(TxnActionEntity::getFormId, form.getId())
                .orderByAsc(TxnActionEntity::getId));
        List<FormAppBundleDocument.TxnActionBundle> actionBundles = actions.stream()
                .map(a -> FormAppBundleDocument.TxnActionBundle.builder()
                        .id(a.getId())
                        .actionKey(a.getActionKey())
                        .name(a.getName())
                        .actionType(a.getActionType())
                        .configJson(a.getConfigJson())
                        .description(a.getDescription())
                        .build())
                .collect(Collectors.toList());

        return FormAppBundleDocument.FormBundle.builder()
                .id(form.getId())
                .dirId(form.getDirId())
                .formKey(form.getFormKey())
                .name(form.getName())
                .logicalTableName(form.getLogicalTableName())
                .description(form.getDescription())
                .visibilityScope(form.getVisibilityScope())
                .configDefinition(config == null ? "{}" : config.getDefinition())
                .listConfig(listConfig == null ? null : listConfig.getConfigJson())
                .extQueries(extBundles)
                .txnActions(actionBundles)
                .build();
    }

    /** 数据源名称（只读名称用于出包匹配；连接信息与密码绝不入包，D022-10/13）。 */
    private String datasourceNameOf(Long datasourceId) {
        if (datasourceId == null) {
            return null;
        }
        try {
            FormAppBundleDatasourcePort port = datasourcePort.getIfAvailable();
            if (port == null) {
                return null;
            }
            return port.findDatasourceNameById(datasourceId).orElse(null);
        } catch (Exception e) {
            log.warn("读取外部数据源名称失败: datasourceId={}", datasourceId);
            return null;
        }
    }

    /** 收集流程节点引用的审批意见表单（按 nodeForm.formKey 与 opinionForm.formId）。 */
    private List<FormAppBundleDocument.OpinionFormBundle> exportOpinionForms(
            List<FormAppBundleDocument.ProcessBundle> processBundles) {
        Set<String> keys = new LinkedHashSet<>();
        Set<String> ids = new LinkedHashSet<>();
        for (FormAppBundleDocument.ProcessBundle process : processBundles) {
            Map<String, Object> graph = parseObjectOrNull(process.getGraphJson());
            if (graph == null || !(graph.get("elements") instanceof List<?> elements)) {
                continue;
            }
            for (Object elementObj : elements) {
                if (!(elementObj instanceof Map<?, ?> element)) {
                    continue;
                }
                Object config = element.get("config");
                if (!(config instanceof Map<?, ?> configMap)) {
                    continue;
                }
                Object nodeForm = configMap.get("nodeForm");
                if (nodeForm instanceof Map<?, ?> nodeFormMap
                        && nodeFormMap.get("formKey") != null
                        && !String.valueOf(nodeFormMap.get("formKey")).isBlank()) {
                    keys.add(String.valueOf(nodeFormMap.get("formKey")).trim());
                }
                Object opinionForm = configMap.get("opinionForm");
                if (opinionForm instanceof Map<?, ?> opinionMap && opinionMap.get("formId") != null) {
                    ids.add(String.valueOf(opinionMap.get("formId")).trim());
                }
            }
        }
        if (keys.isEmpty() && ids.isEmpty()) {
            return List.of();
        }
        List<FormDefEntity> opinionForms = new ArrayList<>();
        if (!keys.isEmpty()) {
            opinionForms.addAll(formDefMapper.selectList(Wrappers.<FormDefEntity>lambdaQuery()
                    .in(FormDefEntity::getFormKey, keys)));
        }
        if (!ids.isEmpty()) {
            opinionForms.addAll(formDefMapper.selectList(Wrappers.<FormDefEntity>lambdaQuery()
                    .in(FormDefEntity::getId, ids)));
        }
        Set<String> seen = new HashSet<>();
        List<FormAppBundleDocument.OpinionFormBundle> bundles = new ArrayList<>();
        for (FormDefEntity form : opinionForms) {
            if (!seen.add(form.getId())) {
                continue;
            }
            FormConfigEntity config = formConfigMapper.selectOne(Wrappers.<FormConfigEntity>lambdaQuery()
                    .eq(FormConfigEntity::getFormId, form.getId())
                    .isNull(FormConfigEntity::getParentTable)
                    .last("LIMIT 1"));
            bundles.add(FormAppBundleDocument.OpinionFormBundle.builder()
                    .id(form.getId())
                    .formKey(form.getFormKey())
                    .name(form.getName())
                    .description(form.getDescription())
                    .configDefinition(config == null ? "{}" : config.getDefinition())
                    .build());
        }
        return bundles;
    }

    /** 包内字典类型与字典项（按 definition 中 DICT.dictType 收集；只取当前租户行）。 */
    private List<FormAppBundleDocument.DictTypeBundle> exportDictTypes(
            List<FormAppBundleDocument.FormBundle> formBundles,
            List<FormAppBundleDocument.OpinionFormBundle> opinionBundles) {
        Set<String> codes = new LinkedHashSet<>();
        for (FormAppBundleDocument.FormBundle form : formBundles) {
            collectDictCodes(parseObjectOrEmpty(form.getConfigDefinition()), codes);
        }
        for (FormAppBundleDocument.OpinionFormBundle form : opinionBundles) {
            collectDictCodes(parseObjectOrEmpty(form.getConfigDefinition()), codes);
        }
        if (codes.isEmpty()) {
            return List.of();
        }
        DictFacade facade = dictFacade.getIfAvailable();
        if (facade == null) {
            // system 模块未装配：包内无字典可读，导出不中断
            log.warn("字典 Facade 未装配，导出包不含字典类型");
            return List.of();
        }
        Map<String, DictTypeDetailDTO> details = facade.findDictTypeDetails(codes).orElse(Map.of());
        List<FormAppBundleDocument.DictTypeBundle> bundles = new ArrayList<>();
        for (DictTypeDetailDTO detail : details.values()) {
            List<FormAppBundleDocument.DictItemBundle> itemBundles =
                    (detail.getItems() == null ? List.<DictItemDTO>of() : detail.getItems()).stream()
                            .map(item -> FormAppBundleDocument.DictItemBundle.builder()
                                    .label(item.getLabel())
                                    .dictValue(item.getCode())
                                    .sort(item.getSort())
                                    .status(item.getStatus())
                                    .isDefault(item.getIsDefault())
                                    .cssClass(item.getCssClass())
                                    .listClass(item.getListClass())
                                    .description(item.getDescription())
                                    .build())
                            .collect(Collectors.toList());
            bundles.add(FormAppBundleDocument.DictTypeBundle.builder()
                    .code(detail.getCode())
                    .name(detail.getName())
                    .status(detail.getStatus())
                    .description(detail.getDescription())
                    .items(itemBundles)
                    .build());
        }
        return bundles;
    }

    private List<FormAppBundleDocument.ScopeBundle> exportAppScopes(String appId) {
        return formAppScopeMapper.selectList(Wrappers.<FormAppScopeEntity>lambdaQuery()
                        .eq(FormAppScopeEntity::getAppId, appId)
                        .orderByAsc(FormAppScopeEntity::getId))
                .stream()
                .map(s -> FormAppBundleDocument.ScopeBundle.builder()
                        .scopeType(s.getScopeType()).scopeId(s.getScopeId()).build())
                .collect(Collectors.toList());
    }

    /** 系统版本号：生产构建取 build.version 标记；无法判定时如实记 DEV，不伪造版本。 */
    private String resolveSystemVersion() {
        try {
            byte[] raw = org.springframework.util.StreamUtils.copyToByteArray(
                    getClass().getResourceAsStream("/META-INF/production-build.properties"));
            for (String line : new String(raw, java.nio.charset.StandardCharsets.UTF_8).split("\\r?\\n")) {
                if (line.startsWith("build.version=")) {
                    String value = line.substring("build.version=".length()).trim();
                    if (!value.isEmpty()) {
                        return value;
                    }
                }
            }
        } catch (Exception ignored) {
            // 非生产构建（dev/local）：无版本标记文件
        }
        try {
            Package pkg = getClass().getPackage();
            if (pkg != null && pkg.getImplementationVersion() != null) {
                return pkg.getImplementationVersion();
            }
        } catch (Exception ignored) {
            // 无 manifest 版本
        }
        return "DEV";
    }

    // ==================================================================================
    // 解析与目标租户事实
    // ==================================================================================

    /** 解析导入包并校验格式版本（不兼容直接拒绝，D022 边界）。 */
    public FormAppBundleDocument parseBundle(byte[] content) {
        if (content == null || content.length == 0) {
            throw new BaseException(FormErrorCode.BUNDLE_INVALID.getCode(), "导入包为空");
        }
        if (content.length > MAX_BUNDLE_FILE_BYTES) {
            throw new BaseException(FormErrorCode.BUNDLE_INVALID.getCode(),
                    "导入包超过大小上限 " + (MAX_BUNDLE_FILE_BYTES / 1024 / 1024) + "MB");
        }
        FormAppBundleDocument document;
        try {
            document = objectMapper.readValue(content, FormAppBundleDocument.class);
        } catch (IOException e) {
            throw new BaseException(FormErrorCode.BUNDLE_INVALID.getCode(), "导入包不是有效的应用导出文件");
        }
        if (document == null || document.getBundleFormatVersion() == null
                || document.getApp() == null
                || document.getApp().getName() == null || document.getApp().getName().isBlank()) {
            throw new BaseException(FormErrorCode.BUNDLE_INVALID.getCode(), "导入包缺少应用信息");
        }
        if (document.getBundleFormatVersion() != FormAppBundleDocument.FORMAT_VERSION) {
            throw new BaseException(FormErrorCode.BUNDLE_FORMAT_UNSUPPORTED.getCode(),
                    "导入包格式版本为 " + document.getBundleFormatVersion()
                            + "，当前系统支持 " + FormAppBundleDocument.FORMAT_VERSION + "，无法导入");
        }
        if (document.getForms() == null) {
            document.setForms(List.of());
        }
        if (document.getDirs() == null) {
            document.setDirs(List.of());
        }
        if (document.getOpinionForms() == null) {
            document.setOpinionForms(List.of());
        }
        if (document.getProcesses() == null) {
            document.setProcesses(List.of());
        }
        if (document.getDictTypes() == null) {
            document.setDictTypes(List.of());
        }
        return document;
    }

    /** 目标租户既有事实（预检与确认共用；预检后目标可能变化，确认时重新收集）。 */
    private record TargetFacts(Set<String> existingFormKeys,
                               boolean appNameTaken,
                               Map<String, String> dictActions,
                               Set<String> existingQueryKeyVersions,
                               Map<String, Boolean> roleCodes,
                               Map<String, Boolean> postCodes) {

        boolean opinionKeyTaken(String key) {
            return existingFormKeys.contains(key);
        }
    }

    private TargetFacts collectTargetFacts(FormAppBundleDocument document) {
        Set<String> allKeys = new LinkedHashSet<>();
        document.getForms().forEach(f -> allKeys.add(f.getFormKey()));
        document.getOpinionForms().forEach(f -> allKeys.add(f.getFormKey()));
        Set<String> existingFormKeys = allKeys.isEmpty() ? Set.of()
                : formDefMapper.selectList(Wrappers.<FormDefEntity>lambdaQuery()
                        .in(FormDefEntity::getFormKey, allKeys)
                        .select(FormDefEntity::getFormKey))
                .stream().map(FormDefEntity::getFormKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        boolean appNameTaken = formAppMapper.selectCount(Wrappers.<FormAppEntity>lambdaQuery()
                .eq(FormAppEntity::getName, document.getApp().getName().trim())) > 0;

        // 字典：目标租户同编码 REUSE；全局缺失 CREATE；被其他租户占用 OCCUPIED（D022-13）。
        // 归属探测是跨租户事实，经 DictFacade.inspectDictTypeForImport（system-biz 内
        // 方法级 @InterceptorIgnore 探测，form 不直查 sys_dict_*）
        Map<String, String> dictActions = new LinkedHashMap<>();
        if (!document.getDictTypes().isEmpty()) {
            DictFacade facade = dictFacade.getIfAvailable();
            for (FormAppBundleDocument.DictTypeBundle type : document.getDictTypes()) {
                String code = type.getCode();
                if (code == null || code.isBlank()) {
                    continue;
                }
                String action = facade == null ? null
                        : facade.inspectDictTypeForImport(code).orElse(null);
                // Facade 未装配按 CREATE 继续，导入阶段记入清除引用清单（DICT_UNAVAILABLE）
                dictActions.put(code, "REUSE".equals(action) ? "REUSE"
                        : "OCCUPIED".equals(action) ? "OCCUPIED" : "CREATE");
            }
        }

        Set<String> queryKeys = new LinkedHashSet<>();
        for (FormAppBundleDocument.FormBundle form : document.getForms()) {
            collectDsBindingKeys(parseObjectOrEmpty(form.getConfigDefinition()), queryKeys);
        }
        Set<String> existingQueryKeyVersions = queryKeys.isEmpty() ? Set.of()
                : formExtQueryMapper.selectList(Wrappers.<FormExtQueryEntity>lambdaQuery()
                        .in(FormExtQueryEntity::getQueryKey, queryKeys))
                .stream()
                .map(q -> q.getQueryKey() + "@" + q.getQueryVersion())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        // 角色/岗位编码（当前租户）经 UserQueryFacade（bpm RoleParticipantResolver 同款
        // sanctioned 路径）；Facade 未装配一律视为未匹配并记入清除引用清单
        Set<String> roleCodeSet = new LinkedHashSet<>();
        Set<String> postCodeSet = new LinkedHashSet<>();
        collectRolePostCodes(document, roleCodeSet, postCodeSet);
        Long tenantId = currentTenantId();
        Map<String, Boolean> roleCodes = lookupRolePostCodes(
                userQueryFacade.getIfAvailable(), true, roleCodeSet, tenantId);
        Map<String, Boolean> postCodes = lookupRolePostCodes(
                userQueryFacade.getIfAvailable(), false, postCodeSet, tenantId);

        return new TargetFacts(existingFormKeys, appNameTaken, dictActions,
                existingQueryKeyVersions, roleCodes, postCodes);
    }

    /**
     * 目标租户角色/岗位编码存在性（跨模块经 UserQueryFacade，禁止直查 sys_role/sys_post）。
     * <p>匹配语义取"编码存在且至少有一名活跃成员"：角色/岗位存在但无成员时运行时
     * 同样解析不出任何审批人/可见主体，按未匹配处理（置空并记入报告）更诚实，
     * 促使管理员在目标环境补配成员或改绑。</p>
     *
     * @param byRole true 查角色编码，false 查岗位编码
     */
    private Map<String, Boolean> lookupRolePostCodes(UserQueryFacade facade, boolean byRole,
                                                     Set<String> codes, Long tenantId) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (String code : codes) {
            boolean matched = false;
            if (facade != null) {
                List<Long> userIds = byRole
                        ? facade.findActiveUserIdsByRoleCodes(List.of(code), tenantId).orElse(List.of())
                        : facade.findActiveUserIdsByPostCodes(List.of(code), tenantId).orElse(List.of());
                matched = !userIds.isEmpty();
            }
            result.put(code, matched);
        }
        return result;
    }

    // ==================================================================================
    // 预检（D022-15 第一步）
    // ==================================================================================

    public FormBundlePreviewResult previewBundle(byte[] content) {
        FormAppBundleDocument document = parseBundle(content);
        TargetFacts facts = collectTargetFacts(document);
        List<FormBundlePreviewResult.ExternalRefPreview> externalRefs = new ArrayList<>();
        Set<String> packageFormKeys = packageFormKeys(document);
        Set<String> packageProcessKeys = packageProcessKeys(document);

        collectAllExternalRefs(document, facts, packageFormKeys, packageProcessKeys, externalRefs);

        List<FormBundlePreviewResult.OpinionFormPreview> opinionPreviews = document.getOpinionForms()
                .stream()
                .map(f -> {
                    boolean conflict = facts.opinionKeyTaken(f.getFormKey());
                    return FormBundlePreviewResult.OpinionFormPreview.builder()
                            .formKey(f.getFormKey())
                            .name(f.getName())
                            .conflict(conflict)
                            .suggestedKey(conflict ? f.getFormKey() + "_" + randomHex(6) : null)
                            .build();
                })
                .collect(Collectors.toList());

        List<FormBundlePreviewResult.FormPreview> formPreviews = document.getForms()
                .stream()
                .map(f -> FormBundlePreviewResult.FormPreview.builder()
                        .formKey(f.getFormKey())
                        .name(f.getName())
                        .keyConflict(facts.existingFormKeys.contains(f.getFormKey()))
                        .build())
                .collect(Collectors.toList());

        int txnActionCount = document.getForms().stream()
                .mapToInt(f -> f.getTxnActions() == null ? 0 : f.getTxnActions().size()).sum();
        int extQueryCount = document.getForms().stream()
                .mapToInt(f -> f.getExtQueries() == null ? 0 : f.getExtQueries().size()).sum();

        return FormBundlePreviewResult.builder()
                .packageName(document.getPackageName())
                .bundleFormatVersion(document.getBundleFormatVersion())
                .systemVersion(document.getSystemVersion())
                .exportedAt(document.getExportedAt())
                .app(FormBundlePreviewResult.AppPreview.builder()
                        .name(document.getApp().getName())
                        .icon(document.getApp().getIcon())
                        .color(document.getApp().getColor())
                        .sortNo(document.getApp().getSortNo())
                        .visibleScopeType(document.getApp().getVisibleScopeType())
                        .nameConflict(facts.appNameTaken)
                        .build())
                .dirs(document.getDirs().stream()
                        .map(d -> FormBundlePreviewResult.DirPreview.builder()
                                .name(d.getName()).sortNo(d.getSortNo()).build())
                        .collect(Collectors.toList()))
                .forms(formPreviews)
                .processes(document.getProcesses().stream()
                        .map(p -> FormBundlePreviewResult.ProcessPreview.builder()
                                .name(p.getName()).formKey(p.getFormKey()).build())
                        .collect(Collectors.toList()))
                .opinionForms(opinionPreviews)
                .dictTypes(document.getDictTypes().stream()
                        .map(t -> FormBundlePreviewResult.DictTypePreview.builder()
                                .code(t.getCode()).name(t.getName())
                                .action(facts.dictActions().getOrDefault(t.getCode(), "CREATE"))
                                .build())
                        .collect(Collectors.toList()))
                .externalRefs(externalRefs)
                .counts(FormBundlePreviewResult.Counts.builder()
                        .dirs(document.getDirs().size())
                        .forms(document.getForms().size())
                        .processes(document.getProcesses().size())
                        .opinionForms(document.getOpinionForms().size())
                        .dictTypes(document.getDictTypes().size())
                        .txnActions(txnActionCount)
                        .extQueries(extQueryCount)
                        .build())
                .build();
    }

    // ==================================================================================
    // 确认导入（D022-15 第二步，单事务整体回滚）
    // ==================================================================================

    /**
     * 确认导入：一律新建，从不覆盖已有对象（D022-11）。
     *
     * @param opinionKeysJson 意见表单冲突解决 JSON：[{"formKey":"包内原 key","newKey":"用户确认的新 key"}]；
     *                        仅冲突项必须提供，非冲突项保持原 key（D022-12）
     */
    @Transactional(rollbackFor = Exception.class)
    public FormBundleImportReport confirmImport(byte[] content, String opinionKeysJson) {
        FormAppBundleDocument document = parseBundle(content);
        TargetFacts facts = collectTargetFacts(document);
        Map<String, String> opinionResolutions = parseOpinionResolutions(opinionKeysJson);

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
        app.setStatus(document.getApp().getStatus() == null ? "ENABLED" : document.getApp().getStatus());
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
        verifyNoSourceResidue(ctx);

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
        record.setReportJson(writeJson(ctx.report));
        importRecordMapper.insert(record);

        log.info("Bundle imported: appId={}, appName={}, forms={}, processes={}, opinionForms={}, clearedRefs={}",
                app.getId(), appName, ctx.report.getCreatedForms().size(),
                ctx.report.getCreatedProcesses().size(),
                ctx.report.getCreatedOpinionForms().size(),
                ctx.report.getClearedRefs().size());
        return ctx.report;
    }

    // ==================================================================================
    // 导入记录
    // ==================================================================================

    public List<FormAppImportRecordEntity> listImportRecords() {
        return importRecordMapper.selectList(Wrappers.<FormAppImportRecordEntity>lambdaQuery()
                .orderByDesc(FormAppImportRecordEntity::getCreateTime)
                .orderByDesc(FormAppImportRecordEntity::getId));
    }

    public FormAppImportRecordEntity getImportRecord(String id) {
        FormAppImportRecordEntity record = importRecordMapper.selectById(id);
        if (record == null) {
            throw new BaseException(FormErrorCode.BUNDLE_RECORD_NOT_FOUND);
        }
        return record;
    }

    // ==================================================================================
    // 导入内部实现
    // ==================================================================================

    /** 单次导入的可变上下文（映射表、报告、保留 key 集与残留校验素材）。 */
    private final class ImportContext {
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
                throw new BaseException(FormErrorCode.BUNDLE_OPINION_KEY_REQUIRED.getCode(),
                        "审批意见表单“" + oldKey + "”在目标租户已存在同名标识，须提供新标识后才能确认导入");
            }
            newKey = newKey.trim();
            if (ctx.facts.existingFormKeys().contains(newKey) || ctx.reservedKeys.contains(newKey)) {
                throw new BaseException(FormErrorCode.BUNDLE_OPINION_KEY_CONFLICT.getCode(),
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
                        ctx.report.getMatchedRoles().add(reused(value, value, null));
                    } else {
                        ctx.report.getClearedRefs().add(cleared("ROLE", value, "应用 可见范围"));
                    }
                }
                case FormAppScopeTypes.POST -> {
                    if (Boolean.TRUE.equals(facts.postCodes().get(value))) {
                        insertScope(appId, type, value);
                        ctx.report.getMatchedPosts().add(reused(value, value, null));
                    } else {
                        ctx.report.getClearedRefs().add(cleared("POST", value, "应用 可见范围"));
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
            collectDsBindingKeys(parseObjectOrEmpty(form.getConfigDefinition()), referenced);
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
            def.setStatus(STATUS_DRAFT);
            def.setFormType(FORM_TYPE_BUSINESS);
            def.setAppId(appId);
            def.setDirId(formBundle.getDirId() == null ? null : dirIdMap.get(formBundle.getDirId()));
            def.setFormVersion(1);
            def.setPhysicalTableName(null);
            def.setSubTableMapping(null);
            // 发起可见范围（D022-13）：指定用户 id 置空（可见范围回到本租户全部用户）
            def.setVisibilityScope(clearedVisibilityScope(formBundle.getVisibilityScope(),
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
                            ? null : findDatasourceIdByName(q.getDatasourceName()).orElse(null);
                    ctx.datasourceIdByOldQueryKey.put(q.getQueryKey(), datasourceId);
                    ctx.datasourceNameByOldQueryKey.put(q.getQueryKey(),
                            q.getDatasourceName() == null ? "" : q.getDatasourceName());
                }
            }

            // 主 config 行（definition 引用改写后写入；子表行发布时派生，D022-14 草稿不建）
            Map<String, Object> definition = parseObjectOrEmpty(formBundle.getConfigDefinition());
            rewriteDefinition(definition, ctx, formBundle.getName());
            insertConfigRow(def.getId(), writeJson(definition));

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
                ctx.report.getMatchedDatasources().add(reused(q.getDatasourceName(),
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
            entity.setStatus(STATUS_DRAFT);
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
            def.setStatus(STATUS_DRAFT);
            def.setFormType(FORM_TYPE_OPINION);
            def.setAppId(null);
            def.setDirId(null);
            def.setFormVersion(1);
            formDefMapper.insert(def);
            ctx.opinionIdMap.put(opinion.getId(), def.getId());

            Map<String, Object> definition = parseObjectOrEmpty(opinion.getConfigDefinition());
            rewriteDefinition(definition, ctx, opinion.getName());
            insertConfigRow(def.getId(), writeJson(definition));

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
                ctx.report.getReusedDictTypes().add(reused(type.getCode(), type.getName(), null));
                continue;
            }
            if ("OCCUPIED".equals(action)) {
                // 编码被其他租户占用：不中断导入，计入报告（D022-13）
                ctx.report.getClearedRefs().add(cleared("DICT_OCCUPIED", type.getCode(),
                        "字典类型 " + type.getCode()));
                continue;
            }
            if (facade == null) {
                // 预检按 CREATE 继续，但 system 模块未装配无法创建：计入清除引用清单
                ctx.report.getClearedRefs().add(cleared("DICT_UNAVAILABLE", type.getCode(),
                        "字典类型 " + type.getCode() + "（系统字典服务未装配，未创建）"));
                continue;
            }
            List<DictItemDTO> items = toItemDTOs(type);
            Boolean created = facade
                    .createDictTypeForImport(type.getCode(), type.getName(), type.getStatus(),
                            type.getDescription(), items)
                    .orElse(false);
            if (!created) {
                // 预检与确认之间无服务端状态：写入前复核发现被并发占用（撞全局唯一
                // 索引 uk_sys_dict_type_code），不中断导入，计入报告（D022-13）
                ctx.report.getClearedRefs().add(cleared("DICT_OCCUPIED", type.getCode(),
                        "字典类型 " + type.getCode()));
            }
        }
    }

    /** 包内字典项 → Facade 导入 DTO（dictValue 归位为 code，类型编码归位 dictType）。 */
    private List<DictItemDTO> toItemDTOs(FormAppBundleDocument.DictTypeBundle type) {
        if (type.getItems() == null) {
            return List.of();
        }
        return type.getItems().stream()
                .map(item -> DictItemDTO.builder()
                        .dictType(type.getCode())
                        .code(item.getDictValue())
                        .label(item.getLabel())
                        .sort(item.getSort())
                        .status(item.getStatus())
                        .isDefault(item.getIsDefault())
                        .cssClass(item.getCssClass())
                        .listClass(item.getListClass())
                        .description(item.getDescription())
                        .build())
                .collect(Collectors.toList());
    }

    private void importProcesses(ImportContext ctx) {
        FormAppBundleProcessPort port = processPort.getIfAvailable();
        for (FormAppBundleDocument.ProcessBundle process : ctx.document.getProcesses()) {
            String newProcessKey = "bpm_" + randomHex(16);
            ctx.processKeyMap.put(process.getProcessKey(), newProcessKey);
        }
        for (FormAppBundleDocument.ProcessBundle process : ctx.document.getProcesses()) {
            String oldFormKey = process.getFormKey();
            FormDefEntity mainForm = ctx.importedFormByKey.get(oldFormKey);
            if (mainForm == null) {
                throw new BaseException(FormErrorCode.BUNDLE_INVALID.getCode(),
                        "流程“" + process.getName() + "”的主表单不在包内，导入包不完整");
            }
            Map<String, Object> graph = parseObjectOrNull(process.getGraphJson());
            if (graph == null) {
                throw new BaseException(FormErrorCode.BUNDLE_INVALID.getCode(),
                        "流程“" + process.getName() + "”的图文档无效");
            }
            rewriteProcessGraph(graph, process, ctx);
            String rewrittenGraph = writeJson(graph);
            ctx.rewrittenGraphs.put(process.getProcessKey(), rewrittenGraph);
            String rewrittenIot = rewriteIotDeviceAction(process.getIotDeviceActionJson(),
                    process.getName(), ctx);

            Long categoryId = null;
            if (process.getCategoryName() != null && !process.getCategoryName().isBlank()) {
                categoryId = findCategoryIdByName(process.getCategoryName()).orElse(null);
                if (categoryId != null) {
                    ctx.report.getMatchedCategories().add(reused(process.getCategoryName(),
                            process.getCategoryName(), categoryId));
                } else {
                    ctx.report.getClearedRefs().add(cleared("CATEGORY", process.getCategoryName(),
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

    // ==================================================================================
    // 引用改写（definition / graph / iotDeviceActionJson）
    // ==================================================================================

    private void rewriteDefinition(Map<String, Object> definition, ImportContext ctx, String formName) {
        if (definition == null) {
            return;
        }
        Object fields = definition.get("fields");
        if (fields instanceof List<?> fieldList) {
            for (Object fieldObj : fieldList) {
                rewriteField(fieldObj, ctx, formName);
            }
        }
        rewriteFieldPermissions(definition.get("fieldPermissions"), ctx, formName);
    }

    @SuppressWarnings("unchecked")
    private void rewriteField(Object fieldObj, ImportContext ctx, String formName) {
        if (!(fieldObj instanceof Map<?, ?> fieldRaw)) {
            return;
        }
        Map<String, Object> field = (Map<String, Object>) fieldRaw;
        String type = text(field.get("type"));
        String fieldLabel = text(field.get("label"));
        String fieldPath = "表单 " + formName + (fieldLabel == null ? "" : " · 字段 " + fieldLabel);
        if ("REFERENCE".equalsIgnoreCase(type)) {
            Object target = field.get("targetFormId");
            String targetKey = text(target);
            if (targetKey != null) {
                if (ctx.formKeyMap.containsKey(targetKey)) {
                    field.put("targetFormId", ctx.formKeyMap.get(targetKey));
                } else if (ctx.opinionKeyMap.containsKey(targetKey)) {
                    field.put("targetFormId", ctx.opinionKeyMap.get(targetKey));
                } else {
                    // 包外表单引用：按外部引用处理，置空并记入报告（方向 §非目标）
                    field.put("targetFormId", null);
                    ctx.report.getClearedRefs().add(cleared("FORM", targetKey, fieldPath + " · 引用"));
                }
            }
        } else if ("DICT".equalsIgnoreCase(type)) {
            // dictType 为编码引用：目标租户复用/新建则保留；被占用则永不可解析，
            // 按 D022-13（匹配不上的置空）清空字段绑定并记字段位置，方便发布前补配
            String dictCode = text(field.get("dictType"));
            if (dictCode != null && "OCCUPIED".equals(ctx.facts.dictActions().get(dictCode))) {
                field.put("dictType", null);
                ctx.report.getClearedRefs().add(cleared("DICT", dictCode, fieldPath + " · 字典"));
            }
        } else if ("DATASOURCE".equalsIgnoreCase(type)) {
            Object binding = field.get("dsBinding");
            if (binding instanceof Map<?, ?> bindingRaw) {
                Map<String, Object> dsBinding = (Map<String, Object>) bindingRaw;
                String oldKey = text(dsBinding.get("queryKey"));
                if (oldKey != null) {
                    String finalKey = ctx.queryKeyMap.get(oldKey);
                    Long datasourceId = ctx.datasourceIdByOldQueryKey.get(oldKey);
                    if (finalKey != null && datasourceId != null) {
                        dsBinding.put("queryKey", finalKey);
                    } else {
                        // 数据源未匹配：契约行无法落库（datasource_id NOT NULL），绑定整体置空
                        dsBinding.put("queryKey", null);
                        dsBinding.put("version", null);
                        ctx.report.getClearedRefs().add(cleared("DATASOURCE",
                                ctx.datasourceNameByOldQueryKey.getOrDefault(oldKey, oldKey),
                                fieldPath + " · 数据源绑定"));
                    }
                }
            }
        }
        Object subFields = field.get("subFields");
        if (subFields instanceof List<?> subList) {
            for (Object sub : subList) {
                rewriteField(sub, ctx, formName);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void rewriteFieldPermissions(Object permsObj, ImportContext ctx, String formName) {
        if (!(permsObj instanceof Map<?, ?> permsRaw)) {
            return;
        }
        Map<String, Object> perms = (Map<String, Object>) permsRaw;
        for (Map.Entry<String, Object> entry : perms.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> modesRaw)) {
                continue;
            }
            Map<String, Object> modes = (Map<String, Object>) modesRaw;
            for (Map.Entry<String, Object> modeEntry : modes.entrySet()) {
                if (!(modeEntry.getValue() instanceof List<?> subjects)) {
                    continue;
                }
                List<Object> kept = new ArrayList<>();
                for (Object subjectObj : subjects) {
                    String subject = text(subjectObj);
                    if (subject == null) {
                        continue;
                    }
                    if (subject.startsWith("user:")) {
                        ctx.report.getClearedRefs().add(cleared("USER", subject,
                                "表单 " + formName + " · 字段权限 " + entry.getKey()));
                        continue;
                    }
                    if (subject.startsWith("dept:")) {
                        ctx.report.getClearedRefs().add(cleared("DEPT", subject,
                                "表单 " + formName + " · 字段权限 " + entry.getKey()));
                        continue;
                    }
                    if (subject.startsWith("role:")) {
                        String code = subject.substring("role:".length());
                        if (Boolean.TRUE.equals(ctx.facts.roleCodes().get(code))) {
                            ctx.report.getMatchedRoles().add(reused("role:" + code, "角色 " + code, null));
                        } else {
                            ctx.report.getClearedRefs().add(cleared("ROLE", subject,
                                    "表单 " + formName + " · 字段权限 " + entry.getKey()));
                            continue;
                        }
                    }
                    kept.add(subject);
                }
                modes.put(modeEntry.getKey(), kept);
            }
        }
    }

    /** 发起可见范围：userIds 指定用户置空（空 = 本租户全部用户），返回改写后的 JSON 或 null。 */
    private String clearedVisibilityScope(String visibilityScope, String formName, ImportContext ctx) {
        if (visibilityScope == null || visibilityScope.isBlank()) {
            return null;
        }
        Map<String, Object> scope = parseObjectOrNull(visibilityScope);
        if (scope == null) {
            return null;
        }
        Object userIds = scope.get("userIds");
        if (userIds instanceof List<?> ids && !ids.isEmpty()) {
            ctx.report.getClearedRefs().add(cleared("USER", joinValues(ids),
                    "表单 " + formName + " · 发起可见范围"));
        }
        return null;
    }

    /**
     * 流程图改写（方向 §风险方向 引用位置清单逐项处理）：
     * <ul>
     *   <li>processKey 归一为新 key；</li>
     *   <li>nodeForm.formKey → 意见表单新 key，formVersion → 新表单当前版本；</li>
     *   <li>opinionForm.formId → 新意见表单 id，version → 新表单当前版本；</li>
     *   <li>participant/approver 按 D022-13 匹配或置空并记报告；</li>
     *   <li>TXN_ACTION actionId → 包内交易动作新 id；</li>
     *   <li>触发器动作 targetProcessDefKey / targetFormKey 包内改写、包外置空记报告；</li>
     *   <li>notification.otherRecipientIds、DYNAMIC_PARALLEL FIXED 来源置空记报告；</li>
     *   <li>节点/边 id、变量、通知渠道枚举、FORM_FIELD 等图内引用原样保留。</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private void rewriteProcessGraph(Map<String, Object> graph, FormAppBundleDocument.ProcessBundle process,
                                     ImportContext ctx) {
        graph.put("processKey", ctx.processKeyMap.get(process.getProcessKey()));
        Object elements = graph.get("elements");
        if (elements instanceof List<?> elementList) {
            for (Object elementObj : elementList) {
                if (!(elementObj instanceof Map<?, ?> elementRaw)) {
                    continue;
                }
                Map<String, Object> element = (Map<String, Object>) elementRaw;
                if (!"node".equals(text(element.get("kind")))) {
                    continue;
                }
                Object configObj = element.get("config");
                if (!(configObj instanceof Map<?, ?> configRaw)) {
                    continue;
                }
                Map<String, Object> config = (Map<String, Object>) configRaw;
                String nodeName = text(config.get("name"));
                String nodePath = "流程 " + process.getName()
                        + (nodeName == null ? "" : " · 节点 " + nodeName);
                rewriteNodeForm(config.get("nodeForm"), ctx);
                rewriteOpinionForm(config.get("opinionForm"), ctx, nodePath);
                rewriteParticipant(config.get("participant"), ctx, nodePath);
                rewriteParticipant(config.get("approver"), ctx, nodePath);
                if ("TXN_ACTION".equalsIgnoreCase(text(element.get("type")))) {
                    String actionId = text(config.get("actionId"));
                    if (actionId != null) {
                        String newActionId = ctx.txnActionIdMap.get(actionId);
                        if (newActionId != null) {
                            config.put("actionId", newActionId);
                        } else {
                            config.put("actionId", null);
                            ctx.report.getClearedRefs().add(cleared("TXN_ACTION", actionId,
                                    nodePath + " · 交易动作"));
                        }
                    }
                }
                if ("DYNAMIC_PARALLEL".equalsIgnoreCase(text(element.get("type")))) {
                    rewriteDynamicParallelSource(config.get("source"), ctx, nodePath);
                }
            }
        }
        rewriteTriggers(graph.get("triggers"), ctx, process.getName());
        rewriteProcessNotification(graph.get("notification"), ctx, process.getName());
    }

    @SuppressWarnings("unchecked")
    private void rewriteNodeForm(Object nodeFormObj, ImportContext ctx) {
        if (!(nodeFormObj instanceof Map<?, ?> nodeFormRaw)) {
            return;
        }
        Map<String, Object> nodeForm = (Map<String, Object>) nodeFormRaw;
        String formKey = text(nodeForm.get("formKey"));
        if (formKey != null) {
            String finalKey = ctx.opinionKeyMap.containsKey(formKey)
                    ? ctx.opinionKeyMap.get(formKey) : ctx.formKeyMap.get(formKey);
            if (finalKey != null) {
                nodeForm.put("formKey", finalKey);
                nodeForm.put("formVersion", 1);
            } else {
                nodeForm.put("formKey", null);
                nodeForm.put("formVersion", null);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void rewriteOpinionForm(Object opinionObj, ImportContext ctx, String nodePath) {
        if (!(opinionObj instanceof Map<?, ?> opinionRaw)) {
            return;
        }
        Map<String, Object> opinionForm = (Map<String, Object>) opinionRaw;
        String oldFormId = text(opinionForm.get("formId"));
        if (oldFormId != null) {
            String newFormId = ctx.opinionIdMap.get(oldFormId);
            if (newFormId != null) {
                opinionForm.put("formId", newFormId);
                opinionForm.put("version", 1);
            } else {
                opinionForm.put("formId", null);
                opinionForm.put("version", null);
            }
        }
    }

    /** 参与人策略改写（D022-13）：FIXED_USER/DEPT 置空；ROLE/POST 编码匹配；DEPT_POST 部门置空。 */
    @SuppressWarnings("unchecked")
    private void rewriteParticipant(Object participantObj, ImportContext ctx, String nodePath) {
        if (!(participantObj instanceof Map<?, ?> participantRaw)) {
            return;
        }
        Map<String, Object> participant = (Map<String, Object>) participantRaw;
        String strategy = text(participant.get("strategy"));
        if (strategy == null) {
            // 兼容旧 approver 形态 {approverType, value}
            strategy = text(participant.get("approverType"));
        }
        if (strategy == null) {
            return;
        }
        switch (strategy.toUpperCase()) {
            case "FIXED_USER" -> {
                Object value = participant.get("value");
                if (hasValue(value)) {
                    ctx.report.getClearedRefs().add(cleared("FIXED_USER", valueText(value),
                            nodePath + " · 参与人（指定用户）"));
                    participant.put("value", null);
                }
            }
            case "DEPT_LEADER" -> {
                Object value = participant.get("value");
                if (hasValue(value)) {
                    ctx.report.getClearedRefs().add(cleared("DEPT", valueText(value),
                            nodePath + " · 参与人（部门负责人）"));
                    participant.put("value", null);
                }
            }
            case "ROLE" -> rewriteCodeList(participant, ctx.facts.roleCodes(), ctx,
                    nodePath + " · 参与人（角色）", ctx.report.getMatchedRoles());
            case "POST" -> rewriteCodeList(participant, ctx.facts.postCodes(), ctx,
                    nodePath + " · 参与人（岗位）", ctx.report.getMatchedPosts());
            case "DEPT_POST" -> {
                Object value = participant.get("value");
                if (value instanceof Map<?, ?> mappingRaw) {
                    Map<String, Object> mapping = (Map<String, Object>) mappingRaw;
                    Object deptId = mapping.get("deptId");
                    if (deptId != null) {
                        ctx.report.getClearedRefs().add(cleared("DEPT", String.valueOf(deptId),
                                nodePath + " · 参与人（部门+岗位）"));
                    }
                    mapping.put("deptId", null);
                    String postCode = text(mapping.get("postCode"));
                    if (postCode != null && !Boolean.TRUE.equals(ctx.facts.postCodes().get(postCode))) {
                        ctx.report.getClearedRefs().add(cleared("POST", postCode,
                                nodePath + " · 参与人（部门+岗位）"));
                        mapping.put("postCode", null);
                    }
                }
            }
            default -> {
                // FORM_FIELD / NODE_FORM_AGGREGATE / EXPRESSION / ADAPTER：图内或表达式引用，原样保留
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void rewriteCodeList(Map<String, Object> participant, Map<String, Boolean> codeFacts,
                                 ImportContext ctx, String location, List<FormBundleImportReport.ReusedObject> matchedSink) {
        Object value = participant.get("value");
        if (!(value instanceof List<?> codes)) {
            if (hasValue(value)) {
                String code = valueText(value);
                if (Boolean.TRUE.equals(codeFacts.get(code))) {
                    matchedSink.add(reused(code, code, null));
                } else {
                    ctx.report.getClearedRefs().add(cleared(
                            codeFacts == ctx.facts.roleCodes() ? "ROLE" : "POST", code, location));
                    participant.put("value", null);
                }
            }
            return;
        }
        List<Object> kept = new ArrayList<>();
        boolean isRole = codeFacts == ctx.facts.roleCodes();
        for (Object codeObj : codes) {
            String code = text(codeObj);
            if (code == null) {
                continue;
            }
            if (Boolean.TRUE.equals(codeFacts.get(code))) {
                kept.add(codeObj);
                matchedSink.add(reused(code, code, null));
            } else {
                ctx.report.getClearedRefs().add(cleared(isRole ? "ROLE" : "POST", code, location));
            }
        }
        participant.put("value", kept.isEmpty() ? null : kept);
    }

    @SuppressWarnings("unchecked")
    private void rewriteDynamicParallelSource(Object sourceObj, ImportContext ctx, String nodePath) {
        if (!(sourceObj instanceof Map<?, ?> sourceRaw)) {
            return;
        }
        Map<String, Object> source = (Map<String, Object>) sourceRaw;
        if (!"FIXED".equalsIgnoreCase(text(source.get("type")))) {
            return;
        }
        String objectType = text(source.get("objectType"));
        Object value = source.get("value");
        if (hasValue(value)) {
            String kind = "DEPT".equalsIgnoreCase(objectType) ? "DEPT" : "USER";
            ctx.report.getClearedRefs().add(cleared(kind, valueText(value),
                    nodePath + " · 动态并行来源"));
            source.put("value", null);
        }
    }

    @SuppressWarnings("unchecked")
    private void rewriteTriggers(Object triggersObj, ImportContext ctx, String processName) {
        if (!(triggersObj instanceof List<?> triggers)) {
            return;
        }
        for (Object triggerObj : triggers) {
            if (!(triggerObj instanceof Map<?, ?> triggerRaw)) {
                continue;
            }
            Map<String, Object> trigger = (Map<String, Object>) triggerRaw;
            Object branches = trigger.get("branches");
            if (!(branches instanceof List<?> branchList)) {
                continue;
            }
            for (Object branchObj : branchList) {
                if (!(branchObj instanceof Map<?, ?> branchRaw)) {
                    continue;
                }
                Map<String, Object> branch = (Map<String, Object>) branchRaw;
                Object actions = branch.get("actions");
                if (!(actions instanceof List<?> actionList)) {
                    continue;
                }
                for (Object actionObj : actionList) {
                    if (!(actionObj instanceof Map<?, ?> actionRaw)) {
                        continue;
                    }
                    Map<String, Object> action = (Map<String, Object>) actionRaw;
                    String targetProcessKey = text(action.get("targetProcessDefKey"));
                    if (targetProcessKey != null) {
                        String newKey = ctx.processKeyMap.get(targetProcessKey);
                        if (newKey != null) {
                            action.put("targetProcessDefKey", newKey);
                        } else {
                            action.put("targetProcessDefKey", null);
                            ctx.report.getClearedRefs().add(cleared("PROCESS", targetProcessKey,
                                    "流程 " + processName + " · 触发器动作"));
                        }
                    }
                    String targetFormKey = text(action.get("targetFormKey"));
                    if (targetFormKey != null) {
                        String newKey = ctx.formKeyMap.get(targetFormKey);
                        if (newKey != null) {
                            action.put("targetFormKey", newKey);
                        } else {
                            action.put("targetFormKey", null);
                            ctx.report.getClearedRefs().add(cleared("FORM", targetFormKey,
                                    "流程 " + processName + " · 触发器动作"));
                        }
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void rewriteProcessNotification(Object notificationObj, ImportContext ctx, String processName) {
        if (!(notificationObj instanceof Map<?, ?> notificationRaw)) {
            return;
        }
        Map<String, Object> notification = (Map<String, Object>) notificationRaw;
        Object otherIds = notification.get("otherRecipientIds");
        if (otherIds instanceof List<?> ids && !ids.isEmpty()) {
            ctx.report.getClearedRefs().add(cleared("USER", joinValues(ids),
                    "流程 " + processName + " · 结束通知接收人"));
            notification.put("otherRecipientIds", null);
        }
    }

    /** iotDeviceActionJson：deviceSource=FIXED 时 deviceId 置空并记报告（设备按外部引用置空）。 */
    private String rewriteIotDeviceAction(String raw, String processName, ImportContext ctx) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        Map<String, Object> config = parseObjectOrNull(raw);
        if (config == null) {
            return raw;
        }
        if ("FIXED".equalsIgnoreCase(text(config.get("deviceSource"))) && config.get("deviceId") != null) {
            ctx.report.getClearedRefs().add(cleared("IOT_DEVICE", String.valueOf(config.get("deviceId")),
                    "流程 " + processName + " · IoT 设备"));
            config.put("deviceId", null);
            return writeJson(config);
        }
        return raw;
    }

    // ==================================================================================
    // 预检外部引用遍历（与导入改写同一张引用位置清单）
    // ==================================================================================

    private void collectAllExternalRefs(FormAppBundleDocument document, TargetFacts facts,
                                        Set<String> packageFormKeys, Set<String> packageProcessKeys,
                                        List<FormBundlePreviewResult.ExternalRefPreview> sink) {
        // 应用可见范围
        List<FormAppBundleDocument.ScopeBundle> scopes = document.getApp().getScopes();
        if (scopes != null) {
            for (FormAppBundleDocument.ScopeBundle scope : scopes) {
                String type = scope.getScopeType() == null ? "" : scope.getScopeType();
                String value = scope.getScopeId() == null ? "" : scope.getScopeId();
                switch (type) {
                    case FormAppScopeTypes.USER, FormAppScopeTypes.DEPT ->
                            sink.add(externalRef(type, value, "应用 可见范围", false, null));
                    case FormAppScopeTypes.ROLE -> sink.add(externalRef("ROLE", value,
                            "应用 可见范围", Boolean.TRUE.equals(facts.roleCodes().get(value)), value));
                    case FormAppScopeTypes.POST -> sink.add(externalRef("POST", value,
                            "应用 可见范围", Boolean.TRUE.equals(facts.postCodes().get(value)), value));
                    default -> {
                        // TENANT：租户自身，保留
                    }
                }
            }
        }
        // 表单定义
        Map<String, String> queryDatasourceNames = new LinkedHashMap<>();
        for (FormAppBundleDocument.FormBundle form : document.getForms()) {
            if (form.getExtQueries() != null) {
                for (FormAppBundleDocument.ExtQueryBundle q : form.getExtQueries()) {
                    if (q.getQueryKey() != null) {
                        queryDatasourceNames.putIfAbsent(q.getQueryKey(), q.getDatasourceName());
                    }
                }
            }
        }
        for (FormAppBundleDocument.FormBundle form : document.getForms()) {
            String formPath = "表单 " + form.getName();
            collectVisibilityScopeRefs(form.getVisibilityScope(), formPath, sink);
            collectDefinitionRefs(parseObjectOrEmpty(form.getConfigDefinition()), formPath,
                    packageFormKeys, queryDatasourceNames, facts, sink);
            if (form.getExtQueries() != null) {
                for (FormAppBundleDocument.ExtQueryBundle q : form.getExtQueries()) {
                    if (q.getDatasourceName() != null && !q.getDatasourceName().isBlank()) {
                        boolean matched = findDatasourceIdByName(q.getDatasourceName()).isPresent();
                        sink.add(externalRef("DATASOURCE", q.getDatasourceName(),
                                formPath + " · 扩展查询 " + q.getQueryKey(), matched,
                                matched ? q.getDatasourceName() : null));
                    }
                }
            }
        }
        // 意见表单定义
        for (FormAppBundleDocument.OpinionFormBundle opinion : document.getOpinionForms()) {
            collectDefinitionRefs(parseObjectOrEmpty(opinion.getConfigDefinition()),
                    "意见表单 " + opinion.getName(), packageFormKeys, queryDatasourceNames, facts, sink);
        }
        // 流程
        for (FormAppBundleDocument.ProcessBundle process : document.getProcesses()) {
            String processPath = "流程 " + process.getName();
            if (process.getCategoryName() != null && !process.getCategoryName().isBlank()) {
                boolean matched = findCategoryIdByName(process.getCategoryName()).isPresent();
                sink.add(externalRef("CATEGORY", process.getCategoryName(),
                        processPath + " · 分类", matched, matched ? process.getCategoryName() : null));
            }
            collectGraphRefs(process, packageFormKeys, packageProcessKeys, facts, sink);
            if (process.getIotDeviceActionJson() != null && !process.getIotDeviceActionJson().isBlank()) {
                Map<String, Object> iot = parseObjectOrNull(process.getIotDeviceActionJson());
                if (iot != null && "FIXED".equalsIgnoreCase(text(iot.get("deviceSource")))
                        && iot.get("deviceId") != null) {
                    sink.add(externalRef("IOT_DEVICE", String.valueOf(iot.get("deviceId")),
                            processPath + " · IoT 设备", false, null));
                }
            }
        }
    }

    private void collectVisibilityScopeRefs(String visibilityScope, String formPath,
                                            List<FormBundlePreviewResult.ExternalRefPreview> sink) {
        if (visibilityScope == null || visibilityScope.isBlank()) {
            return;
        }
        Map<String, Object> scope = parseObjectOrNull(visibilityScope);
        if (scope != null && scope.get("userIds") instanceof List<?> ids && !ids.isEmpty()) {
            sink.add(externalRef("USER", joinValues(ids), formPath + " · 发起可见范围", false, null));
        }
    }

    private void collectDefinitionRefs(Map<String, Object> definition, String formPath,
                                       Set<String> packageFormKeys,
                                       Map<String, String> queryDatasourceNames, TargetFacts facts,
                                       List<FormBundlePreviewResult.ExternalRefPreview> sink) {
        if (definition == null) {
            return;
        }
        Object fields = definition.get("fields");
        if (fields instanceof List<?> fieldList) {
            for (Object fieldObj : fieldList) {
                collectFieldRefs(fieldObj, formPath, packageFormKeys, queryDatasourceNames, facts, sink);
            }
        }
        if (definition.get("fieldPermissions") instanceof Map<?, ?> perms) {
            for (Map.Entry<?, ?> entry : perms.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?> modes)) {
                    continue;
                }
                for (Map.Entry<?, ?> mode : modes.entrySet()) {
                    if (!(mode.getValue() instanceof List<?> subjects)) {
                        continue;
                    }
                    for (Object subjectObj : subjects) {
                        String subject = text(subjectObj);
                        if (subject == null) {
                            continue;
                        }
                        String fieldPath = formPath + " · 字段权限 " + entry.getKey();
                        if (subject.startsWith("user:")) {
                            sink.add(externalRef("USER", subject, fieldPath, false, null));
                        } else if (subject.startsWith("dept:")) {
                            sink.add(externalRef("DEPT", subject, fieldPath, false, null));
                        } else if (subject.startsWith("role:")) {
                            String code = subject.substring("role:".length());
                            sink.add(externalRef("ROLE", subject, fieldPath,
                                    Boolean.TRUE.equals(facts.roleCodes().get(code)),
                                    Boolean.TRUE.equals(facts.roleCodes().get(code)) ? subject : null));
                        }
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void collectFieldRefs(Object fieldObj, String formPath, Set<String> packageFormKeys,
                                  Map<String, String> queryDatasourceNames, TargetFacts facts,
                                  List<FormBundlePreviewResult.ExternalRefPreview> sink) {
        if (!(fieldObj instanceof Map<?, ?> fieldRaw)) {
            return;
        }
        Map<String, Object> field = (Map<String, Object>) fieldRaw;
        String type = text(field.get("type"));
        String fieldLabel = text(field.get("label"));
        String fieldPath = formPath + (fieldLabel == null ? "" : " · 字段 " + fieldLabel);
        if ("REFERENCE".equalsIgnoreCase(type)) {
            String targetKey = text(field.get("targetFormId"));
            if (targetKey != null && !packageFormKeys.contains(targetKey)) {
                sink.add(externalRef("FORM", targetKey, fieldPath + " · 引用", false, null));
            }
        } else if ("DICT".equalsIgnoreCase(type)) {
            String dictCode = text(field.get("dictType"));
            if (dictCode != null) {
                String action = facts.dictActions().get(dictCode);
                sink.add(externalRef("DICT", dictCode, fieldPath + " · 字典",
                        !"OCCUPIED".equals(action),
                        "OCCUPIED".equals(action) ? null : action));
            }
        } else if ("DATASOURCE".equalsIgnoreCase(type)) {
            if (field.get("dsBinding") instanceof Map<?, ?> binding) {
                String queryKey = text(binding.get("queryKey"));
                if (queryKey != null) {
                    // 契约行随包导入自愈；可绑定与否取决于数据源能否按名称匹配
                    String datasourceName = queryDatasourceNames.get(queryKey);
                    boolean datasourceMatched = datasourceName == null || datasourceName.isBlank()
                            || findDatasourceIdByName(datasourceName).isPresent();
                    sink.add(externalRef("QUERY", queryKey, fieldPath + " · 数据源绑定",
                            datasourceMatched, datasourceMatched ? null : datasourceName));
                }
            }
        }
        if (field.get("subFields") instanceof List<?> subList) {
            for (Object sub : subList) {
                collectFieldRefs(sub, formPath, packageFormKeys, queryDatasourceNames, facts, sink);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void collectGraphRefs(FormAppBundleDocument.ProcessBundle process, Set<String> packageFormKeys,
                                  Set<String> packageProcessKeys, TargetFacts facts,
                                  List<FormBundlePreviewResult.ExternalRefPreview> sink) {
        Map<String, Object> graph = parseObjectOrNull(process.getGraphJson());
        if (graph == null) {
            return;
        }
        String processPath = "流程 " + process.getName();
        if (graph.get("elements") instanceof List<?> elements) {
            for (Object elementObj : elements) {
                if (!(elementObj instanceof Map<?, ?> element)
                        || !"node".equals(text(element.get("kind")))
                        || !(element.get("config") instanceof Map<?, ?> configRaw)) {
                    continue;
                }
                Map<String, Object> config = (Map<String, Object>) configRaw;
                String nodeName = text(config.get("name"));
                String nodePath = processPath + (nodeName == null ? "" : " · 节点 " + nodeName);
                collectParticipantRef(config.get("participant"), facts, sink, nodePath);
                collectParticipantRef(config.get("approver"), facts, sink, nodePath);
                if ("DYNAMIC_PARALLEL".equalsIgnoreCase(text(element.get("type")))
                        && config.get("source") instanceof Map<?, ?> sourceRaw
                        && "FIXED".equalsIgnoreCase(text(((Map<?, ?>) sourceRaw).get("type")))) {
                    sink.add(externalRef(
                            "DEPT".equalsIgnoreCase(text(((Map<?, ?>) sourceRaw).get("objectType")))
                                    ? "DEPT" : "USER",
                            valueText(((Map<?, ?>) sourceRaw).get("value")),
                            nodePath + " · 动态并行来源", false, null));
                }
            }
        }
        if (graph.get("triggers") instanceof List<?> triggers) {
            for (Object triggerObj : triggers) {
                if (!(triggerObj instanceof Map<?, ?> trigger)
                        || !(trigger.get("branches") instanceof List<?> branches)) {
                    continue;
                }
                for (Object branchObj : branches) {
                    if (!(branchObj instanceof Map<?, ?> branch)
                            || !(branch.get("actions") instanceof List<?> actions)) {
                        continue;
                    }
                    for (Object actionObj : actions) {
                        if (!(actionObj instanceof Map<?, ?> action)) {
                            continue;
                        }
                        String targetProcessKey = text(action.get("targetProcessDefKey"));
                        if (targetProcessKey != null && !packageProcessKeys.contains(targetProcessKey)) {
                            sink.add(externalRef("PROCESS", targetProcessKey,
                                    processPath + " · 触发器动作", false, null));
                        }
                        String targetFormKey = text(action.get("targetFormKey"));
                        if (targetFormKey != null && !packageFormKeys.contains(targetFormKey)) {
                            sink.add(externalRef("FORM", targetFormKey,
                                    processPath + " · 触发器动作", false, null));
                        }
                    }
                }
            }
        }
        if (graph.get("notification") instanceof Map<?, ?> notification
                && notification.get("otherRecipientIds") instanceof List<?> ids && !ids.isEmpty()) {
            sink.add(externalRef("USER", joinValues(ids), processPath + " · 结束通知接收人", false, null));
        }
    }

    @SuppressWarnings("unchecked")
    private void collectParticipantRef(Object participantObj, TargetFacts facts,
                                       List<FormBundlePreviewResult.ExternalRefPreview> sink, String nodePath) {
        if (!(participantObj instanceof Map<?, ?> participantRaw)) {
            return;
        }
        Map<String, Object> participant = (Map<String, Object>) participantRaw;
        String strategy = text(participant.get("strategy"));
        if (strategy == null) {
            strategy = text(participant.get("approverType"));
        }
        if (strategy == null) {
            return;
        }
        switch (strategy.toUpperCase()) {
            case "FIXED_USER" -> sink.add(externalRef("FIXED_USER", valueText(participant.get("value")),
                    nodePath + " · 参与人（指定用户）", false, null));
            case "DEPT_LEADER" -> sink.add(externalRef("DEPT", valueText(participant.get("value")),
                    nodePath + " · 参与人（部门负责人）", false, null));
            case "ROLE" -> collectCodeListRef(participant.get("value"), facts.roleCodes(), sink,
                    nodePath + " · 参与人（角色）", "ROLE");
            case "POST" -> collectCodeListRef(participant.get("value"), facts.postCodes(), sink,
                    nodePath + " · 参与人（岗位）", "POST");
            case "DEPT_POST" -> {
                if (participant.get("value") instanceof Map<?, ?> mapping) {
                    if (mapping.get("deptId") != null) {
                        sink.add(externalRef("DEPT", String.valueOf(mapping.get("deptId")),
                                nodePath + " · 参与人（部门+岗位）", false, null));
                    }
                    String postCode = text(mapping.get("postCode"));
                    if (postCode != null) {
                        sink.add(externalRef("POST", postCode, nodePath + " · 参与人（部门+岗位）",
                                Boolean.TRUE.equals(facts.postCodes().get(postCode)),
                                Boolean.TRUE.equals(facts.postCodes().get(postCode)) ? postCode : null));
                    }
                }
            }
            default -> {
                // 图内/表达式/适配器引用，不外发
            }
        }
    }

    private void collectCodeListRef(Object value, Map<String, Boolean> codeFacts,
                                    List<FormBundlePreviewResult.ExternalRefPreview> sink,
                                    String location, String kind) {
        if (value instanceof List<?> codes) {
            for (Object codeObj : codes) {
                String code = text(codeObj);
                if (code == null) {
                    continue;
                }
                boolean matched = Boolean.TRUE.equals(codeFacts.get(code));
                sink.add(externalRef(kind, code, location, matched, matched ? code : null));
            }
        } else if (hasValue(value)) {
            String code = valueText(value);
            boolean matched = Boolean.TRUE.equals(codeFacts.get(code));
            sink.add(externalRef(kind, code, location, matched, matched ? code : null));
        }
    }

    // ==================================================================================
    // 残留校验（写入后、记录前；任一残留抛错回滚）
    // ==================================================================================

    private void verifyNoSourceResidue(ImportContext ctx) {
        List<String> jsonDocs = new ArrayList<>();
        for (FormAppBundleDocument.FormBundle form : ctx.document.getForms()) {
            FormDefEntity def = ctx.importedFormByKey.get(form.getFormKey());
            FormConfigEntity config = formConfigMapper.selectOne(Wrappers.<FormConfigEntity>lambdaQuery()
                    .eq(FormConfigEntity::getFormId, def.getId())
                    .isNull(FormConfigEntity::getParentTable)
                    .last("LIMIT 1"));
            if (config != null && config.getDefinition() != null) {
                jsonDocs.add(config.getDefinition());
            }
        }
        for (FormAppBundleDocument.OpinionFormBundle opinion : ctx.document.getOpinionForms()) {
            String newId = ctx.opinionIdMap.get(opinion.getId());
            FormConfigEntity config = formConfigMapper.selectOne(Wrappers.<FormConfigEntity>lambdaQuery()
                    .eq(FormConfigEntity::getFormId, newId)
                    .isNull(FormConfigEntity::getParentTable)
                    .last("LIMIT 1"));
            if (config != null && config.getDefinition() != null) {
                jsonDocs.add(config.getDefinition());
            }
        }
        // UUID 类残留：全文档包含检查（源表单/动作/意见表单 id，随机串不可能误报）
        Set<String> forbiddenIds = new LinkedHashSet<>();
        ctx.document.getForms().forEach(f -> forbiddenIds.add(f.getId()));
        ctx.document.getOpinionForms().forEach(f -> forbiddenIds.add(f.getId()));
        forbiddenIds.addAll(collectTxnActionUuids(ctx.document));
        // 包内已改写的业务表单 key：位置级复核（key 可能与普通文案重名，不做包含检查）
        verifyKeyPositionsRewritten(ctx);

        for (String doc : jsonDocs) {
            for (String id : forbiddenIds) {
                if (doc.contains(id)) {
                    throw new BaseException(FormErrorCode.BUNDLE_IMPORT_FAILED.getCode(),
                            "导入校验失败：包内文档残留源表单/动作 id " + id + "，已整体回滚");
                }
            }
        }
        for (FormAppBundleDocument.ProcessBundle process : ctx.document.getProcesses()) {
            String newKey = ctx.processKeyMap.get(process.getProcessKey());
            FormBundleImportReport.CreatedProcess created = ctx.report.getCreatedProcesses().stream()
                    .filter(p -> p.getOldProcessKey().equals(process.getProcessKey()))
                    .findFirst().orElse(null);
            if (created == null || !created.getNewProcessKey().equals(newKey)) {
                throw new BaseException(FormErrorCode.BUNDLE_IMPORT_FAILED.getCode(),
                        "导入校验失败：流程 key 改写记录不一致，已整体回滚");
            }
        }
    }

    /** 收集包内全部交易动作源 UUID（TXN_ACTION 节点 actionId 引用的标识）。 */
    private Set<String> collectTxnActionUuids(FormAppBundleDocument document) {
        Set<String> uuids = new LinkedHashSet<>();
        for (FormAppBundleDocument.FormBundle form : document.getForms()) {
            if (form.getTxnActions() == null) {
                continue;
            }
            for (FormAppBundleDocument.TxnActionBundle action : form.getTxnActions()) {
                if (action.getId() != null && !action.getId().isBlank()) {
                    uuids.add(action.getId());
                }
            }
        }
        return uuids;
    }

    /**
     * key 类引用位置级复核：重新解析改写后的文档，确认
     * REFERENCE.targetFormId / nodeForm.formKey / opinionForm.formId / TXN_ACTION.actionId /
     * 触发器 targetFormKey / targetProcessDefKey 全部指向包内新对象或已置空。
     */
    private void verifyKeyPositionsRewritten(ImportContext ctx) {
        Set<String> finalFormKeys = ctx.allFinalFormKeys();
        Set<String> finalProcessKeys = new LinkedHashSet<>(ctx.processKeyMap.values());
        for (FormAppBundleDocument.FormBundle form : ctx.document.getForms()) {
            FormDefEntity def = ctx.importedFormByKey.get(form.getFormKey());
            FormConfigEntity config = formConfigMapper.selectOne(Wrappers.<FormConfigEntity>lambdaQuery()
                    .eq(FormConfigEntity::getFormId, def.getId())
                    .isNull(FormConfigEntity::getParentTable)
                    .last("LIMIT 1"));
            if (config == null) {
                continue;
            }
            Map<String, Object> definition = parseObjectOrEmpty(config.getDefinition());
            checkDefinitionKeyPositions(definition, finalFormKeys,
                    "表单 " + form.getName());
        }
        for (FormAppBundleDocument.ProcessBundle process : ctx.document.getProcesses()) {
            checkProcessKeyPositions(process, ctx, finalFormKeys, finalProcessKeys);
        }
    }

    private void checkDefinitionKeyPositions(Map<String, Object> definition, Set<String> finalFormKeys,
                                             String formPath) {
        if (definition == null) {
            return;
        }
        if (definition.get("fields") instanceof List<?> fields) {
            for (Object fieldObj : fields) {
                if (!(fieldObj instanceof Map<?, ?> field)) {
                    continue;
                }
                if ("REFERENCE".equalsIgnoreCase(text(field.get("type")))) {
                    String target = text(field.get("targetFormId"));
                    if (target != null && !finalFormKeys.contains(target)) {
                        throw new BaseException(FormErrorCode.BUNDLE_IMPORT_FAILED.getCode(),
                                "导入校验失败：" + formPath + " 存在未改写的表单引用 " + target + "，已整体回滚");
                    }
                }
                if (field.get("subFields") instanceof List<?> subFields) {
                    for (Object sub : subFields) {
                        checkDefinitionKeyPositions(Map.of("fields", List.of(sub)), finalFormKeys, formPath);
                    }
                }
            }
        }
    }

    private void checkProcessKeyPositions(FormAppBundleDocument.ProcessBundle process, ImportContext ctx,
                                          Set<String> finalFormKeys, Set<String> finalProcessKeys) {
        // 以导入时改写后的图文档复核（原始包内 JSON 仍是源 key，不能作为校验对象）
        Map<String, Object> graph = parseObjectOrNull(ctx.rewrittenGraphs.get(process.getProcessKey()));
        if (graph == null) {
            return;
        }
        String newProcessKey = ctx.processKeyMap.get(process.getProcessKey());
        if (!newProcessKey.equals(text(graph.get("processKey")))) {
            throw new BaseException(FormErrorCode.BUNDLE_IMPORT_FAILED.getCode(),
                    "导入校验失败：流程 '" + process.getName() + " 的 processKey 未归一，已整体回滚");
        }
        if (graph.get("elements") instanceof List<?> elements) {
            for (Object elementObj : elements) {
                if (!(elementObj instanceof Map<?, ?> element)
                        || !"node".equals(text(element.get("kind")))
                        || !(element.get("config") instanceof Map<?, ?> configRaw)) {
                    continue;
                }
                Map<?, ?> config = configRaw;
                if (config.get("nodeForm") instanceof Map<?, ?> nodeForm) {
                    String formKey = text(nodeForm.get("formKey"));
                    if (formKey != null && !finalFormKeys.contains(formKey)) {
                        throw new BaseException(FormErrorCode.BUNDLE_IMPORT_FAILED.getCode(),
                                "导入校验失败：流程 '" + process.getName() + " 存在未改写的意见表单引用 "
                                        + formKey + "，已整体回滚");
                    }
                }
                if (config.get("opinionForm") instanceof Map<?, ?> opinionForm) {
                    String formId = text(opinionForm.get("formId"));
                    if (formId != null && !ctx.opinionIdMap.containsValue(formId)) {
                        throw new BaseException(FormErrorCode.BUNDLE_IMPORT_FAILED.getCode(),
                                "导入校验失败：流程 '" + process.getName() + " 存在未改写的意见表单 id，已整体回滚");
                    }
                }
                if ("TXN_ACTION".equalsIgnoreCase(text(element.get("type")))
                        && config.get("actionId") != null
                        && !ctx.txnActionIdMap.containsValue(String.valueOf(config.get("actionId")))) {
                    throw new BaseException(FormErrorCode.BUNDLE_IMPORT_FAILED.getCode(),
                            "导入校验失败：流程 '" + process.getName() + " 存在未改写的交易动作引用，已整体回滚");
                }
            }
        }
        if (graph.get("triggers") instanceof List<?> triggers) {
            for (Object triggerObj : triggers) {
                if (!(triggerObj instanceof Map<?, ?> trigger)
                        || !(trigger.get("branches") instanceof List<?> branches)) {
                    continue;
                }
                for (Object branchObj : branches) {
                    if (!(branchObj instanceof Map<?, ?> branch)
                            || !(branch.get("actions") instanceof List<?> actions)) {
                        continue;
                    }
                    for (Object actionObj : actions) {
                        if (!(actionObj instanceof Map<?, ?> action)) {
                            continue;
                        }
                        String targetProcessKey = text(action.get("targetProcessDefKey"));
                        if (targetProcessKey != null && !finalProcessKeys.contains(targetProcessKey)) {
                            throw new BaseException(FormErrorCode.BUNDLE_IMPORT_FAILED.getCode(),
                                    "导入校验失败：流程 '" + process.getName() + " 存在未改写的流程引用 "
                                            + targetProcessKey + "，已整体回滚");
                        }
                        String targetFormKey = text(action.get("targetFormKey"));
                        if (targetFormKey != null && !finalFormKeys.contains(targetFormKey)) {
                            throw new BaseException(FormErrorCode.BUNDLE_IMPORT_FAILED.getCode(),
                                    "导入校验失败：流程 '" + process.getName() + " 存在未改写的表单引用 "
                                            + targetFormKey + "，已整体回滚");
                        }
                    }
                }
            }
        }
    }

    /** 数据源按名称匹配（流程/表单模块未装配端口时视为未匹配）。 */
    private Optional<Long> findDatasourceIdByName(String name) {
        FormAppBundleDatasourcePort port = datasourcePort.getIfAvailable();
        return port == null ? Optional.empty() : port.findDatasourceIdByName(name);
    }

    /** 流程分类按名称匹配（流程模块未装配端口时视为未匹配）。 */
    private Optional<Long> findCategoryIdByName(String name) {
        FormAppBundleProcessPort port = processPort.getIfAvailable();
        return port == null ? Optional.empty() : port.findCategoryIdByName(name);
    }

    // ==================================================================================
    // 小工具
    // ==================================================================================

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
                             java.util.function.Predicate<String> extraProbe) {
        String candidate = baseKey + "_" + randomHex(6);
        while (existing.contains(candidate) || reserved.contains(candidate)
                || (extraProbe != null && extraProbe.test(candidate))) {
            candidate = baseKey + "_" + randomHex(6);
        }
        return candidate;
    }

    private String randomHex(int length) {
        StringBuilder sb = new StringBuilder(length);
        while (sb.length() < length) {
            sb.append(UUID.randomUUID().toString().replace("-", ""));
        }
        return sb.substring(0, length);
    }

    private Map<String, String> parseOpinionResolutions(String opinionKeysJson) {
        if (opinionKeysJson == null || opinionKeysJson.isBlank()) {
            return Map.of();
        }
        try {
            List<Map<String, String>> list = objectMapper.readValue(opinionKeysJson,
                    new TypeReference<List<Map<String, String>>>() { });
            Map<String, String> result = new LinkedHashMap<>();
            for (Map<String, String> item : list) {
                String formKey = item.get("formKey");
                String newKey = item.get("newKey");
                if (formKey != null && newKey != null && !newKey.isBlank()) {
                    result.put(formKey.trim(), newKey.trim());
                }
            }
            return result;
        } catch (IOException e) {
            throw new BaseException(FormErrorCode.BUNDLE_INVALID.getCode(), "意见表单标识解决参数格式无效");
        }
    }

    private byte[] serializeDocument(FormAppBundleDocument document) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(document);
        } catch (IOException e) {
            throw new BaseException(FormErrorCode.BUNDLE_IMPORT_FAILED.getCode(), "导出包序列化失败");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new BaseException(FormErrorCode.BUNDLE_IMPORT_FAILED.getCode(), "JSON 序列化失败");
        }
    }

    private Map<String, Object> parseObjectOrNull(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json,
                    new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (IOException e) {
            return null;
        }
    }

    private Map<String, Object> parseObjectOrEmpty(String json) {
        Map<String, Object> value = parseObjectOrNull(json);
        return value == null ? new LinkedHashMap<>() : value;
    }

    private void collectDsBindingKeys(Map<String, Object> definition, Set<String> sink) {
        if (definition == null) {
            return;
        }
        if (definition.get("fields") instanceof List<?> fields) {
            for (Object fieldObj : fields) {
                collectDsBindingKeysOfField(fieldObj, sink);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void collectDsBindingKeysOfField(Object fieldObj, Set<String> sink) {
        if (!(fieldObj instanceof Map<?, ?> fieldRaw)) {
            return;
        }
        Map<String, Object> field = (Map<String, Object>) fieldRaw;
        if ("DATASOURCE".equalsIgnoreCase(text(field.get("type")))
                && field.get("dsBinding") instanceof Map<?, ?> binding
                && text(binding.get("queryKey")) != null) {
            sink.add(text(binding.get("queryKey")));
        }
        if (field.get("subFields") instanceof List<?> subFields) {
            for (Object sub : subFields) {
                collectDsBindingKeysOfField(sub, sink);
            }
        }
    }

    private void collectDictCodes(Map<String, Object> definition, Set<String> sink) {
        if (definition == null) {
            return;
        }
        if (definition.get("fields") instanceof List<?> fields) {
            for (Object fieldObj : fields) {
                collectDictCodesOfField(fieldObj, sink);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void collectDictCodesOfField(Object fieldObj, Set<String> sink) {
        if (!(fieldObj instanceof Map<?, ?> fieldRaw)) {
            return;
        }
        Map<String, Object> field = (Map<String, Object>) fieldRaw;
        if ("DICT".equalsIgnoreCase(text(field.get("type"))) && text(field.get("dictType")) != null) {
            sink.add(text(field.get("dictType")));
        }
        if (field.get("subFields") instanceof List<?> subFields) {
            for (Object sub : subFields) {
                collectDictCodesOfField(sub, sink);
            }
        }
    }

    private void collectRolePostCodes(FormAppBundleDocument document, Set<String> roleCodes,
                                      Set<String> postCodes) {
        // 第一遍（匹配事实为空）：只为收集包内全部角色/岗位编码，匹配判定由 collectTargetFacts 随后完成
        List<FormBundlePreviewResult.ExternalRefPreview> sink = new ArrayList<>();
        collectAllExternalRefs(document, factsForCodeCollection(), packageFormKeys(document),
                packageProcessKeys(document), sink);
        for (FormBundlePreviewResult.ExternalRefPreview ref : sink) {
            if ("ROLE".equals(ref.getKind())) {
                roleCodes.add(stripPrefix(ref.getValue(), "role:"));
            } else if ("POST".equals(ref.getKind())) {
                postCodes.add(ref.getValue());
            }
        }
    }

    /** 编码收集阶段的角色/岗位事实占位：编码集未知，返回空事实。 */
    private TargetFacts factsForCodeCollection() {
        return new TargetFacts(Set.of(), false, Map.of(), Set.of(), Map.of(), Map.of());
    }

    private String stripPrefix(String value, String prefix) {
        return value != null && value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    private Set<String> packageFormKeys(FormAppBundleDocument document) {
        Set<String> keys = new LinkedHashSet<>();
        document.getForms().forEach(f -> keys.add(f.getFormKey()));
        document.getOpinionForms().forEach(f -> keys.add(f.getFormKey()));
        return keys;
    }

    private Set<String> packageProcessKeys(FormAppBundleDocument document) {
        Set<String> keys = new LinkedHashSet<>();
        document.getProcesses().forEach(p -> keys.add(p.getProcessKey()));
        return keys;
    }

    private FormBundlePreviewResult.ExternalRefPreview externalRef(String kind, String value,
                                                                   String location, boolean matched,
                                                                   String matchedTo) {
        return FormBundlePreviewResult.ExternalRefPreview.builder()
                .kind(kind).value(value).location(location).matched(matched).matchedTo(matchedTo)
                .build();
    }

    private FormBundleImportReport.ClearedRef cleared(String kind, String value, String location) {
        return FormBundleImportReport.ClearedRef.builder()
                .kind(kind).originalValue(value).location(location).build();
    }

    private FormBundleImportReport.ReusedObject reused(String value, String name, Long id) {
        return FormBundleImportReport.ReusedObject.builder().value(value).name(name).id(id).build();
    }

    private boolean hasValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        return !String.valueOf(value).isBlank();
    }

    private String valueText(Object value) {
        if (value instanceof Collection<?> collection) {
            return joinValues(collection);
        }
        return value == null ? null : String.valueOf(value);
    }

    private String joinValues(Collection<?> values) {
        return values.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", "));
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private Long currentTenantId() {
        LoginUser user = LoginUserHolder.get();
        if (user == null || user.getTenantId() == null) {
            throw new BaseException(FormErrorCode.BUNDLE_IMPORT_FAILED.getCode(), "缺少登录租户上下文");
        }
        return user.getTenantId();
    }

    private String sanitizeFileName(String name) {
        return name == null ? "bundle" : name.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
    }
}

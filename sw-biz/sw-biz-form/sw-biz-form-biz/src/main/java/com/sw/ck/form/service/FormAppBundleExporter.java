package com.sw.ck.form.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.bundle.FormAppBundleDatasourcePort;
import com.sw.ck.form.api.bundle.FormAppBundleProcessPort;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.bundle.FormAppBundleDocument;
import com.sw.ck.form.entity.FormAppDirEntity;
import com.sw.ck.form.entity.FormAppEntity;
import com.sw.ck.form.entity.FormAppScopeEntity;
import com.sw.ck.form.entity.FormConfigEntity;
import com.sw.ck.form.entity.FormDefEntity;
import com.sw.ck.form.entity.FormExtQueryEntity;
import com.sw.ck.form.entity.FormListConfigEntity;
import com.sw.ck.form.mapper.FormAppDirMapper;
import com.sw.ck.form.mapper.FormAppMapper;
import com.sw.ck.form.mapper.FormAppScopeMapper;
import com.sw.ck.form.mapper.FormConfigMapper;
import com.sw.ck.form.mapper.FormDefMapper;
import com.sw.ck.form.mapper.FormExtQueryMapper;
import com.sw.ck.form.mapper.FormListConfigMapper;
import com.sw.ck.form.txn.entity.TxnActionEntity;
import com.sw.ck.form.txn.mapper.TxnActionMapper;
import com.sw.ck.system.api.dict.DictFacade;
import com.sw.ck.system.api.dict.DictItemDTO;
import com.sw.ck.system.api.dict.DictTypeDetailDTO;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.extern.log4j.Log4j2;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

/**
 * 应用整包导出（0.2.2 F2 D022-10）：以应用为单位导出各对象当前版本的定义——
 * 应用（含可见范围主体）、目录、业务表单（定义/列表配置/扩展查询契约/交易动作）、
 * 主表单属于该应用的流程（当前 graph_json）、流程节点引用的审批意见表单、
 * 包内用到的字典类型与字典项。不含业务记录、子表数据、附件文件、表单历史快照
 * 与流程历史版本；不出现任何密钥、密码或数据源连接串。
 */
@Service
@Log4j2
class FormAppBundleExporter {

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
    private ObjectProvider<FormAppBundleProcessPort> processPort;
    @Autowired
    private ObjectProvider<FormAppBundleDatasourcePort> datasourcePort;
    // 字典读走 DictFacade（模块边界铁律：form 禁止直查 sys_dict_*）。经
    // ObjectProvider 防御模块未装配。
    @Autowired
    private ObjectProvider<DictFacade> dictFacade;
    @Autowired
    private FormBundleSupport support;

    /**
     * 导出应用整包。只读当前租户（超管旁路时为当前选择租户，租户条件由全局拦截器注入）。
     */
    FormAppBundleService.ExportedBundle exportAppBundle(String appId) {
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
                .eq(FormDefEntity::getFormType, FormAppBundleService.FORM_TYPE_BUSINESS)
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

        byte[] content = support.serializeDocument(document);
        String fileName = sanitizeFileName(app.getName()) + "_bundle.json";
        log.info("Bundle exported: appId={}, forms={}, processes={}, opinionForms={}, bytes={}",
                app.getId(), formBundles.size(), processBundles.size(), opinionBundles.size(), content.length);
        return new FormAppBundleService.ExportedBundle(fileName, content, document);
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
        Map<String, Object> definition = support.parseObjectOrEmpty(config == null ? null : config.getDefinition());
        Set<String> referencedKeys = new LinkedHashSet<>();
        support.collectDsBindingKeys(definition, referencedKeys);
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
            Map<String, Object> graph = support.parseObjectOrNull(process.getGraphJson());
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
            support.collectDictCodes(support.parseObjectOrEmpty(form.getConfigDefinition()), codes);
        }
        for (FormAppBundleDocument.OpinionFormBundle form : opinionBundles) {
            support.collectDictCodes(support.parseObjectOrEmpty(form.getConfigDefinition()), codes);
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
            byte[] raw = StreamUtils.copyToByteArray(
                    getClass().getResourceAsStream("/META-INF/production-build.properties"));
            for (String line : new String(raw, StandardCharsets.UTF_8).split("\\r?\\n")) {
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

    private String sanitizeFileName(String name) {
        return name == null ? "bundle" : name.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
    }
}

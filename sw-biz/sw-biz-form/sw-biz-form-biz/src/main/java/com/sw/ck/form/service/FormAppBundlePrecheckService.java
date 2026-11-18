package com.sw.ck.form.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.bundle.FormAppBundleDocument;
import com.sw.ck.form.bundle.FormBundlePreviewResult;
import com.sw.ck.form.entity.FormAppEntity;
import com.sw.ck.form.entity.FormDefEntity;
import com.sw.ck.form.entity.FormExtQueryEntity;
import com.sw.ck.form.mapper.FormAppMapper;
import com.sw.ck.form.mapper.FormDefMapper;
import com.sw.ck.form.mapper.FormExtQueryMapper;
import com.sw.ck.security.holder.LoginUser;
import com.sw.ck.security.holder.LoginUserHolder;
import com.sw.ck.system.api.dict.DictFacade;
import com.sw.ck.system.api.user.UserQueryFacade;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 应用整包导入的预检（D022-15 第一步）：导入包解析与格式版本校验、目标租户
 * 既有事实收集（表单 key/应用名/字典占用/契约 key/角色岗位成员）、外部引用
 * 汇总与预检结果组装。字典归属探测走 DictFacade、角色/岗位存在性走
 * UserQueryFacade（模块边界铁律，经 ObjectProvider 防御模块未装配）。
 */
@Service
class FormAppBundlePrecheckService {

    @Autowired
    private FormAppMapper formAppMapper;
    @Autowired
    private FormDefMapper formDefMapper;
    @Autowired
    private FormExtQueryMapper formExtQueryMapper;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ObjectProvider<DictFacade> dictFacade;
    @Autowired
    private ObjectProvider<UserQueryFacade> userQueryFacade;
    @Autowired
    private FormBundleSupport support;
    @Autowired
    private FormAppBundleRefScanner refScanner;

    /** 解析导入包并校验格式版本（不兼容直接拒绝，D022 边界）。 */
    FormAppBundleDocument parseBundle(byte[] content) {
        if (content == null || content.length == 0) {
            throw new BaseException(FormErrorCode.BUNDLE_INVALID.getCode(), "导入包为空");
        }
        if (content.length > FormAppBundleService.MAX_BUNDLE_FILE_BYTES) {
            throw new BaseException(FormErrorCode.BUNDLE_INVALID.getCode(),
                    "导入包超过大小上限 " + (FormAppBundleService.MAX_BUNDLE_FILE_BYTES / 1024 / 1024) + "MB");
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

    TargetFacts collectTargetFacts(FormAppBundleDocument document) {
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
            support.collectDsBindingKeys(support.parseObjectOrEmpty(form.getConfigDefinition()), queryKeys);
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

    FormBundlePreviewResult previewBundle(byte[] content) {
        FormAppBundleDocument document = parseBundle(content);
        TargetFacts facts = collectTargetFacts(document);
        List<FormBundlePreviewResult.ExternalRefPreview> externalRefs = new ArrayList<>();
        Set<String> packageFormKeys = refScanner.packageFormKeys(document);
        Set<String> packageProcessKeys = refScanner.packageProcessKeys(document);

        refScanner.collectAllExternalRefs(document, facts, packageFormKeys, packageProcessKeys, externalRefs);
        List<FormBundlePreviewResult.OpinionFormPreview> opinionPreviews = document.getOpinionForms()
                .stream()
                .map(f -> {
                    boolean conflict = facts.opinionKeyTaken(f.getFormKey());
                    return FormBundlePreviewResult.OpinionFormPreview.builder()
                            .formKey(f.getFormKey())
                            .name(f.getName())
                            .conflict(conflict)
                            .suggestedKey(conflict ? f.getFormKey() + "_" + support.randomHex(6) : null)
                            .build();
                })
                .collect(Collectors.toList());

        List<FormBundlePreviewResult.FormPreview> formPreviews = document.getForms()
                .stream()
                .map(f -> FormBundlePreviewResult.FormPreview.builder()
                        .formKey(f.getFormKey())
                        .name(f.getName())
                        .keyConflict(facts.existingFormKeys().contains(f.getFormKey()))
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
                        .nameConflict(facts.appNameTaken())
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

    private void collectRolePostCodes(FormAppBundleDocument document, Set<String> roleCodes,
                                      Set<String> postCodes) {
        // 第一遍（匹配事实为空）：只为收集包内全部角色/岗位编码，匹配判定由 collectTargetFacts 随后完成
        List<FormBundlePreviewResult.ExternalRefPreview> sink = new ArrayList<>();
        refScanner.collectAllExternalRefs(document, factsForCodeCollection(),
                refScanner.packageFormKeys(document), refScanner.packageProcessKeys(document), sink);
        for (FormBundlePreviewResult.ExternalRefPreview ref : sink) {
            if (FormAppScopeTypes.ROLE.equals(ref.getKind())) {
                roleCodes.add(stripPrefix(ref.getValue(), "role:"));
            } else if (FormAppScopeTypes.POST.equals(ref.getKind())) {
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

    private Long currentTenantId() {
        LoginUser user = LoginUserHolder.get();
        if (user == null || user.getTenantId() == null) {
            throw new BaseException(FormErrorCode.BUNDLE_IMPORT_FAILED.getCode(), "缺少登录租户上下文");
        }
        return user.getTenantId();
    }
}

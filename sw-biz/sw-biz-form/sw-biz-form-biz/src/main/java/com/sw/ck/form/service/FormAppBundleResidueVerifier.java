package com.sw.ck.form.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.common.exception.BaseException;
import com.sw.ck.form.api.exception.FormErrorCode;
import com.sw.ck.form.bundle.FormAppBundleDocument;
import com.sw.ck.form.bundle.FormBundleImportReport;
import com.sw.ck.form.dynamic.FieldType;
import com.sw.ck.form.entity.FormConfigEntity;
import com.sw.ck.form.entity.FormDefEntity;
import com.sw.ck.form.mapper.FormConfigMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 导入残留校验（写入后、记录前；任一残留抛错回滚）：源环境表单 id、交易动作
 * id、流程 key 不允许残留在任何包内文档中；key 类引用按改写位置复核
 * （REFERENCE.targetFormId / nodeForm.formKey / opinionForm.formId /
 * TXN_ACTION.actionId / 触发器 targetFormKey / targetProcessDefKey）。
 */
@Service
class FormAppBundleResidueVerifier {

    @Autowired
    private FormConfigMapper formConfigMapper;
    @Autowired
    private FormBundleSupport support;

    void verifyNoSourceResidue(ImportContext ctx) {
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
            Map<String, Object> definition = support.parseObjectOrEmpty(config.getDefinition());
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
                if (FieldType.REFERENCE.name().equalsIgnoreCase(support.text(field.get("type")))) {
                    String target = support.text(field.get("targetFormId"));
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
        Map<String, Object> graph = support.parseObjectOrNull(ctx.rewrittenGraphs.get(process.getProcessKey()));
        if (graph == null) {
            return;
        }
        String newProcessKey = ctx.processKeyMap.get(process.getProcessKey());
        if (!newProcessKey.equals(support.text(graph.get("processKey")))) {
            throw new BaseException(FormErrorCode.BUNDLE_IMPORT_FAILED.getCode(),
                    "导入校验失败：流程 '" + process.getName() + " 的 processKey 未归一，已整体回滚");
        }
        if (graph.get("elements") instanceof List<?> elements) {
            for (Object elementObj : elements) {
                if (!(elementObj instanceof Map<?, ?> element)
                        || !"node".equals(support.text(element.get("kind")))
                        || !(element.get("config") instanceof Map<?, ?> configRaw)) {
                    continue;
                }
                Map<?, ?> config = configRaw;
                if (config.get("nodeForm") instanceof Map<?, ?> nodeForm) {
                    String formKey = support.text(nodeForm.get("formKey"));
                    if (formKey != null && !finalFormKeys.contains(formKey)) {
                        throw new BaseException(FormErrorCode.BUNDLE_IMPORT_FAILED.getCode(),
                                "导入校验失败：流程 '" + process.getName() + " 存在未改写的意见表单引用 "
                                        + formKey + "，已整体回滚");
                    }
                }
                if (config.get("opinionForm") instanceof Map<?, ?> opinionForm) {
                    String formId = support.text(opinionForm.get("formId"));
                    if (formId != null && !ctx.opinionIdMap.containsValue(formId)) {
                        throw new BaseException(FormErrorCode.BUNDLE_IMPORT_FAILED.getCode(),
                                "导入校验失败：流程 '" + process.getName() + " 存在未改写的意见表单 id，已整体回滚");
                    }
                }
                if ("TXN_ACTION".equalsIgnoreCase(support.text(element.get("type")))
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
                        String targetProcessKey = support.text(action.get("targetProcessDefKey"));
                        if (targetProcessKey != null && !finalProcessKeys.contains(targetProcessKey)) {
                            throw new BaseException(FormErrorCode.BUNDLE_IMPORT_FAILED.getCode(),
                                    "导入校验失败：流程 '" + process.getName() + " 存在未改写的流程引用 "
                                            + targetProcessKey + "，已整体回滚");
                        }
                        String targetFormKey = support.text(action.get("targetFormKey"));
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
}

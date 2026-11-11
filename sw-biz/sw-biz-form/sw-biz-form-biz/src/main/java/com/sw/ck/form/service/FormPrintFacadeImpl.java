package com.sw.ck.form.service;

import com.sw.ck.form.api.dto.FormDefDTO;
import com.sw.ck.form.api.dto.FormSnapshotDTO;
import com.sw.ck.form.api.print.FormPrintFacade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * {@link FormPrintFacade} 实现（1.0.0 F2）。
 * <p>
 * 记录详情走 {@link FormDataQueryService#getRecordDetail} 的<b>有权限过滤</b>读取链路
 * （登录态 + 字段级 VIEW 隐藏生效），与页面详情同源。版本解析（F2-5）：
 * {@code submittedAt} 非空时在发布快照中取 create_time ≤ 提交时间的最新版本，
 * 命中即按提交时版本打印；快照缺失/未命中回退当前版本并置 versionPinned=false。
 * 无权/不存在统一收敛为 empty，由调用方给出安全结论。
 * </p>
 */
@Slf4j
@Service
public class FormPrintFacadeImpl implements FormPrintFacade {

    private final FormDataQueryService formDataQueryService;
    private final FormDefService formDefService;

    public FormPrintFacadeImpl(FormDataQueryService formDataQueryService,
                               FormDefService formDefService) {
        this.formDataQueryService = formDataQueryService;
        this.formDefService = formDefService;
    }

    @Override
    public Optional<FormPrintData> recordDetail(String formKey, String recordId,
                                                LocalDateTime submittedAt) {
        try {
            FormDefDTO formDef = formDefService.getFormDefByKey(formKey);
            if (formDef == null) {
                return Optional.empty();
            }
            Map<String, Object> record = formDataQueryService.getRecordDetail(formKey, recordId);
            if (record == null || record.isEmpty()) {
                return Optional.empty();
            }

            String printDefinitionJson = formDefService.getDefinition(formKey);
            Integer printVersion = formDef.getFormVersion();
            boolean versionPinned = false;
            if (submittedAt != null && formDef.getId() != null) {
                // 快照列表按版本倒序；取 create_time ≤ 提交时间的最新一版（提交时可见的定义）
                Optional<FormSnapshotDTO> pinned = formDefService.listSnapshots(formDef.getId())
                        .stream()
                        .filter(snapshot -> snapshot.getCreateTime() != null
                                && !snapshot.getCreateTime().isAfter(submittedAt))
                        .findFirst();
                if (pinned.isPresent()) {
                    Integer version = pinned.get().getFormVersion();
                    if (version != null
                            && !version.equals(formDef.getFormVersion())) {
                        try {
                            String snapshotJson =
                                    formDefService.getSnapshotDefinitionByKey(formKey, version);
                            if (snapshotJson != null && !snapshotJson.isBlank()) {
                                printDefinitionJson = snapshotJson;
                                printVersion = version;
                                versionPinned = true;
                            }
                        } catch (Exception e) {
                            log.warn("提交时版本快照读取失败，回退当前版本: formKey={} version={} err={}",
                                    formKey, version, e.getMessage());
                        }
                    } else if (version != null) {
                        // 提交时版本即当前版本
                        versionPinned = true;
                    }
                }
            }
            return Optional.of(new FormPrintData(record, formDef.getName(),
                    printDefinitionJson, printVersion, versionPinned));
        } catch (Exception e) {
            // 无权/不存在/上下文缺失统一 empty：调用方不区分差异（F2-4 安全结论）
            log.info("打印数据读取被拒绝或不可用: formKey={} recordId={} err={}",
                    formKey, recordId, e.getMessage());
            return Optional.empty();
        }
    }
}

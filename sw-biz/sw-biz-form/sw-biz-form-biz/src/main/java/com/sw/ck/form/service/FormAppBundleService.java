package com.sw.ck.form.service;

import com.sw.ck.form.bundle.FormAppBundleDocument;
import com.sw.ck.form.bundle.FormBundleImportReport;
import com.sw.ck.form.bundle.FormBundlePreviewResult;
import com.sw.ck.form.entity.FormAppImportRecordEntity;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
/**
 * 应用整包导入导出服务（0.2.2 F2，D022-10～15）——门面。
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
 *
 * <p>
 * 实现按职责拆分至同包协作类（行为与拆分前一致，公开契约不变）：
 * {@link FormAppBundleExporter}（整包导出）、{@link FormAppBundlePrecheckService}
 * （包解析/目标租户事实/预检预览）、{@link FormAppBundleRefScanner}（预检外部
 * 引用扫描）、{@link FormAppBundleImporter}（确认导入写入编排与导入记录）、
 * {@link FormAppBundleRewriter}（definition/流程图/IoT 引用改写）、
 * {@link FormAppBundleResidueVerifier}（写入后残留校验）、{@link FormBundleSupport}
 * （JSON 编解码/端口探测/报告小工厂）、{@link ImportContext}（单次导入可变上下文）、
 * {@link TargetFacts}（目标租户既有事实）。{@code @Transactional} 事务边界保留在
 * 本门面的 {@code confirmImport} 上（经 Spring 代理调用，单事务整体回滚，语义与
 * 拆分前一致）。
 * </p>
 */
@Service
public class FormAppBundleService {

    /** 导入包大小上限（容器 multipart 上限 6MB，业务层结构化拒绝） */
    public static final int MAX_BUNDLE_FILE_BYTES = 5 * 1024 * 1024;

    static final String FORM_TYPE_BUSINESS = "BUSINESS";
    static final String FORM_TYPE_OPINION = "APPROVAL_COMMENT";
    static final String STATUS_DRAFT = "DRAFT";

    @Autowired
    private FormAppBundleExporter exporter;
    @Autowired
    private FormAppBundlePrecheckService precheckService;
    @Autowired
    private FormAppBundleImporter importer;

    /** 导出结果：文件名 + 包字节 + 结构化文档（报告/预检复用）。 */
    public record ExportedBundle(String fileName, byte[] content, FormAppBundleDocument document) {
    }

    /**
     * 导出应用整包。只读当前租户（超管旁路时为当前选择租户，租户条件由全局拦截器注入）。
     */
    public ExportedBundle exportAppBundle(String appId) {
        return exporter.exportAppBundle(appId);
    }

    /** 解析导入包并校验格式版本（不兼容直接拒绝，D022 边界）。 */
    public FormAppBundleDocument parseBundle(byte[] content) {
        return precheckService.parseBundle(content);
    }

    /** 预检（D022-15 第一步）：对象清单、意见表单冲突、外部引用匹配与计数。 */
    public FormBundlePreviewResult previewBundle(byte[] content) {
        return precheckService.previewBundle(content);
    }

    /**
     * 确认导入：一律新建，从不覆盖已有对象（D022-11）。
     *
     * @param opinionKeysJson 意见表单冲突解决 JSON：[{"formKey":"包内原 key","newKey":"用户确认的新 key"}]；
     *                        仅冲突项必须提供，非冲突项保持原 key（D022-12）
     */
    @Transactional(rollbackFor = Exception.class)
    public FormBundleImportReport confirmImport(byte[] content, String opinionKeysJson) {
        return importer.confirmImport(content, opinionKeysJson);
    }

    public List<FormAppImportRecordEntity> listImportRecords() {
        return importer.listImportRecords();
    }

    public FormAppImportRecordEntity getImportRecord(String id) {
        return importer.getImportRecord(id);
    }
}

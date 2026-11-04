package com.sw.ck.form.bundle;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 应用整包导入预检结果（0.2.2 F2，D022-15 第一步）。
 * <p>
 * 上传导入包后先返回包信息、审批意见表单冲突（每项默认随机新 key，可手动改，
 * D022-12）与外部引用匹配结果；用户确认后才执行真正写入。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FormBundlePreviewResult {

    private String packageName;
    private Integer bundleFormatVersion;
    private String systemVersion;
    private String exportedAt;

    private AppPreview app;
    private List<DirPreview> dirs;
    private List<FormPreview> forms;
    private List<ProcessPreview> processes;
    private List<OpinionFormPreview> opinionForms;
    private List<DictTypePreview> dictTypes;
    /** 外部引用匹配结果（含包内自愈项：应用重名、表单 key 冲突等自动处理项） */
    private List<ExternalRefPreview> externalRefs;

    /** 对象计数（目录/业务表单/流程/意见表单/字典类型） */
    private Counts counts;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AppPreview {
        private String name;
        private String icon;
        private String color;
        private Integer sortNo;
        private String visibleScopeType;
        /** true = 目标租户已有同名应用，导入时自动加后缀（D022-11） */
        private Boolean nameConflict;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DirPreview {
        private String name;
        private Integer sortNo;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FormPreview {
        private String formKey;
        private String name;
        /** true = 目标租户已有同 key 表单，导入时自动重新生成（D022-11） */
        private Boolean keyConflict;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ProcessPreview {
        private String name;
        private String formKey;
    }

    /** 审批意见表单（D022-12）：冲突项必须由用户确认新 key 后才能导入。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OpinionFormPreview {
        private String formKey;
        private String name;
        /** true = 目标租户已有同 key 意见表单，需用户提供新 key */
        private Boolean conflict;
        /** 冲突项默认给出的随机新 key（可直接采用或手动修改） */
        private String suggestedKey;
    }

    /** 字典类型处置（D022-13）：同编码复用 / 缺失新建 / 编码被他租户占用（计入报告）。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DictTypePreview {
        private String code;
        private String name;
        /** REUSE / CREATE / OCCUPIED */
        private String action;
    }

    /**
     * 单条外部引用匹配结果。{@code matched=false} 的项导入时置空并记入导入报告。
     * USER/DEPT 类引用不参与匹配，导入时一律置空（D022-13），此处以 matched=false 呈现。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ExternalRefPreview {
        /** ROLE / POST / DATASOURCE / CATEGORY / FIXED_USER / DEPT / FORM / PROCESS / QUERY */
        private String kind;
        /** 引用值（编码 / 名称 / id / key） */
        private String value;
        /** 引用所在对象（如「流程 发起审批 · 节点 任务审批」「表单 请假申请 · REFERENCE 字段 dept」） */
        private String location;
        /** true = 目标租户可匹配；USER/DEPT 恒为 false（置空） */
        private Boolean matched;
        /** 匹配目标（角色名/数据源名等，匹配失败为 null） */
        private String matchedTo;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Counts {
        private int dirs;
        private int forms;
        private int processes;
        private int opinionForms;
        private int dictTypes;
        private int txnActions;
        private int extQueries;
    }
}

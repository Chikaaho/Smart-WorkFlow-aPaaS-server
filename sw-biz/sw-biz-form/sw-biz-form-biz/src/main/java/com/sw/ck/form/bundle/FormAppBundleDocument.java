package com.sw.ck.form.bundle;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 应用整包导出文档（0.2.2 F2，D022-10）。
 * <p>
 * 一个导出包对应一个应用，只含各对象当前版本的定义（不含业务记录、子表数据、
 * 附件文件、历史快照与流程历史版本）。{@code bundleFormatVersion} 是包格式版本，
 * 导入时版本不兼容直接拒绝；{@code systemVersion} 记录导出时的系统版本。
 * </p>
 * <h3>原样保留字段（结构往返一致的前提）</h3>
 * <ul>
 *   <li>{@code FormBundle#configDefinition} — 表单主 config 行 definition JSON 原文；</li>
 *   <li>{@code FormBundle#listConfig} / {@code visibilityScope} — 配置 JSON 原文；</li>
 *   <li>{@code ProcessBundle#graphJson} / {@code iotDeviceActionJson} — 图文档与设备动作 JSON 原文。</li>
 * </ul>
 * 包内 id/key 为源环境值，导入时统一重映射（D022-11/12）；目录 id 保留在文档内，
 * 仅用于包内 form.dir 归属映射，不落目标库。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FormAppBundleDocument {

    /** 当前包格式版本（结构不兼容时递增；导入方仅接受自身支持的版本） */
    public static final int FORMAT_VERSION = 1;

    private Integer bundleFormatVersion;
    private String systemVersion;
    private String exportedAt;
    private String packageName;

    private AppBundle app;
    private List<DirBundle> dirs;
    private List<FormBundle> forms;
    private List<OpinionFormBundle> opinionForms;
    private List<ProcessBundle> processes;
    private List<DictTypeBundle> dictTypes;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AppBundle {
        private String name;
        private String icon;
        private String color;
        private Integer sortNo;
        /** ALL / SPECIFIED */
        private String visibleScopeType;
        /** ENABLED / DISABLED */
        private String status;
        /** 可见范围主体（D022-13：USER/DEPT 置空，ROLE/POST 按编码匹配，TENANT 保留） */
        private List<ScopeBundle> scopes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ScopeBundle {
        private String scopeType;
        private String scopeId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DirBundle {
        /** 源环境目录 id（包内映射用） */
        private String id;
        private String name;
        private Integer sortNo;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FormBundle {
        /** 源环境表单 id（包内引用改写映射用） */
        private String id;
        /** 源环境目录 id（包内目录归属映射用，不落目标库） */
        private String dirId;
        private String formKey;
        private String name;
        private String logicalTableName;
        private String description;
        /** 发起可见范围 JSON 原文（{"userIds":[...]}；导入时 userIds 置空） */
        private String visibilityScope;
        /** 主 config 行 definition JSON 原文 */
        private String configDefinition;
        /** 列表配置 configJson 原文（可空） */
        private String listConfig;
        /** 扩展查询契约（D022-13：数据源按名称匹配，连接信息不出包） */
        private List<ExtQueryBundle> extQueries;
        /** 交易动作定义（草稿态导入，发布版本历史不导出） */
        private List<TxnActionBundle> txnActions;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ExtQueryBundle {
        /** 源环境数据源名称（按名称匹配目标租户，D022-13） */
        private String datasourceName;
        private String queryKey;
        private Integer queryVersion;
        private String sqlText;
        private String outputFields;
        private Integer enabled;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TxnActionBundle {
        /** 源环境交易动作 id（TXN_ACTION 节点 actionId 改写映射用） */
        private String id;
        private String actionKey;
        private String name;
        private String actionType;
        private String configJson;
        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OpinionFormBundle {
        /** 源环境表单 id（包内 opinionForm.formId 改写映射用） */
        private String id;
        private String formKey;
        private String name;
        private String description;
        private String configDefinition;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ProcessBundle {
        private String processKey;
        private String name;
        private String formKey;
        private String themeRule;
        private Boolean iotAccessEnabled;
        /** 设备动作 JSON 原文（deviceId 按外部引用置空，D022 边界） */
        private String iotDeviceActionJson;
        /** 流程分类名（按名称匹配目标租户分类，D022-13） */
        private String categoryName;
        /** 图文档 JSON 原文（含 elements/triggers/variables/notification/canvas 等全部键） */
        private String graphJson;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DictTypeBundle {
        private String code;
        private String name;
        private Integer status;
        private String description;
        private List<DictItemBundle> items;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DictItemBundle {
        private String label;
        private String dictValue;
        private Integer sort;
        private Integer status;
        private Integer isDefault;
        private String cssClass;
        private String listClass;
        private String description;
    }
}

package com.sw.ck.form.bundle;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 应用整包导入报告（0.2.2 F2，D022-15）。
 * <p>
 * 导入完成后可查看、下载：新建对象清单（原 key → 新 key）、复用对象、
 * 置空的引用及其位置。报告 JSON 随导入记录持久化（同一事务），供导入后追溯。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FormBundleImportReport {

    private String packageName;
    private String importedAt;
    private String operatorName;
    /** 新建应用 id（重名自动加后缀后的最终应用） */
    private String appId;
    private String appName;

    /** 新建对象清单（原 key/id → 新 key/id） */
    private List<CreatedMapping> createdDirs;
    private List<CreatedMapping> createdForms;
    private List<CreatedMapping> createdOpinionForms;
    private List<CreatedProcess> createdProcesses;
    private List<CreatedTxnAction> createdTxnActions;
    private List<CreatedQuery> createdExtQueries;
    /** 新建的字典类型（编码、名称、字典项数；F2-G4） */
    private List<CreatedDictType> createdDictTypes;

    /** 复用/匹配成功的外部对象 */
    private List<ReusedObject> reusedDictTypes;
    private List<ReusedObject> reusedOpinionKeys;
    private List<ReusedObject> matchedDatasources;
    private List<ReusedObject> matchedRoles;
    private List<ReusedObject> matchedPosts;
    private List<ReusedObject> matchedCategories;

    /** 自动改写清单 */
    private List<CreatedMapping> regeneratedFormKeys;
    private List<CreatedMapping> regeneratedQueryKeys;
    private String finalAppName;

    /** 置空的外部引用（对象、位置、原值），发布前须管理员补配 */
    private List<ClearedRef> clearedRefs;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CreatedMapping {
        private String oldKey;
        private String newKey;
        private String oldId;
        private String newId;
        private String name;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CreatedProcess {
        private String name;
        private String oldProcessKey;
        private String newProcessKey;
        private Long newDefId;
        private String formKey;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CreatedTxnAction {
        private String formKey;
        private String actionKey;
        private String name;
        private String newActionId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CreatedQuery {
        private String formKey;
        private String oldQueryKey;
        private String newQueryKey;
        private Integer queryVersion;
        private String datasourceName;
        private Long datasourceId;
    }

    /** 新建的字典类型：按对象列出编码、名称与字典项数（F2-G4）。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CreatedDictType {
        private String code;
        private String name;
        private Integer itemCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ReusedObject {
        /** 匹配值（编码/名称） */
        private String value;
        private String name;
        private Long id;
    }

    /**
     * 置空的外部引用。{@code kind}：FIXED_USER / DEPT / ROLE / POST / DEPT_POST /
     * DATASOURCE / CATEGORY / FORM / PROCESS / DICT_OCCUPIED / FORM_USER_SCOPE / IOT_DEVICE。
     * 参与人被置空的流程节点在此逐条列出，供发布前补配（方向 §导入规则 4）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ClearedRef {
        private String kind;
        private String originalValue;
        /** 引用所在位置（如「流程 周报审批 · 节点 部门审批 · 参与人」） */
        private String location;
    }
}

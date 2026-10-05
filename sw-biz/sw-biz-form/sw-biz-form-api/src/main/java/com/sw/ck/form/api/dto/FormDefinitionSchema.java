package com.sw.ck.form.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 表单 definition 权威形状（contract / schema）。
 * <p>
 * 定义表单配置 JSON 的顶层结构与字段列表结构。
 * 仅 Jackson 可序列化；解析/校验逻辑不在本刀（P2-2）。
 * </p>
 *
 * <h3>约束（设计期约定，运行时由 P2-2 校验）</h3>
 * <ul>
 *   <li>{@code subFields} 内不得再含 TABLE 类型（禁递归）</li>
 *   <li>{@code type} 为 {@code FieldType} 枚举字面量</li>
 *   <li>{@code rules} 为顶层预留槽位（本刀不实现任何规则引擎）</li>
 * </ul>
 *
 * <h3>示例</h3>
 * <pre>{@code
 * {
 *   "schemaVersion": 1,
 *   "title": "请假申请",
 *   "rules": {},
 *   "fields": [
 *     {"name": "reason", "type": "TEXT", "label": "请假事由", "required": true, "length": 500},
 *     {"name": "days", "type": "NUMBER", "label": "天数", "required": true},
 *     {"name": "gender", "type": "DICT", "dictType": "sys_user_sex", "renderAs": "select"},
 *     {"name": "dept", "type": "REFERENCE", "targetFormId": "dept_form"},
 *     {"name": "items", "type": "TABLE", "subFields": [
 *       {"name": "item_name", "type": "TEXT", "label": "物品名"},
 *       {"name": "qty", "type": "NUMBER", "label": "数量"}
 *     ]}
 *   ]
 * }
 * }</pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FormDefinitionSchema implements Serializable {

    /** schema 版本号（当前为 1） */
    private Integer schemaVersion;

    /** 表单标题 */
    private String title;

    /**
     * 规则/公式/表达式顶层预留槽位。
     * <p>
     * 设计器不产出、渲染器不读、本刀不实现任何规则引擎。
     * 保留此字段仅为未来扩展预留 JSON 槽位。
     * </p>
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Object> rules;

    /** 字段定义列表 */
    private List<FieldDef> fields;

    // ==================== 内嵌类型 ====================

    /**
     * 单字段定义。
     * <p>
     * 描述一个表单字段的元数据：逻辑名、类型、标签、校验规则、关联目标等。
     * TABLE 类型的子字段（subFields）复用同一结构，但不得再含 TABLE（禁递归）。
     * </p>
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FieldDef implements Serializable {

        /** 字段逻辑名（发布后不可改） */
        private String name;

        /**
         * 字段类型（{@code FieldType} 枚举字面量，如 TEXT / NUMBER / DICT / REFERENCE / TABLE 等）。
         * 使用 String 而非 FieldType 枚举以保持 -api 模块不依赖 -biz 模块。
         */
        private String type;

        /** 业务标签（用户可见的显示名，可后续修改） */
        private String label;

        /** 是否必填 */
        private Boolean required;

        /** 字段最大长度（字符数） */
        private Integer length;

        /** 字典类型编码（仅 DICT 类型使用，如 sys_user_sex） */
        private String dictType;

        /**
         * 渲染方式（仅 DICT 类型使用）。
         * <ul>
         *   <li>{@code "select"} — 下拉框（默认）</li>
         *   <li>{@code "radio"} — 单选按钮组（RADIO 不单独立类型）</li>
         * </ul>
         */
        private String renderAs;

        /**
         * 是否多选（仅 USER/DEPT 类型使用，P63）。
         * 多选值=稳定对象 ID 列表，落库为 JSON 数组字符串（VARCHAR(1000)）；
         * 缺省 false=单选（VARCHAR(64)，既有语义不变）。
         * 发布后不得在单选/多选间切换（属类型变更，按「改类型永久封死」处理）。
         */
        private Boolean multiple;

        /**
         * 显示格式（仅 DATE 类型使用，P63）。
         * 缺省 {@code "date"}=仅日期（YYYY-MM-DD → LocalDate）；{@code "datetime"}=日期时间
         * （YYYY-MM-DD HH:mm[:ss] → LocalDateTime）。两种格式列类型均为 TIMESTAMP，缺省行为不变。
         */
        private String format;

        /** 关联目标表单 ID（仅 REFERENCE 类型使用） */
        private String targetFormId;

        /**
         * 表格子字段列表（仅 TABLE 类型使用）。
         * <p>
         * 元素复用 {@link FieldDef} 规格，但设计约束禁止再含 TABLE（禁递归）。
         * 运行时递归校验由 P2-2 实现。
         * </p>
         */
        private List<FieldDef> subFields;
    }
}

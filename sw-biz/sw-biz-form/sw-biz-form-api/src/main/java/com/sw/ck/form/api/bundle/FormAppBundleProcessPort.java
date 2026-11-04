package com.sw.ck.form.api.bundle;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 应用导入导出的流程侧端口（0.2.2 F2）。
 * <p>
 * 应用整包导出/导入需要读写 {@code sw_bpm_process_def}（主表单属于该应用的流程）
 * 与 {@code sw_bpm_category}（流程分类按名称匹配，D022-13）。流程定义归 bpm 模块所有，
 * form 模块不得依赖其 -biz 实现，故经本端口（form-api 声明、sw-bpm-process 提供 Bean）交互。
 * 数据边界均为当前登录租户：加载只读当前租户，写入只落当前租户（超管旁路时同为当前租户）。
 * </p>
 */
public interface FormAppBundleProcessPort {

    /**
     * 按主表单 formKey 集合加载当前租户的流程定义（导出用；含流程分类名，未分类为 null）。
     *
     * @return present = 命中列表（空集合为合法零匹配）；
     *         empty = {@code formKeys} 为空，缺少查询目标
     */
    Optional<List<BundleProcessExport>> loadProcessesByMainFormKeys(Collection<String> formKeys);

    /**
     * 写入一条草稿流程定义（导入用；graphJson 已由调用方完成引用改写）。
     *
     * @return present = 新 def id；empty = {@code row} 缺失，缺少写入目标
     */
    Optional<Long> insertDraftProcess(BundleProcessImport row);

    /** 流程分类按名称匹配（当前租户；多个同名取 id 最小，不匹配返回 empty）。 */
    Optional<Long> findCategoryIdByName(String name);

    /** 导出的流程定义快照（def 行字段 + graph_json 原文；不导出发布/部署字段）。 */
    record BundleProcessExport(Long id, String processKey, String name, String formKey,
                               String themeRule, Boolean iotAccessEnabled, String iotDeviceActionJson,
                               String categoryName, String graphJson) {
    }

    /** 导入的流程定义（一律草稿；部署/发布字段为空，物理发布由管理员逐个执行，D022-14）。 */
    record BundleProcessImport(String processKey, String name, String formKey, String themeRule,
                               Boolean iotAccessEnabled, String iotDeviceActionJson,
                               Long categoryId, String graphJson) {
    }
}

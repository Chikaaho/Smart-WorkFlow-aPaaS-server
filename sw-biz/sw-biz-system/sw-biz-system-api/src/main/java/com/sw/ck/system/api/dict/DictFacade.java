package com.sw.ck.system.api.dict;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 字典服务 Facade 接口。
 * <p>
 * 定义于 {@code -api} 模块，由 {@code -biz} 模块实现。
 * 其它模块（如 form、workflow）需要消费字典数据时，<strong>仅可</strong>依赖本接口，
 * 禁止直接访问 {@code sys_dict_type} / {@code sys_dict_data} 表或对应的 Mapper。
 * </p>
 * <p>
 * 模块内部调用边界统一返回非空 {@link Optional}：empty 只表达查询上下文缺失，
 * 合法零匹配以 present 的空集合表达。
 * </p>
 */
public interface DictFacade {

    /**
     * 根据字典类型编码查询字典数据项列表。
     *
     * @param dictType 字典类型编码（如 {@code sys_common_status}）
     * @return present = 字典数据项列表（已按 sort 升序排列、不含停用项；空集合表示该类型当前
     *         无可用项，属合法零匹配）；
     *         empty = {@code dictType} 为空白，缺少查询目标
     */
    Optional<List<DictItemDTO>> listByType(String dictType);

    /**
     * 校验指定字典类型下是否存在指定的字典值。
     * <p>
     * 用于表单字典控件提交时的值域校验。
     * </p>
     *
     * @param dictType 字典类型编码
     * @param code     字典值（dict_value）
     * @return present = 判定结果（true 在值域内 / false 不在值域内，含类型存在但值非法）；
     *         empty = {@code dictType} 或 {@code code} 为空白，缺少判定目标，无法给出值域判定
     */
    Optional<Boolean> isValidCode(String dictType, String code);

    /**
     * 批量查询字典类型详情（含类型元数据与数据项），供应用整包导出（0.2.2 F2）。
     * <p>仅返回当前租户的活跃类型（{@code deleted = 0}）；数据项按 sort 升序，含停用项。</p>
     *
     * @param codes 字典类型编码集合
     * @return present = 编码 → 详情映射（不含当前租户不存在的编码，属合法零匹配）；
     *         empty = {@code codes} 为空或全空白，缺少查询目标
     */
    Optional<Map<String, DictTypeDetailDTO>> findDictTypeDetails(Collection<String> codes);

    /**
     * 导入前探测字典类型编码归属（0.2.2 F2，D022-13）。
     * <p>全局唯一索引 {@code uk_sys_dict_type_code} 为 (code, deleted)：任一租户持有活跃行
     * 即不可新建。探测结果：</p>
     * <ul>
     *   <li>{@code REUSE} — 当前租户已有该编码活跃类型；</li>
     *   <li>{@code OCCUPIED} — 其他租户持有该编码活跃行（目标租户不可新建）；</li>
     *   <li>{@code MISSING} — 全局空闲，可新建。</li>
     * </ul>
     *
     * @param code 字典类型编码
     * @return present = REUSE / OCCUPIED / MISSING 三值之一；
     *         empty = {@code code} 为空白，缺少判定目标
     */
    Optional<String> inspectDictTypeForImport(String code);

    /**
     * 导入路径创建字典类型与数据项（0.2.2 F2，D022-13），归属当前租户。
     * <p>预检与确认之间无服务端状态：目标编码可能在两步之间被并发占用，实现须在
     * 写入前复核归属并在撞全局唯一索引（或复核非 MISSING）时返回 {@code false}
     * 而不是抛异常；调用方把 false 计入导入报告的清除引用清单。</p>
     *
     * @param code        字典类型编码
     * @param name        字典类型名称
     * @param status      状态（null 视为 0 正常）
     * @param description 备注（可空）
     * @param items       数据项（可空 = 无数据项）
     * @return present = true 创建成功 / false 编码已被占用未创建；
     *         empty = code/name 空白等缺少写入目标
     */
    Optional<Boolean> createDictTypeForImport(String code, String name, Integer status,
                                              String description, List<DictItemDTO> items);
}

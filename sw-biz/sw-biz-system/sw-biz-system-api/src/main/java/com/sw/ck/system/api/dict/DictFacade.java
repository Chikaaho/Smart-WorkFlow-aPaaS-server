package com.sw.ck.system.api.dict;

import java.util.List;
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
}

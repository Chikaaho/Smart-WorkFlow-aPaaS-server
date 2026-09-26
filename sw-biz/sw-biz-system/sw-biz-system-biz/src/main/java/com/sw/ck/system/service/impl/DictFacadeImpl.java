package com.sw.ck.system.service.impl;

import com.sw.ck.system.api.dict.DictFacade;
import com.sw.ck.system.api.dict.DictItemDTO;
import com.sw.ck.system.entity.SysDictData;
import com.sw.ck.system.service.SysDictDataService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * DictFacade 实现。
 * <p>
 * 其它模块通过 {@link com.sw.ck.system.api.dict.DictFacade} 接口消费字典数据，
 * 禁止直接访问 sys_dict_data 表或 Mapper。
 * </p>
 * <p>
 * 模块内部调用边界返回非空 {@link Optional}：查询目标缺失（空白 dictType / code）以 empty 表达，
 * 类型存在但零匹配以 present 的空集合表达。
 * </p>
 */
@Service
public class DictFacadeImpl implements DictFacade {

    private final SysDictDataService sysDictDataService;

    public DictFacadeImpl(SysDictDataService sysDictDataService) {
        this.sysDictDataService = sysDictDataService;
    }

    @Override
    public Optional<List<DictItemDTO>> listByType(String dictType) {
        if (dictType == null || dictType.isBlank()) {
            // 缺少查询目标：查询未执行
            return Optional.empty();
        }
        List<SysDictData> list = sysDictDataService.listByDictCode(dictType);
        return Optional.of(list.stream()
                .map(this::toDTO)
                .collect(Collectors.toList()));
    }

    @Override
    public Optional<Boolean> isValidCode(String dictType, String code) {
        if (dictType == null || dictType.isBlank() || code == null || code.isBlank()) {
            // 缺少判定目标：无法给出值域判定
            return Optional.empty();
        }
        return Optional.of(sysDictDataService.isValidCode(dictType, code));
    }

    private DictItemDTO toDTO(SysDictData data) {
        return DictItemDTO.builder()
                .dictType(data.getDictCode())
                .code(data.getDictValue())
                .label(data.getLabel())
                .sort(data.getSort())
                .status(data.getStatus())
                .isDefault(data.getIsDefault())
                .cssClass(data.getCssClass())
                .listClass(data.getListClass())
                .build();
    }
}

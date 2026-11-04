package com.sw.ck.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sw.ck.system.api.dict.DictFacade;
import com.sw.ck.system.api.dict.DictItemDTO;
import com.sw.ck.system.api.dict.DictTypeDetailDTO;
import com.sw.ck.system.entity.SysDictData;
import com.sw.ck.system.entity.SysDictType;
import com.sw.ck.system.mapper.SysDictDataMapper;
import com.sw.ck.system.mapper.SysDictTypeMapper;
import com.sw.ck.system.service.SysDictDataService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * DictFacade 实现。
 * <p>
 * 其它模块通过 {@link com.sw.ck.system.api.dict.DictFacade} 接口消费字典数据，
 * 禁止直接访问 sys_dict_data 表或 Mapper。实现内部（system-biz 模块内）允许经
 * Mapper 访问字典表；跨租户占用探测走 {@link SysDictTypeMapper#selectActiveOwnerTenantId}
 * 的方法级豁免（与 SysUserMapper 先例一致）。
 * </p>
 * <p>
 * 模块内部调用边界返回非空 {@link Optional}：查询目标缺失（空白 dictType / code）以 empty 表达，
 * 类型存在但零匹配以 present 的空集合表达。
 * </p>
 */
@Service
public class DictFacadeImpl implements DictFacade {

    private final SysDictDataService sysDictDataService;
    private final SysDictTypeMapper sysDictTypeMapper;
    private final SysDictDataMapper sysDictDataMapper;

    public DictFacadeImpl(SysDictDataService sysDictDataService,
                          SysDictTypeMapper sysDictTypeMapper,
                          SysDictDataMapper sysDictDataMapper) {
        this.sysDictDataService = sysDictDataService;
        this.sysDictTypeMapper = sysDictTypeMapper;
        this.sysDictDataMapper = sysDictDataMapper;
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

    @Override
    public Optional<Map<String, DictTypeDetailDTO>> findDictTypeDetails(Collection<String> codes) {
        if (codes == null || codes.isEmpty() || codes.stream().allMatch(c -> c == null || c.isBlank())) {
            // 缺少查询目标：查询未执行
            return Optional.empty();
        }
        List<String> normalized = codes.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(String::trim)
                .collect(Collectors.toList());
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        // 租户条件由 TenantLine 拦截器注入：只取当前租户活跃类型
        List<SysDictType> types = sysDictTypeMapper.selectList(Wrappers.<SysDictType>lambdaQuery()
                .in(SysDictType::getCode, normalized)
                .orderByAsc(SysDictType::getId));
        if (types.isEmpty()) {
            return Optional.of(Map.of());
        }
        Map<String, DictTypeDetailDTO> result = new LinkedHashMap<>();
        for (SysDictType type : types) {
            // 导出需要完整往返（含停用项），与 listByType 的"仅可用项"语义不同
            List<SysDictData> items = sysDictDataMapper.selectList(Wrappers.<SysDictData>lambdaQuery()
                    .eq(SysDictData::getDictCode, type.getCode())
                    .orderByAsc(SysDictData::getSort)
                    .orderByAsc(SysDictData::getId));
            result.put(type.getCode(), DictTypeDetailDTO.builder()
                    .code(type.getCode())
                    .name(type.getName())
                    .status(type.getStatus())
                    .description(type.getDescription())
                    .items(items.stream().map(this::toDTO).collect(Collectors.toList()))
                    .build());
        }
        return Optional.of(result);
    }

    @Override
    public Optional<String> inspectDictTypeForImport(String code) {
        if (code == null || code.isBlank()) {
            // 缺少判定目标
            return Optional.empty();
        }
        String normalized = code.trim();
        // 当前租户已有活跃类型 → REUSE（租户条件由拦截器注入）
        Long inTenant = sysDictTypeMapper.selectCount(Wrappers.<SysDictType>lambdaQuery()
                .eq(SysDictType::getCode, normalized));
        if (inTenant != null && inTenant > 0) {
            return Optional.of("REUSE");
        }
        // 全局唯一索引 uk_sys_dict_type_code 为 (code, deleted)：任一租户存在活跃行
        // 即不可新建（OCCUPIED）；仅软删除行占用不影响新建（deleted=0 仍空闲）
        Long ownerTenant = sysDictTypeMapper.selectActiveOwnerTenantId(normalized);
        return Optional.of(ownerTenant != null ? "OCCUPIED" : "MISSING");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Optional<Boolean> createDictTypeForImport(String code, String name, Integer status,
                                                     String description, List<DictItemDTO> items) {
        if (code == null || code.isBlank() || name == null || name.isBlank()) {
            // 缺少写入目标
            return Optional.empty();
        }
        String normalized = code.trim();
        // 预检与确认之间无服务端状态：写入前复核归属，非 MISSING 视为并发占用
        if (!"MISSING".equals(inspectDictTypeForImport(normalized).orElse(null))) {
            return Optional.of(false);
        }
        SysDictType type = new SysDictType();
        type.setCode(normalized);
        type.setName(name.trim());
        type.setStatus(status == null ? 0 : status);
        type.setDescription(description);
        try {
            sysDictTypeMapper.insert(type);
        } catch (DuplicateKeyException e) {
            // 并发竞态撞全局唯一索引：不中断导入，由调用方计入报告
            return Optional.of(false);
        }
        if (items != null) {
            for (DictItemDTO item : items) {
                if (item == null || item.getCode() == null || item.getCode().isBlank()) {
                    continue;
                }
                SysDictData data = new SysDictData();
                data.setDictCode(normalized);
                data.setDictValue(item.getCode());
                data.setLabel(item.getLabel());
                data.setSort(item.getSort() == null ? 0 : item.getSort());
                data.setStatus(item.getStatus() == null ? 0 : item.getStatus());
                data.setIsDefault(item.getIsDefault() == null ? 0 : item.getIsDefault());
                data.setCssClass(item.getCssClass());
                data.setListClass(item.getListClass());
                data.setDescription(item.getDescription());
                sysDictDataMapper.insert(data);
            }
        }
        return Optional.of(true);
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
                .description(data.getDescription())
                .build();
    }
}

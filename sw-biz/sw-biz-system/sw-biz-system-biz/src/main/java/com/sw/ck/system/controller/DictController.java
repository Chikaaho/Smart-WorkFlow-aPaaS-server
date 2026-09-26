package com.sw.ck.system.controller;

import com.sw.ck.common.page.PageParam;
import com.sw.ck.common.page.PageResult;
import com.sw.ck.common.response.R;
import com.sw.ck.system.api.dict.DictFacade;
import com.sw.ck.system.api.dict.DictItemDTO;
import com.sw.ck.system.entity.SysDictData;
import com.sw.ck.system.entity.SysDictType;
import com.sw.ck.system.service.SysDictDataService;
import com.sw.ck.system.service.SysDictTypeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * 字典管理控制器。
 * <p>
 * 提供字典类型与字典数据项的管理端 CRUD 接口。
 * </p>
 */
@RestController
@RequestMapping("/system/dict")
public class DictController {

    private final SysDictTypeService sysDictTypeService;
    private final SysDictDataService sysDictDataService;
    private final DictFacade dictFacade;

    public DictController(SysDictTypeService sysDictTypeService,
                          SysDictDataService sysDictDataService,
                          DictFacade dictFacade) {
        this.sysDictTypeService = sysDictTypeService;
        this.sysDictDataService = sysDictDataService;
        this.dictFacade = dictFacade;
    }

    // ========== 字典类型 ==========

    /**
     * 分页查询字典类型。
     */
    @PostMapping("/type/page")
    public R<PageResult<SysDictType>> pageType(@RequestParam(defaultValue = "1") long pageNum,
                                                @RequestParam(defaultValue = "10") long pageSize,
                                                @RequestBody(required = false) SysDictType query) {
        PageParam pageParam = new PageParam();
        pageParam.setPageNum(pageNum);
        pageParam.setPageSize(pageSize);
        return R.ok(sysDictTypeService.page(pageParam, query));
    }

    /**
     * 获取字典类型详情。
     */
    @GetMapping("/type/{id}")
    public R<SysDictType> getType(@PathVariable Long id) {
        return R.ok(sysDictTypeService.getById(id));
    }

    /**
     * 创建字典类型。
     */
    @PostMapping("/type")
    public R<Long> createType(@Valid @RequestBody SysDictType dictType) {
        return R.ok(sysDictTypeService.create(dictType));
    }

    /**
     * 更新字典类型。
     */
    @PutMapping("/type")
    public R<Void> updateType(@Valid @RequestBody SysDictType dictType) {
        sysDictTypeService.update(dictType);
        return R.ok();
    }

    /**
     * 删除字典类型（逻辑删除）。
     */
    @DeleteMapping("/type/{id}")
    public R<Void> deleteType(@PathVariable Long id) {
        sysDictTypeService.delete(id);
        return R.ok();
    }

    // ========== 字典数据 ==========

    /**
     * 分页查询字典数据项。
     */
    @PostMapping("/data/page")
    public R<PageResult<SysDictData>> pageData(@RequestParam(defaultValue = "1") long pageNum,
                                                @RequestParam(defaultValue = "10") long pageSize,
                                                @RequestBody(required = false) SysDictData query) {
        PageParam pageParam = new PageParam();
        pageParam.setPageNum(pageNum);
        pageParam.setPageSize(pageSize);
        return R.ok(sysDictDataService.page(pageParam, query));
    }

    /**
     * 根据字典类型编码查询字典数据项列表（供前端下拉等场景使用）。
     */
    @GetMapping("/data/list/{dictType}")
    public R<List<DictItemDTO>> listDataByType(@PathVariable String dictType) {
        Optional<List<DictItemDTO>> items = dictFacade.listByType(dictType);
        if (items.isEmpty()) {
            // 契约 empty：dictType 为空白，缺少查询目标；对外响应保持空列表（HTTP 行为不变）
            return R.ok(List.of());
        }
        return R.ok(items.get());
    }

    /**
     * 获取字典数据项详情。
     */
    @GetMapping("/data/{id}")
    public R<SysDictData> getData(@PathVariable Long id) {
        return R.ok(sysDictDataService.getById(id));
    }

    /**
     * 创建字典数据项。
     */
    @PostMapping("/data")
    public R<Long> createData(@Valid @RequestBody SysDictData dictData) {
        return R.ok(sysDictDataService.create(dictData));
    }

    /**
     * 更新字典数据项。
     */
    @PutMapping("/data")
    public R<Void> updateData(@Valid @RequestBody SysDictData dictData) {
        sysDictDataService.update(dictData);
        return R.ok();
    }

    /**
     * 删除字典数据项（逻辑删除）。
     */
    @DeleteMapping("/data/{id}")
    public R<Void> deleteData(@PathVariable Long id) {
        sysDictDataService.delete(id);
        return R.ok();
    }
}

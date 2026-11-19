package com.sw.ck.storage.service.impl;

import org.springframework.beans.factory.annotation.Autowired;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sw.ck.common.datascope.DataScopeFilter;
import com.sw.ck.common.datascope.DeptScopeProvider;
import com.sw.ck.common.security.LoginContextProvider;
import com.sw.ck.common.service.BaseServiceImpl;
import com.sw.ck.storage.entity.StorageFile;
import com.sw.ck.storage.mapper.StorageFileMapper;
import com.sw.ck.storage.service.StorageFileService;
import org.springframework.stereotype.Service;

/**
 * 文件存储 Service 实现。
 */
@Service
public class StorageFileServiceImpl
        extends BaseServiceImpl<StorageFileMapper, StorageFile>
        implements StorageFileService {

    @Autowired
    private LoginContextProvider loginContextProvider;
    @Autowired
    private DeptScopeProvider deptScopeProvider;

    @Override
    public StorageFile findByStorageKey(String storageKey) {
        return lambdaQuery()
                .eq(StorageFile::getStorageKey, storageKey)
                .one();
    }

    @Override
    public Page<StorageFile> pageFiles(long pageNum, long pageSize) {
        // 数据范围：sw_storage_file 无 dept_id 列，等效条件在 selectStorageFilePage 内实现
        DataScopeFilter scope = DataScopeFilter.resolve(loginContextProvider, deptScopeProvider);
        return (Page<StorageFile>) baseMapper.selectStorageFilePage(
                new Page<>(pageNum, pageSize), scope);
    }
}

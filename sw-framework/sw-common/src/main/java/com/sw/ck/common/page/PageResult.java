package com.sw.ck.common.page;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

@Data
public class PageResult<T> implements Serializable {

    private List<T> records;
    private long total;
    private long pageNum;
    private long pageSize;

    public static <T> PageResult<T> of(IPage<T> page) {
        PageResult<T> result = new PageResult<>();
        result.setRecords(page.getRecords());
        result.setTotal(page.getTotal());
        result.setPageNum(page.getCurrent());
        result.setPageSize(page.getSize());
        return result;
    }

    /** 逐条转换记录类型，分页元信息原样保留。 */
    public static <S, T> PageResult<T> map(PageResult<S> source, java.util.function.Function<S, T> mapper) {
        PageResult<T> result = new PageResult<>();
        result.setRecords(source.getRecords() == null ? Collections.emptyList()
                : source.getRecords().stream().map(mapper).toList());
        result.setTotal(source.getTotal());
        result.setPageNum(source.getPageNum());
        result.setPageSize(source.getPageSize());
        return result;
    }

    public static <T> PageResult<T> empty() {
        PageResult<T> result = new PageResult<>();
        result.setRecords(Collections.emptyList());
        result.setTotal(0);
        result.setPageNum(1);
        result.setPageSize(10);
        return result;
    }
}

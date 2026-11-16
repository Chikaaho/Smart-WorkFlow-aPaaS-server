package com.sw.ck.common.page;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;
import java.util.function.Function;

/**
 * MyBatis-Plus {@link Page} 的记录类型转换。
 * <p>
 * 分页元信息（current/size/total/pages 等）原样保留，只把 records 逐条映射为视图类型，
 * 保证响应 JSON 结构不变。
 * </p>
 */
public final class PageConverter {

    private PageConverter() {
    }

    public static <S, T> Page<T> map(Page<S> source, Function<S, T> mapper) {
        Page<T> target = new Page<>(source.getCurrent(), source.getSize(), source.getTotal());
        List<T> records = source.getRecords() == null ? List.of()
                : source.getRecords().stream().map(mapper).toList();
        target.setRecords(records);
        return target;
    }
}

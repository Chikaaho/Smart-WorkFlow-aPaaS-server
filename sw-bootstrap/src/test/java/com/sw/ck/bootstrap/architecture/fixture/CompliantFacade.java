package com.sw.ck.bootstrap.architecture.fixture;

import java.util.List;
import java.util.Optional;

/** 合规夹具：接口全部方法返回参数化 Optional<T>（用于守门正向验证）。 */
public interface CompliantFacade {

    Optional<String> fetch(String key);

    default Optional<List<String>> listAll() {
        return Optional.of(List.of());
    }

    Optional<Boolean> exists(String key);
}

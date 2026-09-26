package com.sw.ck.bootstrap.architecture.fixture;

import java.util.Optional;

/**
 * 违规夹具：接口方法故意返回非 Optional / raw Optional / Optional&lt;Void&gt; / 嵌套 Optional，
 * 用于证明守门在违规输入下会稳定失败（方向 §3 阶段 C 的反例验证）。
 */
@SuppressWarnings({"rawtypes", "unused"})
public interface NonCompliantFacade {

    String fetch(String key);

    boolean exists(String key);

    Optional rawOptional();

    Optional<Void> voidOptional();

    Optional<Optional<String>> nestedOptional();
}

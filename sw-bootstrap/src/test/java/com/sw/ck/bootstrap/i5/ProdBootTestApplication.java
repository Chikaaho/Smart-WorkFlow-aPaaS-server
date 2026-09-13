package com.sw.ck.bootstrap.i5;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * G3 prod 启动测试专用入口：与 StarterApplication 等价的扫描范围，但排除
 * bootstrap 测试类路径中其他用例的隔离配置（p4overlap 等），避免其
 * 数据源/元数据 bean 与真实 prod 启动冲突。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = "com.sw.ck",
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                pattern = "com\\.sw\\.ck\\.bootstrap\\..*"))
@MapperScan({"com.sw.ck.**.mapper", "com.sw.ck.bootstrap.verify"})
public class ProdBootTestApplication {
}

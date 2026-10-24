package io.github.atengk.boot.sample.datasource;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 动态多数据源与物理隔离样例启动类
 *
 * @author Ateng
 * @since 2026-10-24
 */
@SpringBootApplication
@MapperScan("io.github.atengk.boot.sample.datasource.mapper")
public class SampleDatasourceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleDatasourceApplication.class, args);
    }
}

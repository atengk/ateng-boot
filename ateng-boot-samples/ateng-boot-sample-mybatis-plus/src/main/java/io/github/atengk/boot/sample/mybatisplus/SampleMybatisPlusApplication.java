package io.github.atengk.boot.sample.mybatisplus;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MyBatis-Plus 持久层增强场景自验工程启动入口（H2 内存库）
 *
 * @author Ateng
 * @since 2026-10-19
 */
@SpringBootApplication
@MapperScan("io.github.atengk.boot.sample.mybatisplus.mapper")
public class SampleMybatisPlusApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleMybatisPlusApplication.class, args);
    }
}

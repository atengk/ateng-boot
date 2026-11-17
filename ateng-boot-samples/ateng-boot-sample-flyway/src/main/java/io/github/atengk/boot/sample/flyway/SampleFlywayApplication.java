package io.github.atengk.boot.sample.flyway;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Flyway 数据库版本化迁移与多数据源协同样例启动类
 *
 * @author Ateng
 * @since 2026-11-17
 */
@SpringBootApplication
@MapperScan("io.github.atengk.boot.sample.flyway.mapper")
public class SampleFlywayApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleFlywayApplication.class, args);
    }
}

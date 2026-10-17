package io.github.atengk.boot.sample.satoken;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Sa-Token 场景自验示例工程启动类
 *
 * @author Ateng
 * @since 2026-10-17
 */
@EnableAsync
@SpringBootApplication
@MapperScan("io.github.atengk.boot.sample.satoken.mapper")
public class SampleSaTokenApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleSaTokenApplication.class, args);
    }
}

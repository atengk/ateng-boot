package io.github.atengk.boot.sample;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.scheduling.annotation.EnableAsync;

/**
 * ateng-boot 完整技术底座自验与集成示例启动引导类
 * 聚合 core、web 与 mybatis-plus 全量能力，提供端到端真实业务演示
 *
 * @author Ateng
 * @since 2026-10-15
 */
@EnableAsync
@SpringBootApplication
@MapperScan("io.github.atengk.boot.sample.mapper")
public class SampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleApplication.class, args);
    }
}

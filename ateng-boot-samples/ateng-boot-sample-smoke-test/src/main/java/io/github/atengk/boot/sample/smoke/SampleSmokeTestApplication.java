package io.github.atengk.boot.sample.smoke;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * ateng-boot 全家桶自动装配冒烟与集成门禁启动类
 *
 * @author Ateng
 * @since 2026-10-17
 */
@EnableAsync
@SpringBootApplication
@MapperScan("io.github.atengk.boot.sample.smoke.mapper")
public class SampleSmokeTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleSmokeTestApplication.class, args);
    }
}

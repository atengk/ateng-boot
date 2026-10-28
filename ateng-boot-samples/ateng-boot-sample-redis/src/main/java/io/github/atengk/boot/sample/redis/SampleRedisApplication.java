package io.github.atengk.boot.sample.redis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.cache.annotation.EnableCaching;

/**
 * Redis 场景自验示例工程启动类
 *
 * @author Ateng
 * @since 2026-10-17
 */
@EnableCaching
@SpringBootApplication
public class SampleRedisApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleRedisApplication.class, args);
    }
}

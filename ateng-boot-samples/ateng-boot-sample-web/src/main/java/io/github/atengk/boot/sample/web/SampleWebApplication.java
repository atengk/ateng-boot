package io.github.atengk.boot.sample.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Web 契约与运行时场景自验工程启动入口（零 DB 依赖）
 *
 * @author Ateng
 * @since 2026-10-19
 */
@SpringBootApplication
public class SampleWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleWebApplication.class, args);
    }
}

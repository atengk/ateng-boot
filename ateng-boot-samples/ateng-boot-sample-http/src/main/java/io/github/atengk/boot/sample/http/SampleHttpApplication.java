package io.github.atengk.boot.sample.http;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * HTTP 声明式与命令式通信底座自验集成样例应用启动类
 *
 * @author Ateng
 * @since 2026-10-21
 */
@SpringBootApplication
public class SampleHttpApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleHttpApplication.class, args);
    }
}

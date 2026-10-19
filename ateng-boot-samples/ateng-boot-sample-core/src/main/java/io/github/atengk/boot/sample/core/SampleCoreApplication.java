package io.github.atengk.boot.sample.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Core 底座原语场景自验工程启动入口（纯内存极速自验）
 *
 * @author Ateng
 * @since 2026-10-19
 */
@EnableAsync
@SpringBootApplication
public class SampleCoreApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(SampleCoreApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.run(args);
    }
}

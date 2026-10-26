package io.github.atengk.boot.sample.admin;

import de.codecentric.boot.admin.server.config.EnableAdminServer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot Admin 生产级监控管控中心场景样例启动类
 *
 * @author Ateng
 * @since 2026-10-26
 */
@EnableAdminServer
@SpringBootApplication
public class AdminSampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminSampleApplication.class, args);
    }
}

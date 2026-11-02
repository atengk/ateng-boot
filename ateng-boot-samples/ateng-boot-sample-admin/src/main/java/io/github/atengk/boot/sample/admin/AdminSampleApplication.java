package io.github.atengk.boot.sample.admin;

import de.codecentric.boot.admin.server.config.EnableAdminServer;
import io.github.atengk.boot.admin.admission.AdminAdmissionResult;
import io.github.atengk.boot.admin.admission.AdminInstanceAdmissionPolicy;
import io.github.atengk.boot.admin.admission.DefaultAdminInstanceAdmissionPolicy;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

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

    /**
     * 演示企业级生产环境声明自定义实例注册准入策略 SPI
     * 先行委托默认准入策略（密钥与云元数据防 SSRF），再执行企业级服务名准入审查
     */
    @Bean
    public AdminInstanceAdmissionPolicy sampleAdminInstanceAdmissionPolicy(AtengAdminServerProperties properties) {
        DefaultAdminInstanceAdmissionPolicy defaultPolicy = new DefaultAdminInstanceAdmissionPolicy(properties);
        return (registration, request) -> {
            // 1. 委托默认策略执行通信密钥比对、云元数据阻断与正则白名单核验
            AdminAdmissionResult defaultResult = defaultPolicy.evaluate(registration, request);
            if (defaultResult.isRejected()) {
                return defaultResult;
            }

            // 2. 企业级扩展准入规则：服务命名规范审查，拦截未授权或违规命名（如以 forbidden- 开头）的实例
            if (registration != null && StringUtils.hasText(registration.getName())) {
                String name = registration.getName();
                if (name.startsWith("forbidden-") || name.startsWith("unapproved-")) {
                    return AdminAdmissionResult.reject("Service name rejected by sample admission policy: " + name);
                }
            }

            return AdminAdmissionResult.admit();
        };
    }
}

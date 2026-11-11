package io.github.atengk.boot.sample.admin.policy;

import io.github.atengk.boot.admin.admission.AdminAdmissionResult;
import io.github.atengk.boot.admin.admission.AdminInstanceAdmissionPolicy;
import io.github.atengk.boot.admin.admission.DefaultAdminInstanceAdmissionPolicy;
import io.github.atengk.boot.admin.admission.InstanceRegistration;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 样例自定义实例注册准入策略 SPI 实现
 * 先委托默认准入策略核验集群密钥、http/https Scheme、云元数据与环回限制，再执行企业服务命名准入规范审查
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Component
public class SampleInstanceAdmissionPolicy implements AdminInstanceAdmissionPolicy {

    private final DefaultAdminInstanceAdmissionPolicy defaultPolicy;

    public SampleInstanceAdmissionPolicy(AtengAdminServerProperties properties) {
        this.defaultPolicy = new DefaultAdminInstanceAdmissionPolicy(properties);
    }

    @Override
    public AdminAdmissionResult evaluate(InstanceRegistration registration, HttpServletRequest request) {
        // 1. 委托默认准入策略执行集群防伪密钥与防 SSRF 审查
        AdminAdmissionResult defaultResult = defaultPolicy.evaluate(registration, request);
        if (defaultResult.isRejected()) {
            return defaultResult;
        }

        // 2. 企业级命名治理扩展：禁止未批复或违规前缀服务接入生产监控看板
        if (registration != null && StringUtils.hasText(registration.getName())) {
            String serviceName = registration.getName().trim();
            if (serviceName.startsWith("forbidden-") || serviceName.startsWith("unapproved-")) {
                return AdminAdmissionResult.reject(
                        HttpStatus.FORBIDDEN,
                        "Service name rejected by sample admission policy: " + serviceName
                );
            }
        }

        return AdminAdmissionResult.admit();
    }
}

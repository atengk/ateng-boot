package io.github.atengk.boot.admin.admission;

import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.services.InstanceFilter;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

/**
 * 桥接 SBA {@link InstanceFilter} 与 {@link AdminInstanceAdmissionPolicy} 的注册准入守卫
 * <p>
 * 对 {@code source=discovery} 服务发现节点跳过 HTTP 共享密钥握手校验并严格执行四层 SSRF 清洗与白名单筛选；
 * 同时对直调 {@code InstanceRegistry} 写入的非发现实例兜底校验 URL 安全性。
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
public class AdminAdmissionInstanceFilter implements InstanceFilter {

    private final AdminInstanceAdmissionPolicy admissionPolicy;

    public AdminAdmissionInstanceFilter(AdminInstanceAdmissionPolicy admissionPolicy) {
        this.admissionPolicy = Objects.requireNonNull(admissionPolicy, "admissionPolicy must not be null");
    }

    @Override
    public boolean filter(Instance instance) {
        if (instance == null || instance.getRegistration() == null) {
            return false;
        }
        InstanceRegistration registration = InstanceRegistration.from(instance.getRegistration());
        if (registration == null) {
            return false;
        }
        if (DefaultAdminInstanceAdmissionPolicy.DISCOVERY_SOURCE.equalsIgnoreCase(registration.getSource())) {
            AdminAdmissionResult result = admissionPolicy.evaluate(registration, null);
            boolean allowed = result != null && result.isAllowed();
            if (!allowed) {
                log.warn("[Admission InstanceFilter] 服务发现实例准入被拒: name=[{}], reason=[{}]",
                        registration.getName(), result != null ? result.getReason() : "null result");
            }
            return allowed;
        }
        boolean hasAnyUrl = org.springframework.util.StringUtils.hasText(registration.getServiceUrl())
                || org.springframework.util.StringUtils.hasText(registration.getManagementUrl())
                || org.springframework.util.StringUtils.hasText(registration.getHealthUrl());
        boolean urlsSafe = hasAnyUrl
                && isOptionalUrlSafe(registration.getServiceUrl())
                && isOptionalUrlSafe(registration.getManagementUrl())
                && isOptionalUrlSafe(registration.getHealthUrl());
        if (!urlsSafe) {
            log.warn("[Admission InstanceFilter] 注册实例 URL 未通过安全策略校验: name=[{}], source=[{}]",
                    registration.getName(), registration.getSource());
        }
        return urlsSafe;
    }

    private boolean isOptionalUrlSafe(String url) {
        if (!org.springframework.util.StringUtils.hasText(url)) {
            return true;
        }
        return admissionPolicy.isOutboundUrlAllowed(url);
    }
}

package io.github.atengk.boot.admin.admission;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Spring Boot Admin 客户端实例注册准入策略扩展点 SPI
 * 支持企业级平滑扩展基于 VPC/CIDR/服务名白名单/配额审查的多维准入审查
 *
 * @author Ateng
 * @since 2026-11-02
 */
@FunctionalInterface
public interface AdminInstanceAdmissionPolicy {

    /**
     * 评估客户端实例注册请求是否合规并决定准入结果
     *
     * @param registration 待注册实例元数据描述（若请求体解析失败或为空时可能为 null）
     * @param request      当前 HTTP Servlet 请求
     * @return 准入评估决策结果（不允许返回 null）
     */
    AdminAdmissionResult evaluate(InstanceRegistration registration, HttpServletRequest request);

    /**
     * 校验出站健康探测与控制台代理目标 URL 是否安全（用于防御 DNS Rebinding 等出站 SSRF 风险）
     *
     * @param url 待探测或代理的完整目标 URL
     * @return 允许出站请求时返回 {@code true}
     */
    default boolean isOutboundUrlAllowed(String url) {
        return true;
    }
}

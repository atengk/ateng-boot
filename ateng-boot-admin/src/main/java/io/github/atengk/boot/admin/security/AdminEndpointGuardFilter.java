package io.github.atengk.boot.admin.security;

import de.codecentric.boot.admin.server.config.AdminServerProperties;
import io.github.atengk.boot.admin.audit.AdminAuditAction;
import io.github.atengk.boot.admin.audit.AdminAuditService;
import io.github.atengk.boot.admin.observability.AdminMetricsBinder;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.admin.provider.AdminConsolePrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 控制台细粒度端点访问守卫过滤器（Console Endpoint Guard）
 * <p>
 * 继承自 {@link AdminWriteGuardFilter} 并全面保持旧版公开契约兼容，在 Actuator 反向代理边界综合判定：
 * 1. 高危端点物理硬阻断（{@code blocked-endpoints}，默认含 {@code heapdump}/{@code shutdown}/{@code restart}，含 GET）；<br>
 * 2. 全局写开关（{@code enable-write-operations=false} 时拦截所有变更写方法）；<br>
 * 3. 多角色 RBAC 校验（{@code VIEWER} 禁止对 Actuator 代理端点发起 {@code POST}/{@code PUT}/{@code DELETE}/{@code PATCH} 写操作）；<br>
 * 4. 对所有端点拦截与放行的 Actuator 代理变更操作发布结构化审计记录。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
@Order(AdminEndpointGuardFilter.DEFAULT_ORDER)
public class AdminEndpointGuardFilter extends AdminWriteGuardFilter {

    /**
     * 默认执行优先级：位于 SaServletFilter（-100）与 AdminPrincipalBridgeFilter（-95）之后
     */
    public static final int DEFAULT_ORDER = -90;

    private final AdminAuditService auditService;
    private final AdminLoginDefenseService loginDefenseService;
    private final AdminMetricsBinder metricsBinder;

    public AdminEndpointGuardFilter(AtengAdminServerProperties properties,
                                    AdminServerProperties adminServerProperties) {
        this(properties, adminServerProperties, null, null, DEFAULT_ORDER, null);
    }

    public AdminEndpointGuardFilter(AtengAdminServerProperties properties,
                                    AdminServerProperties adminServerProperties,
                                    AdminAuditService auditService) {
        this(properties, adminServerProperties, auditService, null, DEFAULT_ORDER, null);
    }

    public AdminEndpointGuardFilter(AtengAdminServerProperties properties,
                                    AdminServerProperties adminServerProperties,
                                    AdminAuditService auditService,
                                    AdminLoginDefenseService loginDefenseService,
                                    int order) {
        this(properties, adminServerProperties, auditService, loginDefenseService, order, null);
    }

    public AdminEndpointGuardFilter(AtengAdminServerProperties properties,
                                    AdminServerProperties adminServerProperties,
                                    AdminAuditService auditService,
                                    AdminLoginDefenseService loginDefenseService,
                                    int order,
                                    AdminMetricsBinder metricsBinder) {
        super(properties, adminServerProperties, order);
        this.auditService = auditService;
        this.loginDefenseService = loginDefenseService;
        this.metricsBinder = metricsBinder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String method = request.getMethod();
        String normalizedUri = AdminPathMatcher.normalizeRequestPath(request.getRequestURI(), request.getContextPath());
        if (!AdminPathMatcher.isActuatorProxyEndpoint(normalizedUri, "", contextPath)) {
            filterChain.doFilter(request, response);
            return;
        }

        String actor = AdminSessionHelper.getCurrentActorOrFallback("anonymous");
        String sourceIp = resolveClientIp(request);

        // 1. 物理硬阻断高危子端点（含 GET/POST 等全部方法）
        String endpointSegment = AdminPathMatcher.extractActuatorEndpointSegment(normalizedUri, "", contextPath);
        if (isBlockedEndpoint(endpointSegment)) {
            String reason = "Access to high-risk actuator endpoint [" + endpointSegment + "] is blocked by policy";
            log.warn("[Endpoint Guard] 拦截高危 Actuator 端点访问: actor=[{}], method=[{}], uri=[{}]",
                    AdminConsolePrincipal.maskUsername(actor), method, normalizedUri);
            recordAudit(AdminAuditAction.ENDPOINT_BLOCKED, actor, sourceIp, endpointSegment, method, normalizedUri,
                    "DENIED", reason);
            reject(response, HttpStatus.FORBIDDEN, reason);
            return;
        }

        // 2. 非幂等写操作综合校验（全局写开关 + 角色权限）
        if (isMutatingMethod(method)) {
            if (properties != null && !properties.isEnableWriteOperations()) {
                String reason = "Console write operations are disabled by policy";
                log.warn("[Endpoint Guard] 只读模式拦截写操作: actor=[{}], method=[{}], uri=[{}]",
                        AdminConsolePrincipal.maskUsername(actor), method, normalizedUri);
                recordAudit(AdminAuditAction.ENDPOINT_BLOCKED, actor, sourceIp, endpointSegment, method, normalizedUri,
                        "DENIED", reason);
                reject(response, HttpStatus.FORBIDDEN, reason);
                return;
            }
            Optional<AdminConsolePrincipal> principal = AdminSessionHelper.resolvePrincipal(request);
            if (principal.isEmpty() || !principal.get().hasWritePermission()) {
                String rolesDesc = principal.map(p -> String.valueOf(p.getRoleCodes())).orElse("[]");
                String maskedActor = principal.map(AdminConsolePrincipal::getMaskedUsername)
                        .orElseGet(() -> AdminConsolePrincipal.maskUsername(actor));
                String reason = "Insufficient console role to execute mutating actuator operation";
                log.warn("[Endpoint Guard] 越权或缺失控制台主体写操作拦截: actor=[{}], roles=[{}], method=[{}], uri=[{}]",
                        maskedActor, rolesDesc, method, normalizedUri);
                recordAudit(AdminAuditAction.ENDPOINT_BLOCKED, actor, sourceIp, endpointSegment, method, normalizedUri,
                        "DENIED", reason + ", roles=" + rolesDesc);
                reject(response, HttpStatus.FORBIDDEN, reason);
                return;
            }

            try {
                filterChain.doFilter(request, response);
            } finally {
                recordAudit(AdminAuditAction.ACTUATOR_PROXY_MUTATION, actor, sourceIp, endpointSegment, method,
                        normalizedUri, String.valueOf(response.getStatus()), "执行 Actuator 代理变更请求");
            }
            return;
        }

        filterChain.doFilter(request, response);
    }

    @Override
    public boolean shouldBlock(String method, String uri) {
        if (!isActuatorProxyEndpoint(uri)) {
            return false;
        }
        String segment = AdminPathMatcher.extractActuatorEndpointSegment(uri, "", contextPath);
        if (isBlockedEndpoint(segment)) {
            return true;
        }
        return super.shouldBlock(method, uri);
    }

    /**
     * 判断子端点名称是否命中 {@code blocked-endpoints} 高危黑名单
     *
     * @param endpointSegment 小写首段子端点名称
     * @return true 若命中阻断清单
     */
    public boolean isBlockedEndpoint(String endpointSegment) {
        if (!StringUtils.hasText(endpointSegment) || properties == null) {
            return false;
        }
        List<String> blocked = properties.getBlockedEndpoints();
        if (blocked == null || blocked.isEmpty()) {
            return false;
        }
        String normalized = endpointSegment.trim().toLowerCase(Locale.ROOT);
        for (String item : blocked) {
            if (StringUtils.hasText(item) && item.trim().toLowerCase(Locale.ROOT).equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private String resolveClientIp(HttpServletRequest request) {
        return AdminLoginDefenseService.resolveClientIpOrRemote(loginDefenseService, request);
    }

    private void recordAudit(AdminAuditAction action,
                             String actor,
                             String sourceIp,
                             String target,
                             String method,
                             String path,
                             String outcome,
                             String detail) {
        if (action == AdminAuditAction.ENDPOINT_BLOCKED) {
            AdminMetricsBinder.recordEndpointBlockedIfPresent(metricsBinder);
        }
        AdminAuditService.recordIfPresent(auditService, action, actor, sourceIp, target, method, path, outcome, detail);
    }
}

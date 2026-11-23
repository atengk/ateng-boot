package io.github.atengk.boot.admin.controller;

import io.github.atengk.boot.admin.audit.AdminAuditAction;
import io.github.atengk.boot.admin.audit.AdminAuditLogRepository;
import io.github.atengk.boot.admin.audit.AdminAuditRecord;
import io.github.atengk.boot.admin.audit.AdminAuditService;
import io.github.atengk.boot.admin.observability.AdminMetricsBinder;
import io.github.atengk.boot.admin.provider.AdminConsolePrincipal;
import io.github.atengk.boot.admin.provider.AdminRole;
import io.github.atengk.boot.admin.security.AdminLoginDefenseService;
import io.github.atengk.boot.admin.security.AdminSessionHelper;
import io.github.atengk.boot.admin.silence.AdminSilenceManager;
import io.github.atengk.boot.admin.silence.SilenceRule;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Spring Boot Admin 内置控制台主体、操作审计与维护静默窗口管理 API 控制器
 * <p>
 * 挂载于 {@code {contextPath}/console/**}，受 Sa-Token 登录屏障与控制台 RBAC 角色守卫保护，
 * 显式返回强类型 {@link Result} 脱敏 VO 契约视图，严禁暴露密码/密钥等明文凭据。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
@RestController
@RequestMapping("${spring.boot.admin.context-path:}/console")
public class AdminConsoleController {

    private static final Set<String> SENSITIVE_ATTR_KEYS = Set.of("password", "passwd", "secret", "token", "credential");

    private final AdminAuditLogRepository auditLogRepository;
    private final AdminSilenceManager silenceManager;
    private final AdminAuditService auditService;
    private final AdminLoginDefenseService loginDefenseService;
    private final AdminMetricsBinder metricsBinder;

    public AdminConsoleController(AdminAuditLogRepository auditLogRepository) {
        this(auditLogRepository, null, null, null, null);
    }

    public AdminConsoleController(AdminAuditLogRepository auditLogRepository,
                                  AdminSilenceManager silenceManager,
                                  AdminAuditService auditService) {
        this(auditLogRepository, silenceManager, auditService, null, null);
    }

    public AdminConsoleController(AdminAuditLogRepository auditLogRepository,
                                  AdminSilenceManager silenceManager,
                                  AdminAuditService auditService,
                                  AdminLoginDefenseService loginDefenseService) {
        this(auditLogRepository, silenceManager, auditService, loginDefenseService, null);
    }

    public AdminConsoleController(AdminAuditLogRepository auditLogRepository,
                                  AdminSilenceManager silenceManager,
                                  AdminAuditService auditService,
                                  AdminLoginDefenseService loginDefenseService,
                                  AdminMetricsBinder metricsBinder) {
        this.auditLogRepository = auditLogRepository;
        this.silenceManager = silenceManager;
        this.auditService = auditService;
        this.loginDefenseService = loginDefenseService;
        this.metricsBinder = metricsBinder;
    }

    /**
     * 获取当前已登录控制台操作主体信息与权限视图（已剥离/掩码任何敏感凭据）
     *
     * @param request  HTTP 请求
     * @param response HTTP 响应
     * @return 当前主体脱敏视图
     */
    @GetMapping("/me")
    public Result<AdminConsoleMeVO> me(HttpServletRequest request, HttpServletResponse response) {
        Optional<AdminConsolePrincipal> principalOpt = AdminSessionHelper.resolvePrincipal(request);
        if (principalOpt.isEmpty()) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return Result.fail(HttpStatus.UNAUTHORIZED.value(), "未登录或会话缺少控制台主体");
        }
        return Result.success(AdminConsoleMeVO.from(principalOpt.get()));
    }

    /**
     * 只读检索有界环形内存审计仓库中的结构化脱敏操作记录（需 VIEWER 及以上角色）
     *
     * @param action   审计动作编码过滤条件（可选）
     * @param actor    操作主体用户名过滤条件（可选）
     * @param limit    返回最大记录条数（默认 50）
     * @param request  HTTP 请求
     * @param response HTTP 响应
     * @return 结构化脱敏审计记录 VO 列表
     */
    @GetMapping("/audits")
    public Result<List<AdminAuditRecordVO>> audits(@RequestParam(value = "action", required = false) String action,
                                                   @RequestParam(value = "actor", required = false) String actor,
                                                   @RequestParam(value = "limit", defaultValue = "50") int limit,
                                                   HttpServletRequest request,
                                                   HttpServletResponse response) {
        AdminConsolePrincipal principal = requireRole(request, response, false);
        if (principal == null) {
            return Result.fail(response.getStatus(), response.getStatus() == 401 ? "未登录或会话缺少控制台主体" : "当前角色无权查看审计记录");
        }
        AdminAuditAction parsedAction = null;
        if (StringUtils.hasText(action)) {
            Optional<AdminAuditAction> actionOpt = AdminAuditAction.fromCode(action);
            if (actionOpt.isEmpty()) {
                response.setStatus(HttpStatus.BAD_REQUEST.value());
                return Result.fail(HttpStatus.BAD_REQUEST.value(), "非法的审计动作类型: " + action);
            }
            parsedAction = actionOpt.get();
        }
        if (auditLogRepository == null) {
            return Result.success(Collections.emptyList());
        }
        List<AdminAuditRecordVO> vos = auditLogRepository.query(parsedAction, actor, limit)
                .stream()
                .map(AdminAuditRecordVO::from)
                .toList();
        return Result.success(vos);
    }

    /**
     * 查询当前全部生效中的动态维护静默窗口规则（需 VIEWER 及以上角色）
     *
     * @param request  HTTP 请求
     * @param response HTTP 响应
     * @return 生效中的维护静默规则视图列表
     */
    @GetMapping("/silences")
    public Result<List<AdminSilenceRuleVO>> listSilences(HttpServletRequest request, HttpServletResponse response) {
        AdminConsolePrincipal principal = requireRole(request, response, false);
        if (principal == null) {
            return Result.fail(response.getStatus(), response.getStatus() == 401 ? "未登录或会话缺少控制台主体" : "当前角色无权查看维护静默规则");
        }
        if (silenceManager == null) {
            return Result.success(Collections.emptyList());
        }
        List<AdminSilenceRuleVO> rules = silenceManager.listActiveSilences()
                .stream()
                .map(AdminSilenceRuleVO::from)
                .toList();
        return Result.success(rules);
    }

    /**
     * 新增一条运行时维护静默窗口规则（需 OPERATOR 或 ADMIN 角色）
     *
     * @param body     创建请求体
     * @param request  HTTP 请求
     * @param response HTTP 响应
     * @return 已创建的维护静默规则视图
     */
    @PostMapping("/silences")
    public Result<AdminSilenceRuleVO> createSilence(@Valid @RequestBody(required = false) AdminSilenceCreateRequest body,
                                                    HttpServletRequest request,
                                                    HttpServletResponse response) {
        AdminConsolePrincipal principal = requireRole(request, response, true);
        if (principal == null) {
            return Result.fail(response.getStatus(), response.getStatus() == 401 ? "未登录或会话缺少控制台主体" : "仅 OPERATOR/ADMIN 角色允许创建维护静默规则");
        }
        if (silenceManager == null) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            return Result.fail(HttpStatus.SERVICE_UNAVAILABLE.value(), "维护静默管理器尚未装配");
        }
        if (body == null || !StringUtils.hasText(body.getServicePattern())) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            return Result.fail(HttpStatus.BAD_REQUEST.value(), "服务匹配模式 servicePattern 不能为空");
        }
        try {
            SilenceRule created = silenceManager.createSilence(
                    body.getServicePattern().trim(),
                    body.getDuration(),
                    body.getReason(),
                    principal.getUsername()
            );
            AdminAuditService.recordIfPresent(
                    auditService,
                    AdminAuditAction.SILENCE_CREATED,
                    principal.getUsername(),
                    AdminLoginDefenseService.resolveClientIpOrRemote(loginDefenseService, request),
                    created.getId(),
                    "POST",
                    request != null ? request.getRequestURI() : "/console/silences",
                    "SUCCESS",
                    "创建维护静默规则 pattern=" + created.getServicePattern() + ", reason=" + created.getReason()
            );
            return Result.success(AdminSilenceRuleVO.from(created));
        } catch (IllegalArgumentException | BusinessException ex) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            return Result.fail(HttpStatus.BAD_REQUEST.value(), ex.getMessage());
        }
    }

    /**
     * 按规则 ID 撤销运行时维护静默规则（需 OPERATOR 或 ADMIN 角色）
     *
     * @param id       静默规则唯一标识
     * @param request  HTTP 请求
     * @param response HTTP 响应
     * @return 是否成功撤销
     */
    @DeleteMapping("/silences/{id}")
    public Result<Boolean> revokeSilence(@PathVariable("id") String id,
                                         HttpServletRequest request,
                                         HttpServletResponse response) {
        AdminConsolePrincipal principal = requireRole(request, response, true);
        if (principal == null) {
            return Result.fail(response.getStatus(), response.getStatus() == 401 ? "未登录或会话缺少控制台主体" : "仅 OPERATOR/ADMIN 角色允许撤销维护静默规则");
        }
        if (silenceManager == null) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            return Result.fail(HttpStatus.SERVICE_UNAVAILABLE.value(), "维护静默管理器尚未装配");
        }
        if (!StringUtils.hasText(id)) {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            return Result.fail(HttpStatus.BAD_REQUEST.value(), "静默规则 ID 不能为空");
        }
        boolean revoked = silenceManager.revokeSilence(id.trim());
        if (!revoked) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return Result.fail(HttpStatus.NOT_FOUND.value(), "未找到对应的维护静默规则: " + id);
        }
        AdminAuditService.recordIfPresent(
                auditService,
                AdminAuditAction.SILENCE_REVOKED,
                principal.getUsername(),
                AdminLoginDefenseService.resolveClientIpOrRemote(loginDefenseService, request),
                id.trim(),
                "DELETE",
                request != null ? request.getRequestURI() : "/console/silences/" + id,
                "SUCCESS",
                "撤销维护静默规则"
        );
        return Result.success(Boolean.TRUE);
    }

    private AdminConsolePrincipal requireRole(HttpServletRequest request,
                                              HttpServletResponse response,
                                              boolean requireWrite) {
        Optional<AdminConsolePrincipal> principalOpt = AdminSessionHelper.resolvePrincipal(request);
        if (principalOpt.isEmpty()) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return null;
        }
        AdminConsolePrincipal principal = principalOpt.get();
        boolean permitted = requireWrite
                ? principal.hasWritePermission()
                : principal.hasAnyRole(AdminRole.VIEWER, AdminRole.OPERATOR, AdminRole.ADMIN);
        if (!permitted) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            AdminMetricsBinder.recordEndpointBlockedIfPresent(metricsBinder);
            AdminAuditService.recordIfPresent(
                    auditService,
                    AdminAuditAction.ENDPOINT_BLOCKED,
                    principal.getUsername(),
                    AdminLoginDefenseService.resolveClientIpOrRemote(loginDefenseService, request),
                    "console",
                    request != null ? request.getMethod() : "",
                    request != null ? request.getRequestURI() : "/console",
                    "DENIED",
                    "Insufficient console role, roles=" + principal.getRoleCodes()
            );
            return null;
        }
        return principal;
    }

    private static Map<String, Object> sanitizeAttributes(Map<String, Object> rawAttrs) {
        if (rawAttrs == null || rawAttrs.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>(rawAttrs.size());
        for (Map.Entry<String, Object> entry : rawAttrs.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            String lowerKey = key.toLowerCase(Locale.ROOT);
            boolean sensitive = SENSITIVE_ATTR_KEYS.stream().anyMatch(lowerKey::contains);
            sanitized.put(key, sensitive ? "******" : entry.getValue());
        }
        return Collections.unmodifiableMap(sanitized);
    }

    /**
     * 创建维护静默规则请求入参 DTO
     *
     * @author Ateng
     * @since 2026-11-21
     */
    @Getter
    @Setter
    public static class AdminSilenceCreateRequest {
        @NotBlank(message = "服务匹配模式 servicePattern 不能为空")
        private String servicePattern;
        private Duration duration;
        private String reason;
    }

    /**
     * 当前登录控制台主体脱敏响应视图模型
     *
     * @author Ateng
     * @since 2026-11-21
     */
    @Getter
    @Builder
    public static final class AdminConsoleMeVO {

        private final String username;
        private final String maskedUsername;
        private final List<String> roles;
        private final boolean writePermitted;
        private final boolean admin;
        private final Instant authenticatedAt;
        private final Map<String, Object> attributes;

        public static AdminConsoleMeVO from(AdminConsolePrincipal principal) {
            return AdminConsoleMeVO.builder()
                    .username(principal.getUsername())
                    .maskedUsername(principal.getMaskedUsername())
                    .roles(principal.getRoleCodes())
                    .writePermitted(principal.hasWritePermission())
                    .admin(principal.hasAdminPermission())
                    .authenticatedAt(principal.getAuthenticatedAt())
                    .attributes(sanitizeAttributes(principal.getAttributes()))
                    .build();
        }
    }

    /**
     * 控制台操作审计记录脱敏响应视图模型（隐去原始明文操作主体名）
     *
     * @author Ateng
     * @since 2026-11-21
     */
    @Getter
    @Builder
    public static final class AdminAuditRecordVO {

        private final String id;
        private final String action;
        private final String actionLabel;
        private final String maskedActor;
        private final String sourceIp;
        private final String target;
        private final String method;
        private final String path;
        private final String outcome;
        private final String detail;
        private final Instant occurredAt;

        public static AdminAuditRecordVO from(AdminAuditRecord record) {
            return AdminAuditRecordVO.builder()
                    .id(record.getId())
                    .action(record.getAction().getCode())
                    .actionLabel(record.getAction().getLabel())
                    .maskedActor(record.getMaskedActor())
                    .sourceIp(record.getSourceIp())
                    .target(record.getTarget())
                    .method(record.getMethod())
                    .path(record.getPath())
                    .outcome(record.getOutcome())
                    .detail(record.getDetail())
                    .occurredAt(record.getOccurredAt())
                    .build();
        }
    }

    /**
     * 动态维护静默规则脱敏响应视图模型
     *
     * @author Ateng
     * @since 2026-11-21
     */
    @Getter
    @Builder
    public static final class AdminSilenceRuleVO {

        private final String id;
        private final String servicePattern;
        private final String reason;
        private final String maskedOperator;
        private final Instant createdAt;
        private final Instant expiresAt;

        public static AdminSilenceRuleVO from(SilenceRule rule) {
            return AdminSilenceRuleVO.builder()
                    .id(rule.getId())
                    .servicePattern(rule.getServicePattern())
                    .reason(rule.getReason())
                    .maskedOperator(AdminConsolePrincipal.maskUsername(rule.getOperator()))
                    .createdAt(rule.getCreatedAt())
                    .expiresAt(rule.getExpiresAt())
                    .build();
        }
    }
}

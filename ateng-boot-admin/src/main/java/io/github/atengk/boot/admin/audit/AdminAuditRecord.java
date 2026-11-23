package io.github.atengk.boot.admin.audit;

import io.github.atengk.boot.admin.provider.AdminConsolePrincipal;
import io.github.atengk.boot.core.util.IdUtil;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 控制台操作审计不可变结构化记录模型
 * <p>
 * 自动清洗并掩码路径与详情文本中的潜在口令、令牌及密钥参数，严守“审计日志零明文凭据”安全基线。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
public final class AdminAuditRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final Pattern SENSITIVE_KV_PATTERN = Pattern.compile(
            "(?i)(password|passwd|pwd|secret|token|authorization|client-secret|x-admin-client-secret|x-actuator-token)\\s*([=:])\\s*([^\\s&,;\"']+)"
    );

    private static final Pattern USERINFO_URL_PATTERN = Pattern.compile(
            "(?i)(https?://)([^/@\\s:]+):([^/@\\s]+)@"
    );

    private static final int MAX_DETAIL_LENGTH = 512;

    private final String id;
    private final AdminAuditAction action;
    private final String actor;
    private final String maskedActor;
    private final String sourceIp;
    private final String target;
    private final String method;
    private final String path;
    private final String outcome;
    private final String detail;
    private final Instant occurredAt;

    private AdminAuditRecord(String id,
                             AdminAuditAction action,
                             String actor,
                             String sourceIp,
                             String target,
                             String method,
                             String path,
                             String outcome,
                             String detail,
                             Instant occurredAt) {
        this.id = (id != null && !id.isBlank()) ? id : IdUtil.fastSimpleUUID();
        this.action = Objects.requireNonNull(action, "action must not be null");
        this.actor = normalizeActor(actor);
        this.maskedActor = AdminConsolePrincipal.maskUsername(this.actor);
        this.sourceIp = trimOrEmpty(sourceIp);
        this.target = sanitizeText(target, 256);
        this.method = trimOrEmpty(method);
        this.path = sanitizeText(path, 256);
        this.outcome = trimOrEmpty(outcome);
        this.detail = sanitizeText(detail, MAX_DETAIL_LENGTH);
        this.occurredAt = occurredAt != null ? occurredAt : Instant.now();
    }

    /**
     * 构造结构化脱敏审计记录
     *
     * @param action     审计动作
     * @param actor      操作主体（无登录主体时可传 anonymous / client-secret）
     * @param sourceIp   来源客户端 IP
     * @param target     操作目标（如服务名、实例 ID、规则 ID）
     * @param method     HTTP 方法或操作方式
     * @param path       请求路径
     * @param outcome    结果摘要（如 ALLOW / DENIED / LOCKED / 200）
     * @param detail     补充描述（自动脱敏截断）
     * @param occurredAt 发生时间
     * @return 不可变审计记录
     */
    public static AdminAuditRecord of(AdminAuditAction action,
                                      String actor,
                                      String sourceIp,
                                      String target,
                                      String method,
                                      String path,
                                      String outcome,
                                      String detail,
                                      Instant occurredAt) {
        return new AdminAuditRecord(null, action, actor, sourceIp, target, method, path, outcome, detail, occurredAt);
    }

    public String getId() {
        return this.id;
    }

    public AdminAuditAction getAction() {
        return this.action;
    }

    public String getActor() {
        return this.actor;
    }

    public String getMaskedActor() {
        return this.maskedActor;
    }

    public String getSourceIp() {
        return this.sourceIp;
    }

    public String getTarget() {
        return this.target;
    }

    public String getMethod() {
        return this.method;
    }

    public String getPath() {
        return this.path;
    }

    public String getOutcome() {
        return this.outcome;
    }

    public String getDetail() {
        return this.detail;
    }

    public Instant getOccurredAt() {
        return this.occurredAt;
    }

    private static String normalizeActor(String actor) {
        if (actor == null || actor.isBlank()) {
            return "anonymous";
        }
        return actor.trim();
    }

    private static String trimOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    static String sanitizeText(String raw, int maxLen) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String sanitized = SENSITIVE_KV_PATTERN.matcher(raw.trim()).replaceAll("$1$2******");
        sanitized = USERINFO_URL_PATTERN.matcher(sanitized).replaceAll("$1$2:******@");
        if (sanitized.length() > maxLen) {
            return sanitized.substring(0, maxLen) + "...";
        }
        return sanitized;
    }
}

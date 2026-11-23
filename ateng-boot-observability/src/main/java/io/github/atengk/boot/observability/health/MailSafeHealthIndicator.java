package io.github.atengk.boot.observability.health;

import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.registry.MailAccountRegistry;
import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;

import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 邮件通信基础设施受管安全健康探针
 * <p>
 * 仅针对 {@link MailAccountRegistry} 中静态预热的发信账号列表执行物理 SMTP 连通性探活（跳过动态租户缓存账号，
 * 避免单个已下线外部租户账号拖垮全局健康态；静态账号列表为空时安全返回 {@code UNKNOWN}）。
 * 继承 {@link AbstractSafeHealthIndicator} 实施受管线程池异步超时切断与告警防风暴治理，
 * 默认赋予 {@code 60s} 防刷缓存 TTL 以防高频抓取触发公网邮件服务商认证限流，并对异常详情中的敏感凭据自动脱敏。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Slf4j
public class MailSafeHealthIndicator extends AbstractSafeHealthIndicator {

    /**
     * 邮件探针默认防刷缓存 TTL（60 秒）
     */
    public static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(60);

    private static final String MASK = "******";
    private static final Pattern SENSITIVE_KV_PATTERN = Pattern.compile(
            "(?i)(password|passwd|pwd|pass|access[_-]?token|refresh[_-]?token|secret|token|authorization|auth)\\s*([=:])\\s*[^,;\\s]+"
    );
    private static final Pattern SENSITIVE_AUTH_HEADER_PATTERN = Pattern.compile(
            "(?i)(bearer|basic)\\s+[A-Za-z0-9._~+/=-]+"
    );
    private static final Pattern URI_CREDENTIALS_PATTERN = Pattern.compile(
            "(://[^:/@\\s]+:)[^@\\s]+(@)"
    );
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"
    );

    private final ObjectProvider<MailAccountRegistry> registryProvider;

    public MailSafeHealthIndicator(ObjectProvider<MailAccountRegistry> registryProvider) {
        this(registryProvider, (ObservabilityProperties) null, null);
    }

    public MailSafeHealthIndicator(
            ObjectProvider<MailAccountRegistry> registryProvider,
            ObservabilityProperties properties) {
        this(registryProvider, properties, null);
    }

    public MailSafeHealthIndicator(
            ObjectProvider<MailAccountRegistry> registryProvider,
            ObservabilityProperties properties,
            Executor executor) {
        super(
                (properties != null && properties.getHealth() != null) ? properties.getHealth().getTimeout() : null,
                executor,
                resolveMailCacheTtl(properties),
                (properties != null && properties.getHealth() != null) ? properties.getHealth().getDegradedEventCooldown() : null
        );
        this.registryProvider = registryProvider;
    }

    public MailSafeHealthIndicator(
            ObjectProvider<MailAccountRegistry> registryProvider,
            Duration timeout,
            Executor executor) {
        this(registryProvider, timeout, DEFAULT_CACHE_TTL, executor);
    }

    public MailSafeHealthIndicator(
            ObjectProvider<MailAccountRegistry> registryProvider,
            Duration timeout,
            Duration cacheTtl,
            Executor executor) {
        super(timeout, executor, cacheTtl != null ? cacheTtl : DEFAULT_CACHE_TTL, Duration.ZERO);
        this.registryProvider = registryProvider;
    }

    @Override
    protected String getComponentName() {
        return "mail";
    }

    @Override
    protected void doCheck(Health.Builder builder) throws Exception {
        MailAccountRegistry registry = registryProvider != null ? registryProvider.getIfAvailable() : null;
        if (registry == null) {
            builder.unknown().withDetail("message", "未检测到已装配的 MailAccountRegistry 实例");
            return;
        }

        Collection<MailAccount> rawStaticAccounts = registry.getAll();
        int dynamicCount = safeDynamicSize(registry);
        if (rawStaticAccounts == null || rawStaticAccounts.isEmpty()) {
            builder.unknown()
                    .withDetail("message", "未配置静态发信账号，跳过物理 SMTP 探活")
                    .withDetail("staticAccountCount", 0)
                    .withDetail("dynamicAccountCount", dynamicCount);
            return;
        }

        List<MailAccount> staticAccounts = rawStaticAccounts.stream()
                .filter(acc -> acc != null && acc.getName() != null && !acc.getName().isBlank())
                .sorted(Comparator.comparing(MailAccount::getName))
                .toList();

        if (staticAccounts.isEmpty()) {
            builder.unknown()
                    .withDetail("message", "未配置有效的静态发信账号，跳过物理 SMTP 探活")
                    .withDetail("staticAccountCount", 0)
                    .withDetail("dynamicAccountCount", dynamicCount);
            return;
        }

        Map<String, Object> accountsDetail = new LinkedHashMap<>();
        int healthyCount = 0;
        int unhealthyCount = 0;

        for (MailAccount account : staticAccounts) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Mail health probe interrupted");
            }
            Map<String, Object> accountInfo = probeStaticAccount(registry, account);
            accountsDetail.put(account.getName(), accountInfo);
            if ("UP".equals(accountInfo.get("status"))) {
                healthyCount++;
            } else {
                unhealthyCount++;
            }
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("staticAccountCount", staticAccounts.size());
        details.put("dynamicAccountCount", dynamicCount);
        details.put("healthyCount", healthyCount);
        details.put("unhealthyCount", unhealthyCount);
        details.put("accounts", accountsDetail);

        if (unhealthyCount == 0) {
            builder.up().withDetails(details);
        } else {
            builder.down()
                    .withDetail("reason", unhealthyCount + " 个静态发信账号连通性探测失败")
                    .withDetails(details);
        }
    }

    private Map<String, Object> probeStaticAccount(MailAccountRegistry registry, MailAccount account)
            throws InterruptedException {
        Map<String, Object> info = new LinkedHashMap<>();
        if (account.getHost() != null && !account.getHost().isBlank()) {
            info.put("host", account.getHost());
        }
        info.put("port", account.getPort());
        info.put("authMode", account.resolveEffectiveAuthMode().name());
        info.put("ssl", account.isSsl());
        info.put("starttls", account.isStarttls());

        try {
            registry.testConnectionOrThrow(account);
            info.put("status", "UP");
        } catch (Throwable ex) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Mail health probe interrupted");
            }
            String sanitized = sanitizeErrorMessage(ex, account);
            info.put("status", "DOWN");
            info.put("error", sanitized);
            log.warn("MailSafeHealthIndicator 静态账号 [{}] 探测异常: {}", account.getName(), sanitized);
        }
        return info;
    }

    /**
     * 脱敏异常信息中的明文口令、访问令牌、账号用户名、邮箱地址与 URI 认证凭据
     */
    static String sanitizeErrorMessage(Throwable ex, MailAccount account) {
        String raw = extractReadableErrorMessage(ex);
        if (raw == null || raw.isBlank()) {
            return "SMTP 连通性探测异常";
        }
        String sanitized = raw;
        if (account != null) {
            sanitized = maskLiteralSecret(sanitized, account.getPassword());
            sanitized = maskLiteralSecret(sanitized, account.getAccessToken());
            String username = account.getUsername();
            if (username != null && !username.isBlank()) {
                sanitized = sanitized.replace(username, username.contains("@") ? DesensitizeUtil.email(username) : MASK);
            }
        }
        sanitized = URI_CREDENTIALS_PATTERN.matcher(sanitized).replaceAll("$1" + MASK + "$2");
        sanitized = SENSITIVE_KV_PATTERN.matcher(sanitized).replaceAll("$1$2" + MASK);
        sanitized = SENSITIVE_AUTH_HEADER_PATTERN.matcher(sanitized).replaceAll("$1 " + MASK);
        sanitized = maskEmails(sanitized);
        return sanitized;
    }

    private static String maskEmails(String text) {
        Matcher matcher = EMAIL_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb, Matcher.quoteReplacement(DesensitizeUtil.email(matcher.group())));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String extractReadableErrorMessage(Throwable ex) {
        if (ex == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        Throwable current = ex;
        int depth = 0;
        while (current != null && depth < 5) {
            String msg = current.getMessage();
            if (msg != null && !msg.isBlank()) {
                if (sb.length() == 0) {
                    sb.append(msg);
                } else if (!sb.toString().contains(msg)) {
                    sb.append(" | cause: ").append(msg);
                }
            }
            current = current.getCause();
            depth++;
        }
        return sb.length() > 0 ? sb.toString() : ex.getClass().getSimpleName();
    }

    private static String maskLiteralSecret(String text, String secret) {
        if (text == null || secret == null || secret.isBlank()) {
            return text;
        }
        return text.replace(secret, MASK);
    }

    private static int safeDynamicSize(MailAccountRegistry registry) {
        try {
            return registry.dynamicSize();
        } catch (Exception ex) {
            log.debug("获取动态发信账号缓存数量失败: {}", ex.getMessage());
            return 0;
        }
    }

    private static Duration resolveMailCacheTtl(ObservabilityProperties properties) {
        if (properties != null && properties.getHealth() != null && properties.getHealth().getMail() != null) {
            Duration mailTtl = properties.getHealth().getMail().getCacheTtl();
            if (mailTtl != null) {
                return mailTtl;
            }
        }
        return DEFAULT_CACHE_TTL;
    }
}

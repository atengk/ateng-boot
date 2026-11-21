package io.github.atengk.boot.admin.silence;

import io.github.atengk.boot.admin.cluster.AdminClusterCoordinator;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.IdUtil;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于 {@link AdminClusterCoordinator} 的默认动态维护静默管理器实现
 * <p>
 * 单机部署自动写入纯内存协调器；开启 Redis 集群协调时自动同步至多副本共享哈希表并支持 Fail-Open 降级。
 *
 * @author Ateng
 * @since 2026-11-21
 */
public class DefaultAdminSilenceManager implements AdminSilenceManager {

    private final AdminClusterCoordinator coordinator;
    private final Duration defaultSilenceDuration;
    private final Clock clock;

    public DefaultAdminSilenceManager(AdminClusterCoordinator coordinator, Duration defaultSilenceDuration) {
        this(coordinator, defaultSilenceDuration, Clock.systemUTC());
    }

    public DefaultAdminSilenceManager(AdminClusterCoordinator coordinator,
                                      Duration defaultSilenceDuration,
                                      Clock clock) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator must not be null");
        this.defaultSilenceDuration = (defaultSilenceDuration != null
                && !defaultSilenceDuration.isZero()
                && !defaultSilenceDuration.isNegative())
                ? defaultSilenceDuration
                : Duration.ofHours(2);
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public SilenceRule createSilence(String servicePattern, Duration duration, String reason, String operator) {
        if (!StringUtils.hasText(servicePattern)) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "维护静默规则服务匹配模式不能为空");
        }
        Duration effectiveDuration = duration != null ? duration : defaultSilenceDuration;
        if (effectiveDuration.isZero() || effectiveDuration.isNegative()) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST,
                    "维护静默持续时长必须为正值: [" + effectiveDuration + "]");
        }
        Instant now = clock.instant();
        SilenceRule rule = SilenceRule.builder()
                .id(IdUtil.fastSimpleUUID())
                .servicePattern(servicePattern.trim())
                .reason(StringUtils.hasText(reason) ? reason.trim() : "")
                .operator(StringUtils.hasText(operator) ? operator.trim() : "system")
                .createdAt(now)
                .expiresAt(now.plus(effectiveDuration))
                .build();
        coordinator.saveSilenceRule(rule);
        return rule;
    }

    @Override
    public boolean revokeSilence(String ruleId) {
        if (!StringUtils.hasText(ruleId)) {
            return false;
        }
        String trimmedId = ruleId.trim();
        boolean existed = coordinator.listSilenceRules().stream()
                .anyMatch(rule -> trimmedId.equals(rule.getId()));
        if (!existed) {
            return false;
        }
        coordinator.deleteSilenceRule(trimmedId);
        return true;
    }

    @Override
    public boolean isSilenced(String serviceName) {
        return findMatchingRule(serviceName).isPresent();
    }

    @Override
    public Optional<SilenceRule> findMatchingRule(String serviceName) {
        if (!StringUtils.hasText(serviceName)) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return coordinator.listSilenceRules().stream()
                .filter(rule -> rule.matches(serviceName, now))
                .findFirst();
    }

    @Override
    public List<SilenceRule> listActiveSilences() {
        return coordinator.listSilenceRules();
    }

    @Override
    public int cleanExpiredSilences() {
        return coordinator.pruneExpiredSilenceRules();
    }
}

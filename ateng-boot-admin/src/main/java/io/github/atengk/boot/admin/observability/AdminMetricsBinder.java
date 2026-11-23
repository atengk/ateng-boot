package io.github.atengk.boot.admin.observability;

import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import io.github.atengk.boot.admin.notifier.AtengAdminNotificationNotifier;
import io.github.atengk.boot.admin.silence.AdminSilenceManager;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;

/**
 * Spring Boot Admin 服务端低基数 Micrometer 自观测指标绑定器
 * <p>
 * 采集受管实例状态分布、准入放行/拒绝次数、登录认证结果分布、高危端点拦截次数、
 * 告警防抖/发送/重提醒/雪崩聚合/静默次数及僵尸驱逐计数。
 * 严格遵循低基数防线：禁止将 {@code instanceId}、URL、IP 或用户名等无界动态值写入 Metric Tag，防止时序库维度爆炸。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
public class AdminMetricsBinder implements MeterBinder {

    private static final List<String> TRACKED_STATUSES = List.of("UP", "DOWN", "OFFLINE", "UNKNOWN", "RESTRICTED");
    private static final Duration REPO_READ_TIMEOUT = Duration.ofSeconds(1);

    private final ObjectProvider<InstanceRepository> repositoryProvider;
    private final ObjectProvider<AtengAdminNotificationNotifier> notifierProvider;
    private final ObjectProvider<AdminSilenceManager> silenceManagerProvider;
    private final InstanceRepository directRepository;
    private final AtengAdminNotificationNotifier directNotifier;
    private final AdminSilenceManager directSilenceManager;

    private final LongAdder admissionAdmitted = new LongAdder();
    private final LongAdder admissionRejected = new LongAdder();
    private final LongAdder loginSuccess = new LongAdder();
    private final LongAdder loginFailure = new LongAdder();
    private final LongAdder loginLocked = new LongAdder();
    private final LongAdder endpointBlocked = new LongAdder();
    private final LongAdder alertDebounced = new LongAdder();
    private final LongAdder alertSent = new LongAdder();
    private final LongAdder alertReminder = new LongAdder();
    private final LongAdder alertStormAggregated = new LongAdder();
    private final LongAdder alertSilenced = new LongAdder();
    private final LongAdder zombiesEvicted = new LongAdder();

    public AdminMetricsBinder(InstanceRepository repository,
                              AtengAdminNotificationNotifier notifier,
                              AdminSilenceManager silenceManager) {
        this.directRepository = repository;
        this.directNotifier = notifier;
        this.directSilenceManager = silenceManager;
        this.repositoryProvider = null;
        this.notifierProvider = null;
        this.silenceManagerProvider = null;
    }

    public AdminMetricsBinder(ObjectProvider<InstanceRepository> repositoryProvider,
                              ObjectProvider<AtengAdminNotificationNotifier> notifierProvider,
                              ObjectProvider<AdminSilenceManager> silenceManagerProvider) {
        this.directRepository = null;
        this.directNotifier = null;
        this.directSilenceManager = null;
        this.repositoryProvider = repositoryProvider;
        this.notifierProvider = notifierProvider;
        this.silenceManagerProvider = silenceManagerProvider;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        if (registry == null) {
            return;
        }
        Gauge.builder("ateng.admin.instances.registered", this, AdminMetricsBinder::countRegisteredInstances)
                .description("Total number of currently registered instances in Spring Boot Admin")
                .register(registry);

        for (String status : TRACKED_STATUSES) {
            Gauge.builder("ateng.admin.instances", this, binder -> binder.countInstancesByStatus(status))
                    .tag("status", status)
                    .description("Number of registered instances grouped by low-cardinality status")
                    .register(registry);
        }

        Gauge.builder("ateng.admin.notifier.pending.tasks", this, AdminMetricsBinder::countPendingAlerts)
                .description("Current number of pending debounce alert tasks")
                .register(registry);

        Gauge.builder("ateng.admin.notifier.alerted.instances", this, AdminMetricsBinder::countAlertedInstances)
                .description("Current number of instances in active fault alerted state")
                .register(registry);

        Gauge.builder("ateng.admin.silences.active", this, AdminMetricsBinder::countActiveSilences)
                .description("Current number of active maintenance silence rules")
                .register(registry);

        FunctionCounter.builder("ateng.admin.admission.requests", admissionAdmitted, LongAdder::sum)
                .tag("outcome", "admitted")
                .description("Total number of admitted instance registration requests")
                .register(registry);

        FunctionCounter.builder("ateng.admin.admission.requests", admissionRejected, LongAdder::sum)
                .tag("outcome", "rejected")
                .description("Total number of rejected instance registration requests")
                .register(registry);

        FunctionCounter.builder("ateng.admin.login.attempts", loginSuccess, LongAdder::sum)
                .tag("outcome", "success")
                .description("Total number of successful console login attempts")
                .register(registry);

        FunctionCounter.builder("ateng.admin.login.attempts", loginFailure, LongAdder::sum)
                .tag("outcome", "failure")
                .description("Total number of failed console login attempts")
                .register(registry);

        FunctionCounter.builder("ateng.admin.login.attempts", loginLocked, LongAdder::sum)
                .tag("outcome", "locked")
                .description("Total number of console login attempts blocked by brute-force defense")
                .register(registry);

        FunctionCounter.builder("ateng.admin.endpoint.blocked.total", endpointBlocked, LongAdder::sum)
                .description("Total number of requests blocked by endpoint or RBAC guard")
                .register(registry);

        FunctionCounter.builder("ateng.admin.alerts", alertDebounced, LongAdder::sum)
                .tag("type", "debounced")
                .description("Total number of fault transitions scheduled into debounce window")
                .register(registry);

        FunctionCounter.builder("ateng.admin.alerts", alertSent, LongAdder::sum)
                .tag("type", "sent")
                .description("Total number of alert cards dispatched")
                .register(registry);

        FunctionCounter.builder("ateng.admin.alerts", alertReminder, LongAdder::sum)
                .tag("type", "reminder")
                .description("Total number of continuous fault escalation reminders dispatched")
                .register(registry);

        FunctionCounter.builder("ateng.admin.alerts", alertStormAggregated, LongAdder::sum)
                .tag("type", "storm_aggregated")
                .description("Total number of fault events folded by storm aggregation")
                .register(registry);

        FunctionCounter.builder("ateng.admin.alerts", alertSilenced, LongAdder::sum)
                .tag("type", "silenced")
                .description("Total number of fault events suppressed by silence rules or deregistration window")
                .register(registry);

        FunctionCounter.builder("ateng.admin.zombies.evicted.total", zombiesEvicted, LongAdder::sum)
                .description("Total number of expired offline/unknown zombie instances evicted")
                .register(registry);
    }

    /**
     * 记录一次实例准入判定结果
     *
     * @param admitted true 表示放行，false 表示拒绝
     */
    public void recordAdmission(boolean admitted) {
        if (admitted) {
            admissionAdmitted.increment();
        } else {
            admissionRejected.increment();
        }
    }

    /**
     * 记录一次控制台登录尝试结果（{@code success} / {@code failure} / {@code locked}）
     *
     * @param outcome 登录结果类型
     */
    public void recordLogin(String outcome) {
        if (outcome == null) {
            return;
        }
        switch (outcome.trim().toLowerCase(Locale.ROOT)) {
            case "success" -> loginSuccess.increment();
            case "failure" -> loginFailure.increment();
            case "locked" -> loginLocked.increment();
            default -> log.debug("[Admin Metrics] 忽略未知登录结果标签: {}", outcome);
        }
    }

    /**
     * 记录一次高危端点或越权写操作守卫拦截
     */
    public void recordEndpointBlocked() {
        endpointBlocked.increment();
    }

    /**
     * 记录一次告警状态机指标事件（{@code debounced} / {@code sent} / {@code reminder} / {@code storm_aggregated} / {@code silenced}）
     *
     * @param type 告警指标事件类型
     */
    public void recordAlert(String type) {
        if (type == null) {
            return;
        }
        switch (type.trim().toLowerCase(Locale.ROOT)) {
            case "debounced" -> alertDebounced.increment();
            case "sent" -> alertSent.increment();
            case "reminder" -> alertReminder.increment();
            case "storm_aggregated" -> alertStormAggregated.increment();
            case "silenced" -> alertSilenced.increment();
            default -> log.debug("[Admin Metrics] 忽略未知告警事件类型: {}", type);
        }
    }

    /**
     * 累加驱逐僵尸实例个数
     *
     * @param count 本次驱逐数量
     */
    public void recordZombieEvicted(int count) {
        if (count > 0) {
            zombiesEvicted.add(count);
        }
    }

    private double countRegisteredInstances() {
        return fetchRegisteredInstances().size();
    }

    private double countInstancesByStatus(String targetStatus) {
        int count = 0;
        for (Instance instance : fetchRegisteredInstances()) {
            String status = instance.getStatusInfo() != null ? instance.getStatusInfo().getStatus() : "UNKNOWN";
            if (targetStatus.equalsIgnoreCase(status)) {
                count++;
            }
        }
        return count;
    }

    private List<Instance> fetchRegisteredInstances() {
        InstanceRepository repo = resolveRepository();
        if (repo == null) {
            return Collections.emptyList();
        }
        try {
            List<Instance> all = repo.findAll().collectList().block(REPO_READ_TIMEOUT);
            if (all == null || all.isEmpty()) {
                return Collections.emptyList();
            }
            return all.stream().filter(Instance::isRegistered).toList();
        } catch (Exception ex) {
            log.debug("[Admin Metrics] 读取实例仓库统计失败: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    private double countPendingAlerts() {
        AtengAdminNotificationNotifier notifier = resolveNotifier();
        return notifier != null ? notifier.getPendingTasks().size() : 0;
    }

    private double countAlertedInstances() {
        AtengAdminNotificationNotifier notifier = resolveNotifier();
        return notifier != null ? notifier.getAlertedInstances().size() : 0;
    }

    private double countActiveSilences() {
        AdminSilenceManager manager = resolveSilenceManager();
        return manager != null ? manager.listActiveSilences().size() : 0;
    }

    private InstanceRepository resolveRepository() {
        return directRepository != null
                ? directRepository
                : (repositoryProvider != null ? repositoryProvider.getIfAvailable() : null);
    }

    private AtengAdminNotificationNotifier resolveNotifier() {
        return directNotifier != null
                ? directNotifier
                : (notifierProvider != null ? notifierProvider.getIfAvailable() : null);
    }

    private AdminSilenceManager resolveSilenceManager() {
        return directSilenceManager != null
                ? directSilenceManager
                : (silenceManagerProvider != null ? silenceManagerProvider.getIfAvailable() : null);
    }

    /**
     * 空安全静态辅助：在绑定器存在时记录准入结果
     */
    public static void recordAdmissionIfPresent(AdminMetricsBinder binder, boolean admitted) {
        if (binder != null) {
            binder.recordAdmission(admitted);
        }
    }

    /**
     * 空安全静态辅助：在绑定器存在时记录端点拦截
     */
    public static void recordEndpointBlockedIfPresent(AdminMetricsBinder binder) {
        if (binder != null) {
            binder.recordEndpointBlocked();
        }
    }

    /**
     * 空安全静态辅助：在绑定器存在时记录控制台登录尝试结果
     */
    public static void recordLoginIfPresent(AdminMetricsBinder binder, String outcome) {
        if (binder != null) {
            binder.recordLogin(outcome);
        }
    }
}

package io.github.atengk.boot.sample.admin.controller;

import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import io.github.atengk.boot.admin.audit.AdminAuditAction;
import io.github.atengk.boot.admin.audit.AdminAuditLogRepository;
import io.github.atengk.boot.admin.audit.AdminAuditRecord;
import io.github.atengk.boot.admin.audit.AdminAuditService;
import io.github.atengk.boot.admin.notifier.AtengAdminNotificationNotifier;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.admin.provider.AdminConsolePrincipal;
import io.github.atengk.boot.admin.provider.AdminRole;
import io.github.atengk.boot.admin.security.AdminEndpointGuardFilter;
import io.github.atengk.boot.admin.security.AdminPathMatcher;
import io.github.atengk.boot.admin.silence.AdminSilenceManager;
import io.github.atengk.boot.admin.silence.SilenceRule;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.sample.admin.dto.EndpointGuardEvaluateRequest;
import io.github.atengk.boot.sample.admin.dto.EndpointGuardEvaluateVO;
import io.github.atengk.boot.sample.admin.dto.SampleAuditRecordVO;
import io.github.atengk.boot.sample.admin.dto.SampleSilenceCreateRequest;
import io.github.atengk.boot.sample.admin.dto.SampleSilenceVO;
import io.github.atengk.boot.sample.admin.dto.StormHeartbeatSummaryVO;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
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
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 控制台安全治理、维护静默、审计检索与自观测摘要领域控制器
 *
 * @author Ateng
 * @since 2026-11-21
 */
@RestController
@RequestMapping("/sample/admin/console")
public class AdminSecurityConsoleController {

    private final AdminSilenceManager silenceManager;
    private final AdminAuditLogRepository auditLogRepository;
    private final AdminAuditService auditService;
    private final ObjectProvider<AdminEndpointGuardFilter> endpointGuardFilterProvider;
    private final AtengAdminNotificationNotifier notifier;
    private final InstanceRepository instanceRepository;
    private final AtengAdminServerProperties properties;

    public AdminSecurityConsoleController(AdminSilenceManager silenceManager,
                                          AdminAuditLogRepository auditLogRepository,
                                          ObjectProvider<AdminAuditService> auditServiceProvider,
                                          ObjectProvider<AdminEndpointGuardFilter> endpointGuardFilterProvider,
                                          AtengAdminNotificationNotifier notifier,
                                          InstanceRepository instanceRepository,
                                          AtengAdminServerProperties properties) {
        this.silenceManager = silenceManager;
        this.auditLogRepository = auditLogRepository;
        this.auditService = auditServiceProvider.getIfAvailable();
        this.endpointGuardFilterProvider = endpointGuardFilterProvider;
        this.notifier = notifier;
        this.instanceRepository = instanceRepository;
        this.properties = properties;
    }

    /**
     * 动态创建维护静默规则并写入脱敏操作审计轨迹
     *
     * @param request 静默规则创建请求 DTO
     * @return 创建成功的静默规则视图 VO
     */
    @PostMapping("/silences")
    public Result<SampleSilenceVO> createSilence(@Valid @RequestBody SampleSilenceCreateRequest request) {
        Duration ttl = request.getTtlSeconds() != null ? Duration.ofSeconds(request.getTtlSeconds()) : null;
        String reason = StringUtils.hasText(request.getReason()) ? request.getReason().trim() : "Sample maintenance silence";
        SilenceRule created = silenceManager.createSilence(request.getPattern().trim(), ttl, reason, "sample-operator");
        AdminAuditService.recordIfPresent(
                auditService,
                AdminAuditAction.SILENCE_CREATED,
                "sample-operator",
                "127.0.0.1",
                created.getId(),
                "POST",
                "/sample/admin/console/silences",
                "SUCCESS",
                "pattern=" + created.getServicePattern() + ", reason=" + created.getReason()
        );
        return Result.success(toSilenceVO(created));
    }

    /**
     * 查询当前所有有效维护静默规则只读列表
     *
     * @return 静默规则视图列表
     */
    @GetMapping("/silences")
    public Result<List<SampleSilenceVO>> listSilences() {
        List<SilenceRule> rules = silenceManager.listActiveSilences();
        if (rules == null || rules.isEmpty()) {
            return Result.success(Collections.emptyList());
        }
        return Result.success(rules.stream().map(this::toSilenceVO).toList());
    }

    /**
     * 按规则 ID 撤销指定维护静默规则并追加审计记录
     *
     * @param id 静默规则唯一标识
     * @return 撤销是否命中并移除
     */
    @DeleteMapping("/silences/{id}")
    public Result<Boolean> revokeSilence(@PathVariable("id") String id) {
        BusinessAssert.isTrue(StringUtils.hasText(id), "静默规则 ID 不能为空");
        boolean revoked = silenceManager.revokeSilence(id.trim());
        BusinessAssert.isTrue(revoked, "静默规则不存在或已过期: " + id);
        AdminAuditService.recordIfPresent(
                auditService,
                AdminAuditAction.SILENCE_REVOKED,
                "sample-operator",
                "127.0.0.1",
                id.trim(),
                "DELETE",
                "/sample/admin/console/silences/" + id.trim(),
                "SUCCESS",
                "样例控制台主动撤销维护静默规则"
        );
        return Result.success(true);
    }

    /**
     * 检索脱敏操作审计轨迹（默认返回最近 20 条）
     *
     * @param action 可选审计动作枚举过滤
     * @param limit  返回最大条数（限制 [1, 100]）
     * @return 审计记录视图列表
     */
    @GetMapping("/audits")
    public Result<List<SampleAuditRecordVO>> queryAudits(
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        AdminAuditAction parsedAction = null;
        if (StringUtils.hasText(action)) {
            try {
                parsedAction = AdminAuditAction.valueOf(action.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                BusinessAssert.isTrue(false, "不支持的审计动作类型: " + action);
            }
        }
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        List<AdminAuditRecord> records = auditLogRepository.query(parsedAction, null, boundedLimit);
        if (records == null || records.isEmpty()) {
            return Result.success(Collections.emptyList());
        }
        return Result.success(records.stream().map(this::toAuditVO).toList());
    }

    /**
     * 预检 Actuator 反向代理请求是否会触发高危端点硬阻断或全局只读拦截
     *
     * @param request 端点预检请求 DTO
     * @return 端点守卫预检结果 VO
     */
    @PostMapping("/endpoint-guard/evaluate")
    public Result<EndpointGuardEvaluateVO> evaluateEndpointGuard(@Valid @RequestBody EndpointGuardEvaluateRequest request) {
        String method = request.getMethod().trim().toUpperCase(Locale.ROOT);
        String uri = request.getEndpointUri().trim();
        String roleCode = StringUtils.hasText(request.getRole())
                ? request.getRole().trim().toUpperCase(Locale.ROOT)
                : AdminRole.ADMIN.name();
        boolean mutating = "POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method) || "PATCH".equals(method);
        boolean viewerWriteBlocked = mutating && AdminRole.VIEWER.name().equals(roleCode);

        AdminEndpointGuardFilter filter = endpointGuardFilterProvider.getIfAvailable();
        boolean guardBlocked = filter != null && filter.shouldBlock(method, uri);
        boolean blocked = guardBlocked || viewerWriteBlocked;
        String segment = AdminPathMatcher.extractActuatorEndpointSegment(uri, "", "/admin");
        String reason;
        if (guardBlocked) {
            reason = "请求被端点守卫策略阻断: segment=" + (StringUtils.hasText(segment) ? segment : "n/a");
        } else if (viewerWriteBlocked) {
            reason = "VIEWER 角色禁止对 Actuator 代理端点执行变更写操作 (" + method + ")";
        } else {
            reason = "允许通过端点守卫与角色校验";
        }

        return Result.success(EndpointGuardEvaluateVO.builder()
                .method(method)
                .endpointUri(uri)
                .role(roleCode)
                .blocked(blocked)
                .writeOperationsEnabled(properties != null && properties.isEnableWriteOperations())
                .blockedEndpoints(properties != null && properties.getBlockedEndpoints() != null
                        ? List.copyOf(properties.getBlockedEndpoints())
                        : Collections.emptyList())
                .reason(reason)
                .build());
    }

    /**
     * 查询服务端雪崩风暴聚合参数、持续重提醒开关、活跃静默数及客户端心跳摘要
     *
     * @return 自观测摘要视图 VO
     */
    @GetMapping("/observability/summary")
    public Result<StormHeartbeatSummaryVO> getObservabilitySummary() {
        List<Instance> instances = instanceRepository.findAll().collectList().block(Duration.ofSeconds(3));
        long registeredCount = 0L;
        long upCount = 0L;
        java.time.Instant latestHeartbeat = null;
        java.util.Map<String, Long> statusCounts = new java.util.LinkedHashMap<>();
        if (instances != null) {
            for (Instance instance : instances) {
                if (instance.isRegistered()) {
                    registeredCount++;
                    String status = instance.getStatusInfo() != null ? instance.getStatusInfo().getStatus() : "UNKNOWN";
                    statusCounts.merge(status, 1L, Long::sum);
                    if ("UP".equalsIgnoreCase(status)) {
                        upCount++;
                    }
                    java.time.Instant ts = instance.getStatusTimestamp();
                    if (ts != null && (latestHeartbeat == null || ts.isAfter(latestHeartbeat))) {
                        latestHeartbeat = ts;
                    }
                }
            }
        }
        int pendingCount = (int) notifier.getPendingTasks().values().stream()
                .filter(f -> f != null && !f.isDone())
                .count();
        AtengAdminServerProperties.AlertProperties alert = properties != null ? properties.getAlert() : null;
        AtengAdminServerProperties.StormAggregationProperties storm = alert != null ? alert.getStormAggregation() : null;
        AtengAdminServerProperties.ReminderProperties reminder = alert != null ? alert.getReminder() : null;
        AtengAdminServerProperties.ClusterProperties cluster = properties != null ? properties.getCluster() : null;

        return Result.success(StormHeartbeatSummaryVO.builder()
                .registeredInstances(registeredCount)
                .upInstances(upCount)
                .statusCounts(java.util.Collections.unmodifiableMap(statusCounts))
                .lastHeartbeatTimestamp(latestHeartbeat)
                .pendingAlertTasks(pendingCount)
                .activeAlertedInstances(notifier.getAlertedInstances().size())
                .activeSilenceRules(silenceManager.listActiveSilences().size())
                .stormAggregationEnabled(storm != null && storm.isEnabled())
                .stormWindow(storm != null ? storm.getWindow() : Duration.ofSeconds(10))
                .stormThreshold(storm != null ? storm.getThreshold() : 5)
                .currentStormBucketCount(notifier.getStormWindowEntryCount())
                .reminderEnabled(reminder != null && reminder.isEnabled())
                .reminderInterval(reminder != null ? reminder.getInterval() : Duration.ofMinutes(15))
                .reminderMaxCount(reminder != null ? reminder.getMaxCount() : 3)
                .clusterEnabled(cluster != null && cluster.isEnabled())
                .build());
    }

    private SampleSilenceVO toSilenceVO(SilenceRule rule) {
        return SampleSilenceVO.builder()
                .id(rule.getId())
                .pattern(rule.getServicePattern())
                .creator(AdminConsolePrincipal.maskUsername(rule.getOperator()))
                .reason(rule.getReason())
                .createdAt(rule.getCreatedAt())
                .expiresAt(rule.getExpiresAt())
                .build();
    }

    private SampleAuditRecordVO toAuditVO(AdminAuditRecord record) {
        return SampleAuditRecordVO.builder()
                .id(record.getId())
                .timestamp(record.getOccurredAt())
                .action(record.getAction() != null ? record.getAction().name() : null)
                .actor(record.getMaskedActor())
                .sourceIp(record.getSourceIp())
                .target(record.getTarget())
                .method(record.getMethod())
                .path(record.getPath())
                .outcome(record.getOutcome())
                .detail(record.getDetail())
                .build();
    }
}

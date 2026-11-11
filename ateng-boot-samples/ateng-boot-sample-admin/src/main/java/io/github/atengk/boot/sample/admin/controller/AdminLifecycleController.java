package io.github.atengk.boot.sample.admin.controller;

import de.codecentric.boot.admin.server.domain.entities.Instance;
import de.codecentric.boot.admin.server.domain.entities.InstanceRepository;
import de.codecentric.boot.admin.server.domain.values.InstanceId;
import de.codecentric.boot.admin.server.domain.values.Registration;
import de.codecentric.boot.admin.server.domain.values.StatusInfo;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import io.github.atengk.boot.admin.scheduler.ZombieInstanceCleanupScheduler;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.admin.dto.AdminInstanceSummaryVO;
import io.github.atengk.boot.sample.admin.dto.InstanceMockRegisterRequest;
import io.github.atengk.boot.sample.admin.dto.ZombieCleanRequest;
import io.github.atengk.boot.sample.admin.dto.ZombieCleanupResultVO;
import io.github.atengk.boot.sample.admin.support.SampleStatusConverter;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 实例生命周期与僵尸实例清理领域控制器
 * 严守 Zero-Entity-Leakage：全部响应统一映射为脱敏 AdminInstanceSummaryVO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@RestController
@RequestMapping("/sample/admin/lifecycle")
public class AdminLifecycleController {

    private final InstanceRepository instanceRepository;
    private final AtengAdminServerProperties properties;
    private final ObjectProvider<ZombieInstanceCleanupScheduler> cleanupSchedulerProvider;
    private final ReentrantLock cleanupThresholdLock = new ReentrantLock();

    public AdminLifecycleController(InstanceRepository instanceRepository,
                                    AtengAdminServerProperties properties,
                                    ObjectProvider<ZombieInstanceCleanupScheduler> cleanupSchedulerProvider) {
        this.instanceRepository = instanceRepository;
        this.properties = properties;
        this.cleanupSchedulerProvider = cleanupSchedulerProvider;
    }

    /**
     * 登记演练业务实例或模拟离线僵尸实例（如 payment-gateway-service、inventory-stock-service）
     *
     * @param request 演练实例登记请求参数
     * @return 脱敏实例摘要视图
     */
    @PostMapping("/instances/mock")
    public Result<AdminInstanceSummaryVO> registerMockInstance(@Valid @RequestBody InstanceMockRegisterRequest request) {
        String baseUrl = request.getServiceUrl().trim();
        String healthUrl = StringUtils.hasText(request.getHealthUrl())
                ? request.getHealthUrl().trim()
                : (baseUrl.endsWith("/") ? baseUrl + "actuator/health" : baseUrl + "/actuator/health");

        Registration.Builder regBuilder = Registration.builder()
                .name(request.getServiceName().trim())
                .serviceUrl(baseUrl)
                .healthUrl(healthUrl)
                .managementUrl(baseUrl.endsWith("/") ? baseUrl + "actuator" : baseUrl + "/actuator");

        if (request.getMetadata() != null && !request.getMetadata().isEmpty()) {
            regBuilder.metadata(request.getMetadata());
        }
        if (StringUtils.hasText(request.getVersion())) {
            regBuilder.metadata("version", request.getVersion().trim());
        }
        if (StringUtils.hasText(request.getZone())) {
            regBuilder.metadata("zone", request.getZone().trim());
        }

        StatusInfo statusInfo = SampleStatusConverter.toStatusInfo(request.getStatus());
        InstanceId instanceId = InstanceId.of(request.getInstanceId().trim());
        Registration registration = regBuilder.build();

        Instance existing = instanceRepository.find(instanceId).block();
        Instance persisted;
        if (existing == null) {
            Instance created = Instance.create(instanceId)
                    .register(registration)
                    .withStatusInfo(statusInfo);
            persisted = instanceRepository.save(created).block();
        } else {
            persisted = instanceRepository.compute(instanceId, (id, current) -> {
                Instance target = current != null ? current : Instance.create(id);
                return Mono.just(target.register(registration).withStatusInfo(statusInfo));
            }).block();
        }
        return Result.success(toSummaryVO(persisted != null ? persisted : existing));
    }

    /**
     * 查询当前所有受管实例的脱敏摘要列表
     *
     * @return 脱敏实例摘要列表
     */
    @GetMapping("/instances")
    public Result<List<AdminInstanceSummaryVO>> listInstances() {
        List<Instance> instances = instanceRepository.findAll().collectList().block();
        if (instances == null || instances.isEmpty()) {
            return Result.success(Collections.emptyList());
        }
        List<AdminInstanceSummaryVO> summaries = instances.stream()
                .map(this::toSummaryVO)
                .toList();
        return Result.success(summaries);
    }

    /**
     * 手动触发 Zombie Instance Eviction 僵尸实例清理扫描
     *
     * @param request 可选临时阈值覆盖参数（受 JSR-303 非负校验约束）
     * @return 本次清理结果与剩余存活实例统计视图
     */
    @PostMapping("/zombies/clean")
    public Result<ZombieCleanupResultVO> cleanZombies(@Valid @RequestBody(required = false) ZombieCleanRequest request) {
        ZombieInstanceCleanupScheduler scheduler = cleanupSchedulerProvider.getIfAvailable();
        if (scheduler == null) {
            return Result.success(ZombieCleanupResultVO.builder()
                    .evictedCount(0)
                    .schedulerRunning(false)
                    .zombieThreshold(properties.getCleanup().getZombieThreshold())
                    .remainingRegisteredCount(countRegisteredInstances())
                    .build());
        }

        Duration appliedThreshold = properties.getCleanup().getZombieThreshold();
        int evicted;

        // 使用重入锁隔离单机沙箱临时阈值演示与清理扫描周期，防止并发修改污染
        cleanupThresholdLock.lock();
        Duration originalThreshold = properties.getCleanup().getZombieThreshold();
        try {
            if (request != null && request.getZombieThresholdOverride() != null) {
                appliedThreshold = request.getZombieThresholdOverride();
                properties.getCleanup().setZombieThreshold(appliedThreshold);
            }
            evicted = scheduler.cleanZombies();
        } finally {
            properties.getCleanup().setZombieThreshold(originalThreshold);
            cleanupThresholdLock.unlock();
        }

        return Result.success(ZombieCleanupResultVO.builder()
                .evictedCount(evicted)
                .schedulerRunning(scheduler.isRunning())
                .zombieThreshold(appliedThreshold)
                .remainingRegisteredCount(countRegisteredInstances())
                .build());
    }

    private long countRegisteredInstances() {
        List<Instance> instances = instanceRepository.findAll().collectList().block();
        if (instances == null || instances.isEmpty()) {
            return 0L;
        }
        return instances.stream().filter(Instance::isRegistered).count();
    }

    private AdminInstanceSummaryVO toSummaryVO(Instance instance) {
        Registration reg = instance.getRegistration();
        Map<String, String> metadata = reg != null ? reg.getMetadata() : Collections.emptyMap();
        return AdminInstanceSummaryVO.builder()
                .instanceId(instance.getId().getValue())
                .serviceName(reg != null ? reg.getName() : "UNKNOWN")
                .status(instance.getStatusInfo() != null ? instance.getStatusInfo().getStatus() : "UNKNOWN")
                .registered(instance.isRegistered())
                .serviceUrl(reg != null ? reg.getServiceUrl() : null)
                .version(metadata != null ? metadata.get("version") : null)
                .zone(metadata != null ? metadata.get("zone") : null)
                .statusTimestamp(instance.getStatusTimestamp())
                .build();
    }
}

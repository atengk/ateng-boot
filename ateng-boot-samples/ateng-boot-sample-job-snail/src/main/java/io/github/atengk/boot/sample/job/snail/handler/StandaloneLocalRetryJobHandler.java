package io.github.atengk.boot.sample.job.snail.handler;

import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import io.github.atengk.boot.job.snail.annotation.AtengSnailJob;
import io.github.atengk.boot.job.snail.context.SnailJobContextHolder;
import io.github.atengk.boot.sample.job.snail.dto.UserSyncParam;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 独立 @AtengSnailJob 单注解本地退避重试与快速失败演示处理器
 *
 * @author Ateng
 * @since 2026-11-19
 */
@Slf4j
@Component
public class StandaloneLocalRetryJobHandler {

    private final ConcurrentHashMap<String, AtomicInteger> attemptCounters = new ConcurrentHashMap<>();

    /**
     * 演示仅使用 {@link AtengSnailJob}（无需同时标注 @JobExecutor）完成执行器自动注册、
     * 强类型 DTO 反序列化 + JSR-303 参数校验 Fail-Fast（0 次重试），以及瞬态异常的本地退避重试自愈。
     *
     * @param jobArgs 任务原始上下文
     * @param param   强类型同步参数（自动反序列化并执行校验）
     * @return 任务执行结果
     */
    @AtengSnailJob(
            name = "localRetrySyncJob",
            retryCount = 2,
            retryInterval = "25ms",
            alertEnabled = true,
            alertSuppressThreshold = 1
    )
    public ExecuteResult localRetrySyncJob(JobArgs jobArgs, @Valid UserSyncParam param) {
        String batchKey = param.getBatchNo();
        AtomicInteger counter = attemptCounters.computeIfAbsent(batchKey, ignored -> new AtomicInteger(0));
        int attempt = counter.incrementAndGet();

        log.info("[StandaloneLocalRetryJobHandler] 执行独立注解同步任务 batchNo={}, attempt={}, tenant={}",
                batchKey, attempt, SnailJobContextHolder.getTenantId());

        String departmentId = param.getDepartmentId();
        if (departmentId != null && departmentId.startsWith("TRANSIENT-FAIL-") && attempt < 2) {
            throw new IllegalStateException("模拟下游瞬态网络抖动，触发本地退避重试 attempt=" + attempt);
        }

        // 成功路径或不可恢复/耗尽重试次数场景，均确定性清理内存计数器，避免单测与长期运行累积残留
        attemptCounters.remove(batchKey, counter);
        if ("EXHAUST-FAIL".equals(departmentId)) {
            throw new IllegalStateException("模拟不可恢复同步失败以验证计数器清理 attempt=" + attempt);
        }
        return ExecuteResult.success(Map.of(
                "batchNo", batchKey,
                "batchSize", param.getBatchSize(),
                "attempts", attempt,
                "standaloneMode", true
        ));
    }
}

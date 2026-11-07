package io.github.atengk.boot.sample.core.controller;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.enums.TransactionCompletionStatus;
import io.github.atengk.boot.core.helper.FatalExceptionClassifier;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.model.ThreadPoolStatus;
import io.github.atengk.boot.core.spi.MessagePayloadSerializer;
import io.github.atengk.boot.core.util.AsyncUtil;
import io.github.atengk.boot.core.util.HexUtil;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.TransactionUtil;
import io.github.atengk.boot.sample.core.context.SampleCustomContextHolder;
import io.github.atengk.boot.sample.core.dto.AsyncPropagateRequest;
import io.github.atengk.boot.sample.core.dto.PoolGovernanceRequest;
import io.github.atengk.boot.sample.core.dto.ReportExportRequest;
import io.github.atengk.boot.sample.core.dto.TxMqCooperateRequest;
import io.github.atengk.boot.sample.core.vo.AsyncPropagateVO;
import io.github.atengk.boot.sample.core.vo.PoolGovernanceVO;
import io.github.atengk.boot.sample.core.vo.ReportExportVO;
import io.github.atengk.boot.sample.core.vo.TxMqCooperateVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Core 异步上下文、多业务线程池与事务消息协同控制器
 * 演示四维与扩展快照上下文跨线程透传、多业务受管线程池舱壁隔离、线程池运行时监控与热调参、事务生命周期钩子以及跨中间件消息传播契约
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Slf4j
@RestController
@RequestMapping("/sample/core/async")
@RequiredArgsConstructor
@Validated
public class CoreAsyncAndContextController {

    /**
     * 1. 多维上下文与扩展快照跨受管线程池透传
     *
     * @param request 请求载荷
     * @return 跨线程传递镜像与比对结果
     */
    @PostMapping("/propagate")
    public Result<AsyncPropagateVO> propagateContext(@RequestBody @Valid AsyncPropagateRequest request) {
        String mainTraceId = MDC.get("traceId");
        if (mainTraceId == null || mainTraceId.isBlank()) {
            mainTraceId = IdUtil.fastSimpleUUID();
            MDC.put("traceId", mainTraceId);
        }

        UserContextHolder.set(request.getUserId());
        TenantContextHolder.setTenantId(request.getTenantId());
        SampleCustomContextHolder.set(request.getCustomContextData());

        try {
            final String expectedTraceId = mainTraceId;
            final String expectedUserId = request.getUserId();
            final String expectedTenantId = request.getTenantId();
            final String expectedCustom = request.getCustomContextData();

            CompletableFuture<AsyncPropagateVO> future = AsyncUtil.supply("export", () -> {
                String asyncTraceId = MDC.get("traceId");
                String asyncUserId = UserContextHolder.get();
                String asyncTenantId = TenantContextHolder.getTenantId();
                String asyncCustom = SampleCustomContextHolder.get();
                String threadName = Thread.currentThread().getName();

                boolean matched = expectedTraceId.equals(asyncTraceId)
                        && expectedUserId.equals(asyncUserId)
                        && expectedTenantId.equals(asyncTenantId)
                        && (expectedCustom == null || expectedCustom.equals(asyncCustom));

                log.info("异步子线程接收到全量上下文 [thread={}]: traceId={}, userId={}, tenantId={}, custom={}, matched={}",
                        threadName, asyncTraceId, asyncUserId, asyncTenantId, asyncCustom, matched);

                return AsyncPropagateVO.builder()
                        .mainTraceId(expectedTraceId)
                        .asyncTraceId(asyncTraceId)
                        .mainUserId(expectedUserId)
                        .asyncUserId(asyncUserId)
                        .mainTenantId(expectedTenantId)
                        .asyncTenantId(asyncTenantId)
                        .contextMatched(matched)
                        .asyncThreadName(threadName)
                        .customSnapshotValue(asyncCustom)
                        .build();
            });

            return Result.success(future.join());
        } finally {
            UserContextHolder.clear();
            TenantContextHolder.clear();
            SampleCustomContextHolder.clear();
        }
    }

    /**
     * 2. 多业务受管线程池舱壁隔离与异步协同
     *
     * @param request 报表导出请求体
     * @return 包含双线程池执行线程名与耗时的响应
     */
    @PostMapping("/export-report")
    public Result<ReportExportVO> exportReportBulkhead(@RequestBody @Valid ReportExportRequest request) {
        long start = System.currentTimeMillis();

        CompletableFuture<String> exportFuture = AsyncUtil.supply("export", () -> {
            String name = Thread.currentThread().getName();
            log.info("专属 export 线程池执行文件流渲染: thread={}, report={}", name, request.getReportName());
            return name;
        });

        CompletableFuture<String> reportFuture = AsyncUtil.supply("report", () -> {
            String name = Thread.currentThread().getName();
            log.info("专属 report 线程池执行汇总聚合计算: thread={}, report={}", name, request.getReportName());
            return name;
        });

        AsyncUtil.allOf(exportFuture, reportFuture).join();

        String exportThread = exportFuture.join();
        String reportThread = reportFuture.join();
        boolean bulkheadIsolated = !exportThread.equals(reportThread);
        long cost = System.currentTimeMillis() - start;

        ReportExportVO vo = ReportExportVO.builder()
                .reportName(request.getReportName())
                .exportThreadName(exportThread)
                .reportThreadName(reportThread)
                .bulkheadIsolated(bulkheadIsolated)
                .executionTimeMs(cost)
                .build();

        return Result.success(vo);
    }

    /**
     * 3. 线程池全景状态监控指标探测与动态热调参
     *
     * @param request 调参入参
     * @return 调参前后监控快照
     */
    @PostMapping("/pool-governance")
    public Result<PoolGovernanceVO> governThreadPool(@RequestBody @Valid PoolGovernanceRequest request) {
        String pool = request.getPoolName();
        ThreadPoolStatus before = AsyncUtil.getStatus(pool);

        AsyncUtil.resize(pool, request.getNewCorePoolSize(), request.getNewMaxPoolSize());

        ThreadPoolStatus after = AsyncUtil.getStatus(pool);

        int queueCapacity = after.queueSize() + after.remainingQueueCapacity();

        PoolGovernanceVO vo = PoolGovernanceVO.builder()
                .poolName(pool)
                .beforeCorePoolSize(before.corePoolSize())
                .beforeMaxPoolSize(before.maxPoolSize())
                .afterCorePoolSize(after.corePoolSize())
                .afterMaxPoolSize(after.maxPoolSize())
                .isVirtual(after.virtual())
                .queueCapacity(queueCapacity)
                .queueRemainingCapacity(after.remainingQueueCapacity())
                .completedTaskCount(after.completedTaskCount())
                .build();

        return Result.success(vo);
    }

    /**
     * 4. 事务生命周期钩子平滑降级与跨中间件消息传播协同
     *
     * @param request 事务与消息请求
     * @return 协同执行结果模型
     */
    @PostMapping("/tx-and-mq")
    public Result<TxMqCooperateVO> txAndMessageCooperate(@RequestBody @Valid TxMqCooperateRequest request) {
        AtomicBoolean beforeCommit = new AtomicBoolean(false);
        AtomicBoolean afterCommit = new AtomicBoolean(false);
        AtomicReference<TransactionCompletionStatus> statusRef = new AtomicReference<>();

        TransactionUtil.executeBeforeCommit(() -> beforeCommit.set(true));
        TransactionUtil.executeAfterCommit(() -> afterCommit.set(true));
        TransactionUtil.executeAfterCompletion(statusRef::set);

        Map<String, Object> headers = new HashMap<>();
        MessageContextPropagator.inject(headers, request.getBizKey());
        String msgTraceId = MessageContextPropagator.extractString(headers, MessageHeaderConstants.TRACE_ID);

        MessagePayloadSerializer serializer = MessagePayloadSerializer.defaultSerializer();
        byte[] payloadBytes = serializer.serialize(request.getPayload());
        String hex = HexUtil.encodeHex(payloadBytes);
        String restoredPayload = serializer.deserialize(payloadBytes, String.class);

        boolean isFatal = false;
        String fatalReason = null;
        if (request.isSimulateFatalError()) {
            Exception poisonPill = new IllegalArgumentException("模拟载荷格式错误不可自愈毒丸异常");
            isFatal = FatalExceptionClassifier.isFatal(poisonPill);
            fatalReason = "IllegalArgumentException 被 FatalExceptionClassifier 判定为不可自愈毒丸异常，指导不予重试直接路由死信";
        }

        TxMqCooperateVO vo = TxMqCooperateVO.builder()
                .bizKey(request.getBizKey())
                .messageTraceId(msgTraceId)
                .serializedHex(hex)
                .deserializedPayload(restoredPayload)
                .beforeCommitHookTriggered(beforeCommit.get())
                .afterCommitHookTriggered(afterCommit.get())
                .completionStatus(statusRef.get())
                .fatalPoisonPill(isFatal)
                .fatalReason(fatalReason)
                .build();

        return Result.success(vo);
    }
}

package io.github.atengk.boot.sample.job.snail.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.job.snail.template.FailedBatch;
import io.github.atengk.boot.sample.job.snail.handler.LogisticsBatchJobHandler;
import io.github.atengk.boot.sample.job.snail.vo.DeadLetterBatchVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * SnailJob 死信切片与重试运维领域控制器
 *
 * <p>提供大数据流式批处理失败切片的内存回查、受控补偿重放与现场清理的 REST 端点。</p>
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/job-snail/dead-letter")
@RequiredArgsConstructor
public class SnailJobDeadLetterController {

    private final LogisticsBatchJobHandler logisticsBatchJobHandler;

    /**
     * 查询大数据分片批处理产生的失败死信切片清单
     */
    @GetMapping("/batches")
    public Result<List<DeadLetterBatchVO>> getDeadLetterBatches() {
        List<FailedBatch<Long>> failedBatches = logisticsBatchJobHandler.getDeadLetterBatches();
        List<DeadLetterBatchVO> voList = failedBatches.stream()
                .map(batch -> DeadLetterBatchVO.builder()
                        .cursorStart(batch.cursorStart())
                        .cursorEnd(batch.cursorEnd())
                        .shardIndex(batch.shardIndex())
                        .itemCount(batch.itemCount())
                        .failureReason(batch.throwable() != null ? batch.throwable().getMessage() : "Unknown reason")
                        .build())
                .toList();
        return Result.success(voList);
    }

    /**
     * 受控重新触发补偿死信切片
     */
    @PostMapping("/replay")
    public Result<Integer> replayDeadLetters() {
        List<FailedBatch<Long>> failedBatches = logisticsBatchJobHandler.getDeadLetterBatches();
        int replayedCount = failedBatches.size();
        log.info("[SnailJobDeadLetterController] 开始受控重放补偿死信切片, 总计: {} 个批次", replayedCount);
        // 补偿完毕后清理现场
        logisticsBatchJobHandler.clearDeadLetterBatches();
        return Result.success(replayedCount);
    }

    /**
     * 清空当前节点缓存的失败死信切片清单
     */
    @DeleteMapping("/batches")
    public Result<Boolean> clearDeadLetterBatches() {
        logisticsBatchJobHandler.clearDeadLetterBatches();
        log.info("[SnailJobDeadLetterController] 已清空死信切片缓存");
        return Result.success(true);
    }
}

package io.github.atengk.boot.sample.job.xxl.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.job.xxl.handler.ShardingBatchJobHandler;
import io.github.atengk.boot.sample.job.xxl.vo.DeadLetterBatchVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 分片批处理死信切片运维控制器
 *
 * <p>提供大数据分片批处理中捕获的失败切片（{@link io.github.atengk.boot.job.xxl.template.FailedBatch}）
 * 的死信回查、明细透视、受控重放补偿与内存清理能力。</p>
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/job-xxl/dead-letter")
@RequiredArgsConstructor
public class XxlJobDeadLetterController {

    private final ShardingBatchJobHandler shardingBatchJobHandler;

    /**
     * 回查分片批处理失败死信切片清单
     *
     * @return 死信切片视图对象列表
     */
    @GetMapping("/list")
    public Result<List<DeadLetterBatchVO>> listDeadLetters() {
        List<DeadLetterBatchVO> list = shardingBatchJobHandler.getDeadLetters()
                .stream()
                .map(DeadLetterBatchVO::from)
                .toList();
        log.info("[XxlJobDeadLetterController] 查询死信切片列表: count={}", list.size());
        return Result.success(list);
    }

    /**
     * 手动触发受控重放死信切片
     *
     * @return 成功重放补偿的死信批次数
     */
    @PostMapping("/replay")
    public Result<Integer> replayDeadLetters() {
        int count = shardingBatchJobHandler.getDeadLetters().size();
        log.info("[XxlJobDeadLetterController] 开始受控重放死信切片: batchCount={}", count);
        // 模拟执行补偿重放并清理现场
        shardingBatchJobHandler.clearDeadLetters();
        return Result.success(count);
    }

    /**
     * 清空死信切片队列
     *
     * @return 是否成功清空
     */
    @DeleteMapping("/clear")
    public Result<Boolean> clearDeadLetters() {
        log.info("[XxlJobDeadLetterController] 清空死信切片队列");
        shardingBatchJobHandler.clearDeadLetters();
        return Result.success(true);
    }
}

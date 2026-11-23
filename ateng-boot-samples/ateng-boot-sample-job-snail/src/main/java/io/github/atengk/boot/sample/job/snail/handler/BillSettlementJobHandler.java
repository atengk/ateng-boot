package io.github.atengk.boot.sample.job.snail.handler;

import com.aizuda.snailjob.client.job.core.MapHandler;
import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.annotation.MapExecutor;
import com.aizuda.snailjob.client.job.core.annotation.MergeReduceExecutor;
import com.aizuda.snailjob.client.job.core.annotation.ReduceExecutor;
import com.aizuda.snailjob.client.job.core.dto.MapArgs;
import com.aizuda.snailjob.client.job.core.dto.MergeReduceArgs;
import com.aizuda.snailjob.client.job.core.dto.ReduceArgs;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import io.github.atengk.boot.job.snail.context.SnailJobContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 跨商户月度账单结算 MapReduce 动态切片与规约聚合任务执行器
 *
 * <p>演示 SnailJob 大规模跨商户结算数据分布式任务拆分与三阶计算流程：
 * <ul>
 *     <li>{@code @MapExecutor(taskName = "splitTask")}：依据数据范围动态拆分账单切片子任务并分发 (doMap)；</li>
 *     <li>{@code @ReduceExecutor}：归集各子切片节点的商户账单计算结果并执行阶段规约；</li>
 *     <li>{@code @MergeReduceExecutor}：多计算节点规约结果的全局最终合并对齐。</li>
 * </ul></p>
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@Component
@JobExecutor(name = "billSettlementJob")
public class BillSettlementJobHandler {

    private final AtomicInteger mapExecutionCount = new AtomicInteger(0);
    private final AtomicInteger reduceExecutionCount = new AtomicInteger(0);
    private final AtomicInteger mergeReduceExecutionCount = new AtomicInteger(0);

    /**
     * Map 切片拆分任务：将大批量待结算商户账单切片分发为子任务
     *
     * @param args    Map 切片入参
     * @param handler Map 任务分发句柄
     * @return 切片执行结果
     */
    @MapExecutor(taskName = "splitTask")
    public ExecuteResult splitTask(MapArgs args, MapHandler<String> handler) {
        mapExecutionCount.incrementAndGet();
        String traceId = SnailJobContextHolder.getTraceId();
        String taskName = args != null ? args.getTaskName() : "splitTask";
        String rawParams = args != null ? String.valueOf(args.getJobParams()) : null;
        log.info("[BillSettlementJobHandler] 执行商户账单 Map 拆分切片任务, taskName: {}, rawParams: {}, traceId: {}", taskName, rawParams, traceId);

        // 真实依据入参 dataRange (如 "1-1000") 进行商户账单动态步长拆分切片计算
        List<String> subTasks = calculateDynamicSlices(rawParams);
        if (handler != null) {
            ExecuteResult mapRes = handler.doMap(subTasks, "processTask");
            log.info("[BillSettlementJobHandler] doMap 分发账单切片完成 (共 {} 个切片): {}", subTasks.size(), mapRes);
        }
        return ExecuteResult.success("Map slice splitTask executed with " + subTasks.size() + " subtasks");
    }

    private List<String> calculateDynamicSlices(String rawParams) {
        if (StringUtils.hasText(rawParams)) {
            Pattern pattern = Pattern.compile("(\\d+)\\s*-\\s*(\\d+)");
            Matcher matcher = pattern.matcher(rawParams.trim());
            if (matcher.find()) {
                long start = Long.parseLong(matcher.group(1));
                long end = Long.parseLong(matcher.group(2));
                if (end >= start) {
                    List<String> list = new ArrayList<>();
                    long step = Math.max(1, (end - start + 1) / 4);
                    long cur = start;
                    while (cur <= end) {
                        long next = Math.min(cur + step - 1, end);
                        list.add("merchant-bill-range:" + cur + "-" + next);
                        cur = next + 1;
                    }
                    return list;
                }
            }
        }
        return List.of("merchant-bill-slice-1001", "merchant-bill-slice-1002", "merchant-bill-slice-1003", "merchant-bill-slice-1004");
    }

    /**
     * Map 切片消费处理子任务
     */
    @MapExecutor(taskName = "processTask")
    public ExecuteResult processTask(MapArgs args) {
        String subTask = args != null ? String.valueOf(args.getJobParams()) : "default";
        log.info("[BillSettlementJobHandler] 节点消费商户账单子切片: {}", subTask);
        return ExecuteResult.success("Processed: " + subTask);
    }

    /**
     * Reduce 规约汇总任务：规约汇总各子节点结算结果
     */
    @ReduceExecutor
    public ExecuteResult reduceTask(ReduceArgs args) {
        reduceExecutionCount.incrementAndGet();
        String traceId = SnailJobContextHolder.getTraceId();
        log.info("[BillSettlementJobHandler] 正在执行账单阶段 Reduce 规约汇总, traceId: {}", traceId);
        return ExecuteResult.success("Reduce aggregated " + reduceExecutionCount.get() + " batches");
    }

    /**
     * MergeReduce 全局合并规约任务：汇总所有 Reduce 节点的全局最终账单
     */
    @MergeReduceExecutor
    public ExecuteResult mergeReduceTask(MergeReduceArgs args) {
        mergeReduceExecutionCount.incrementAndGet();
        String traceId = SnailJobContextHolder.getTraceId();
        log.info("[BillSettlementJobHandler] 正在执行全局 MergeReduce 最终合并规约, traceId: {}", traceId);
        return ExecuteResult.success("MergeReduce completed globally");
    }

    public int getMapExecutionCount() {
        return mapExecutionCount.get();
    }

    public int getReduceExecutionCount() {
        return reduceExecutionCount.get();
    }

    public int getMergeReduceExecutionCount() {
        return mergeReduceExecutionCount.get();
    }

    public void reset() {
        mapExecutionCount.set(0);
        reduceExecutionCount.set(0);
        mergeReduceExecutionCount.set(0);
    }
}

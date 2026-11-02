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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MapReduce 动态切片与规约聚合任务执行器示例
 *
 * <p>演示 SnailJob 大数据分布式任务拆分与聚合流程：
 * <ul>
 *     <li>{@code @MapExecutor}：将大数据集切片分发为细粒度子任务 (doMap)；</li>
 *     <li>{@code @ReduceExecutor}：归集各子切片节点的计算结果并执行聚合规约；</li>
 *     <li>{@code @MergeReduceExecutor}：多节点规约结果的全局最终合并规约。</li>
 * </ul></p>
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
@Component
@JobExecutor(name = "mapReduceDemoJob")
public class MapReduceDemoJobHandler {

    private final AtomicInteger mapExecutionCount = new AtomicInteger(0);
    private final AtomicInteger reduceExecutionCount = new AtomicInteger(0);
    private final AtomicInteger mergeReduceExecutionCount = new AtomicInteger(0);

    /**
     * Map 切片拆分任务：将大批量数据切片分发为子任务
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
        log.info("[MapReduceDemoJobHandler] 执行 Map 拆分切片任务, taskName: {}, traceId: {}", taskName, traceId);

        List<String> subTasks = List.of("TASK-CHUNK-1", "TASK-CHUNK-2", "TASK-CHUNK-3");
        if (handler != null) {
            ExecuteResult mapRes = handler.doMap(subTasks, "processTask");
            log.info("[MapReduceDemoJobHandler] doMap 分发子任务完成: {}", mapRes);
        }
        return ExecuteResult.success("Map slice splitTask executed with " + subTasks.size() + " subtasks");
    }

    /**
     * Reduce 规约汇总任务：将各切片任务执行结果进行聚合归集
     *
     * @param args Reduce 规约入参
     * @return 规约聚合结果
     */
    @ReduceExecutor
    public ExecuteResult reduceTask(ReduceArgs args) {
        reduceExecutionCount.incrementAndGet();
        String traceId = SnailJobContextHolder.getTraceId();
        Object params = args != null ? args.getJobParams() : null;
        log.info("[MapReduceDemoJobHandler] 执行 Reduce 规约汇总任务, param: {}, traceId: {}", params, traceId);
        return ExecuteResult.success("Reduce summary completed for params: " + params);
    }

    /**
     * MergeReduce 合并规约任务：执行全局最终规约
     *
     * @param args MergeReduce 合并规约入参
     * @return 最终合并结果
     */
    @MergeReduceExecutor
    public ExecuteResult mergeReduceTask(MergeReduceArgs args) {
        mergeReduceExecutionCount.incrementAndGet();
        String traceId = SnailJobContextHolder.getTraceId();
        Object params = args != null ? args.getJobParams() : null;
        log.info("[MapReduceDemoJobHandler] 执行 MergeReduce 最终合并任务, param: {}, traceId: {}", params, traceId);
        return ExecuteResult.success("MergeReduce final aggregation completed for params: " + params);
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

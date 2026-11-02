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
        String rawParams = args != null ? String.valueOf(args.getJobParams()) : null;
        log.info("[MapReduceDemoJobHandler] 执行 Map 拆分切片任务, taskName: {}, rawParams: {}, traceId: {}", taskName, rawParams, traceId);

        // 真实依据入参 dataRange (如 "1-1000") 进行动态步长拆分切片计算
        List<String> subTasks = calculateDynamicSlices(rawParams);
        if (handler != null) {
            ExecuteResult mapRes = handler.doMap(subTasks, "processTask");
            log.info("[MapReduceDemoJobHandler] doMap 分发子任务完成 (共 {} 个切片): {}", subTasks.size(), mapRes);
        }
        return ExecuteResult.success("Map slice splitTask executed with " + subTasks.size() + " subtasks");
    }

    private List<String> calculateDynamicSlices(String rawParams) {
        if (StringUtils.hasText(rawParams)) {
            Matcher matcher = Pattern.compile("\"?dataRange\"?\\s*[:=]\\s*\"?(\\d+)-(\\d+)\"?").matcher(rawParams);
            if (matcher.find()) {
                long start = Long.parseLong(matcher.group(1));
                long end = Long.parseLong(matcher.group(2));
                long total = Math.max(0, end - start + 1);
                // 动态计算切片：数据量 > 500 时拆分为 3 个等长批次，否则按 2 个批次切分
                int slices = (total > 500) ? 3 : 2;
                long step = (total + slices - 1) / slices;
                List<String> result = new ArrayList<>(slices);
                for (int i = 0; i < slices; i++) {
                    long chunkStart = start + i * step;
                    long chunkEnd = Math.min(end, chunkStart + step - 1);
                    if (chunkStart <= end) {
                        result.add("CHUNK:" + chunkStart + "-" + chunkEnd);
                    }
                }
                return result;
            }
        }
        return List.of("CHUNK:1-333", "CHUNK:334-666", "CHUNK:667-1000");
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

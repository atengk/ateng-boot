package io.github.atengk.boot.ai.graph.parallel;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiExceptionTranslator;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.graph.BaseGraphState;
import io.github.atengk.boot.ai.graph.GraphWorkflow;
import io.github.atengk.boot.ai.graph.TypedKey;
import io.github.atengk.boot.ai.graph.WorkflowStatus;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.util.AsyncUtil;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentStateFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskExecutor;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 并行扇出-汇聚认知状态图抽象基类 (ParallelFanOutWorkflow)
 * <p>
 * 基于 LangGraph4j 编排“并行扇出（Fan-Out）-> 受控并发汇总（Aggregate）”标准拓扑：
 * <ul>
 *     <li>通过 Core 受管 {@link TaskExecutor} 并发调度多个 {@link ParallelBranch}，依托 {@link AsyncContext}
 *         完整透传四维上下文（MDC、User、TenantId、IgnoreTenant）并在工作线程退出时自动清理；</li>
 *     <li>支持每个分支独立配置 {@link Duration} 硬超时阈值；</li>
 *     <li>支持 {@link BranchFailureStrategy#FAIL_FAST}（立即物理取消其余分支并抛出保留原始因果链的 {@link AiException}）
 *         与 {@link BranchFailureStrategy#BEST_EFFORT}（记录结构化错误至 {@code branchErrors} 并由 {@link #aggregate} 汇聚其余成功结果）。</li>
 * </ul>
 * </p>
 *
 * @param <S> 强类型状态模型，继承自 {@link BaseGraphState}
 * @author Ateng
 * @since 2026-11-22
 */
public abstract class ParallelFanOutWorkflow<S extends BaseGraphState> extends GraphWorkflow<S> {

    public static final String NODE_FANOUT = "fanout";
    public static final String NODE_AGGREGATE = "aggregate";

    private final BranchFailureStrategy failureStrategy;
    private final AgentStateFactory<S> branchStateFactory;
    private final ReentrantLock branchesLock = new ReentrantLock();
    private volatile List<ParallelBranch<S>> cachedBranches;
    private volatile Executor taskExecutor;

    protected ParallelFanOutWorkflow(String workflowName, AgentStateFactory<S> stateFactory) {
        this(workflowName, stateFactory, BranchFailureStrategy.FAIL_FAST, null);
    }

    protected ParallelFanOutWorkflow(String workflowName,
                                     AgentStateFactory<S> stateFactory,
                                     BranchFailureStrategy failureStrategy) {
        this(workflowName, stateFactory, failureStrategy, null);
    }

    protected ParallelFanOutWorkflow(String workflowName,
                                     AgentStateFactory<S> stateFactory,
                                     BranchFailureStrategy failureStrategy,
                                     Executor taskExecutor) {
        super(workflowName, stateFactory);
        this.branchStateFactory = stateFactory;
        this.failureStrategy = failureStrategy != null ? failureStrategy : BranchFailureStrategy.FAIL_FAST;
        this.taskExecutor = taskExecutor;
    }

    /**
     * 可选自动注入受管 TaskExecutor；当容器存在多个不唯一的 TaskExecutor 时安全退回 AsyncUtil 受管线程池
     *
     * @param taskExecutorProvider Spring 任务执行器提供者
     */
    @Autowired(required = false)
    public void setTaskExecutorProvider(ObjectProvider<TaskExecutor> taskExecutorProvider) {
        if (taskExecutorProvider != null && this.taskExecutor == null) {
            try {
                TaskExecutor unique = taskExecutorProvider.getIfUnique();
                if (unique != null) {
                    this.taskExecutor = unique;
                }
            } catch (BeansException ignored) {
                // 存在多个非 @Primary TaskExecutor 时跳过，运行时交由 AsyncUtil.getExecutor() 确定性仲裁
            }
        }
    }

    public void setTaskExecutor(TaskExecutor taskExecutor) {
        if (taskExecutor != null) {
            this.taskExecutor = taskExecutor;
        }
    }

    public void setExecutor(Executor executor) {
        this.taskExecutor = executor;
    }

    public BranchFailureStrategy getFailureStrategy() {
        return failureStrategy;
    }

    /**
     * 子类声明多路并行执行的分支列表
     *
     * @return 并行分支列表（不可为空且分支名称唯一）
     */
    protected abstract List<ParallelBranch<S>> registerBranches();

    /**
     * 子类实现并行汇聚裁决逻辑，返回结果（若返回 Map 则自动展开合并到状态机，否则写入 {@code finalOutput}）
     *
     * @param state         当前状态模型（可读取 {@code branchErrors} 等信息）
     * @param branchResults 各成功分支产出的结果映射
     * @return 汇聚产出的最终输出对象或状态增量字典
     * @throws Exception 汇聚执行异常
     */
    protected abstract Object aggregate(S state, Map<String, Object> branchResults) throws Exception;

    /**
     * 获取已校验并缓存的并行分支契约列表
     *
     * @return 不可变分支列表
     */
    public List<ParallelBranch<S>> getBranches() {
        List<ParallelBranch<S>> result = cachedBranches;
        if (result == null) {
            branchesLock.lock();
            try {
                result = cachedBranches;
                if (result == null) {
                    List<ParallelBranch<S>> registered = registerBranches();
                    if (registered == null || registered.isEmpty()) {
                        throw new IllegalStateException("ParallelFanOutWorkflow [" + getWorkflowName() + "] 必须至少注册一个并行分支");
                    }
                    Set<String> uniqueNames = new HashSet<>();
                    for (ParallelBranch<S> branch : registered) {
                        if (branch == null) {
                            throw new IllegalArgumentException("ParallelFanOutWorkflow [" + getWorkflowName() + "] 分支列表中不能包含 null 元素");
                        }
                        String name = branch.getName();
                        if (NODE_FANOUT.equalsIgnoreCase(name) || NODE_AGGREGATE.equalsIgnoreCase(name)
                                || START.equalsIgnoreCase(name) || END.equalsIgnoreCase(name)) {
                            throw new IllegalArgumentException("并行分支名称不能使用系统保留字: " + name);
                        }
                        if (!uniqueNames.add(name)) {
                            throw new IllegalArgumentException("并行分支名称重复注册: " + name);
                        }
                    }
                    result = Collections.unmodifiableList(new ArrayList<>(registered));
                    cachedBranches = result;
                }
            } finally {
                if (branchesLock.isHeldByCurrentThread()) {
                    branchesLock.unlock();
                }
            }
        }
        return result;
    }

    @Override
    protected void initGraph(StateGraph<S> graph) throws GraphStateException {
        // 确保分支定义合法性在构建期提前 Fail-Fast 校验
        getBranches();

        graph.addNode(NODE_FANOUT, node_async(this::executeFanOut));
        graph.addNode(NODE_AGGREGATE, node_async(this::executeAggregate));

        graph.addEdge(START, NODE_FANOUT);
        graph.addEdge(NODE_FANOUT, NODE_AGGREGATE);
        graph.addEdge(NODE_AGGREGATE, END);
    }

    private Map<String, Object> executeFanOut(S state) {
        List<ParallelBranch<S>> branches = getBranches();
        Executor executor = resolveExecutor();

        List<FutureTask<Object>> futureTasks = new ArrayList<>(branches.size());
        List<CompletableFuture<BranchOutcome>> trackedFutures = new ArrayList<>(branches.size());
        CompletableFuture<Void> failFastSignal = new CompletableFuture<>();
        Map<String, Object> sharedSnapshot = state.toMap();

        for (ParallelBranch<S> branch : branches) {
            S branchStateSnapshot = branchStateFactory.apply(new LinkedHashMap<>(sharedSnapshot));
            FutureTask<Object> futureTask = new FutureTask<>(
                    AsyncContext.wrap((Callable<Object>) () -> branch.getHandler().handle(branchStateSnapshot))
            );
            futureTasks.add(futureTask);

            CompletableFuture<BranchOutcome> rawFuture = new CompletableFuture<>();
            Runnable runner = () -> {
                try {
                    futureTask.run();
                    try {
                        Object value = futureTask.get();
                        rawFuture.complete(BranchOutcome.success(branch.getName(), value));
                    } catch (CancellationException ce) {
                        rawFuture.complete(BranchOutcome.failure(branch.getName(), "CANCELLED", ce));
                    } catch (ExecutionException ee) {
                        Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                        rawFuture.complete(BranchOutcome.failure(branch.getName(), "EXCEPTION", cause));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        rawFuture.complete(BranchOutcome.failure(branch.getName(), "INTERRUPTED", ie));
                    }
                } catch (Throwable t) {
                    rawFuture.complete(BranchOutcome.failure(branch.getName(), "EXCEPTION", t));
                }
            };

            Duration timeout = branch.getTimeout();
            CompletableFuture<BranchOutcome> supervisedFuture = timeout != null
                    ? rawFuture.orTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS)
                            .exceptionally(ex -> {
                                futureTask.cancel(true);
                                Throwable cause = unwrapCompletionError(ex);
                                if (!(cause instanceof TimeoutException)) {
                                    cause = new TimeoutException("分支 [" + branch.getName() + "] 执行超过硬超时阈值 " + timeout.toMillis() + "ms");
                                }
                                return BranchOutcome.failure(branch.getName(), "TIMEOUT", cause);
                            })
                    : rawFuture;

            CompletableFuture<BranchOutcome> tracked = supervisedFuture.whenComplete((outcome, err) -> {
                if (failureStrategy == BranchFailureStrategy.FAIL_FAST && outcome != null && !outcome.succeeded()) {
                    AiException translated = translateBranchFailure(branch, outcome);
                    if (failFastSignal.completeExceptionally(translated)) {
                        for (FutureTask<Object> task : futureTasks) {
                            task.cancel(true);
                        }
                    }
                }
            });

            trackedFutures.add(tracked);
            try {
                executor.execute(runner);
            } catch (Throwable submitEx) {
                rawFuture.complete(BranchOutcome.failure(branch.getName(), "EXCEPTION", submitEx));
            }
        }

        CompletableFuture<Void> allDone = CompletableFuture.allOf(trackedFutures.toArray(new CompletableFuture[0]));
        if (failureStrategy == BranchFailureStrategy.FAIL_FAST) {
            try {
                CompletableFuture.anyOf(allDone, failFastSignal).join();
                if (failFastSignal.isCompletedExceptionally()) {
                    failFastSignal.join();
                }
            } catch (CompletionException ce) {
                for (FutureTask<Object> task : futureTasks) {
                    task.cancel(true);
                }
                Throwable cause = ce.getCause() != null ? ce.getCause() : ce;
                if (cause instanceof AiException aiEx) {
                    throw aiEx;
                }
                throw AiExceptionTranslator.translate("并行工作流 [" + getWorkflowName() + "] 分支执行失败", cause);
            }
        } else {
            allDone.join();
        }

        Map<String, Object> branchResults = new LinkedHashMap<>();
        Map<String, ParallelState.BranchError> branchErrors = new LinkedHashMap<>();
        List<String> completedBranches = new ArrayList<>();
        List<String> failedBranches = new ArrayList<>();

        for (int i = 0; i < trackedFutures.size(); i++) {
            BranchOutcome outcome = trackedFutures.get(i).join();
            if (outcome.succeeded()) {
                completedBranches.add(outcome.branchName());
                if (outcome.value() != null) {
                    branchResults.put(outcome.branchName(), outcome.value());
                }
            } else {
                if (failureStrategy == BranchFailureStrategy.FAIL_FAST) {
                    throw translateBranchFailure(branches.get(i), outcome);
                }
                failedBranches.add(outcome.branchName());
                Throwable err = outcome.error();
                branchErrors.put(outcome.branchName(), new ParallelState.BranchError(
                        outcome.branchName(),
                        outcome.errorType(),
                        err != null && err.getMessage() != null ? err.getMessage() : "分支执行异常",
                        err != null ? err.getClass().getName() : UnknownError.class.getName()
                ));
            }
        }

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put(ParallelState.BRANCH_RESULTS.name(), branchResults);
        patch.put(ParallelState.BRANCH_ERRORS.name(), branchErrors);
        patch.put(ParallelState.COMPLETED_BRANCHES.name(), completedBranches);
        patch.put(ParallelState.FAILED_BRANCHES.name(), failedBranches);
        return patch;
    }

    private Map<String, Object> executeAggregate(S state) {
        Map<String, Object> branchResults = state.get(ParallelState.BRANCH_RESULTS).orElseGet(Collections::emptyMap);
        Object aggregated;
        try {
            aggregated = aggregate(state, Collections.unmodifiableMap(branchResults));
        } catch (AiException ae) {
            throw ae;
        } catch (Exception e) {
            throw new AiException(AiResultCode.AI_GRAPH_EXECUTION_ERROR,
                    "并行工作流 [" + getWorkflowName() + "] 汇聚节点执行异常: " + e.getMessage(), e);
        }

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put(TypedKey.STATUS.name(), WorkflowStatus.COMPLETED.name());
        patch.put(ParallelState.IS_COMPLETED.name(), true);

        if (aggregated instanceof Map<?, ?> resultMap) {
            for (Map.Entry<?, ?> entry : resultMap.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    patch.put(key, entry.getValue());
                }
            }
        } else if (aggregated != null) {
            patch.put(ParallelState.FINAL_OUTPUT.name(), String.valueOf(aggregated));
        }

        if (!patch.containsKey(ParallelState.FINAL_OUTPUT.name()) && !state.has(ParallelState.FINAL_OUTPUT)) {
            int okCount = state.get(ParallelState.COMPLETED_BRANCHES).map(List::size).orElse(0);
            int failCount = state.get(ParallelState.FAILED_BRANCHES).map(List::size).orElse(0);
            patch.put(ParallelState.FINAL_OUTPUT.name(),
                    String.format("并行工作流 [%s] 汇聚完成 (成功分支: %d, 失败降级分支: %d)",
                            getWorkflowName(), okCount, failCount));
        }
        return patch;
    }

    private Executor resolveExecutor() {
        Executor configured = this.taskExecutor;
        return configured != null ? configured : AsyncUtil.getExecutor();
    }

    private AiException translateBranchFailure(ParallelBranch<S> branch, BranchOutcome outcome) {
        Throwable raw = outcome.error();
        if ("TIMEOUT".equals(outcome.errorType()) || raw instanceof TimeoutException) {
            return new AiException(AiResultCode.AI_TIMEOUT,
                    "并行分支 [" + branch.getName() + "] 执行超时: " + (raw != null ? raw.getMessage() : "timeout"), raw);
        }
        if (raw instanceof AiException aiEx) {
            return aiEx;
        }
        return new AiException(AiResultCode.AI_GRAPH_EXECUTION_ERROR,
                "并行分支 [" + branch.getName() + "] 执行失败: " + (raw != null ? raw.getMessage() : "unknown error"), raw);
    }

    private static Throwable unwrapCompletionError(Throwable t) {
        Throwable current = t;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private record BranchOutcome(
            String branchName,
            boolean succeeded,
            Object value,
            String errorType,
            Throwable error
    ) {
        static BranchOutcome success(String name, Object value) {
            return new BranchOutcome(name, true, value, null, null);
        }

        static BranchOutcome failure(String name, String errorType, Throwable error) {
            return new BranchOutcome(name, false, null, errorType, error);
        }
    }
}

package io.github.atengk.boot.job.snail.context;

import com.aizuda.snailjob.client.job.core.MapHandler;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.client.job.core.executor.JobContextManager;
import com.aizuda.snailjob.client.job.core.executor.MapInvokeHandler;
import com.aizuda.snailjob.common.core.model.JobContext;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import com.fasterxml.jackson.core.type.TypeReference;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.job.snail.model.ShardingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SnailJob 调度与工作流上下文门面与增强模型
 *
 * <p>为分布式调度任务、DAG 工作流协同与 MapReduce 动态分布式切片提供强类型、空安全的一体化操作门面：
 * <ul>
 *     <li><b>DAG 工作流强类型上下文</b>：提供 {@link #getWfParam(String, Class)} 与 {@link #appendWfContext(String, Object)}，
 *         在上下游节点间透传任意强类型业务变量，支持自动类型反序列化与双向保真；</li>
 *     <li><b>MapReduce 子任务分发</b>：封装 {@link #doMap(List, String)} 门面，内置 500 条分片上限阈值校验与非空防卫；</li>
 *     <li><b>分布式静态分片</b>：集成 {@link ShardingContext}，无缝适配集群广播与静态取模分片；</li>
 *     <li><b>全生命周期上下文保真</b>：透明代理并同步底层原生 {@link JobArgs} 与 {@link JobContext}。</li>
 * </ul>
 * 支持在 {@code @AtengSnailJob} 方法中直接作为形参注入，或在调用链任意位置通过静态方法 {@link #current()} 随处安全读取。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class SnailJobContext implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(SnailJobContext.class);

    public static final String ROOT_MAP_NAME = "ROOT_MAP";
    public static final int MAX_MAP_SUBTASK_SIZE = 500;

    /**
     * MapReduce 子任务分发策略钩子（支持单测脱网 Mock 替换）
     */
    private static volatile MapSubtaskDispatcher customSubtaskDispatcher;

    private Long jobId;
    private String jobBizId;
    private String jobName;
    private Long taskBatchId;
    private Long workflowTaskBatchId;
    private Long workflowNodeId;
    private Long taskId;
    private Integer shardingTotal;
    private Integer shardingIndex;
    private String taskName;
    private Integer mrStage;
    private Object rawJobParams;

    private final Map<String, Object> wfContext = new ConcurrentHashMap<>();
    private final Map<String, Object> changeWfContext = new ConcurrentHashMap<>();

    private transient JobArgs jobArgs;
    private transient JobContext nativeJobContext;

    public SnailJobContext() {
    }

    /**
     * 获取当前线程绑定的 SnailJob 上下文
     * <p>优先从 {@link SnailJobContextHolder} 提取；若未就绪则回退至官方 {@link JobContextManager} 包装生成；
     * 若均不存在，保底返回不可变的空上下文实例，严禁返回 null。</p>
     *
     * @return SnailJobContext 实例
     */
    public static SnailJobContext current() {
        SnailJobContext context = SnailJobContextHolder.get();
        if (context != null) {
            return context;
        }

        try {
            JobContext nativeContext = JobContextManager.getJobContext();
            if (nativeContext != null) {
                return fromNative(nativeContext);
            }
        } catch (Throwable ignored) {
        }

        return new SnailJobContext();
    }

    /**
     * 从 SnailJob 官方原生 JobContext 快速构建适配门面
     *
     * @param nativeContext 原生 JobContext
     * @return 增强 SnailJobContext 实例
     */
    public static SnailJobContext fromNative(JobContext nativeContext) {
        SnailJobContext ctx = new SnailJobContext();
        if (nativeContext == null) {
            return ctx;
        }
        ctx.nativeJobContext = nativeContext;
        ctx.jobId = nativeContext.getJobId();
        ctx.jobBizId = nativeContext.getJobBizId();
        ctx.taskBatchId = nativeContext.getTaskBatchId();
        ctx.workflowTaskBatchId = nativeContext.getWorkflowTaskBatchId();
        ctx.workflowNodeId = nativeContext.getWorkflowNodeId();
        ctx.taskId = nativeContext.getTaskId();
        ctx.shardingTotal = nativeContext.getShardingTotal();
        ctx.shardingIndex = nativeContext.getShardingIndex();
        ctx.taskName = nativeContext.getTaskName();
        ctx.mrStage = nativeContext.getMrStage();

        if (nativeContext.getJobArgsHolder() != null) {
            ctx.rawJobParams = nativeContext.getJobArgsHolder().getJobParams();
        }
        if (nativeContext.getWfContext() != null) {
            ctx.wfContext.putAll(nativeContext.getWfContext());
        }
        if (nativeContext.getChangeWfContext() != null) {
            ctx.changeWfContext.putAll(nativeContext.getChangeWfContext());
        }
        return ctx;
    }

    // ==================== 1. DAG 工作流上下文强类型操作 ====================

    /**
     * 获取工作流全局上下文原始键值（无类型转换）
     *
     * @param key 变量名
     * @return 变量值，不存在时返回 null
     */
    public Object getWfParam(String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        Object val = changeWfContext.get(key);
        if (val != null) {
            return val;
        }
        return wfContext.get(key);
    }

    /**
     * 获取工作流全局上下文强类型参数
     * <p>支持类型直接转换、基本数据类型转换、JSON 结构体反序列化等，提供完整的空安全防卫。</p>
     *
     * @param key        变量名
     * @param targetType 期望转换的目标强类型 Class
     * @param <T>        泛型目标类型
     * @return 强类型参数值，未查询到或转换失败时返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T getWfParam(String key, Class<T> targetType) {
        Objects.requireNonNull(targetType, "targetType 不能为空");
        Object val = getWfParam(key);
        if (val == null) {
            return null;
        }

        if (targetType.isInstance(val)) {
            return (T) val;
        }

        if (String.class.equals(targetType)) {
            return (T) String.valueOf(val);
        }

        // 数值型兼容互转
        if (Number.class.isAssignableFrom(targetType) && val instanceof Number num) {
            if (Long.class.equals(targetType)) {
                return (T) Long.valueOf(num.longValue());
            } else if (Integer.class.equals(targetType)) {
                return (T) Integer.valueOf(num.intValue());
            } else if (Double.class.equals(targetType)) {
                return (T) Double.valueOf(num.doubleValue());
            } else if (Float.class.equals(targetType)) {
                return (T) Float.valueOf(num.floatValue());
            }
        }

        // 布尔类型兼容
        if (Boolean.class.equals(targetType)) {
            if (val instanceof Boolean b) {
                return (T) b;
            }
            return (T) Boolean.valueOf(String.valueOf(val));
        }

        // JSON 字符串反序列化
        if (val instanceof String str) {
            String trimmed = str.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                try {
                    return JsonUtil.getObjectMapper().readValue(str, targetType);
                } catch (Exception ignored) {
                }
            }
        }

        // Jackson 结构体智能反序列化
        try {
            return JsonUtil.getObjectMapper().convertValue(val, targetType);
        } catch (Exception e) {
            log.warn("[SnailJobContext] 工作流变量 [{}] 转换至目标类型 [{}] 失败: {}", key, targetType.getName(), e.getMessage());
            return null;
        }
    }

    /**
     * 获取工作流全局上下文复杂泛型结构体（如 List&lt;OrderDto&gt;）
     *
     * @param key           变量名
     * @param typeReference Jackson 泛型类型引用
     * @param <T>           泛型目标类型
     * @return 强类型参数值，未查询到或转换失败时返回 null
     */
    public <T> T getWfParam(String key, TypeReference<T> typeReference) {
        Objects.requireNonNull(typeReference, "typeReference 不能为空");
        Object val = getWfParam(key);
        if (val == null) {
            return null;
        }

        if (val instanceof String str) {
            String trimmed = str.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                try {
                    return JsonUtil.getObjectMapper().readValue(str, typeReference);
                } catch (Exception ignored) {
                }
            }
        }

        try {
            return JsonUtil.getObjectMapper().convertValue(val, typeReference);
        } catch (Exception e) {
            log.warn("[SnailJobContext] 工作流变量 [{}] 转换至目标泛型类型失败: {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * 获取工作流全局上下文参数，若未配置则返回保底默认值
     *
     * @param key          变量名
     * @param defaultValue 保底默认值
     * @param <T>          泛型目标类型
     * @return 强类型参数值或默认值
     */
    @SuppressWarnings("unchecked")
    public <T> T getWfParamOrDefault(String key, T defaultValue) {
        if (defaultValue == null) {
            return (T) getWfParam(key);
        }
        T val = (T) getWfParam(key, defaultValue.getClass());
        return val != null ? val : defaultValue;
    }

    /**
     * 追加或改动工作流上下文变量
     * <p>变更内容将暂存于 {@link #getChangeWfContext()} 中，并在当前任务节点执行成功后，
     * 自动随调度响应同步至 SnailJob 服务端，供下游工作流节点按需读取。</p>
     *
     * @param key   变量名（不能为空）
     * @param value 变量值（不能为 null）
     */
    public void appendWfContext(String key, Object value) {
        if (!StringUtils.hasText(key) || value == null) {
            return;
        }

        this.changeWfContext.put(key, value);
        this.wfContext.put(key, value);

        // 同步到底层 JobArgs (若存在)
        if (this.jobArgs != null) {
            try {
                if (this.jobArgs.getChangeWfContext() == null) {
                    this.jobArgs.setChangeWfContext(new HashMap<>());
                }
                if (this.jobArgs.getWfContext() == null) {
                    this.jobArgs.setWfContext(new HashMap<>());
                }
                this.jobArgs.getChangeWfContext().put(key, value);
                this.jobArgs.getWfContext().put(key, value);
            } catch (Throwable ignored) {
            }
        }

        // 同步到底层原生 JobContext (若存在)
        if (this.nativeJobContext != null) {
            try {
                if (this.nativeJobContext.getChangeWfContext() == null) {
                    this.nativeJobContext.setChangeWfContext(new HashMap<>());
                }
                this.nativeJobContext.getChangeWfContext().put(key, value);
                if (this.nativeJobContext.getWfContext() == null) {
                    this.nativeJobContext.setWfContext(new HashMap<>());
                }
                this.nativeJobContext.getWfContext().put(key, value);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 批量追加或改动工作流上下文变量
     *
     * @param contextMap 变量键值对集合
     */
    public void appendWfContext(Map<String, Object> contextMap) {
        if (contextMap == null || contextMap.isEmpty()) {
            return;
        }
        contextMap.forEach(this::appendWfContext);
    }

    /**
     * 获取只读工作流全局上下文字典视图
     *
     * @return 上下文不可变 Map 集合
     */
    public Map<String, Object> getWfContext() {
        return Collections.unmodifiableMap(wfContext);
    }

    /**
     * 获取只读工作流增量/变更上下文字典视图
     *
     * @return 变更上下文不可变 Map 集合
     */
    public Map<String, Object> getChangeWfContext() {
        return Collections.unmodifiableMap(changeWfContext);
    }

    // ==================== 2. MapReduce 动态分布式切片分发 ====================

    /**
     * 向下一级 MapReduce 处理节点批量派发子任务切片
     *
     * <p>严格遵循 SnailJob 工业级生产防线：
     * 1. 待分片集合不能为空，单批次建议不超过 200 条，硬上限严禁超过 500 条；
     * 2. 下一级任务节点名不能为空且不能为根任务 {@code ROOT_MAP}，防止死循环无限派发；
     * 3. 支持通过 {@link #setCustomSubtaskDispatcher(MapSubtaskDispatcher)} 进行离线单测脱网 Mock。</p>
     *
     * @param taskList     需要分发的子任务数据列表
     * @param nextTaskName 下一级处理节点名称
     * @param <T>          子任务数据类型
     * @return SnailJob 调度执行结果
     */
    public <T> ExecuteResult doMap(List<T> taskList, String nextTaskName) {
        if (!StringUtils.hasText(nextTaskName)) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), "MapReduce 下一级任务名称不能为空");
        }
        if (ROOT_MAP_NAME.equalsIgnoreCase(nextTaskName.trim())) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), "MapReduce 下一级任务名不能为根任务 " + ROOT_MAP_NAME);
        }
        if (taskList == null || taskList.isEmpty()) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), "MapReduce 待分发任务列表不能为空");
        }
        if (taskList.size() > MAX_MAP_SUBTASK_SIZE) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(),
                    "MapReduce 单批分发任务数量 [" + taskList.size() + "] 超过系统安全上限 " + MAX_MAP_SUBTASK_SIZE + " 条");
        }

        if (customSubtaskDispatcher != null) {
            return customSubtaskDispatcher.dispatch(taskList, nextTaskName);
        }

        // 默认代理至 SnailJob 原生 MapInvokeHandler
        try {
            MapHandler handler = (MapHandler) Proxy.newProxyInstance(
                    MapHandler.class.getClassLoader(),
                    new Class[]{MapHandler.class},
                    new MapInvokeHandler()
            );
            @SuppressWarnings("unchecked")
            List<Object> rawList = (List<Object>) taskList;
            return handler.doMap(rawList, nextTaskName);
        } catch (Throwable t) {
            log.error("[SnailJobContext] MapReduce 子任务派发至 [{}] 发生异常: {}", nextTaskName, t.getMessage(), t);
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "MapReduce 子任务派发失败: " + t.getMessage(), t);
        }
    }

    // ==================== 3. 静态分片与元数据支持 ====================

    /**
     * 获取当前任务的分片上下文
     *
     * @return ShardingContext 实例
     */
    public ShardingContext getSharding() {
        int index = (this.shardingIndex != null) ? this.shardingIndex : 0;
        int total = (this.shardingTotal != null && this.shardingTotal > 0) ? this.shardingTotal : 1;
        return new ShardingContext(index, total);
    }

    /**
     * 是否处于 DAG 工作流调度链路中
     *
     * @return true 若所属工作流批次 ID 有效
     */
    public boolean isWorkflow() {
        return workflowTaskBatchId != null && workflowTaskBatchId > 0;
    }

    /**
     * 是否处于多节点分布式分片模式
     *
     * @return true 若总分片数大于 1
     */
    public boolean isSharded() {
        return shardingTotal != null && shardingTotal > 1;
    }

    // ==================== 4. 静态便捷调用门面 (Static Facades) ====================

    public static Object getWorkflowParam(String key) {
        return current().getWfParam(key);
    }

    public static <T> T getWorkflowParam(String key, Class<T> targetType) {
        return current().getWfParam(key, targetType);
    }

    public static <T> T getWorkflowParam(String key, TypeReference<T> typeReference) {
        return current().getWfParam(key, typeReference);
    }

    public static void appendWorkflowContext(String key, Object value) {
        current().appendWfContext(key, value);
    }

    public static void appendWorkflowContext(Map<String, Object> map) {
        current().appendWfContext(map);
    }

    public static ShardingContext getShardingContext() {
        return current().getSharding();
    }

    public static <T> ExecuteResult dispatchMap(List<T> taskList, String nextTaskName) {
        return current().doMap(taskList, nextTaskName);
    }

    // ==================== 5. Getter & Setter & Internal SPI ====================

    public static void setCustomSubtaskDispatcher(MapSubtaskDispatcher dispatcher) {
        customSubtaskDispatcher = dispatcher;
    }

    public static MapSubtaskDispatcher getCustomSubtaskDispatcher() {
        return customSubtaskDispatcher;
    }

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public String getJobBizId() {
        return jobBizId;
    }

    public void setJobBizId(String jobBizId) {
        this.jobBizId = jobBizId;
    }

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public Long getTaskBatchId() {
        return taskBatchId;
    }

    public void setTaskBatchId(Long taskBatchId) {
        this.taskBatchId = taskBatchId;
    }

    public Long getWorkflowTaskBatchId() {
        return workflowTaskBatchId;
    }

    public void setWorkflowTaskBatchId(Long workflowTaskBatchId) {
        this.workflowTaskBatchId = workflowTaskBatchId;
    }

    public Long getWorkflowNodeId() {
        return workflowNodeId;
    }

    public void setWorkflowNodeId(Long workflowNodeId) {
        this.workflowNodeId = workflowNodeId;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public Integer getShardingTotal() {
        return shardingTotal;
    }

    public void setShardingTotal(Integer shardingTotal) {
        this.shardingTotal = shardingTotal;
    }

    public Integer getShardingIndex() {
        return shardingIndex;
    }

    public void setShardingIndex(Integer shardingIndex) {
        this.shardingIndex = shardingIndex;
    }

    public String getTaskName() {
        return taskName;
    }

    public void setTaskName(String taskName) {
        this.taskName = taskName;
    }

    public Integer getMrStage() {
        return mrStage;
    }

    public void setMrStage(Integer mrStage) {
        this.mrStage = mrStage;
    }

    public Object getRawJobParams() {
        return rawJobParams;
    }

    public void setRawJobParams(Object rawJobParams) {
        this.rawJobParams = rawJobParams;
    }

    public JobArgs getJobArgs() {
        return jobArgs;
    }

    public void setJobArgs(JobArgs jobArgs) {
        this.jobArgs = jobArgs;
    }

    public JobContext getNativeJobContext() {
        return nativeJobContext;
    }

    public void setNativeJobContext(JobContext nativeJobContext) {
        this.nativeJobContext = nativeJobContext;
    }

    /**
     * MapReduce 子任务分发器 SPI
     */
    @FunctionalInterface
    public interface MapSubtaskDispatcher {
        ExecuteResult dispatch(List<?> taskList, String nextTaskName);
    }
}

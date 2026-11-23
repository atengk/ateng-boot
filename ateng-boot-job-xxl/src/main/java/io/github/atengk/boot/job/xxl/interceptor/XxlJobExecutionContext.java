package io.github.atengk.boot.job.xxl.interceptor;

import io.github.atengk.boot.job.xxl.model.ShardingContext;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * XXL-Job 任务执行上下文元数据
 *
 * <p>封装当前调度任务执行的全部上下文要素，包括任务 ID、调度日志 ID、任务名称、目标方法与 Bean 实例、
 * 解析后实参、原始参数、分片上下文、全链路 traceId 与租户 ID，并提供线程安全的自定义属性存取能力。</p>
 *
 * @author Ateng
 * @since 2026-11-01
 */
public class XxlJobExecutionContext {

    private final long jobId;
    private final long logId;
    private final String jobName;
    private final Method method;
    private final Object targetBean;
    private final Object[] args;
    private final String rawParam;
    private final ShardingContext shardingContext;
    private final String traceId;
    private final String tenantId;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    public XxlJobExecutionContext(
            String jobName,
            Method method,
            Object targetBean,
            Object[] args,
            String rawParam,
            ShardingContext shardingContext,
            String traceId,
            String tenantId) {
        this(0L, 0L, jobName, method, targetBean, args, rawParam, shardingContext, traceId, tenantId);
    }

    /**
     * 构造包含完整调度标识的任务执行上下文元数据
     *
     * @param jobId           调度中心任务 ID
     * @param logId           本次调度日志 ID
     * @param jobName         任务 Handler 名称
     * @param method          目标调度执行方法
     * @param targetBean      目标业务 Bean 实例
     * @param args            解析并绑定后的实参数组
     * @param rawParam        原始调度文本入参
     * @param shardingContext 分片广播上下文
     * @param traceId         全链路追踪 traceId
     * @param tenantId        多租户标识
     */
    public XxlJobExecutionContext(
            long jobId,
            long logId,
            String jobName,
            Method method,
            Object targetBean,
            Object[] args,
            String rawParam,
            ShardingContext shardingContext,
            String traceId,
            String tenantId) {
        this.jobId = jobId;
        this.logId = logId;
        this.jobName = jobName;
        this.method = method;
        this.targetBean = targetBean;
        this.args = args != null ? args.clone() : new Object[0];
        this.rawParam = rawParam;
        this.shardingContext = shardingContext != null ? shardingContext : ShardingContext.current();
        this.traceId = traceId;
        this.tenantId = tenantId;
    }

    public long getJobId() {
        return jobId;
    }

    public long getLogId() {
        return logId;
    }

    public String getJobName() {
        return jobName;
    }

    /**
     * 获取任务 Handler 标识（与 {@link #getJobName()} 对称语义）
     *
     * @return 任务 Handler 名称
     */
    public String getJobHandler() {
        return jobName;
    }

    public Method getMethod() {
        return method;
    }

    /**
     * 获取目标调度执行方法（与 {@link #getMethod()} 对称语义）
     *
     * @return 目标方法反射对象
     */
    public Method getTargetMethod() {
        return method;
    }

    public Object getTargetBean() {
        return targetBean;
    }

    public Object[] getArgs() {
        return args != null ? args.clone() : new Object[0];
    }

    public String getRawParam() {
        return rawParam;
    }

    /**
     * 获取任务原始入参文本（与 {@link #getRawParam()} 对称语义）
     *
     * @return 原始调度参数
     */
    public String getJobParam() {
        return rawParam;
    }

    public ShardingContext getShardingContext() {
        return shardingContext;
    }

    /**
     * 获取当前节点分片索引序号（从 0 开始）
     *
     * @return 分片索引序号
     */
    public int getShardIndex() {
        return shardingContext.getShardIndex();
    }

    /**
     * 获取集群总分片数
     *
     * @return 总分片数
     */
    public int getShardTotal() {
        return shardingContext.getShardTotal();
    }

    public String getTraceId() {
        return traceId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setAttribute(String name, Object value) {
        if (value != null) {
            this.attributes.put(name, value);
        } else {
            this.attributes.remove(name);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String name) {
        return (T) this.attributes.get(name);
    }

    public Map<String, Object> getAttributes() {
        return Collections.unmodifiableMap(this.attributes);
    }
}

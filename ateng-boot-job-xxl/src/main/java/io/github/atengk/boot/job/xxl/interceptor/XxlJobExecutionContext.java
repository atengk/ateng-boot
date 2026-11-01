package io.github.atengk.boot.job.xxl.interceptor;

import io.github.atengk.boot.job.xxl.model.ShardingContext;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * XXL-Job 任务执行上下文元数据
 *
 * <p>封装当前调度任务执行的全部上下文要素，包括任务名称、目标方法与 Bean 实例、
 * 解析后实参、原始参数、分片上下文、全链路 traceId 与租户 ID，并提供线程安全的自定义属性存取能力。</p>
 *
 * @author Ateng
 * @since 2026-11-01
 */
public class XxlJobExecutionContext {

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
        this.jobName = jobName;
        this.method = method;
        this.targetBean = targetBean;
        this.args = args != null ? args.clone() : new Object[0];
        this.rawParam = rawParam;
        this.shardingContext = shardingContext != null ? shardingContext : ShardingContext.current();
        this.traceId = traceId;
        this.tenantId = tenantId;
    }

    public String getJobName() {
        return jobName;
    }

    public Method getMethod() {
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

    public ShardingContext getShardingContext() {
        return shardingContext;
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

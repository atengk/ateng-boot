package io.github.atengk.boot.job.local.model;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.core.util.ValidatorUtil;
import io.github.atengk.boot.job.local.enums.TriggerType;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 任务运行时执行上下文
 * <p>
 * 在任务方法执行时作为参数传入，承载本次调度的元数据、参数、MDC 追踪号与有界实时日志收集，
 * 并提供 {@link #bind(Class)} 强类型 DTO 编程式绑定与 JSR-303 Fail-Fast 防卫。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Getter
@Builder
@ToString
public class JobContext {

    /**
     * 单次任务执行默认允许收集的最大日志行数（防单次执行堆内存 OOM）
     */
    public static final int DEFAULT_MAX_LOG_LINES = 500;

    /**
     * 日志行数触达上限时的截断提示标记
     */
    public static final String LOG_TRUNCATED_MARKER = "[LOG_TRUNCATED]";

    /**
     * 任务名称
     */
    private final String jobName;

    /**
     * 本次调度的触发类型
     */
    private final TriggerType triggerType;

    /**
     * 当前调度的租户标识（若未启用租户隔离或处于 IGNORE 模态则为 null）
     */
    private final String tenantId;

    /**
     * 本次任务触发时间戳
     */
    private final Instant fireTime;

    /**
     * 外部手动触发传入的业务参数（不可变）
     */
    @Builder.Default
    private final Map<String, Object> params = Collections.emptyMap();

    /**
     * 本次执行绑定的链路追踪号
     */
    private final String traceId;

    /**
     * 单次任务允许收集的最大日志行数（默认 500 行）
     */
    @Builder.Default
    private final int maxLogLines = DEFAULT_MAX_LOG_LINES;

    /**
     * 本次调度的实时执行日志收集缓冲
     */
    @Getter(AccessLevel.NONE)
    @Builder.Default
    private final List<String> logs = new ArrayList<>();

    @Getter(AccessLevel.NONE)
    @ToString.Exclude
    private final ReentrantLock logLock = new ReentrantLock();

    @Getter(AccessLevel.NONE)
    @ToString.Exclude
    private transient boolean logTruncated;

    /**
     * 获取本次执行已收集的实时日志只读快照（防御外部绕过有界上限直接修改内部缓冲）
     *
     * @return 不可变日志列表快照
     */
    public List<String> getLogs() {
        logLock.lock();
        try {
            return Collections.unmodifiableList(new ArrayList<>(logs));
        } finally {
            if (logLock.isHeldByCurrentThread()) {
                logLock.unlock();
            }
        }
    }

    /**
     * 记录本次执行的临时业务日志（受 {@link #maxLogLines} 有界保护防 OOM）
     *
     * @param message 日志内容
     */
    public void log(String message) {
        if (message == null) {
            return;
        }
        int limit = maxLogLines > 0 ? maxLogLines : DEFAULT_MAX_LOG_LINES;
        logLock.lock();
        try {
            if (logs.size() < limit) {
                logs.add("[" + Instant.now() + "] " + message);
            } else if (!logTruncated) {
                logTruncated = true;
                logs.add("[" + Instant.now() + "] " + LOG_TRUNCATED_MARKER
                        + " Max log lines per execution (" + limit + ") reached, subsequent logs are omitted");
            }
        } finally {
            if (logLock.isHeldByCurrentThread()) {
                logLock.unlock();
            }
        }
    }

    /**
     * 将当前任务上下文中的参数绑定反序列化为指定强类型 DTO，并执行 JSR-303 Fail-Fast 校验
     *
     * @param <T>         目标 DTO 类型
     * @param targetClass 目标 DTO 类
     * @return 经过强类型转换与 JSR-303 校验合规的 DTO 实例
     * @throws BusinessException 当反序列化失败或字段校验违规时抛出
     */
    public <T> T bind(Class<T> targetClass) {
        if (targetClass == null) {
            throw new IllegalArgumentException("targetClass must not be null");
        }
        T instance = null;
        if (params == null || params.isEmpty()) {
            try {
                instance = JsonUtil.convertValue(Collections.emptyMap(), targetClass);
            } catch (Exception e) {
                try {
                    instance = targetClass.getDeclaredConstructor().newInstance();
                } catch (Exception ex) {
                    throw new BusinessException(CommonResultCode.BAD_REQUEST, "无法实例化目标参数类型: " + targetClass.getName(), ex);
                }
            }
        } else {
            // 1. 若 params 中某个属性值直接就是目标类型实例，直接复用
            for (Object val : params.values()) {
                if (val != null && targetClass.isInstance(val)) {
                    instance = targetClass.cast(val);
                    break;
                }
            }
            // 2. 若 params 只有一个键值对且值为 JSON 格式字符串，尝试按 JSON 字符串反序列化
            if (instance == null && params.size() == 1) {
                Object singleVal = params.values().iterator().next();
                if (singleVal instanceof String strVal && (strVal.trim().startsWith("{") || strVal.trim().startsWith("["))) {
                    try {
                        instance = JsonUtil.parseObject(strVal, targetClass);
                    } catch (Exception ignored) {
                        // 忽略并继续走标准 convertValue
                    }
                }
            }
            // 3. 标准 Map 转换
            if (instance == null) {
                try {
                    instance = JsonUtil.convertValue(params, targetClass);
                } catch (Exception e) {
                    throw new BusinessException(CommonResultCode.BAD_REQUEST, "任务入参反序列化为 [" + targetClass.getSimpleName() + "] 失败: " + e.getMessage(), e);
                }
            }
        }

        if (instance == null) {
            try {
                instance = targetClass.getDeclaredConstructor().newInstance();
            } catch (Exception ex) {
                throw new BusinessException(CommonResultCode.BAD_REQUEST, "无法解析或实例化入参对象: " + targetClass.getSimpleName(), ex);
            }
        }

        // 4. JSR-303 Fail-Fast 校验
        ValidatorUtil.validate(instance);
        return instance;
    }
}

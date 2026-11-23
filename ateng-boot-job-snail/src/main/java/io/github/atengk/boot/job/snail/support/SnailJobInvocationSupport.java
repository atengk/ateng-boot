package io.github.atengk.boot.job.snail.support;

import com.aizuda.snailjob.client.core.annotation.Retryable;
import com.aizuda.snailjob.client.core.cache.RetryerInfoCache;
import com.aizuda.snailjob.client.core.retryer.RetryerInfo;
import com.aizuda.snailjob.client.job.core.MapHandler;
import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.annotation.MapExecutor;
import com.aizuda.snailjob.client.job.core.annotation.MergeReduceExecutor;
import com.aizuda.snailjob.client.job.core.annotation.ReduceExecutor;
import com.aizuda.snailjob.client.job.core.cache.JobExecutorInfoCache;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.client.job.core.dto.JobExecutorInfo;
import com.aizuda.snailjob.client.job.core.dto.MapArgs;
import com.aizuda.snailjob.client.job.core.dto.MergeReduceArgs;
import com.aizuda.snailjob.client.job.core.dto.ReduceArgs;
import com.aizuda.snailjob.client.job.core.dto.ShardingJobArgs;
import com.aizuda.snailjob.common.core.model.JobContext;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.core.util.ValidatorUtil;
import io.github.atengk.boot.job.snail.annotation.AtengSnailJob;
import io.github.atengk.boot.job.snail.annotation.AtengSnailRetry;
import io.github.atengk.boot.job.snail.context.SnailJobContext;
import io.github.atengk.boot.job.snail.governor.SnailJobRuntimeGovernor;
import io.github.atengk.boot.job.snail.model.ShardingContext;
import io.github.atengk.boot.job.snail.properties.SnailJobProperties;
import io.github.atengk.boot.job.snail.retry.scanner.AtengSnailRetryScanner;
import jakarta.validation.Constraint;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link io.github.atengk.boot.job.snail.actuator.SnailJobEndpoint} 与
 * {@link io.github.atengk.boot.job.snail.helper.SnailJobTestHelper} 共享调度解析、多参数绑定与校验支撑
 *
 * <p>集中收口：
 * <ul>
 *     <li>统一发现原生 {@link JobExecutor}、独立 {@link AtengSnailJob}（含类级与 {@code Map/Reduce/MergeReduce} 切片）、
 *     原生 {@link Retryable} 与 {@link AtengSnailRetry}；</li>
 *     <li>统一多参数绑定（{@link JobArgs}/{@link MapArgs}/{@link ReduceArgs}/{@link MergeReduceArgs}/{@link ShardingJobArgs}、
 *     {@link ShardingContext}、{@link SnailJobContext}、{@link MapHandler} 与强类型 DTO），并在 JSON 反序列化异常时显式抛出语义异常；</li>
 *     <li>统一前置执行 {@link SnailJobRuntimeGovernor} 暂停态拦截、{@code maxPayloadSize} 载荷守卫与 {@code @Valid} JSR-303 校验。</li>
 * </ul></p>
 *
 * @author Ateng
 * @since 2026-11-20
 */
public final class SnailJobInvocationSupport {

    private static final Logger log = LoggerFactory.getLogger(SnailJobInvocationSupport.class);

    private SnailJobInvocationSupport() {
    }

    /**
     * 扫描指定 Spring 容器中的全部调度任务与重试场景 Bean，并注册至给定目标映射表
     */
    public static void scanSpringBeansInto(
            ApplicationContext context,
            Map<String, JobExecutorInfo> jobRepo,
            Map<String, RetryerInfo> retryRepo) {
        if (context == null || jobRepo == null || retryRepo == null) {
            return;
        }
        String[] beanNames = context.getBeanNamesForType(Object.class);
        for (String beanName : beanNames) {
            Object bean;
            try {
                bean = context.getBean(beanName);
            } catch (BeansException ex) {
                log.trace("跳过无法实例化的 Spring Bean [{}]: {}", beanName, ex.getMessage());
                continue;
            }
            if (bean == null) {
                continue;
            }
            scanSingleBean(bean, jobRepo, retryRepo);
        }
    }

    private static void scanSingleBean(
            Object bean,
            Map<String, JobExecutorInfo> jobRepo,
            Map<String, RetryerInfo> retryRepo) {
        Class<?> targetClass = AopUtils.getTargetClass(bean);

        // 1. 扫描方法级 @JobExecutor
        Map<Method, JobExecutor> jobMethods = MethodIntrospector.selectMethods(
                targetClass,
                (MethodIntrospector.MetadataLookup<JobExecutor>) m -> AnnotatedElementUtils.findMergedAnnotation(m, JobExecutor.class)
        );
        for (Map.Entry<Method, JobExecutor> entry : jobMethods.entrySet()) {
            Method m = AopUtils.selectInvocableMethod(entry.getKey(), bean.getClass());
            String name = entry.getValue().name();
            if (StringUtils.hasText(name)) {
                JobExecutorInfo info = new JobExecutorInfo(name.trim(), m, Collections.emptyMap(), null, null, bean);
                jobRepo.put(name.trim(), info);
            }
        }

        // 2. 扫描方法级独立 @AtengSnailJob
        Map<Method, AtengSnailJob> atengJobMethods = MethodIntrospector.selectMethods(
                targetClass,
                (MethodIntrospector.MetadataLookup<AtengSnailJob>) m -> AnnotatedElementUtils.findMergedAnnotation(m, AtengSnailJob.class)
        );
        for (Map.Entry<Method, AtengSnailJob> entry : atengJobMethods.entrySet()) {
            Method m = AopUtils.selectInvocableMethod(entry.getKey(), bean.getClass());
            AtengSnailJob atengJob = entry.getValue();
            JobExecutor jobExec = AnnotatedElementUtils.findMergedAnnotation(entry.getKey(), JobExecutor.class);
            String explicitAtengName = SnailJobInternalSupport.resolveStandaloneJobName(atengJob, "");
            if (jobExec != null && StringUtils.hasText(jobExec.name()) && !StringUtils.hasText(explicitAtengName)) {
                continue;
            }
            String name = SnailJobInternalSupport.resolveStandaloneJobName(atengJob, m.getName());
            if (StringUtils.hasText(name)) {
                JobExecutorInfo info = new JobExecutorInfo(name, m, Collections.emptyMap(), null, null, bean);
                jobRepo.put(name, info);
            }
        }

        // 3. 扫描类级 @JobExecutor / @AtengSnailJob 与 Map/Reduce/MergeReduce 切片
        String rootJobName = resolveRootJobName(targetClass);
        Map<String, Method> mapExecutorMap = new HashMap<>();
        Method reduceMethod = null;
        Method mergeReduceMethod = null;

        Map<Method, MapExecutor> mapMethods = MethodIntrospector.selectMethods(
                targetClass,
                (MethodIntrospector.MetadataLookup<MapExecutor>) m -> AnnotatedElementUtils.findMergedAnnotation(m, MapExecutor.class)
        );
        for (Map.Entry<Method, MapExecutor> entry : mapMethods.entrySet()) {
            Method invocable = AopUtils.selectInvocableMethod(entry.getKey(), bean.getClass());
            String taskName = StringUtils.hasText(entry.getValue().taskName())
                    ? entry.getValue().taskName().trim() : invocable.getName();
            mapExecutorMap.put(taskName, invocable);
            JobExecutorInfo sliceInfo = new JobExecutorInfo(taskName, invocable, Collections.emptyMap(), null, null, bean);
            jobRepo.put(rootJobName + ":" + taskName, sliceInfo);
            jobRepo.putIfAbsent(taskName, sliceInfo);
        }

        Map<Method, ReduceExecutor> reduceMethods = MethodIntrospector.selectMethods(
                targetClass,
                (MethodIntrospector.MetadataLookup<ReduceExecutor>) m -> AnnotatedElementUtils.findMergedAnnotation(m, ReduceExecutor.class)
        );
        for (Map.Entry<Method, ReduceExecutor> entry : reduceMethods.entrySet()) {
            Method invocable = AopUtils.selectInvocableMethod(entry.getKey(), bean.getClass());
            if (reduceMethod == null) {
                reduceMethod = invocable;
            }
            JobExecutorInfo reduceInfo = new JobExecutorInfo("reduce", invocable, Collections.emptyMap(), null, null, bean);
            jobRepo.put(rootJobName + ":reduce", reduceInfo);
            jobRepo.putIfAbsent("reduce", reduceInfo);
            jobRepo.putIfAbsent(invocable.getName(), reduceInfo);
        }

        Map<Method, MergeReduceExecutor> mergeReduceMethods = MethodIntrospector.selectMethods(
                targetClass,
                (MethodIntrospector.MetadataLookup<MergeReduceExecutor>) m -> AnnotatedElementUtils.findMergedAnnotation(m, MergeReduceExecutor.class)
        );
        for (Map.Entry<Method, MergeReduceExecutor> entry : mergeReduceMethods.entrySet()) {
            Method invocable = AopUtils.selectInvocableMethod(entry.getKey(), bean.getClass());
            if (mergeReduceMethod == null) {
                mergeReduceMethod = invocable;
            }
            JobExecutorInfo mergeInfo = new JobExecutorInfo("mergeReduce", invocable, Collections.emptyMap(), null, null, bean);
            jobRepo.put(rootJobName + ":mergeReduce", mergeInfo);
            jobRepo.putIfAbsent("mergeReduce", mergeInfo);
            jobRepo.putIfAbsent(invocable.getName(), mergeInfo);
        }

        JobExecutor classJobExec = AnnotatedElementUtils.findMergedAnnotation(targetClass, JobExecutor.class);
        AtengSnailJob classAtengJob = AnnotatedElementUtils.findMergedAnnotation(targetClass, AtengSnailJob.class);
        if (classJobExec != null && StringUtils.hasText(classJobExec.name())) {
            String targetMethodName = StringUtils.hasText(classJobExec.method()) ? classJobExec.method().trim() : "jobExecute";
            Method matchedMethod = findClassLevelJobMethod(targetClass, targetMethodName);
            Method invocable = matchedMethod != null ? AopUtils.selectInvocableMethod(matchedMethod, bean.getClass()) : null;
            if (invocable != null || !mapExecutorMap.isEmpty() || reduceMethod != null || mergeReduceMethod != null) {
                jobRepo.putIfAbsent(classJobExec.name().trim(),
                        new JobExecutorInfo(classJobExec.name().trim(), invocable, mapExecutorMap, reduceMethod, mergeReduceMethod, bean));
            }
        } else if (classAtengJob != null) {
            String classJobName = SnailJobInternalSupport.resolveStandaloneJobName(classAtengJob, targetClass.getSimpleName());
            String targetMethodName = StringUtils.hasText(classAtengJob.method()) ? classAtengJob.method().trim() : "jobExecute";
            Method matchedMethod = findClassLevelJobMethod(targetClass, targetMethodName);
            Method invocable = matchedMethod != null ? AopUtils.selectInvocableMethod(matchedMethod, bean.getClass()) : null;
            if (StringUtils.hasText(classJobName)
                    && (invocable != null || !mapExecutorMap.isEmpty() || reduceMethod != null || mergeReduceMethod != null)) {
                jobRepo.putIfAbsent(classJobName,
                        new JobExecutorInfo(classJobName, invocable, mapExecutorMap, reduceMethod, mergeReduceMethod, bean));
            }
        }

        // 4. 扫描 @Retryable 与 @AtengSnailRetry（显式 @Retryable 优先）
        Map<Method, Retryable> retryMethods = MethodIntrospector.selectMethods(
                targetClass,
                (MethodIntrospector.MetadataLookup<Retryable>) m -> AnnotatedElementUtils.findMergedAnnotation(m, Retryable.class)
        );
        for (Map.Entry<Method, Retryable> entry : retryMethods.entrySet()) {
            Method m = AopUtils.selectInvocableMethod(entry.getKey(), bean.getClass());
            Retryable r = entry.getValue();
            AtengSnailRetry ar = AnnotatedElementUtils.findMergedAnnotation(entry.getKey(), AtengSnailRetry.class);
            String scene = StringUtils.hasText(r.scene()) ? r.scene().trim() : AtengSnailRetryScanner.resolveScene(ar, m);
            String key = scene + "#" + bean.getClass().getName();
            Set<Class<? extends Throwable>> includeSet = r.include().length > 0 ? Set.of(r.include()) : Collections.emptySet();
            Set<Class<? extends Throwable>> excludeSet = r.exclude().length > 0 ? Set.of(r.exclude()) : Collections.emptySet();
            retryRepo.put(key, new RetryerInfo(
                    scene, bean.getClass().getName(), includeSet, excludeSet, bean, m,
                    r.retryStrategy(), r.localTimes(), r.localInterval(), r.idempotentId(), r.bizNo(),
                    r.retryMethod(), r.isThrowException(), r.retryCompleteCallback(),
                    r.async(), false, r.timeout(), r.unit(), r.retryIfResult()
            ));
        }

        Map<Method, AtengSnailRetry> atengRetryMethods = MethodIntrospector.selectMethods(
                targetClass,
                (MethodIntrospector.MetadataLookup<AtengSnailRetry>) m -> AnnotatedElementUtils.findMergedAnnotation(m, AtengSnailRetry.class)
        );
        for (Map.Entry<Method, AtengSnailRetry> entry : atengRetryMethods.entrySet()) {
            Method m = AopUtils.selectInvocableMethod(entry.getKey(), bean.getClass());
            AtengSnailRetry ar = entry.getValue();
            String scene = AtengSnailRetryScanner.resolveScene(ar, m);
            String key = scene + "#" + bean.getClass().getName();
            if (retryRepo.containsKey(key)) {
                continue;
            }
            Set<Class<? extends Throwable>> includeSet = ar.include().length > 0 ? Set.of(ar.include()) : Collections.emptySet();
            Set<Class<? extends Throwable>> excludeSet = ar.exclude().length > 0 ? Set.of(ar.exclude()) : Collections.emptySet();
            retryRepo.putIfAbsent(key, new RetryerInfo(
                    scene, bean.getClass().getName(), includeSet, excludeSet, bean, m,
                    ar.retryStrategy(), ar.localTimes(), ar.localInterval(), ar.idempotentId(), ar.bizNo(),
                    null, ar.isThrowException(), ar.retryCompleteCallback(),
                    false, false, 0, null, null
            ));
        }
    }

    /**
     * 从官方缓存与 Spring 扫描集合中解析统一的任务执行目标（兼容 composite 切片名）
     */
    public static JobExecutorInfo resolveJobExecutor(String jobName, Map<String, JobExecutorInfo> scannedJobs) {
        if (!StringUtils.hasText(jobName)) {
            return null;
        }
        JobExecutorInfo official = JobExecutorInfoCache.get(jobName);
        if (official != null && official.getMethod() != null) {
            return official;
        }
        if (scannedJobs != null) {
            JobExecutorInfo direct = scannedJobs.get(jobName);
            if (direct != null && direct.getMethod() != null) {
                return direct;
            }
        }

        // 支持 <rootJob>:<stageOrTask> 从根 JobExecutorInfo 的 mapExecutorMap / reduce / mergeReduce 解析
        if (jobName.contains(":")) {
            int sep = jobName.indexOf(':');
            String rootName = jobName.substring(0, sep);
            String stage = jobName.substring(sep + 1);
            JobExecutorInfo rootInfo = JobExecutorInfoCache.get(rootName);
            if (rootInfo == null && scannedJobs != null) {
                rootInfo = scannedJobs.get(rootName);
            }
            if (rootInfo != null) {
                Method stageMethod = null;
                if ("reduce".equalsIgnoreCase(stage)) {
                    stageMethod = rootInfo.getReduceExecutor();
                } else if ("mergeReduce".equalsIgnoreCase(stage)) {
                    stageMethod = rootInfo.getMergeReduceExecutor();
                } else if (rootInfo.getMapExecutorMap() != null) {
                    stageMethod = rootInfo.getMapExecutorMap().get(stage);
                }
                if (stageMethod != null) {
                    return new JobExecutorInfo(jobName, stageMethod, Collections.emptyMap(), null, null, rootInfo.getExecutor());
                }
            }
            if (scannedJobs != null) {
                JobExecutorInfo byStage = scannedJobs.get(stage);
                if (byStage != null && byStage.getMethod() != null) {
                    return byStage;
                }
            }
        }
        return official != null ? official : (scannedJobs != null ? scannedJobs.get(jobName) : null);
    }

    /**
     * 检查目标任务（含根任务名）是否已被运行时治理总线暂停
     */
    public static void enforceGovernorNotPaused(SnailJobRuntimeGovernor governor, String jobName) {
        if (governor == null || !StringUtils.hasText(jobName)) {
            return;
        }
        if (governor.isPaused(jobName)) {
            throw new BusinessException(CommonResultCode.SERVICE_UNAVAILABLE,
                    "SnailJob 任务 [" + jobName + "] 已被运行时治理总线暂停执行");
        }
        if (jobName.contains(":")) {
            String rootName = jobName.substring(0, jobName.indexOf(':'));
            if (governor.isPaused(rootName)) {
                throw new BusinessException(CommonResultCode.SERVICE_UNAVAILABLE,
                        "SnailJob 根任务 [" + rootName + "] 已被运行时治理总线暂停执行");
            }
        }
    }

    /**
     * 校验调度入参是否超过全局 {@code maxPayloadSize} 限制
     */
    public static void enforceMaxPayloadSize(SnailJobProperties properties, Object rawParam, String jobName) {
        if (properties == null || rawParam == null) {
            return;
        }
        DataSize limit = properties.getMaxPayloadSize();
        if (limit == null || limit.toBytes() <= 0) {
            return;
        }
        Object effective = (rawParam instanceof JobArgs ja) ? ja.getJobParams() : rawParam;
        if (!(effective instanceof String str) || str.isEmpty()) {
            return;
        }
        int payloadBytes = str.getBytes(StandardCharsets.UTF_8).length;
        if (payloadBytes > limit.toBytes()) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(),
                    "SnailJob 任务 [" + jobName + "] 入参载荷超出 maxPayloadSize 限制 ("
                            + payloadBytes + " bytes > " + limit.toBytes() + " bytes)");
        }
    }

    /**
     * 按方法元数据构建对应的 SnailJob {@link JobArgs} 实例（兼容分片与 MapReduce 子类型）
     */
    public static JobArgs buildJobArgs(
            String jobName,
            Method method,
            Object rawParam,
            int shardIndex,
            int shardTotal) {
        if (rawParam instanceof JobArgs ja) {
            if (!StringUtils.hasText(ja.getExecutorInfo())) {
                ja.setExecutorInfo(jobName);
            }
            return ja;
        }
        JobArgs resolved;
        if (shardTotal > 1) {
            ShardingJobArgs shardingArgs = new ShardingJobArgs();
            shardingArgs.setShardingIndex(shardIndex);
            shardingArgs.setShardingTotal(shardTotal);
            resolved = shardingArgs;
        } else if (jobName.endsWith(":reduce")
                || (method != null && AnnotatedElementUtils.findMergedAnnotation(method, ReduceExecutor.class) != null)) {
            resolved = new ReduceArgs();
        } else if (jobName.endsWith(":mergeReduce")
                || (method != null && AnnotatedElementUtils.findMergedAnnotation(method, MergeReduceExecutor.class) != null)) {
            resolved = new MergeReduceArgs();
        } else if (jobName.contains(":")
                || (method != null && AnnotatedElementUtils.findMergedAnnotation(method, MapExecutor.class) != null)) {
            MapArgs mapArgs = new MapArgs();
            if (jobName.contains(":")) {
                mapArgs.setTaskName(jobName.substring(jobName.indexOf(':') + 1));
            }
            resolved = mapArgs;
        } else {
            resolved = new JobArgs();
        }
        resolved.setExecutorInfo(jobName);
        resolved.setJobParams(rawParam);
        return resolved;
    }

    /**
     * 统一多参数绑定与 JSR-303 参数校验（严禁吞咽 JSON 反序列化异常）
     */
    @SuppressWarnings("unchecked")
    public static Object[] prepareAndValidateArguments(
            Method method,
            JobArgs jobArgs,
            Object rawParam,
            ShardingContext shardingContext,
            SnailJobContext snailContext) {
        if (method == null) {
            return new Object[0];
        }
        Parameter[] parameters = method.getParameters();
        if (parameters.length == 0) {
            return new Object[0];
        }

        Object explicitParam = (rawParam instanceof JobArgs ja) ? ja.getJobParams() : rawParam;
        Object[] resolvedArgs = new Object[parameters.length];

        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];
            Class<?> pt = param.getType();

            if (JobArgs.class.isAssignableFrom(pt)) {
                resolvedArgs[i] = adaptJobArgsType(pt, jobArgs, explicitParam);
                continue;
            }
            if (ShardingContext.class.isAssignableFrom(pt)) {
                resolvedArgs[i] = shardingContext != null ? shardingContext : ShardingContext.current();
                continue;
            }
            if (SnailJobContext.class.isAssignableFrom(pt)) {
                resolvedArgs[i] = snailContext;
                continue;
            }
            if (MapHandler.class.isAssignableFrom(pt)) {
                resolvedArgs[i] = (MapHandler<Object>) (list, tName) -> ExecuteResult.success();
                continue;
            }

            Object boundValue = bindBusinessParameter(param, pt, explicitParam);
            validateSingleParameter(param, boundValue);
            resolvedArgs[i] = boundValue;
        }
        return resolvedArgs;
    }

    private static JobArgs adaptJobArgsType(Class<?> targetType, JobArgs source, Object explicitParam) {
        if (targetType.isInstance(source)) {
            return source;
        }
        if (MapArgs.class.isAssignableFrom(targetType)) {
            MapArgs args = new MapArgs();
            args.setExecutorInfo(source.getExecutorInfo());
            args.setJobParams(explicitParam);
            if (source instanceof MapArgs ma) {
                args.setTaskName(ma.getTaskName());
            }
            return args;
        }
        if (ReduceArgs.class.isAssignableFrom(targetType)) {
            ReduceArgs args = new ReduceArgs();
            args.setExecutorInfo(source.getExecutorInfo());
            args.setJobParams(explicitParam);
            return args;
        }
        if (MergeReduceArgs.class.isAssignableFrom(targetType)) {
            MergeReduceArgs args = new MergeReduceArgs();
            args.setExecutorInfo(source.getExecutorInfo());
            args.setJobParams(explicitParam);
            return args;
        }
        if (ShardingJobArgs.class.isAssignableFrom(targetType)) {
            ShardingJobArgs args = new ShardingJobArgs();
            args.setExecutorInfo(source.getExecutorInfo());
            args.setJobParams(explicitParam);
            args.setShardingIndex(0);
            args.setShardingTotal(1);
            return args;
        }
        return source;
    }

    private static Object bindBusinessParameter(Parameter param, Class<?> targetType, Object explicitParam) {
        if (explicitParam == null) {
            return SnailJobInternalSupport.defaultPrimitiveValue(targetType);
        }
        if (targetType.isInstance(explicitParam)) {
            return explicitParam;
        }
        if (explicitParam instanceof String str) {
            if (targetType == String.class) {
                return str;
            }
            if (!StringUtils.hasText(str)) {
                return SnailJobInternalSupport.defaultPrimitiveValue(targetType);
            }
            try {
                return JsonUtil.parseObject(str, targetType);
            } catch (Exception ex) {
                throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(),
                        "任务参数 [" + param.getName() + "] JSON 反序列化为 [" + targetType.getSimpleName() + "] 失败: "
                                + ex.getMessage(),
                        ex);
            }
        }
        throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(),
                "任务参数 [" + param.getName() + "] 类型不兼容: 期望 [" + targetType.getSimpleName()
                        + "]，实际为 [" + explicitParam.getClass().getSimpleName() + "]");
    }

    /**
     * 对已绑定的任务实参批量执行 JSR-303 格式校验与 Fail-Fast 防卫（自动跳过框架运行时上下文形参）
     *
     * @param parameters   方法形参定义数组
     * @param resolvedArgs 已绑定的实参列表
     */
    public static void validateParameters(Parameter[] parameters, Object[] resolvedArgs) {
        if (parameters == null || parameters.length == 0) {
            return;
        }
        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];
            Class<?> type = param.getType();
            if (JobArgs.class.isAssignableFrom(type)
                    || JobContext.class.isAssignableFrom(type)
                    || MapHandler.class.isAssignableFrom(type)
                    || SnailJobContext.class.isAssignableFrom(type)
                    || ShardingContext.class.isAssignableFrom(type)) {
                continue;
            }
            Object arg = (resolvedArgs != null && i < resolvedArgs.length) ? resolvedArgs[i] : null;
            validateSingleParameter(param, arg);
        }
    }

    private static void validateSingleParameter(Parameter param, Object arg) {
        if (!shouldValidateParameter(param)) {
            return;
        }
        Class<?>[] groups = resolveValidationGroups(param);
        if (arg == null) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(),
                    "JSR-303 参数校验失败: 任务参数 [" + param.getName() + "] 不能为空");
        }
        try {
            ValidatorUtil.validate(arg, groups);
        } catch (BusinessException be) {
            throw new BusinessException(be.getCode(), "JSR-303 参数校验失败: " + be.getMessage(), be);
        }
    }

    /**
     * 判断形参是否需要执行 JSR-303 格式校验
     *
     * @param param 方法形参元数据
     * @return true 表示需要校验
     */
    public static boolean shouldValidateParameter(Parameter param) {
        if (param.isAnnotationPresent(Valid.class)) {
            return true;
        }
        for (Annotation ann : param.getAnnotations()) {
            String name = ann.annotationType().getName();
            if ("org.springframework.validation.annotation.Validated".equals(name)) {
                return true;
            }
            if (ann.annotationType().isAnnotationPresent(Constraint.class)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从形参注解中提取校验分组
     *
     * @param param 方法形参元数据
     * @return 校验分组数组，无分组返回空数组
     */
    public static Class<?>[] resolveValidationGroups(Parameter param) {
        for (Annotation ann : param.getAnnotations()) {
            if ("org.springframework.validation.annotation.Validated".equals(ann.annotationType().getName())) {
                try {
                    Method valueMethod = ann.annotationType().getMethod("value");
                    Object groups = valueMethod.invoke(ann);
                    if (groups instanceof Class<?>[] classArray) {
                        return classArray;
                    }
                } catch (Exception ex) {
                    log.trace("[SnailJobInvocationSupport] 提取 @Validated 分组失败，回退默认分组: {}", ex.getMessage());
                }
            }
        }
        return new Class<?>[0];
    }

    @SuppressWarnings("unchecked")
    public static Map<String, JobExecutorInfo> getLoadedJobExecutors() {
        try {
            Field field = JobExecutorInfoCache.class.getDeclaredField("JOB_EXECUTOR_REPOSITORY");
            field.setAccessible(true);
            Map<String, JobExecutorInfo> map = (Map<String, JobExecutorInfo>) field.get(null);
            return map != null ? map : Collections.emptyMap();
        } catch (ReflectiveOperationException | SecurityException | LinkageError ex) {
            log.debug("读取 JobExecutorInfoCache.JOB_EXECUTOR_REPOSITORY 失败，退化为空映射: {}", ex.getMessage());
            return Collections.emptyMap();
        }
    }

    public static Object getRetryerRepository() {
        try {
            Field field = RetryerInfoCache.class.getDeclaredField("RETRY_HANDLER_REPOSITORY");
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException | SecurityException | LinkageError ex) {
            log.debug("读取 RetryerInfoCache.RETRY_HANDLER_REPOSITORY 失败: {}", ex.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static Collection<RetryerInfo> getLoadedRetryers() {
        Object repository = getRetryerRepository();
        if (repository == null) {
            return Collections.emptyList();
        }
        try {
            if (repository instanceof Map<?, ?> map) {
                return (Collection<RetryerInfo>) map.values();
            }
            Class<?> tableInterface = Class.forName("com.google.common.collect.Table");
            if (tableInterface.isInstance(repository)) {
                return (Collection<RetryerInfo>) tableInterface.getMethod("values").invoke(repository);
            }
        } catch (ReflectiveOperationException | SecurityException | LinkageError ex) {
            log.debug("读取 RetryerInfoCache 重试器列表失败，退化为空集合: {}", ex.getMessage());
        }
        return Collections.emptyList();
    }

    private static String resolveRootJobName(Class<?> targetClass) {
        JobExecutor jobExecutor = AnnotatedElementUtils.findMergedAnnotation(targetClass, JobExecutor.class);
        if (jobExecutor != null && StringUtils.hasText(jobExecutor.name())) {
            return jobExecutor.name().trim();
        }
        AtengSnailJob atengJob = AnnotatedElementUtils.findMergedAnnotation(targetClass, AtengSnailJob.class);
        if (atengJob != null) {
            String name = SnailJobInternalSupport.resolveStandaloneJobName(atengJob, "");
            if (StringUtils.hasText(name)) {
                return name;
            }
        }
        for (Method m : targetClass.getDeclaredMethods()) {
            JobExecutor mExec = AnnotatedElementUtils.findMergedAnnotation(m, JobExecutor.class);
            if (mExec != null && StringUtils.hasText(mExec.name())) {
                return mExec.name().trim();
            }
            AtengSnailJob mAteng = AnnotatedElementUtils.findMergedAnnotation(m, AtengSnailJob.class);
            if (mAteng != null) {
                String mName = SnailJobInternalSupport.resolveStandaloneJobName(mAteng, "");
                if (StringUtils.hasText(mName)) {
                    return mName;
                }
            }
        }
        return targetClass.getSimpleName();
    }

    private static Method findClassLevelJobMethod(Class<?> targetClass, String methodName) {
        Method[] methods = ReflectionUtils.getAllDeclaredMethods(targetClass);
        Method zeroArgCandidate = null;
        Method fallbackCandidate = null;
        for (Method m : methods) {
            if (!methodName.equals(m.getName())) {
                continue;
            }
            if (m.getParameterCount() == 1 && JobArgs.class.isAssignableFrom(m.getParameterTypes()[0])) {
                return m;
            }
            if (m.getParameterCount() == 0 && zeroArgCandidate == null) {
                zeroArgCandidate = m;
            } else if (fallbackCandidate == null) {
                fallbackCandidate = m;
            }
        }
        return zeroArgCandidate != null ? zeroArgCandidate : fallbackCandidate;
    }

    /**
     * 轻量空安全扫描容器（用于非缓存按需合并查询）
     */
    public static Map<String, JobExecutorInfo> scanSpringJobs(ApplicationContext context) {
        Map<String, JobExecutorInfo> jobs = new LinkedHashMap<>();
        Map<String, RetryerInfo> retryers = new ConcurrentHashMap<>();
        scanSpringBeansInto(context, jobs, retryers);
        return jobs;
    }

    public static Map<String, RetryerInfo> scanSpringRetryers(ApplicationContext context) {
        Map<String, JobExecutorInfo> jobs = new ConcurrentHashMap<>();
        Map<String, RetryerInfo> retryers = new LinkedHashMap<>();
        scanSpringBeansInto(context, jobs, retryers);
        return retryers;
    }
}

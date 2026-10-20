package io.github.atengk.boot.web.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.audit.OperateLogEvent;
import io.github.atengk.boot.core.audit.OperateLogModel;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.web.annotation.OperateLog;
import io.github.atengk.boot.web.audit.DefaultOperateLogStorageProvider;
import io.github.atengk.boot.web.audit.OperateLogStorageProvider;
import io.github.atengk.boot.web.properties.WebProperties;
import io.github.atengk.boot.web.trace.TraceUtil;
import io.github.atengk.boot.web.util.IpUtil;
import io.github.atengk.boot.web.util.ServletUtil;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * 结构化业务操作审计切面
 * 自动拦截标注了 {@link OperateLog} 的 Controller 方法，提取上下文信息、耗时、出入参脱敏并异步持久化
 *
 * @author Ateng
 * @since 2026-10-18
 */
@Aspect
public class OperateLogAspect {

    private static final Logger log = LoggerFactory.getLogger(OperateLogAspect.class);
    private static final String EXCLUDED_MASK = "[EXCLUDED]";

    private final OperateLogStorageProvider storageProvider;
    private final ObjectProvider<Executor> taskExecutorProvider;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final WebProperties webProperties;

    public OperateLogAspect(OperateLogStorageProvider storageProvider,
                            ObjectProvider<Executor> taskExecutorProvider,
                            ObjectMapper objectMapper,
                            ApplicationEventPublisher eventPublisher,
                            WebProperties webProperties) {
        this.storageProvider = storageProvider != null ? storageProvider : new DefaultOperateLogStorageProvider(objectMapper);
        this.taskExecutorProvider = taskExecutorProvider;
        this.objectMapper = objectMapper != null ? objectMapper : JsonUtil.getObjectMapper();
        this.eventPublisher = eventPublisher;
        this.webProperties = webProperties != null ? webProperties : new WebProperties();
    }

    public OperateLogAspect(OperateLogStorageProvider storageProvider) {
        this(storageProvider, null, JsonUtil.getObjectMapper(), null, new WebProperties());
    }

    public OperateLogAspect() {
        this(new DefaultOperateLogStorageProvider(), null, JsonUtil.getObjectMapper(), null, new WebProperties());
    }

    /**
     * 切入点：标注了 @OperateLog 的方法或类
     */
    @Pointcut("@annotation(io.github.atengk.boot.web.annotation.OperateLog) || @within(io.github.atengk.boot.web.annotation.OperateLog)")
    public void operateLogPointcut() {
    }

    /**
     * 环绕通知：记录操作审计并向存储 SPI 派发数据
     *
     * @param joinPoint 切入连接点
     * @return 目标方法执行结果
     * @throws Throwable 目标方法可能抛出的所有异常（无损重抛）
     */
    @Around("operateLogPointcut()")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Class<?> targetClass = joinPoint.getTarget() != null ? joinPoint.getTarget().getClass() : signature.getDeclaringType();
        Method specificMethod = org.springframework.aop.support.AopUtils.getMostSpecificMethod(method, targetClass);

        // 优先获取实现类/具体方法级注解，若无则回退至声明方法或类级注解
        OperateLog operateLog = AnnotationUtils.findAnnotation(specificMethod, OperateLog.class);
        if (operateLog == null) {
            operateLog = AnnotationUtils.findAnnotation(method, OperateLog.class);
        }
        if (operateLog == null && targetClass != null) {
            operateLog = AnnotationUtils.findAnnotation(targetClass, OperateLog.class);
        }
        if (operateLog == null) {
            return joinPoint.proceed();
        }

        // 校验开关配置：若全局关闭则直接透传放行
        if (!webProperties.getOperateLog().isEnabled()) {
            return joinPoint.proceed();
        }

        HttpServletRequest request = ServletUtil.getRequest();
        String traceId = TraceUtil.getTraceId();
        String tenantId = TenantContextHolder.getTenantId();
        String userId = UserContextHolder.getUserId();
        String userName = UserContextHolder.get();
        boolean isRunAsPre = UserContextHolder.isRunAs();
        String originalUserIdPre = UserContextHolder.getOriginalUserId();
        String requestMethod = request != null ? request.getMethod() : "";
        String requestUrl = request != null ? request.getRequestURI() : "";
        String clientIp = IpUtil.getClientIp(request);

        String module = StringUtils.hasText(operateLog.module()) ? operateLog.module()
                : (StringUtils.hasText(operateLog.title()) ? operateLog.title() : operateLog.value());

        // 提取并过滤入参
        String paramsJson = "";
        if (operateLog.saveParams()) {
            Map<String, Object> paramMap = extractParams(joinPoint, operateLog.excludeParams());
            paramsJson = toJson(paramMap);
        }

        long startTime = System.currentTimeMillis();
        Object result = null;
        Throwable caughtException = null;

        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable ex) {
            caughtException = ex;
            throw ex;
        } finally {
            long costTime = System.currentTimeMillis() - startTime;
            // 协调执行后的最终上下文身份（兼顾方法内切换身份或正常模拟请求）
            boolean isRunAsPost = UserContextHolder.isRunAs();
            boolean isRunAs = isRunAsPre || isRunAsPost;
            String originalUserId = isRunAsPre ? originalUserIdPre : UserContextHolder.getOriginalUserId();
            // 若进入方法时即处于模拟态，被模拟人优先锁定为进入时的目标身份，防止方法内执行 endSwitch() 导致被模拟人丢失
            String finalUserId;
            String finalUserName;
            if (!isRunAsPre && isRunAsPost) {
                finalUserId = UserContextHolder.getUserId() != null ? UserContextHolder.getUserId() : userId;
                finalUserName = UserContextHolder.get() != null ? UserContextHolder.get() : userName;
            } else {
                finalUserId = userId;
                finalUserName = userName;
            }

            OperateLogModel logModel = new OperateLogModel();
            logModel.setTraceId(traceId);
            logModel.setTenantId(tenantId);
            logModel.setUserId(finalUserId);
            logModel.setUserName(finalUserName);
            logModel.setModule(module);
            logModel.setOperateType(operateLog.type());
            logModel.setDescription(operateLog.description());
            logModel.setRequestMethod(requestMethod);
            logModel.setRequestUrl(requestUrl);
            logModel.setClientIp(clientIp);
            logModel.setRequestParams(paramsJson);
            logModel.setCostTime(costTime);
            logModel.setOperateTime(LocalDateTime.now());

            if (isRunAs) {
                logModel.setOriginalUserId(originalUserId);
                logModel.addExtra("isRunAs", true);
                if (originalUserId != null) {
                    logModel.addExtra("originalUserId", originalUserId);
                }
                if (finalUserId != null) {
                    logModel.addExtra("targetUserId", finalUserId);
                }
            }

            if (caughtException != null) {
                logModel.setStatus(1); // 1-失败
                logModel.setErrorMsg(caughtException.getMessage() != null
                        ? caughtException.getMessage() : caughtException.getClass().getSimpleName());
            } else {
                logModel.setStatus(0); // 0-成功
                if (operateLog.saveResult() && result != null) {
                    logModel.setResponseResult(sanitizeResult(result));
                }
            }

            // 异步派发至存储 SPI 与事件广播
            dispatchLog(logModel);
        }
    }

    private void dispatchLog(OperateLogModel logModel) {
        if (storageProvider == null) {
            return;
        }
        Executor executor = taskExecutorProvider != null ? taskExecutorProvider.getIfAvailable() : null;
        if (executor != null) {
            try {
                executor.execute(() -> safeStore(logModel));
            } catch (Exception e) {
                log.warn("[OperateLog] 异步派发审计记录失败，降级同步执行: traceId={}, error={}", logModel.getTraceId(), e.getMessage());
                safeStore(logModel);
            }
        } else {
            safeStore(logModel);
        }
    }

    private void safeStore(OperateLogModel logModel) {
        try {
            storageProvider.store(logModel);
            if (eventPublisher != null) {
                eventPublisher.publishEvent(new OperateLogEvent(this, logModel));
            }
        } catch (Throwable t) {
            log.error("[OperateLog] 审计日志持久化异常: traceId={}, module={}, error={}",
                    logModel.getTraceId(), logModel.getModule(), t.getMessage(), t);
        }
    }

    private Map<String, Object> extractParams(ProceedingJoinPoint joinPoint, String[] excludeParams) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] paramNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();
        if (args == null || args.length == 0) {
            return Collections.emptyMap();
        }

        Set<String> excludedSet = new HashSet<>();
        if (excludeParams != null) {
            for (String p : excludeParams) {
                if (StringUtils.hasText(p)) {
                    excludedSet.add(p.trim().toLowerCase());
                }
            }
        }

        Map<String, Object> paramMap = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String name = (paramNames != null && i < paramNames.length) ? paramNames[i] : "arg" + i;
            if (excludedSet.contains(name.toLowerCase())) {
                paramMap.put(name, EXCLUDED_MASK);
                continue;
            }
            Object arg = args[i];
            paramMap.put(name, sanitizeArg(arg, excludedSet));
        }
        return paramMap;
    }

    private Object sanitizeArg(Object arg, Set<String> excludedSet) {
        if (arg == null) {
            return null;
        }
        String placeholder = getSkipPlaceholder(arg);
        if (placeholder != null) {
            return placeholder;
        }
        if (excludedSet.isEmpty()) {
            return arg;
        }
        if (arg instanceof Map<?, ?> map) {
            return filterMap(map, excludedSet);
        }
        if (isPrimitiveOrBasic(arg)) {
            return arg;
        }
        if (arg instanceof Collection<?> col) {
            List<Object> list = new ArrayList<>();
            for (Object item : col) {
                list.add(sanitizeArg(item, excludedSet));
            }
            return list;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.convertValue(arg, Map.class);
            return filterMap(map, excludedSet);
        } catch (Exception e) {
            return arg;
        }
    }

    private String sanitizeResult(Object result) {
        if (result == null) {
            return "null";
        }
        String placeholder = getSkipPlaceholder(result);
        if (placeholder != null) {
            return placeholder;
        }
        return toJson(result);
    }

    private Map<Object, Object> filterMap(Map<?, ?> map, Set<String> excludedSet) {
        Map<Object, Object> filtered = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object key = entry.getKey();
            if (key != null && excludedSet.contains(key.toString().toLowerCase())) {
                filtered.put(key, EXCLUDED_MASK);
            } else {
                String valPlaceholder = getSkipPlaceholder(entry.getValue());
                if (valPlaceholder != null) {
                    filtered.put(key, valPlaceholder);
                } else {
                    filtered.put(key, sanitizeArg(entry.getValue(), excludedSet));
                }
            }
        }
        return filtered;
    }

    private String getSkipPlaceholder(Object arg) {
        if (arg == null) {
            return null;
        }
        if (arg instanceof MultipartFile file) {
            return "[MultipartFile: name=" + file.getOriginalFilename() + ", size=" + file.getSize() + "]";
        }
        if (arg instanceof MultipartFile[] files) {
            return "[MultipartFile[], length=" + files.length + "]";
        }
        if (arg instanceof ServletRequest) {
            return "[HttpServletRequest]";
        }
        if (arg instanceof ServletResponse) {
            return "[HttpServletResponse]";
        }
        if (arg instanceof InputStream || arg instanceof OutputStream) {
            return "[Stream]";
        }
        if (arg instanceof Reader || arg instanceof Writer) {
            return "[Reader/Writer]";
        }
        if (arg instanceof BindingResult) {
            return "[BindingResult]";
        }
        return null;
    }

    private boolean isPrimitiveOrBasic(Object arg) {
        return arg instanceof String
                || arg instanceof Number
                || arg instanceof Boolean
                || arg instanceof Character
                || arg instanceof TemporalAccessor
                || arg.getClass().isPrimitive()
                || arg.getClass().isEnum();
    }

    private String toJson(Object obj) {
        if (obj == null) {
            return "null";
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }
}

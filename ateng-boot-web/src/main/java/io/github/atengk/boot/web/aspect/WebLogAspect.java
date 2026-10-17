package io.github.atengk.boot.web.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.web.annotation.WebLog;
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
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Method;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Web 接口审计日志切面
 * 自动拦截标注了 {@link WebLog} 的 Controller 方法或类，统一记录请求来源、真实 IP、入参、出参及耗时
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Aspect
public class WebLogAspect {

    private static final Logger log = LoggerFactory.getLogger(WebLogAspect.class);
    private static final String EXCLUDED_MASK = "[EXCLUDED]";

    private final ObjectMapper objectMapper;

    public WebLogAspect(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : JsonUtil.getObjectMapper();
    }

    public WebLogAspect() {
        this(JsonUtil.getObjectMapper());
    }

    /**
     * 匹配标注了 @WebLog 注解的方法或类
     */
    @Pointcut("@annotation(io.github.atengk.boot.web.annotation.WebLog) || @within(io.github.atengk.boot.web.annotation.WebLog)")
    public void webLogPointcut() {
    }

    /**
     * 环绕通知拦截并打印 Web 审计日志
     *
     * @param joinPoint 连接点
     * @return 方法执行返回值
     * @throws Throwable 业务异常
     */
    @Around("webLogPointcut()")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        // 优先获取方法级注解，若无则回退至类级注解
        WebLog webLog = AnnotationUtils.findAnnotation(method, WebLog.class);
        if (webLog == null) {
            webLog = AnnotationUtils.findAnnotation(joinPoint.getTarget().getClass(), WebLog.class);
        }
        if (webLog == null) {
            return joinPoint.proceed();
        }

        HttpServletRequest request = ServletUtil.getRequest();
        String uri = request != null ? request.getRequestURI() : "";
        String httpMethod = request != null ? request.getMethod() : "";
        String ip = IpUtil.getClientIp(request);
        String targetMethod = signature.getDeclaringTypeName() + "#" + signature.getName();
        String description = webLog.value();

        // 提取并过滤入参
        String paramsJson = "";
        if (webLog.printParams()) {
            Map<String, Object> paramMap = extractParams(joinPoint, webLog);
            paramsJson = toJson(paramMap);
            log.info("[WebLog] 开始请求: URL=[{} {}], IP=[{}], 操作=[{}], 目标=[{}], 入参={}",
                    httpMethod, uri, ip, description, targetMethod, paramsJson);
        } else {
            log.info("[WebLog] 开始请求: URL=[{} {}], IP=[{}], 操作=[{}], 目标=[{}]",
                    httpMethod, uri, ip, description, targetMethod);
        }

        long startTime = System.currentTimeMillis();
        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable ex) {
            long costTime = System.currentTimeMillis() - startTime;
            StringBuilder errorMsg = new StringBuilder("[WebLog] 请求异常: URL=[{} {}], IP=[{}], 操作=[{}], 目标=[{}]");
            List<Object> errorArgs = new ArrayList<>(8);
            errorArgs.add(httpMethod);
            errorArgs.add(uri);
            errorArgs.add(ip);
            errorArgs.add(description);
            errorArgs.add(targetMethod);

            if (webLog.printCostTime()) {
                errorMsg.append(", 耗时=[{}ms]");
                errorArgs.add(costTime);
            }
            if (webLog.printParams()) {
                errorMsg.append(", 入参={}");
                errorArgs.add(paramsJson.isEmpty() ? toJson(extractParams(joinPoint, webLog)) : paramsJson);
            }
            errorMsg.append(", 异常=[{}] {}");
            errorArgs.add(ex.getClass().getSimpleName());
            errorArgs.add(ex.getMessage());
            if (ex instanceof BusinessException) {
                log.warn(errorMsg.toString(), errorArgs.toArray());
            } else {
                log.error(errorMsg.toString(), errorArgs.toArray());
            }
            throw ex;
        }

        long costTime = System.currentTimeMillis() - startTime;
        StringBuilder finishMsg = new StringBuilder("[WebLog] 结束请求: URL=[{} {}]");
        List<Object> finishArgs = new ArrayList<>(4);
        finishArgs.add(httpMethod);
        finishArgs.add(uri);

        if (webLog.printCostTime()) {
            finishMsg.append(", 耗时=[{}ms]");
            finishArgs.add(costTime);
        }
        if (webLog.printResult()) {
            finishMsg.append(", 出参={}");
            finishArgs.add(sanitizeResult(result));
        }

        log.info(finishMsg.toString(), finishArgs.toArray());

        return result;
    }

    private Map<String, Object> extractParams(ProceedingJoinPoint joinPoint, WebLog webLog) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] paramNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();
        if (args == null || args.length == 0) {
            return Collections.emptyMap();
        }

        Set<String> excludedSet = new HashSet<>();
        if (webLog.excludeParams() != null) {
            for (String p : webLog.excludeParams()) {
                if (p != null && !p.isBlank()) {
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

    private boolean shouldSkipArg(Object arg) {
        return getSkipPlaceholder(arg) != null;
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

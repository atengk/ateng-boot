package io.github.atengk.boot.web.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.web.annotation.WebLog;
import io.github.atengk.boot.web.properties.WebProperties;
import io.github.atengk.boot.web.util.IpUtil;
import io.github.atengk.boot.web.util.ServletUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Web 接口审计日志切面
 * 自动拦截标注了 {@link WebLog} 的 Controller 方法或类，统一记录请求来源、真实 IP、入参、出参及耗时
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Aspect
public class WebLogAspect implements Ordered {

    /**
     * 切面优先级：最内层切面，业务处理完成后记录日志
     */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 600;

    private static final Logger log = LoggerFactory.getLogger(WebLogAspect.class);

    private final ObjectMapper objectMapper;
    private final WebProperties webProperties;

    public WebLogAspect(ObjectMapper objectMapper, WebProperties webProperties) {
        this.objectMapper = objectMapper != null ? objectMapper : JsonUtil.getObjectMapper();
        this.webProperties = webProperties != null ? webProperties : new WebProperties();
    }

    public WebLogAspect(ObjectMapper objectMapper) {
        this(objectMapper, new WebProperties());
    }

    public WebLogAspect() {
        this(JsonUtil.getObjectMapper(), new WebProperties());
    }

    @Override
    public int getOrder() {
        return ORDER;
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
        List<String> defaults = webProperties.getLog() != null ? webProperties.getLog().getDefaultExcludeParams() : null;
        return AuditParamSanitizer.extractParams(joinPoint, defaults, webLog.excludeParams(), objectMapper);
    }

    private String sanitizeResult(Object result) {
        return AuditParamSanitizer.sanitizeResult(result, objectMapper);
    }

    private String toJson(Object obj) {
        return AuditParamSanitizer.toJson(obj, objectMapper);
    }
}

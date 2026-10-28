package io.github.atengk.boot.web.ip.aspect;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.web.ip.IpAccessMode;
import io.github.atengk.boot.web.ip.annotation.IpAccessControl;
import io.github.atengk.boot.web.ip.filter.IpAccessControlFilter;
import io.github.atengk.boot.web.ip.properties.IpAccessControlProperties;
import io.github.atengk.boot.web.util.IpUtil;
import io.github.atengk.boot.web.util.ServletUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * 声明式 @IpAccessControl AOP 拦截切面
 * 解析 Controller 方法或类上声明的黑白名单规则与 CIDR 子网，对未授权客户端真实 IP 实施快速业务阻断
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Slf4j
@Aspect
public class IpAccessControlAspect {

    private final IpAccessControlProperties properties;

    public IpAccessControlAspect(IpAccessControlProperties properties) {
        this.properties = properties != null ? properties : new IpAccessControlProperties();
    }

    @Pointcut("@annotation(io.github.atengk.boot.web.ip.annotation.IpAccessControl) || @within(io.github.atengk.boot.web.ip.annotation.IpAccessControl)")
    public void ipAccessControlPointcut() {
    }

    @Around("ipAccessControlPointcut()")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!properties.isEnabled()) {
            return joinPoint.proceed();
        }

        IpAccessControl effectiveAnnotation = resolveAnnotation(joinPoint);
        if (effectiveAnnotation == null) {
            return joinPoint.proceed();
        }

        HttpServletRequest request = ServletUtil.getRequest();
        String clientIp = IpUtil.getClientIp(request);

        IpAccessMode mode = effectiveAnnotation.mode();
        String[] rules = effectiveAnnotation.rules();
        boolean matched = IpUtil.isInRange(clientIp, rules);

        boolean blocked = false;
        if (mode == IpAccessMode.WHITE_LIST) {
            // 白名单模式：未匹配任何规则即拒绝
            if (!matched) {
                blocked = true;
            }
        } else if (mode == IpAccessMode.BLACK_LIST) {
            // 黑名单模式：匹配任何规则即拒绝
            if (matched) {
                blocked = true;
            }
        }

        if (blocked) {
            HttpServletResponse response = ServletUtil.getResponse();
            if (response != null) {
                response.setHeader(IpAccessControlFilter.DENIED_HEADER, IpAccessControlFilter.DENIED_REASON);
                if (effectiveAnnotation.httpStatus403() || properties.isHttpStatus403()) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                }
            }

            String message = StringUtils.hasText(effectiveAnnotation.message())
                    ? effectiveAnnotation.message()
                    : properties.getDefaultDeniedMessage();

            String location = "unknown";
            if (joinPoint.getSignature() != null) {
                if (StringUtils.hasText(joinPoint.getSignature().toShortString())) {
                    location = joinPoint.getSignature().toShortString();
                } else if (StringUtils.hasText(joinPoint.getSignature().getName())) {
                    location = joinPoint.getSignature().getName();
                }
            }

            log.warn("IpAccessControlAspect blocked request from IP [{}] on [{}] (mode={}, rules={})",
                    clientIp, location, mode, Arrays.toString(rules));

            throw new BusinessException(CommonResultCode.FORBIDDEN.getCode(), message);
        }

        return joinPoint.proceed();
    }

    private IpAccessControl resolveAnnotation(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        IpAccessControl methodAnnotation = AnnotationUtils.findAnnotation(method, IpAccessControl.class);
        if (methodAnnotation != null) {
            return methodAnnotation;
        }
        Class<?> targetClass = joinPoint.getTarget() != null ? joinPoint.getTarget().getClass() : signature.getDeclaringType();
        return AnnotationUtils.findAnnotation(targetClass, IpAccessControl.class);
    }
}

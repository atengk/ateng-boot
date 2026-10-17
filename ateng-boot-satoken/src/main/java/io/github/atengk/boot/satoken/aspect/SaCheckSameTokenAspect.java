package io.github.atengk.boot.satoken.aspect;

import cn.dev33.satoken.same.SaSameUtil;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;

/**
 * 微服务内部 Same-Token 零信任切面
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Slf4j
@Aspect
public class SaCheckSameTokenAspect {

    @Pointcut("@annotation(io.github.atengk.boot.satoken.annotation.CheckSameToken) || @within(io.github.atengk.boot.satoken.annotation.CheckSameToken)")
    public void checkSameTokenPointcut() {
    }

    @Before("checkSameTokenPointcut()")
    public void beforeCheck() {
        log.debug("执行微服务内部 Same-Token 零信任安全校验");
        SaSameUtil.checkCurrentRequestToken();
    }
}

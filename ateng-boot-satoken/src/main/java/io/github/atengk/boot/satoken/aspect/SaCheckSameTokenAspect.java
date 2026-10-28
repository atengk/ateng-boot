package io.github.atengk.boot.satoken.aspect;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.context.model.SaRequest;
import cn.dev33.satoken.same.SaSameUtil;
import io.github.atengk.boot.satoken.properties.SaTokenProperties;
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

    private final SaTokenProperties properties;

    public SaCheckSameTokenAspect() {
        this(null);
    }

    public SaCheckSameTokenAspect(SaTokenProperties properties) {
        this.properties = properties;
    }

    @Pointcut("@annotation(io.github.atengk.boot.satoken.annotation.CheckSameToken) || @within(io.github.atengk.boot.satoken.annotation.CheckSameToken)")
    public void checkSameTokenPointcut() {
    }

    @Before("checkSameTokenPointcut()")
    public void beforeCheck() {
        if (properties != null && properties.getSameToken() != null && !properties.getSameToken().isEnabled()) {
            log.debug("微服务内部 Same-Token 校验已显式禁用，放行调用");
            return;
        }
        log.debug("执行微服务内部 Same-Token 零信任安全校验");
        if (properties != null && properties.getSameToken() != null) {
            String customHeader = properties.getSameToken().getHeaderName();
            if (customHeader != null && !customHeader.isBlank() && !customHeader.equalsIgnoreCase(SaSameUtil.SAME_TOKEN)) {
                try {
                    SaRequest request = SaHolder.getRequest();
                    if (request != null) {
                        String token = request.getHeader(customHeader);
                        if (token != null && !token.isBlank()) {
                            SaSameUtil.checkToken(token);
                            return;
                        }
                    }
                } catch (cn.dev33.satoken.exception.SameTokenInvalidException e) {
                    throw e;
                } catch (Exception e) {
                    log.warn("读取自定义 Same-Token 请求头失败，回退默认校验: header={}, error={}", customHeader, e.getMessage());
                }
            }
        }
        SaSameUtil.checkCurrentRequestToken();
    }
}

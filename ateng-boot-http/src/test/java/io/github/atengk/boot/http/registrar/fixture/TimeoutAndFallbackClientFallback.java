package io.github.atengk.boot.http.registrar.fixture;

import org.springframework.stereotype.Component;

/**
 * TimeoutAndFallbackClient 本地优雅降级实现
 *
 * @author Ateng
 * @since 2026-10-29
 */
@Component
public class TimeoutAndFallbackClientFallback implements TimeoutAndFallbackClient {

    @Override
    public String defaultTimeout() {
        return "fallback:defaultTimeout";
    }

    @Override
    public String methodTimeout() {
        return "fallback:methodTimeout";
    }

    @Override
    public String pureDigitsTimeout() {
        return "fallback:pureDigitsTimeout";
    }

    @Override
    public String placeholderTimeout() {
        return "fallback:placeholderTimeout";
    }

    @Override
    public String simulateError(int code) {
        return "fallback:handledError:" + code;
    }

    @Override
    public String simulateException() {
        return "fallback:handledException";
    }

}

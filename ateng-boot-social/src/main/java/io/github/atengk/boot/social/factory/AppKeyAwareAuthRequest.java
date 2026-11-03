package io.github.atengk.boot.social.factory;

import io.github.atengk.boot.social.context.CurrentSocialAppContextHolder;
import org.dromara.anduin.model.AuthCallback;
import org.dromara.anduin.model.AuthResponse;
import org.dromara.anduin.model.AuthToken;
import org.dromara.anduin.model.AuthUser;
import org.dromara.anduin.request.AuthRequest;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 具备应用标识上下文感知能力的 AuthRequest 装饰器
 * <p>
 * 保证下游无论通过门面调用还是直接获取原生 AuthRequest 发起请求，
 * 均在当前调用线程中自动绑定并清理 {@link CurrentSocialAppContextHolder}，
 * 确保专有应用网络代理与定制超时路由 100% 闭环。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class AppKeyAwareAuthRequest implements AuthRequest {

    private final String appKey;
    private final AuthRequest delegate;

    public AppKeyAwareAuthRequest(String appKey, AuthRequest delegate) {
        this.appKey = Objects.requireNonNull(appKey, "appKey must not be null");
        this.delegate = Objects.requireNonNull(delegate, "delegate AuthRequest must not be null");
    }

    public String getAppKey() {
        return appKey;
    }

    public AuthRequest getDelegate() {
        return delegate;
    }

    private <T> T executeInContext(Supplier<T> action) {
        String previous = CurrentSocialAppContextHolder.getAppKey();
        CurrentSocialAppContextHolder.setAppKey(appKey);
        try {
            return action.get();
        } finally {
            if (previous != null) {
                CurrentSocialAppContextHolder.setAppKey(previous);
            } else {
                CurrentSocialAppContextHolder.clear();
            }
        }
    }

    @Override
    public String authorize(String state) {
        return executeInContext(() -> delegate.authorize(state));
    }

    @Override
    public AuthToken getAccessToken(AuthCallback authCallback) {
        return executeInContext(() -> delegate.getAccessToken(authCallback));
    }

    @Override
    public AuthUser getUserInfo(AuthToken authToken) {
        return executeInContext(() -> delegate.getUserInfo(authToken));
    }

    @Override
    public AuthResponse<AuthUser> login(AuthCallback authCallback) {
        return executeInContext(() -> delegate.login(authCallback));
    }

    @Override
    public AuthResponse<?> revoke(AuthToken authToken) {
        return executeInContext(() -> delegate.revoke(authToken));
    }

    @Override
    public AuthResponse<AuthToken> refresh(AuthToken authToken) {
        return executeInContext(() -> delegate.refresh(authToken));
    }
}

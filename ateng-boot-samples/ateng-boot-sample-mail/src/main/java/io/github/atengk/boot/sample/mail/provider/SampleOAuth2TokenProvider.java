package io.github.atengk.boot.sample.mail.provider;

import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.provider.MailAccessTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 示例 XOAUTH2 动态访问令牌供给与刷新实现
 * <p>
 * 演示接入外部身份中心获取短期 OAuth2 Access Token，并在遇到 SMTP 535 认证异常时
 * 由框架回调 {@link #refreshAccessToken(MailAccount)} 执行单次刷新重连。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Component
public class SampleOAuth2TokenProvider implements MailAccessTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(SampleOAuth2TokenProvider.class);

    private final Map<String, String> tokenStore = new ConcurrentHashMap<>();
    private final AtomicInteger refreshSequence = new AtomicInteger(0);

    @Override
    public String getAccessToken(MailAccount account) {
        String key = resolveKey(account);
        return tokenStore.computeIfAbsent(key, k -> "initial-oauth2-token-" + k);
    }

    @Override
    public String refreshAccessToken(MailAccount account) {
        String key = resolveKey(account);
        int seq = refreshSequence.incrementAndGet();
        String refreshed = "refreshed-oauth2-token-" + seq;
        tokenStore.put(key, refreshed);
        log.info("Refreshed XOAUTH2 access token for account [{}], seq={}", key, seq);
        return refreshed;
    }

    /**
     * 为演示/测试预置指定账号的初始待刷新令牌
     *
     * @param accountName  账号标识
     * @param initialToken 初始令牌值
     */
    public void seedInitialToken(String accountName, String initialToken) {
        if (accountName != null && !accountName.isBlank() && initialToken != null) {
            tokenStore.put(accountName, initialToken);
        }
    }

    private static String resolveKey(MailAccount account) {
        if (account != null && account.getName() != null && !account.getName().isBlank()) {
            return account.getName();
        }
        return "oauth2-default";
    }
}

package io.github.atengk.boot.sms.guard;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 纯内存出站黑名单过滤器默认实现
 * 保障单机开发与白板单元测试 100% 离线自洽
 *
 * @author Ateng
 * @since 2026-11-02
 */
public class InMemorySmsBlacklistProvider implements SmsBlacklistProvider {

    private static final String DEFAULT_REASON = "目标手机号已退订或处于出站安全风控黑名单";

    private final ConcurrentHashMap<String, String> blacklistMap = new ConcurrentHashMap<>();

    public InMemorySmsBlacklistProvider() {
    }

    public InMemorySmsBlacklistProvider(Collection<String> initialBlacklist) {
        if (initialBlacklist != null) {
            for (String phone : initialBlacklist) {
                add(phone);
            }
        }
    }

    @Override
    public boolean isBlacklisted(String phone) {
        if (phone == null || phone.isBlank()) {
            return false;
        }
        return blacklistMap.containsKey(phone.trim());
    }

    @Override
    public String getReason(String phone) {
        if (phone == null || phone.isBlank()) {
            return DEFAULT_REASON;
        }
        return blacklistMap.getOrDefault(phone.trim(), DEFAULT_REASON);
    }

    @Override
    public void add(String phone) {
        add(phone, DEFAULT_REASON);
    }

    @Override
    public void add(String phone, String reason) {
        if (phone != null && !phone.isBlank()) {
            blacklistMap.put(phone.trim(), (reason != null && !reason.isBlank()) ? reason.trim() : DEFAULT_REASON);
        }
    }

    @Override
    public void addAll(Collection<String> phones) {
        if (phones != null) {
            for (String phone : phones) {
                add(phone);
            }
        }
    }

    @Override
    public void remove(String phone) {
        if (phone != null && !phone.isBlank()) {
            blacklistMap.remove(phone.trim());
        }
    }

    @Override
    public void removeAll(Collection<String> phones) {
        if (phones != null) {
            for (String phone : phones) {
                remove(phone);
            }
        }
    }

    @Override
    public Set<String> getBlacklist() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(blacklistMap.keySet()));
    }

    @Override
    public void clear() {
        blacklistMap.clear();
    }

    @Override
    public int size() {
        return blacklistMap.size();
    }
}

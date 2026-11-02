package io.github.atengk.boot.sms.guard;

import java.util.Collection;
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

    private final Set<String> blacklist = ConcurrentHashMap.newKeySet();

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
        return blacklist.contains(phone.trim());
    }

    /**
     * 将指定手机号加入黑名单
     *
     * @param phone 手机号
     */
    public void add(String phone) {
        if (phone != null && !phone.isBlank()) {
            blacklist.add(phone.trim());
        }
    }

    /**
     * 批量添加手机号到黑名单
     *
     * @param phones 手机号集合
     */
    public void addAll(Collection<String> phones) {
        if (phones != null) {
            for (String phone : phones) {
                add(phone);
            }
        }
    }

    /**
     * 将指定手机号从黑名单移除
     *
     * @param phone 手机号
     */
    public void remove(String phone) {
        if (phone != null) {
            blacklist.remove(phone.trim());
        }
    }

    /**
     * 清空黑名单集合
     */
    public void clear() {
        blacklist.clear();
    }

    /**
     * 获取当前黑名单数量
     *
     * @return 黑名单条数
     */
    public int size() {
        return blacklist.size();
    }
}

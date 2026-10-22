package io.github.atengk.boot.mail.registry;

import io.github.atengk.boot.mail.exception.MailResultCode;
import io.github.atengk.boot.mail.exception.MailSendException;
import io.github.atengk.boot.mail.model.MailAccount;
import lombok.Getter;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 发信账号注册中心与底层物理 Sender 容器
 *
 * @author Ateng
 * @since 2026-10-22
 */
public class MailAccountRegistry {

    private final Map<String, MailAccount> accountMap = new ConcurrentHashMap<>();

    @Getter
    private final String defaultAccountName;

    public MailAccountRegistry(String defaultAccountName) {
        this.defaultAccountName = (defaultAccountName != null && !defaultAccountName.isBlank())
                ? defaultAccountName
                : "default";
    }

    /**
     * 注册发信账号
     *
     * @param account 账号模型
     */
    public void register(MailAccount account) {
        if (account != null && account.getName() != null) {
            accountMap.put(account.getName(), account);
        }
    }

    /**
     * 根据名称获取发信账号
     *
     * @param name 账号名称
     * @return 账号实例，不存在返回 null
     */
    public MailAccount get(String name) {
        if (name == null || name.isBlank()) {
            return getDefault();
        }
        return accountMap.get(name);
    }

    /**
     * 获取默认发信账号
     *
     * @return 默认账号实例
     * @throws MailSendException 若无任何可用账号则抛出异常
     */
    public MailAccount getDefault() {
        MailAccount account = accountMap.get(defaultAccountName);
        if (account != null) {
            return account;
        }
        if (!accountMap.isEmpty()) {
            return accountMap.values().iterator().next();
        }
        throw new MailSendException(MailResultCode.MAIL_ACCOUNT_NOT_FOUND, "未找到默认发件账号: [" + defaultAccountName + "]");
    }

    /**
     * 是否包含指定名称的账号
     *
     * @param name 账号名称
     * @return 存在返回 true
     */
    public boolean contains(String name) {
        return name != null && accountMap.containsKey(name);
    }

    /**
     * 获取已注册账号数量
     *
     * @return 账号数量
     */
    public int size() {
        return accountMap.size();
    }

    /**
     * 获取所有已注册账号只读集合
     *
     * @return 账号集合
     */
    public Collection<MailAccount> getAll() {
        return Collections.unmodifiableCollection(accountMap.values());
    }
}

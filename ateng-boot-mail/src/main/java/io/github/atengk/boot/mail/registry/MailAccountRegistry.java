package io.github.atengk.boot.mail.registry;

import io.github.atengk.boot.mail.exception.MailResultCode;
import io.github.atengk.boot.mail.exception.MailSendException;
import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.properties.MailProperties;
import lombok.Getter;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 发信账号注册中心与底层物理 Sender 容器
 * <p>
 * 纳管启动时静态预热的多发件账号（支持多租户配置与 Spring 原生平滑纳管），
 * 并提供基于受控容量 LRU 缓存的编程式动态账号管理能力，杜绝高频 TCP 握手与内存泄漏。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
public class MailAccountRegistry {

    private final Map<String, MailAccount> accountMap = new ConcurrentHashMap<>();

    @Getter
    private final String defaultAccountName;

    @Getter
    private final int maxDynamicAccounts;

    private final Map<String, MailAccount> dynamicAccountLruCache;
    private final Map<String, MailAccount> dynamicAccountByNameCache;

    public MailAccountRegistry(String defaultAccountName) {
        this(defaultAccountName, 100);
    }

    public MailAccountRegistry(String defaultAccountName, int maxDynamicAccounts) {
        this.defaultAccountName = (defaultAccountName != null && !defaultAccountName.isBlank())
                ? defaultAccountName
                : "default";
        this.maxDynamicAccounts = maxDynamicAccounts > 0 ? maxDynamicAccounts : 100;
        this.dynamicAccountLruCache = Collections.synchronizedMap(
                new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, MailAccount> eldest) {
                        return size() > MailAccountRegistry.this.maxDynamicAccounts;
                    }
                }
        );
        this.dynamicAccountByNameCache = Collections.synchronizedMap(
                new LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, MailAccount> eldest) {
                        return size() > MailAccountRegistry.this.maxDynamicAccounts;
                    }
                }
        );
    }

    /**
     * 注册静态发信账号
     *
     * @param account 账号模型
     */
    public void register(MailAccount account) {
        if (account != null && account.getName() != null) {
            accountMap.put(account.getName(), account);
        }
    }

    /**
     * 注销并移除已注册的发信账号（保障门面操作的对称语义完备性）
     *
     * @param name 账号名称
     * @return 被移除的账号实例，若不存在返回 null
     */
    public MailAccount unregister(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return accountMap.remove(name);
    }

    /**
     * 根据名称获取已注册发信账号
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
     * 是否包含指定名称的静态账号
     *
     * @param name 账号名称
     * @return 存在返回 true
     */
    public boolean contains(String name) {
        return name != null && accountMap.containsKey(name);
    }

    /**
     * 获取已注册静态账号数量
     *
     * @return 静态账号数量
     */
    public int size() {
        return accountMap.size();
    }

    /**
     * 获取所有已注册静态账号只读集合
     *
     * @return 账号集合
     */
    public Collection<MailAccount> getAll() {
        return Collections.unmodifiableCollection(accountMap.values());
    }

    // ==================== 编程式动态账号与 LRU 缓存 ====================

    /**
     * 根据账号逻辑名称获取已缓存的动态账号
     *
     * @param name 账号逻辑名称
     * @return 动态账号实体，若未缓存返回 null
     */
    public MailAccount getDynamic(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return dynamicAccountByNameCache.get(name);
    }

    /**
     * 获取或创建编程式动态账号（依托 LRU 缓存并发复用底层 Sender，防范内存泄漏与重复握手）
     *
     * @param dynamicAccount 动态发信账号配置实体
     * @param globalProps    全局配置属性（提供连接与读写超时）
     * @return 绑定了物理池化 Sender 的受管动态账号实例
     */
    public MailAccount getOrCreateDynamic(MailAccount dynamicAccount, MailProperties globalProps) {
        Objects.requireNonNull(dynamicAccount, "dynamicAccount must not be null");
        return getOrCreateDynamic(dynamicAccount.getName(), dynamicAccount, globalProps);
    }

    /**
     * 获取或创建编程式动态账号并按指定逻辑名称纳管（依托 LRU 缓存并发复用底层 Sender，防范内存泄漏与重复握手）
     *
     * @param accountName    账号逻辑标识
     * @param dynamicAccount 动态发信账号配置实体
     * @param globalProps    全局配置属性（提供连接与读写超时）
     * @return 绑定了物理池化 Sender 的受管动态账号实例
     */
    public MailAccount getOrCreateDynamic(String accountName, MailAccount dynamicAccount, MailProperties globalProps) {
        Objects.requireNonNull(dynamicAccount, "dynamicAccount must not be null");
        String effectiveName = (accountName != null && !accountName.isBlank())
                ? accountName
                : (dynamicAccount.getName() != null && !dynamicAccount.getName().isBlank() ? dynamicAccount.getName() : "dynamic");
        if (dynamicAccount.getName() == null || dynamicAccount.getName().isBlank()) {
            dynamicAccount.setName(effectiveName);
        }

        MailAccount managed;
        // 若调用方已自行显式注入物理 Sender，则直接使用
        if (dynamicAccount.getSender() != null) {
            managed = dynamicAccount;
        } else {
            String fingerprint = dynamicAccount.toFingerprint();
            MailAccount cached = dynamicAccountLruCache.get(fingerprint);
            if (cached != null && cached.getSender() != null) {
                // 复用底层 Sender，保留入参的定制发件人与昵称
                managed = copyWithSender(dynamicAccount, cached.getSender());
            } else {
                synchronized (dynamicAccountLruCache) {
                    cached = dynamicAccountLruCache.get(fingerprint);
                    if (cached != null && cached.getSender() != null) {
                        managed = copyWithSender(dynamicAccount, cached.getSender());
                    } else {
                        JavaMailSenderImpl sender = createSender(dynamicAccount, globalProps);
                        managed = copyWithSender(dynamicAccount, sender);
                        dynamicAccountLruCache.put(fingerprint, managed);
                    }
                }
            }
        }

        dynamicAccountByNameCache.put(effectiveName, managed);
        return managed;
    }

    /**
     * 精准驱逐指定逻辑名称的动态账号缓存
     *
     * @param name 账号逻辑名称
     * @return 被移除的动态账号实例，若不存在返回 null
     */
    public MailAccount evictDynamic(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return dynamicAccountByNameCache.remove(name);
    }

    /**
     * 获取当前处于受管状态的动态账号 LRU 缓存条目数
     *
     * @return 动态缓存条目数
     */
    public int dynamicSize() {
        return Math.max(dynamicAccountByNameCache.size(), dynamicAccountLruCache.size());
    }

    /**
     * 清理所有动态账号 LRU 缓存
     */
    public void clearDynamic() {
        dynamicAccountByNameCache.clear();
        dynamicAccountLruCache.clear();
    }

    /**
     * 为指定账号模型构建物理 JavaMailSenderImpl 实例
     *
     * @param account     账号配置模型
     * @param globalProps 全局超时与协议配置
     * @return 发送器实现
     */
    public static JavaMailSenderImpl createSender(MailAccount account, MailProperties globalProps) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(account.getHost());
        sender.setPort(account.getPort() > 0 ? account.getPort() : 465);
        sender.setUsername(account.getUsername());
        sender.setPassword(account.getPassword());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", account.getProtocol() != null ? account.getProtocol() : "smtp");
        props.put("mail.smtp.auth", "true");

        if (globalProps != null) {
            if (globalProps.getConnectionTimeout() != null) {
                props.put("mail.smtp.connectiontimeout", String.valueOf(globalProps.getConnectionTimeout().toMillis()));
            }
            if (globalProps.getReadTimeout() != null) {
                props.put("mail.smtp.timeout", String.valueOf(globalProps.getReadTimeout().toMillis()));
            }
            if (globalProps.getWriteTimeout() != null) {
                props.put("mail.smtp.writetimeout", String.valueOf(globalProps.getWriteTimeout().toMillis()));
            }
        }

        if (account.isSsl()) {
            props.put("mail.smtp.ssl.enable", "true");
        }
        if (account.isStarttls()) {
            props.put("mail.smtp.starttls.enable", "true");
        }
        if (account.getProperties() != null) {
            props.putAll(account.getProperties());
        }

        return sender;
    }

    private MailAccount copyWithSender(MailAccount source, JavaMailSenderImpl sender) {
        return source.withSender(sender);
    }
}

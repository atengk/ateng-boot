package io.github.atengk.boot.mail.provider;

import io.github.atengk.boot.mail.model.MailAccount;

/**
 * 发件账号动态加载供给器 SPI
 * <p>
 * 提供依据逻辑账号标识从外部数据源（如数据库、分布式配置中心等）按需懒加载 {@link MailAccount} 领域模型的能力。
 * 业务实现方在持久层读取密文后自行完成解密，向框架传递真明文配置（捍卫 ADR-0012 内存真明文契约）。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@FunctionalInterface
public interface MailAccountProvider {

    /**
     * 根据发信账号逻辑标识加载发件账号模型
     *
     * @param accountName 发信账号逻辑标识
     * @return 账号配置实体（内部已解密），若不存在返回 null
     */
    MailAccount loadAccount(String accountName);
}

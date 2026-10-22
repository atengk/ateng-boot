package io.github.atengk.boot.sample.mail.provider;

import io.github.atengk.boot.mail.model.MailAccount;
import io.github.atengk.boot.mail.provider.MailAccountProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模拟外部数据库发信账号动态供给器
 * <p>
 * 演示在生产实践中如何从外部介质（如数据库、配置中心等）按需懒加载发信账号，
 * 业务实现方在持久层读取密文后自主完成密码解密，向框架传递真明文凭据（捍卫 ADR-0012 契约）。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Component
public class SampleDatabaseMailAccountProvider implements MailAccountProvider {

    private static final Logger log = LoggerFactory.getLogger(SampleDatabaseMailAccountProvider.class);

    /**
     * 模拟数据库表 sys_mail_account 存储记录（密码存储为密文形式）
     */
    private final Map<String, DbAccountRecord> mockDatabase = new ConcurrentHashMap<>();

    private final AtomicInteger queryCount = new AtomicInteger(0);

    @Override
    public MailAccount loadAccount(String accountName) {
        if (accountName == null || accountName.isBlank()) {
            return null;
        }

        queryCount.incrementAndGet();
        log.info("Querying mock database sys_mail_account table for accountName: [{}]", accountName);

        DbAccountRecord record = mockDatabase.get(accountName);
        if (record == null) {
            log.warn("Account [{}] not found in mock database", accountName);
            return null;
        }

        // 模拟解密密文密码，保证内存真明文
        String plainPassword = decryptPassword(record.encryptedPassword());

        return MailAccount.builder()
                .name(record.accountName())
                .host(record.host())
                .port(record.port())
                .username(record.username())
                .password(plainPassword)
                .ssl(record.ssl())
                .defaultFrom(record.defaultFrom())
                .defaultFromName(record.defaultFromName())
                .build();
    }

    /**
     * 向模拟数据库保存或更新账号记录（对象聚合方式）
     */
    public void saveAccount(DbAccountRecord record) {
        if (record != null && record.accountName() != null) {
            mockDatabase.put(record.accountName(), record);
            log.info("Saved dynamic account [{}] to mock database", record.accountName());
        }
    }

    /**
     * 向模拟数据库保存或更新账号记录
     */
    public void saveAccount(String accountName, String host, int port, String username, String plainPassword,
                            boolean ssl, String defaultFrom, String defaultFromName) {
        String encrypted = encryptPassword(plainPassword);
        saveAccount(new DbAccountRecord(accountName, host, port, username, encrypted, ssl, defaultFrom, defaultFromName));
    }

    /**
     * 从模拟数据库删除账号
     */
    public void deleteAccount(String accountName) {
        mockDatabase.remove(accountName);
        log.info("Deleted dynamic account [{}] from mock database", accountName);
    }

    /**
     * 获取数据库查询执行次数
     */
    public int getQueryCount() {
        return queryCount.get();
    }

    /**
     * 重置查询计数器
     */
    public void resetQueryCount() {
        queryCount.set(0);
    }

    private String encryptPassword(String plain) {
        return "ENC(" + plain + ")";
    }

    private String decryptPassword(String cipher) {
        if (cipher != null && cipher.startsWith("ENC(") && cipher.endsWith(")")) {
            return cipher.substring(4, cipher.length() - 1);
        }
        return cipher;
    }

    public record DbAccountRecord(
            String accountName,
            String host,
            int port,
            String username,
            String encryptedPassword,
            boolean ssl,
            String defaultFrom,
            String defaultFromName
    ) {
    }
}

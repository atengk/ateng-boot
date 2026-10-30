package io.github.atengk.boot.mail.properties;

import lombok.Data;

/**
 * 批量邮件合并发信配置属性
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Data
public class MailBatchProperties {

    /**
     * 批量个性化发信受控并发度，默认 4，配置范围 1~32
     */
    private int concurrency = 4;
}

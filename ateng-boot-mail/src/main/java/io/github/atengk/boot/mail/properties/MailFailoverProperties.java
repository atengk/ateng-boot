package io.github.atengk.boot.mail.properties;

import lombok.Data;
import org.springframework.validation.annotation.Validated;

/**
 * 邮件主备账号自动顺延容灾（Cascade Failover）配置属性
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Data
@Validated
public class MailFailoverProperties {

    /**
     * 是否开启主备账号顺延容灾，默认开启（仅当账号配置了非空 backupAccounts 时生效）
     */
    private boolean enabled = true;

    /**
     * 切换至备用账号投递时是否自动覆盖对齐物理 From 地址并将原发件地址写入 Reply-To，
     * 以避免严格 SPF/DMARC 域一致性校验导致备用网关拒信，默认 true
     */
    private boolean overrideFromOnSwitch = true;
}

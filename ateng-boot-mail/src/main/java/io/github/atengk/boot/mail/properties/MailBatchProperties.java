package io.github.atengk.boot.mail.properties;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.validation.annotation.Validated;

/**
 * 批量邮件合并发信配置属性
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Data
@Validated
public class MailBatchProperties {

    /**
     * 批量个性化发信受控并发度，默认 4，配置范围 1~32
     */
    @Min(value = 1, message = "batch.concurrency 必须大于或等于 1")
    @Max(value = 32, message = "batch.concurrency 不能超过 32")
    private int concurrency = 4;
}

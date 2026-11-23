package io.github.atengk.boot.mail.properties;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

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

    /**
     * 基于 BatchSliceUtil 切分的单个批处理分片规模，默认 50，配置范围 1~1000
     */
    @Min(value = 1, message = "batch.sliceSize 必须大于或等于 1")
    @Max(value = 1000, message = "batch.sliceSize 不能超过 1000")
    private int sliceSize = 50;

    /**
     * 是否在单个批次分片内部按最终路由账号复用底层物理 Transport 连接连续投递，默认 true
     */
    private boolean reuseTransport = true;

    /**
     * 整批合并发信总超时熔断阈值，默认 5 分钟；超时后未完成项按 Fail-Safe 标为超时失败并保留已完成回执
     */
    @NotNull(message = "batch.timeout 不能为空")
    @ValidDuration(message = "batch.timeout 必须大于 0")
    private Duration timeout = Duration.ofMinutes(5);
}

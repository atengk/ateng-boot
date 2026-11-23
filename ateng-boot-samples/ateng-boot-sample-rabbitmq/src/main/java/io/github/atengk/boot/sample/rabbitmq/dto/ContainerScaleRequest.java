package io.github.atengk.boot.sample.rabbitmq.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 监听容器动态并发扩缩容请求 DTO
 *
 * @author Ateng
 * @since 2026-11-15
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContainerScaleRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 目标监听容器 ID 或队列名称
     */
    @NotBlank(message = "目标容器 ID 或队列名称不能为空")
    private String target;

    /**
     * 最小并发消费者数
     */
    @Positive(message = "最小并发消费者数必须大于 0")
    private int concurrentConsumers;

    /**
     * 最大并发消费者数
     */
    @Positive(message = "最大并发消费者数必须大于 0")
    private int maxConcurrentConsumers;

    /**
     * 校验最大并发数不得小于最小并发数
     *
     * @return 并发区间合法返回 true
     */
    @JsonIgnore
    @AssertTrue(message = "最大并发消费者数不能小于最小并发消费者数")
    public boolean isValidConcurrencyRange() {
        return maxConcurrentConsumers >= concurrentConsumers;
    }
}

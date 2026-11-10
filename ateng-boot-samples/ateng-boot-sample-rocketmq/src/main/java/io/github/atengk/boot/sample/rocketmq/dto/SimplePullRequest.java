package io.github.atengk.boot.sample.rocketmq.dto;

import io.github.atengk.boot.sample.rocketmq.producer.TradeRocketmqProducer;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;

/**
 * SimpleConsumer 流式微批拉取请求入参
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimplePullRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 目标主题（默认 TradeRocketmqProducer.TOPIC_ORDER）
     */
    private String topic;

    /**
     * 消费者组（默认 sample_simple_group）
     */
    private String group;

    /**
     * 过滤标签 Tag（默认 *）
     */
    private String tag;

    /**
     * 一次拉取的最大消息数量（1-32，默认 10）
     */
    @Min(value = 1, message = "拉取消息数量最小为 1")
    @Max(value = 32, message = "拉取消息数量最大为 32")
    private Integer maxNum;

    /**
     * 不可见租约时长（秒，默认 30）
     */
    @Min(value = 1, message = "不可见租约时长最小为 1 秒")
    private Long invisibleSeconds;

    public String resolveTopic() {
        return StringUtils.hasText(topic) ? topic : TradeRocketmqProducer.TOPIC_ORDER;
    }

    public String resolveGroup() {
        return StringUtils.hasText(group) ? group : "sample_simple_group";
    }

    public String resolveTag() {
        return StringUtils.hasText(tag) ? tag : "*";
    }

    public int resolveMaxNum() {
        return maxNum != null && maxNum > 0 ? maxNum : 10;
    }

    public Duration resolveInvisibleDuration() {
        return Duration.ofSeconds(invisibleSeconds != null && invisibleSeconds > 0 ? invisibleSeconds : 30);
    }
}

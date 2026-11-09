package io.github.atengk.boot.sample.kafka.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 消费组位移安全回拨重设请求 DTO
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OffsetResetRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 目标消费组ID
     */
    @NotBlank(message = "消费组ID不能为空")
    private String groupId;

    /**
     * 目标业务主题
     */
    @NotBlank(message = "主题名称不能为空")
    private String topic;

    /**
     * 位移重设策略类型
     */
    @NotNull(message = "重设策略类型不能为空")
    private ResetType resetType;

    /**
     * 回拨指定时间戳毫秒数（当 resetType 为 TIMESTAMP 时必填，正数毫秒值）
     */
    private Long timestampMs;

    /**
     * 位移重设类型枚举
     */
    public enum ResetType {
        /**
         * 回拨至主题最早可用位移
         */
        EARLIEST,

        /**
         * 推进至主题最新位移 (LEO)
         */
        LATEST,

        /**
         * 按指定毫秒时间戳安全回拨
         */
        TIMESTAMP
    }
}

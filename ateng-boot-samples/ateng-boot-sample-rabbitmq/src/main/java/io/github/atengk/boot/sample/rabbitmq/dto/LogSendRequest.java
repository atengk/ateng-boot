package io.github.atengk.boot.sample.rabbitmq.dto;

import io.github.atengk.boot.sample.rabbitmq.model.LogMessage;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 日志消息投递请求 DTO
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogSendRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 路由键 (例如 log.error.order)
     */
    @NotBlank(message = "路由键不能为空")
    private String routingKey;

    /**
     * 日志级别
     */
    @NotBlank(message = "日志级别不能为空")
    private String level;

    /**
     * 服务名称
     */
    @NotBlank(message = "服务名称不能为空")
    private String serviceName;

    /**
     * 日志内容
     */
    @NotBlank(message = "日志内容不能为空")
    private String content;

    /**
     * 转换为日志领域消息实体
     *
     * @return 日志消息
     */
    public LogMessage toMessage() {
        return new LogMessage(level, serviceName, content);
    }
}

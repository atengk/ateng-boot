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
 * 监听容器动态管控请求 DTO
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContainerActionRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 容器注册ID或主题名称
     */
    @NotBlank(message = "容器ID或主题名称不能为空")
    private String containerIdOrTopic;

    /**
     * 管控动作
     */
    @NotNull(message = "管控动作不能为空")
    private ContainerAction action;

    /**
     * 容器管控动作枚举
     */
    public enum ContainerAction {
        /**
         * 暂停消费拉取
         */
        PAUSE,

        /**
         * 恢复消费拉取
         */
        RESUME,

        /**
         * 启动监听容器
         */
        START,

        /**
         * 停止监听容器
         */
        STOP
    }
}

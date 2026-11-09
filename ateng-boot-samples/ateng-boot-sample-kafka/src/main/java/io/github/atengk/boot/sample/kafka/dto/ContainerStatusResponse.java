package io.github.atengk.boot.sample.kafka.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 消费监听容器运行与暂停状态响应 VO
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContainerStatusResponse implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 容器注册ID或主题名称
     */
    private String idOrTopic;

    /**
     * 是否正在运行
     */
    private boolean running;

    /**
     * 是否处于暂停消费拉取状态
     */
    private boolean paused;
}

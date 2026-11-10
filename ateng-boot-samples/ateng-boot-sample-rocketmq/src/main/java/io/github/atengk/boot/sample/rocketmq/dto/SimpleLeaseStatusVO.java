package io.github.atengk.boot.sample.rocketmq.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * SimpleConsumer 待决租约句柄审计视图对象
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimpleLeaseStatusVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 当前缓存的待决消息句柄总数
     */
    private int cachedCount;

    /**
     * 待决句柄消息列表
     */
    private List<SimpleMessageVO> messages;
}

package io.github.atengk.boot.sample.http.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * SSE 流式事件消费响应视图对象
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SseStreamVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 接收到的事件总行数
     */
    private int eventCount;

    /**
     * 事件明细数据列表
     */
    private List<String> events;
}

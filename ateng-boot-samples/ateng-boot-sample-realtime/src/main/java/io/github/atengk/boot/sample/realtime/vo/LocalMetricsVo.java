package io.github.atengk.boot.sample.realtime.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 本地会话连接度量指标视图对象
 *
 * @author Ateng
 * @since 2026-11-09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LocalMetricsVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 当前服务节点本地长连接总数
     */
    private Integer onlineCount;

    /**
     * 当前服务节点本地独立在线用户数
     */
    private Integer onlineUserCount;

    /**
     * 当前服务节点本地 WebSocket 通道连接数
     */
    private Integer websocketCount;

    /**
     * 当前服务节点本地 Server-Sent Events 通道连接数
     */
    private Integer sseCount;
}

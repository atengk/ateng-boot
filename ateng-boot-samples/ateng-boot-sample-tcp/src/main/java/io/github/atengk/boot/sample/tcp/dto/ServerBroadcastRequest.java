package io.github.atengk.boot.sample.tcp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 服务端全网广播请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServerBroadcastRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 广播下行文本或告警消息内容
     */
    @NotBlank(message = "广播消息内容 message 不能为空")
    private String message;

    /**
     * 是否通过集群桥接器跨节点广域投递
     */
    @Builder.Default
    private Boolean cluster = false;
}

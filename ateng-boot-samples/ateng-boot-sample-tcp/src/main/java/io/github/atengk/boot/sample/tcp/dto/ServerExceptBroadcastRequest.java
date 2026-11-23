package io.github.atengk.boot.sample.tcp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 排他全网广播请求 DTO（支持排除指定来源会话 ID 集合）
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServerExceptBroadcastRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 广播下行消息内容
     */
    @NotBlank(message = "广播消息内容 message 不能为空")
    private String message;

    /**
     * 需排他跳过的会话 ID 集合（可选）
     */
    private List<String> excludedSessionIds;
}

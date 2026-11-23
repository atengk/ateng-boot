package io.github.atengk.boot.sample.tcp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 车队/会话分组群发下行请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupSendRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标业务分组名称（如 FLEET-ALPHA）
     */
    @NotBlank(message = "车队分组 group 不能为空")
    private String group;

    /**
     * 群发下行消息内容
     */
    @NotBlank(message = "消息内容 message 不能为空")
    private String message;

    /**
     * 是否通过集群桥接器跨节点投递给同一分组终端
     */
    @Builder.Default
    private Boolean cluster = false;
}

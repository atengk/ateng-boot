package io.github.atengk.boot.sample.tcp.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

import java.io.Serializable;

/**
 * 集群桥接（LocalTcpClusterBridge / RedisPubSubTcpClusterBridge）消息投递请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterDispatchRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 集群投递范围（DEVICE 单播 / GROUP 组播 / BROADCAST 全网广播）
     */
    @NotBlank(message = "投递模式 mode 不能为空")
    @Pattern(regexp = "DEVICE|GROUP|BROADCAST", message = "mode 仅支持 DEVICE、GROUP 或 BROADCAST")
    private String mode;

    /**
     * 目标设备号或分组名称（BROADCAST 模式下可为空）
     */
    private String target;

    /**
     * 下行消息内容
     */
    @NotBlank(message = "消息内容 message 不能为空")
    private String message;

    /**
     * 校验非全网广播模式下必须提供目标设备或分组标识
     *
     * @return 是否合法
     */
    @AssertTrue(message = "DEVICE 或 GROUP 模式下目标标识 target 不能为空")
    public boolean isTargetPresentWhenRequired() {
        if ("BROADCAST".equals(mode) || mode == null) {
            return true;
        }
        return StringUtils.hasText(target);
    }
}

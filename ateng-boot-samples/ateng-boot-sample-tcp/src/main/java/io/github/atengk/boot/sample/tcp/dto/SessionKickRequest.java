package io.github.atengk.boot.sample.tcp.dto;

import jakarta.validation.constraints.AssertTrue;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

import java.io.Serializable;

/**
 * 强踢会话或按设备下线断开请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionKickRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 设备编号（deviceId 与 sessionId 至少提供一项）
     */
    private String deviceId;

    /**
     * 会话唯一编号（deviceId 为空时按 sessionId 断开）
     */
    private String sessionId;

    /**
     * 强制断连业务说明
     */
    private String reason;

    /**
     * 校验设备编号与会话编号至少指定一项
     *
     * @return 是否合法
     */
    @AssertTrue(message = "deviceId 与 sessionId 至少提供一项")
    public boolean isIdentityPresent() {
        return StringUtils.hasText(deviceId) || StringUtils.hasText(sessionId);
    }
}

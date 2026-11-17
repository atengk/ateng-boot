package io.github.atengk.boot.sample.web.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 图形验证码登录响应视图模型
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@Builder
public class CaptchaLoginVO {

    /**
     * 登录账号
     */
    private String username;

    /**
     * 演示会话令牌
     */
    private String accessToken;

    /**
     * 登录时间
     */
    private LocalDateTime loginTime;
}

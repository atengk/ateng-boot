package io.github.atengk.boot.social.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 社交令牌刷新请求契约
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SocialTokenRefreshRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 刷新令牌 (Refresh Token)
     */
    @NotBlank(message = "刷新令牌 refreshToken 不能为空")
    private String refreshToken;
}

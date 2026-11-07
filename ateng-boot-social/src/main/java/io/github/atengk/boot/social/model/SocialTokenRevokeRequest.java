package io.github.atengk.boot.social.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 社交授权吊销请求契约
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SocialTokenRevokeRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 授权令牌 (Access Token)
     */
    @NotBlank(message = "授权令牌 accessToken 不能为空")
    private String accessToken;
}

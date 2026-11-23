package io.github.atengk.boot.social.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 社交账号解绑请求对象
 * <p>
 * 用于发起本地用户与指定第三方应用的解绑操作，前置经由孤儿账号防卫检查。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SocialUnbindRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 本地用户唯一标识
     */
    @NotBlank(message = "本地用户标识 userId 不能为空")
    private String userId;
}

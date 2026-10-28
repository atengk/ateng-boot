package io.github.atengk.boot.sample.satoken.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 用户个人资料更新传输对象
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileDTO implements Serializable {

    private String userId;
    private String nickname;
    private String email;
}

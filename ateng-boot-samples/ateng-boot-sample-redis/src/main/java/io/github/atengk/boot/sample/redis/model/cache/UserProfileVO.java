package io.github.atengk.boot.sample.redis.model.cache;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 会员档案两级缓存视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long userId;
    private String username;
    private String membershipTier;
    private Integer points;
}

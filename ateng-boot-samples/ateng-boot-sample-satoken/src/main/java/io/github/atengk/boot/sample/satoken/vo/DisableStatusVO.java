package io.github.atengk.boot.sample.satoken.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 账号封禁状态视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DisableStatusVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户唯一标识
     */
    private String userId;

    /**
     * 是否处于封禁状态
     */
    private boolean disabled;

    /**
     * 剩余封禁时间（秒，-1 为永久，-2 为未封禁）
     */
    private long disableTime;
}

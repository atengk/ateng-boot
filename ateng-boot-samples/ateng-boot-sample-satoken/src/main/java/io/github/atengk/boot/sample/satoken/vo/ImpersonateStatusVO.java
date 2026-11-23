package io.github.atengk.boot.sample.satoken.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 身份模拟与真实操作人状态视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImpersonateStatusVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 当前是否处于身份模拟状态
     */
    @JsonProperty("isRunAs")
    private Boolean isRunAs;

    /**
     * 当前生效的操作人用户标识
     */
    private String currentUserId;

    /**
     * 真实用户标识
     */
    private String realUserId;

    /**
     * 真实原始操作人用户标识
     */
    private String originalUserId;
}

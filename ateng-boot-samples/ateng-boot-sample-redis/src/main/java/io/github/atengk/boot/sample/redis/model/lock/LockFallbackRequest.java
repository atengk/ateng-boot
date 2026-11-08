package io.github.atengk.boot.sample.redis.model.lock;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 争锁超时降级演示请求对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LockFallbackRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "资源标识不能为空")
    private String resourceId;
}

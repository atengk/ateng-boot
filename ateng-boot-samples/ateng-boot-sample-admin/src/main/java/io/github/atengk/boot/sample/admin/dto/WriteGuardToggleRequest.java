package io.github.atengk.boot.sample.admin.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 控制台只读写守卫策略切换与探测请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
public class WriteGuardToggleRequest {

    /**
     * 是否允许控制台执行写操作（false 表示开启只读守卫）
     */
    @NotNull(message = "enableWriteOperations 开关不能为空")
    private Boolean enableWriteOperations;

    /**
     * 拟探测的 HTTP 方法（默认 POST）
     */
    private String probeMethod;

    /**
     * 拟探测的目标代理路径（默认 /admin/instances/order-core-01/actuator/loggers/root）
     */
    private String probeUri;
}

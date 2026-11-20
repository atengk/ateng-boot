package io.github.atengk.boot.sample.tcp.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 动态运行期 SPI 路由热注册请求 DTO
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DynamicRouteRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 待动态注册的业务命令字
     */
    @NotBlank(message = "命令字 command 不能为空")
    private String command;

    /**
     * 路由备注说明
     */
    private String note;
}

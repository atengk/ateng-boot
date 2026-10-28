package io.github.atengk.boot.sample.tcp.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 全网车机广播预警请求 DTO
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BroadcastAlertRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 预警通知内容
     */
    @NotBlank(message = "广播内容 message 不能为空")
    private String message;
}

package io.github.atengk.boot.sample.core.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 事务生命周期钩子与消息协同请求对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TxMqCooperateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 业务唯一标识键
     */
    @NotBlank(message = "业务主键不能为空")
    private String bizKey;

    /**
     * 待投递消息载荷
     */
    @NotBlank(message = "消息载荷不能为空")
    private String payload;

    /**
     * 是否模拟捕获不可自愈毒丸异常
     */
    private boolean simulateFatalError;
}

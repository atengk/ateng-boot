package io.github.atengk.boot.sample.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 跨进程业务异常链路标识透传验证视图模型
 *
 * @author Ateng
 * @since 2026-11-19
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RemoteTraceUnpackVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 订单编号
     */
    private String orderNo;

    /**
     * 业务错误码
     */
    private Integer code;

    /**
     * 错误提示文案
     */
    private String msg;

    /**
     * 从远端 Result 解包还原到 BusinessException 的链路追踪标识
     */
    private String propagatedTraceId;
}

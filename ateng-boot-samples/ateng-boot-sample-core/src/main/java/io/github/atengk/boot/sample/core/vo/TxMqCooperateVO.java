package io.github.atengk.boot.sample.core.vo;

import io.github.atengk.boot.core.enums.TransactionCompletionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 事务生命周期钩子与消息协同响应视图对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TxMqCooperateVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 业务主键
     */
    private String bizKey;

    /**
     * 消息头中提取的 TraceId
     */
    private String messageTraceId;

    /**
     * 序列化后的十六进制字符串
     */
    private String serializedHex;

    /**
     * 反序列化还原的明文载荷
     */
    private String deserializedPayload;

    /**
     * 事务提交前钩子是否触发
     */
    private boolean beforeCommitHookTriggered;

    /**
     * 事务提交后钩子是否触发
     */
    private boolean afterCommitHookTriggered;

    /**
     * 事务终态生命周期状态
     */
    private TransactionCompletionStatus completionStatus;

    /**
     * 是否判定为不可自愈毒丸异常
     */
    private boolean fatalPoisonPill;

    /**
     * 毒丸致命原因说明
     */
    private String fatalReason;
}

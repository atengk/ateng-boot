package io.github.atengk.boot.sms.registry;

import org.springframework.core.Ordered;

/**
 * 短信渠道动态解析器 SPI
 * 依据当前运行时上下文（如多租户、组织部门、业务场景等）动态决策目标短信渠道名称
 *
 * @author Ateng
 * @since 2026-11-02
 */
public interface SmsChannelResolver extends Ordered {

    /**
     * 解析目标短信渠道名称
     *
     * @return 解析到的渠道名称，若未匹配或无需切源则返回 null
     */
    String resolveChannel();

    @Override
    default int getOrder() {
        return 0;
    }
}

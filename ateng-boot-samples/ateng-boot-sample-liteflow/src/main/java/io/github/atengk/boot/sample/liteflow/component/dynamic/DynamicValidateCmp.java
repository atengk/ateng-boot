package io.github.atengk.boot.sample.liteflow.component.dynamic;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.liteflow.component.BaseNodeComponent;
import io.github.atengk.boot.sample.liteflow.context.DynamicFlowContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 动态规则即时入参前置校验组件
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@Component("dynamicValidateCmp")
public class DynamicValidateCmp extends BaseNodeComponent<DynamicFlowContext> {

    @Override
    public void process(DynamicFlowContext context) {
        String payload = context.resolvePayload(getRequestData());
        log.info("【动态规则-VALIDATE】校验输入有效性：{}", payload);
        if (payload == null || payload.isBlank()) {
            throw new BusinessException("动态规则执行入参 payload 不能为空");
        }
        context.put("validated", true);
    }
}

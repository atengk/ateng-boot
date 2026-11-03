package io.github.atengk.boot.sample.liteflow.component.dynamic;

import io.github.atengk.boot.liteflow.component.BaseNodeComponent;
import io.github.atengk.boot.sample.liteflow.context.DynamicFlowContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 动态规则即时执行回声测试组件
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@Component("dynamicEchoCmp")
public class DynamicEchoCmp extends BaseNodeComponent<DynamicFlowContext> {

    @Override
    public void process(DynamicFlowContext context) {
        String payload = context.resolvePayload(getRequestData());
        log.info("【动态规则-ECHO】收到即时入参：{}", payload);
        context.put("echoTimestamp", System.currentTimeMillis());
        context.put("echoPayload", "ECHO:" + payload);
        context.setResult("ECHO_SUCCESS");
    }
}

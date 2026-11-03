package io.github.atengk.boot.sample.liteflow.component.dynamic;

import io.github.atengk.boot.liteflow.component.BaseNodeComponent;
import io.github.atengk.boot.sample.liteflow.context.DynamicFlowContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 动态规则即时数据规整与特征转换组件
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@Component("dynamicTransformCmp")
public class DynamicTransformCmp extends BaseNodeComponent<DynamicFlowContext> {

    @Override
    public void process(DynamicFlowContext context) {
        String payload = context.resolvePayload(getRequestData());
        String transformed = payload == null ? "" : payload.trim().toUpperCase(Locale.ROOT);
        log.info("【动态规则-TRANSFORM】数据大写转换：origin={}, transformed={}", payload, transformed);
        context.put("transformedPayload", transformed);
        context.setResult("TRANSFORMED:" + transformed);
    }
}

package io.github.atengk.boot.sample.liteflow.component.routing;

import io.github.atengk.boot.liteflow.component.BaseIteratorComponent;
import io.github.atengk.boot.sample.liteflow.context.BatchSettleContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Iterator;

/**
 * 批量结算明细迭代器组件
 * 负责驱动当前批次内的明细项循环执行
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Slf4j
@Component("settleItemIteratorCmp")
public class SettleItemIteratorCmp extends BaseIteratorComponent<BatchSettleContext> {

    @Override
    public Iterator<?> processIterator(BatchSettleContext context) {
        log.info("【批量结算】开始迭代明细列表：总数={}", context.getItems().size());
        return context.getItems().iterator();
    }
}

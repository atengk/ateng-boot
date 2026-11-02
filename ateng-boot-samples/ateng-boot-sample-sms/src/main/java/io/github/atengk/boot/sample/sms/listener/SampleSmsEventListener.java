package io.github.atengk.boot.sample.sms.listener;

import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.sms.event.SmsBudgetExceededEvent;
import io.github.atengk.boot.sms.event.SmsDeliveryReportEvent;
import io.github.atengk.boot.sms.event.SmsMoMessageEvent;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 示例短信全生命周期领域事件监听器
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Slf4j
@Component
public class SampleSmsEventListener {

    @Getter
    private final CopyOnWriteArrayList<SmsDeliveryReportEvent> deliveryReportEvents = new CopyOnWriteArrayList<>();

    @Getter
    private final CopyOnWriteArrayList<SmsMoMessageEvent> moMessageEvents = new CopyOnWriteArrayList<>();

    @Getter
    private final CopyOnWriteArrayList<SmsBudgetExceededEvent> budgetExceededEvents = new CopyOnWriteArrayList<>();

    @EventListener
    public void onDeliveryReport(SmsDeliveryReportEvent event) {
        deliveryReportEvents.add(event);
        log.info("[SampleSmsEventListener] 监听到运营商送达回执 -> 渠道: {}, 手机: {}, 送达状态: {}, 结果码: {}",
                event.getChannel(), DesensitizeUtil.mobilePhone(event.getPhone()), event.isSuccess(), event.getResultCode());
    }

    @EventListener
    public void onMoMessage(SmsMoMessageEvent event) {
        moMessageEvents.add(event);
        log.info("[SampleSmsEventListener] 监听到用户上行回复 -> 渠道: {}, 手机: {}, 内容: {}",
                event.getChannel(), DesensitizeUtil.mobilePhone(event.getPhone()), event.getContent());
    }

    @EventListener
    public void onBudgetExceeded(SmsBudgetExceededEvent event) {
        budgetExceededEvents.add(event);
        log.warn("[SampleSmsEventListener] 监听到单日短信成本预算熔断告警 -> 当前发信量: {}, 预算上限: {}",
                event.getCurrentSent(), event.getDailyQuota());
    }

    public void clear() {
        deliveryReportEvents.clear();
        moMessageEvents.clear();
        budgetExceededEvents.clear();
    }
}

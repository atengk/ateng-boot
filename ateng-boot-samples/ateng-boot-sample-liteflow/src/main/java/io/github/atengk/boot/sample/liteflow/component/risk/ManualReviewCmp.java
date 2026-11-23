package io.github.atengk.boot.sample.liteflow.component.risk;

import io.github.atengk.boot.liteflow.component.BaseNodeComponent;
import io.github.atengk.boot.sample.liteflow.context.RiskApprovalContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 转人工专家复核终态节点组件
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@Component("manualReviewCmp")
public class ManualReviewCmp extends BaseNodeComponent<RiskApprovalContext> {

    @Override
    public void process(RiskApprovalContext context) {
        context.setApproved(false);
        context.setDecision("MANUAL");
        // 暂定建议额度为申请额度的 50%
        BigDecimal halfAmount = context.getApplyAmount().divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
        context.setApprovedAmount(halfAmount);
        log.info("【风控流程-人工复核】进入人工坐席复核任务池，applicantId={}, 建议预批额度={}",
                context.getApplicantId(), halfAmount);
    }
}

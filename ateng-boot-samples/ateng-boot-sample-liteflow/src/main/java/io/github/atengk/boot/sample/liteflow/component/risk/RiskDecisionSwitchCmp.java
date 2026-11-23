package io.github.atengk.boot.sample.liteflow.component.risk;

import io.github.atengk.boot.liteflow.component.BaseSwitchComponent;
import io.github.atengk.boot.sample.liteflow.context.RiskApprovalContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 信贷审批综合决策分流 SWITCH 组件
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@Component("riskDecisionSwitchCmp")
public class RiskDecisionSwitchCmp extends BaseSwitchComponent<RiskApprovalContext> {

    @Override
    public String processSwitch(RiskApprovalContext context) {
        int fraudScore = context.getAntiFraudScore();
        int creditScore = context.getCreditScore() == null ? 0 : context.getCreditScore();

        log.info("【风控流程-决策分流】开始综合评级，applicantId={}, fraudScore={}, creditScore={}",
                context.getApplicantId(), fraudScore, creditScore);

        if (fraudScore >= 80 || creditScore < 550) {
            context.setRiskLevel("HIGH");
            context.setDecisionReason("反欺诈评分超标或征信分严重偏低");
            log.info("【风控流程-决策分流】路由至拒贷节点: loanRejectCmp");
            return "loanRejectCmp";
        }

        if (fraudScore >= 50 || creditScore < 680) {
            context.setRiskLevel("MEDIUM");
            context.setDecisionReason("处于风险边缘阈值区间，转人工专家坐席终审");
            log.info("【风控流程-决策分流】路由至人工复核节点: manualReviewCmp");
            return "manualReviewCmp";
        }

        context.setRiskLevel("LOW");
        context.setDecisionReason("信用优良且无欺诈风险，系统自动核准");
        log.info("【风控流程-决策分流】路由至极速放款节点: loanPassCmp");
        return "loanPassCmp";
    }
}

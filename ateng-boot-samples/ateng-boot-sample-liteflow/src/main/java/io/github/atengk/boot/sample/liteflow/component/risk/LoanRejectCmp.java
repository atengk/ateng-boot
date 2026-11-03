package io.github.atengk.boot.sample.liteflow.component.risk;

import io.github.atengk.boot.liteflow.component.BaseNodeComponent;
import io.github.atengk.boot.sample.liteflow.context.RiskApprovalContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 审批直接拒绝终态节点组件
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@Component("loanRejectCmp")
public class LoanRejectCmp extends BaseNodeComponent<RiskApprovalContext> {

    @Override
    public void process(RiskApprovalContext context) {
        context.setApproved(false);
        context.setDecision("REJECT");
        context.setApprovedAmount(BigDecimal.ZERO);
        log.warn("【风控流程-拒贷终态】信贷审批未通过！applicantId={}, 原因={}",
                context.getApplicantId(), context.getDecisionReason());
    }
}

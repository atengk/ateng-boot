package io.github.atengk.boot.sample.liteflow.component.risk;

import io.github.atengk.boot.liteflow.component.BaseNodeComponent;
import io.github.atengk.boot.sample.liteflow.context.RiskApprovalContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 自动核准授信终态节点组件
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@Component("loanPassCmp")
public class LoanPassCmp extends BaseNodeComponent<RiskApprovalContext> {

    @Override
    public void process(RiskApprovalContext context) {
        context.setApproved(true);
        context.setDecision("PASS");
        context.setApprovedAmount(context.getApplyAmount());
        log.info("【风控流程-核准终态】信贷审批通过！applicantId={}, 授信额度={}",
                context.getApplicantId(), context.getApprovedAmount());
    }
}

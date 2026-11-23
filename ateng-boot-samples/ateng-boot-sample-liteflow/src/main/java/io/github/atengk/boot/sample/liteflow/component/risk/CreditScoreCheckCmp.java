package io.github.atengk.boot.sample.liteflow.component.risk;

import io.github.atengk.boot.liteflow.component.BaseNodeComponent;
import io.github.atengk.boot.sample.liteflow.context.RiskApprovalContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 百行征信及央行征信报告基础分并行评估组件
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@Component("creditScoreCheckCmp")
public class CreditScoreCheckCmp extends BaseNodeComponent<RiskApprovalContext> {

    @Override
    public void process(RiskApprovalContext context) {
        Integer creditScore = context.getCreditScore();
        log.info("【风控流程-并行征信】读取申请人征信报告评分：applicantId={}, creditScore={}",
                context.getApplicantId(), creditScore);
    }
}

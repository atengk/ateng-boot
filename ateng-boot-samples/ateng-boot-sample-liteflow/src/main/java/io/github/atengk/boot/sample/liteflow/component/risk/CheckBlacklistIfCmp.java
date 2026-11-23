package io.github.atengk.boot.sample.liteflow.component.risk;

import io.github.atengk.boot.liteflow.component.BaseIfComponent;
import io.github.atengk.boot.sample.liteflow.context.RiskApprovalContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 央行及行业高危失信黑名单前置筛查 IF 条件组件
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@Component("checkBlacklistIfCmp")
public class CheckBlacklistIfCmp extends BaseIfComponent<RiskApprovalContext> {

    @Override
    public boolean processIf(RiskApprovalContext context) {
        String idCard = context.getIdCard();
        String applicantId = context.getApplicantId();

        boolean hitBlacklist = (idCard != null && idCard.contains("999"))
                || (applicantId != null && applicantId.toLowerCase().contains("black"));

        if (hitBlacklist) {
            log.warn("【风控流程】申请人命中严重失信黑名单，直接阻断！applicantId={}, idCard={}", applicantId, idCard);
            context.setBlacklisted(true);
            context.setRiskLevel("HIGH");
            context.setDecisionReason("命中行业涉案涉诉与失信惩戒黑名单");
            return true;
        }

        log.info("【风控流程】申请人未命中黑名单，准予进入深层并行风控评估，applicantId={}", applicantId);
        context.setBlacklisted(false);
        return false;
    }
}

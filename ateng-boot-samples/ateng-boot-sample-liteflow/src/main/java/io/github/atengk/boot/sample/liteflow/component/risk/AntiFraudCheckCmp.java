package io.github.atengk.boot.sample.liteflow.component.risk;

import io.github.atengk.boot.liteflow.component.BaseNodeComponent;
import io.github.atengk.boot.sample.liteflow.context.RiskApprovalContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 设备指纹与行为特征多维反欺诈并行筛查组件
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@Component("antiFraudCheckCmp")
public class AntiFraudCheckCmp extends BaseNodeComponent<RiskApprovalContext> {

    @Override
    public void process(RiskApprovalContext context) {
        String applicantId = context.getApplicantId();

        // 模拟反欺诈多维评分计算（0-100 分，分值越高风险越高）
        int fraudScore = 15;
        if (applicantId != null && applicantId.toLowerCase().contains("fraud")) {
            fraudScore = 85;
        }

        context.setAntiFraudScore(fraudScore);
        log.info("【风控流程-并行反欺诈】反欺诈评估完成，applicantId={}, fraudScore={}", applicantId, fraudScore);
    }
}

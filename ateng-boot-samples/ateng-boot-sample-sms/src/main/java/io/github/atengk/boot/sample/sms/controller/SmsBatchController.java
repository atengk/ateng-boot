package io.github.atengk.boot.sample.sms.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.sms.dto.BatchSmsSendRequest;
import io.github.atengk.boot.sample.sms.dto.DifferentialBatchSmsRequest;
import io.github.atengk.boot.sample.sms.service.SmsScenarioService;
import io.github.atengk.boot.sms.model.BatchSmsResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 批量营销与差异化短信群发业务领域控制器
 * 严格遵循显式 Result 响应契约与 JSR-303 分层防卫
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Slf4j
@RestController
@RequestMapping("/sample/sms/batch")
@RequiredArgsConstructor
public class SmsBatchController {

    private final SmsScenarioService smsScenarioService;

    /**
     * 同参海量短信自动切片批量群发接口
     *
     * @param request 批量同参发信请求传输对象
     * @return 统一响应体包装的批量短信发送汇总结果
     */
    @PostMapping("/same")
    public Result<BatchSmsResult> sendSameBatch(@Valid @RequestBody BatchSmsSendRequest request) {
        log.info("[SmsBatchController] 收到同参海量批量群发请求 -> 目标总数: {}, 模板: {}",
                request.getPhones().size(), request.getTemplateId());
        BatchSmsResult result = smsScenarioService.sendBatchSms(request);
        return Result.success(result);
    }

    /**
     * 千人千面个性化差异批次短信群发接口
     *
     * @param request 差异化批量发信请求传输对象
     * @return 统一响应体包装的批量短信发送汇总结果
     */
    @PostMapping("/differential")
    public Result<BatchSmsResult> sendDifferentialBatch(@Valid @RequestBody DifferentialBatchSmsRequest request) {
        log.info("[SmsBatchController] 收到千人千面差异化批量群发请求 -> 目标总数: {}, 模板: {}",
                request.getPhoneParams().size(), request.getTemplateId());
        BatchSmsResult result = smsScenarioService.sendDifferentialBatchSms(request);
        return Result.success(result);
    }
}

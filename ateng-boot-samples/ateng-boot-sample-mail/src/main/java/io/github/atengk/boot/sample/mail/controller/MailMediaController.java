package io.github.atengk.boot.sample.mail.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.mail.model.MailSendResult;
import io.github.atengk.boot.sample.mail.dto.AttachmentMailRequest;
import io.github.atengk.boot.sample.mail.dto.InlineMediaMailRequest;
import io.github.atengk.boot.sample.mail.service.MailScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * 邮件富媒体、附件与内联资源控制器
 * <p>
 * 提供 RFC 6266 标准流式附件装配与正交内联资源 (Inline CID) 图文混排发信端点。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-10
 */
@RestController
@RequestMapping("/sample/mail/media")
@RequiredArgsConstructor
@Validated
public class MailMediaController {

    private final MailScenarioService mailScenarioService;

    /**
     * 发送挂载流式大附件的邮件（RFC 6266 文件防爆与中文文件名防乱码）
     *
     * @param request 附件发信请求
     * @return 强类型发信回执
     */
    @PostMapping("/attachment")
    public Result<MailSendResult> sendWithAttachment(@Valid @RequestBody AttachmentMailRequest request) {
        String fileName = request.getFileName() != null && !request.getFileName().isBlank()
                ? request.getFileName() : "月度电子账单.pdf";
        // 模拟电子账单二进制流数据
        byte[] demoContent = ("%PDF-1.4\n1 0 obj\n<< /Title (Monthly Statement) /Content ("
                + request.getContent() + ") >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF")
                .getBytes(StandardCharsets.UTF_8);

        MailSendResult result = mailScenarioService.sendWithAttachment(
                request.getTo(),
                request.getSubject(),
                request.getContent(),
                fileName,
                demoContent
        );
        return Result.success(result);
    }

    /**
     * 发送正交内联资源 (Inline CID) 图文混排邮件
     *
     * @param request 内联媒体请求
     * @return 强类型发信回执
     */
    @PostMapping("/inline")
    public Result<MailSendResult> sendWithInline(@Valid @RequestBody InlineMediaMailRequest request) {
        MailSendResult result = mailScenarioService.sendWithInline(
                request.getTo(),
                request.getSubject(),
                request.getTitle()
        );
        return Result.success(result);
    }
}

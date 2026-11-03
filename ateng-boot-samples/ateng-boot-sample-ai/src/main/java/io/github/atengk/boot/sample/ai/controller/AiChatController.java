package io.github.atengk.boot.sample.ai.controller;

import io.github.atengk.boot.ai.template.AiTemplate;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ai.dto.AiChatRequestDto;
import io.github.atengk.boot.sample.ai.vo.AiChatResponseVo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 智能体对话控制器
 * 提供通用同步对话、图文多模态识别与双通道 SSE 打字机流式推流能力
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/ai/chat")
@RequiredArgsConstructor
public class AiChatController {

    private final AiTemplate aiTemplate;

    /**
     * 同步对话与图文多模态分析
     *
     * @param request 对话入参 DTO
     * @return 统一响应契约包装的对话结果 VO
     */
    @PostMapping("/sync")
    public Result<AiChatResponseVo> syncChat(@Valid @RequestBody AiChatRequestDto request) {
        String reply;
        boolean hasModel = StringUtils.hasText(request.getModel());
        boolean hasImages = request.getImages() != null && !request.getImages().isEmpty();

        if (hasImages) {
            reply = hasModel
                    ? aiTemplate.chatWithModel(request.getModel(), request.getPrompt(), request.getImages())
                    : aiTemplate.chat(request.getPrompt(), request.getImages());
        } else {
            reply = hasModel
                    ? aiTemplate.chatWithModel(request.getModel(), request.getPrompt())
                    : aiTemplate.chat(request.getPrompt());
        }

        String resolvedModel = hasModel ? request.getModel() : "default";
        return Result.success(new AiChatResponseVo(reply, resolvedModel));
    }

    /**
     * 打字机流式推流接口 (SSE)
     * 推送 reasoning 思考链分片、text 正文分片以及 [DONE] 结束标志
     *
     * @param prompt 提示词
     * @param model  可选指定大模型名称
     * @return SseEmitter 实例
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public SseEmitter streamChat(
            @RequestParam("prompt") @NotBlank(message = "用户提示词 prompt 不能为空") String prompt,
            @RequestParam(name = "model", required = false) String model) {
        SseEmitter emitter = new SseEmitter(180_000L);
        if (StringUtils.hasText(model)) {
            aiTemplate.streamWithModel(model, prompt, emitter);
        } else {
            aiTemplate.stream(prompt, emitter);
        }
        return emitter;
    }
}

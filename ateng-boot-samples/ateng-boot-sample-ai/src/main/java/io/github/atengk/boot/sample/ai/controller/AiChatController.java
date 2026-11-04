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
        boolean hasImages = request.getImages() != null && !request.getImages().isEmpty();
        String reply = hasImages
                ? aiTemplate.chatWithModel(request.getModel(), request.getPrompt(), request.getImages())
                : aiTemplate.chatWithModel(request.getModel(), request.getPrompt());

        String resolvedModel = StringUtils.hasText(request.getModel()) ? request.getModel().trim() : "default";
        return Result.success(new AiChatResponseVo(reply, resolvedModel));
    }

    /**
     * 打字机流式推流接口 (SSE)
     * 推送 reasoning 思考链分片、text 正文分片以及 [DONE] 结束标志，支持通过 streamId 绑定物理连接句柄
     *
     * @param prompt   提示词
     * @param model    可选指定大模型名称
     * @param streamId 可选指定流式唯一标识 (未传时自动生成)
     * @return SseEmitter 实例
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public SseEmitter streamChat(
            @RequestParam("prompt") @NotBlank(message = "用户提示词 prompt 不能为空") String prompt,
            @RequestParam(name = "model", required = false) String model,
            @RequestParam(name = "streamId", required = false) String streamId) {
        SseEmitter emitter = new SseEmitter(AiTemplate.DEFAULT_SSE_TIMEOUT.toMillis());
        aiTemplate.streamWithModel(model, prompt, emitter, streamId);
        return emitter;
    }

    /**
     * 主动物理切断指定流式推流连接
     * 当前端用户点击“停止生成”或关闭会话时，由后端立即熔断 OkHttp 物理连接并终止大模型推流计费
     *
     * @param streamId 流式会话唯一标识
     * @return 切断结果
     */
    @PostMapping("/stream/cancel")
    public Result<Boolean> cancelStream(@RequestParam("streamId") @NotBlank(message = "流式标识 streamId 不能为空") String streamId) {
        boolean cancelled = aiTemplate.cancelStream(streamId);
        log.info("手动中断活跃流式连接 [streamId={}, success={}]", streamId, cancelled);
        return Result.success(cancelled);
    }

    /**
     * 获取当前处于活跃推流状态的物理连接总数
     *
     * @return 活跃连接数
     */
    @GetMapping("/stream/active-count")
    public Result<Integer> getActiveStreamCount() {
        int count = aiTemplate.getActiveStreamRegistry() != null ? aiTemplate.getActiveStreamRegistry().getActiveCount() : 0;
        return Result.success(count);
    }
}

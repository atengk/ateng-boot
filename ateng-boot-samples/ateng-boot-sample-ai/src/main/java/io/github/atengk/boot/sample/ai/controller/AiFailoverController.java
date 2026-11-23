package io.github.atengk.boot.sample.ai.controller;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.failover.FailoverChatLanguageModel;
import io.github.atengk.boot.ai.failover.NamedModel;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import io.github.atengk.boot.ai.template.AiTemplate;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ai.dto.AiFailoverChatRequest;
import io.github.atengk.boot.sample.ai.vo.AiFailoverChatVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 多模型故障自动转移与全局时延预算容灾体验接口
 *
 * @author Ateng
 * @since 2026-11-05
 */
@RestController
@RequestMapping("/sample/ai/failover")
@RequiredArgsConstructor
public class AiFailoverController {

    private static final Logger log = LoggerFactory.getLogger(AiFailoverController.class);

    private final AiModelRegistry aiModelRegistry;
    private final AiTemplate aiTemplate;

    /**
     * 故障转移容灾对话体验
     * <p>
     * 支持正常路由或模拟主模型 503 宕机异常，全自动无缝降级至备用模型，保障业务高可用。
     * </p>
     *
     * @param request 请求参数
     * @return 最终响应文本及实际接管模型详情
     */
    @PostMapping("/chat")
    public Result<AiFailoverChatVo> chat(@Valid @RequestBody AiFailoverChatRequest request) {
        String prompt = request.getPrompt();
        boolean simulateFailure = Boolean.TRUE.equals(request.getSimulatePrimaryFailure());

        if (simulateFailure) {
            return Result.success(executeSimulatedFailover(request, prompt));
        }

        // 正常生产路径：直接通过 AiTemplate 或模型注册表执行调用
        String reply = aiTemplate.chat(prompt);
        AiFailoverChatVo vo = AiFailoverChatVo.builder()
                .prompt(prompt)
                .response(reply)
                .resolvedModel(request.getPrimaryModel())
                .failoverOccurred(false)
                .message("主模型调用成功，无需故障转移")
                .build();
        return Result.success(vo);
    }

    private AiFailoverChatVo executeSimulatedFailover(AiFailoverChatRequest request, String prompt) {
        // 构造多模型候选序列：主模型抛出可重试服务端 503 异常，备选模型正常返回
        ChatLanguageModel failingPrimary = messages -> {
            throw new AiException(AiResultCode.AI_CALL_FAILED, "模拟主模型服务过载 503 Service Unavailable");
        };

        ChatLanguageModel healthyFallback = messages -> {
            // 如果容器中存在可用模型，则委托容器模型；否则保底返回容灾回复
            if (aiModelRegistry.hasChatModel(AiModelRegistry.DEFAULT_MODEL_NAME)) {
                return aiModelRegistry.getChatModel(AiModelRegistry.DEFAULT_MODEL_NAME).generate(messages);
            }
            return Response.from(AiMessage.from("【备用模型 " + request.getFallbackModel() + " 成功接管】已为您处理该请求：" + prompt));
        };

        List<NamedModel<ChatLanguageModel>> candidates = new ArrayList<>();
        candidates.add(NamedModel.of(request.getPrimaryModel(), failingPrimary));
        candidates.add(NamedModel.of(request.getFallbackModel(), healthyFallback));

        FailoverChatLanguageModel failoverModel = new FailoverChatLanguageModel(candidates, Duration.ofSeconds(10));
        Response<AiMessage> response = failoverModel.generate(UserMessage.from(prompt));

        return AiFailoverChatVo.builder()
                .prompt(prompt)
                .response(response.content().text())
                .resolvedModel(request.getFallbackModel())
                .failoverOccurred(true)
                .message("主模型故障，FailoverChatLanguageModel 触发无感转移至备选模型")
                .build();
    }
}

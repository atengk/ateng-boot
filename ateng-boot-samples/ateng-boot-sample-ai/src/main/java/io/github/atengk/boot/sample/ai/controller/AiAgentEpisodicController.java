package io.github.atengk.boot.sample.ai.controller;

import io.github.atengk.boot.ai.memory.episodic.EpisodicMemoryTemplate;
import io.github.atengk.boot.ai.memory.episodic.MemoryFact;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ai.agent.CustomerPersonaAgent;
import io.github.atengk.boot.sample.ai.agent.ReadOnlyConsultantAgent;
import io.github.atengk.boot.sample.ai.dto.AgentEpisodicChatRequest;
import io.github.atengk.boot.sample.ai.dto.MemoryFactRememberRequest;
import io.github.atengk.boot.sample.ai.vo.AgentEpisodicChatVo;
import io.github.atengk.boot.sample.ai.vo.MemoryFactVo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

/**
 * 声明式长效情景记忆智能体控制器
 * <p>
 * 提供声明式情景记忆感知、用户画像长效检索与自动沉淀的双向体验端点。
 * 遵循强类型契约设计与三阶梯用户识别规范。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/ai/agent/episodic")
@RequiredArgsConstructor
public class AiAgentEpisodicController {

    private final CustomerPersonaAgent customerPersonaAgent;
    private final ReadOnlyConsultantAgent readOnlyConsultantAgent;
    private final EpisodicMemoryTemplate episodicMemoryTemplate;

    /**
     * 与长效情景记忆智能体对话 (支持三阶梯用户感知与对话后事实自动提炼)
     *
     * @param request 对话请求 DTO
     * @return 对话响应 VO (包含智能体回复与前置召回画像清单)
     */
    @PostMapping("/chat")
    public Result<AgentEpisodicChatVo> chat(@Valid @RequestBody AgentEpisodicChatRequest request) {
        String effectiveUserId = resolveEffectiveUserId(request.getUserId(), request.getSessionId());
        log.info("[AGENT-EPISODIC-SAMPLE] 接收到智能体对话请求: userId={}, sessionId={}, message={}",
                effectiveUserId, request.getSessionId(), request.getMessage());

        // 召回当前画像用于出参透出 (Agent 内部也会自动召回并注入提示词)
        List<MemoryFactVo> recalled = getRecalledFactVos(effectiveUserId, request.getMessage());

        String reply;
        if (StringUtils.hasText(request.getUserId())) {
            reply = customerPersonaAgent.chatWithUser(request.getUserId().trim(), request.getMessage().trim());
        } else if (StringUtils.hasText(request.getSessionId())) {
            reply = customerPersonaAgent.chatWithSession(request.getSessionId().trim(), request.getMessage().trim());
        } else {
            reply = customerPersonaAgent.chat(request.getMessage().trim());
        }

        AgentEpisodicChatVo vo = AgentEpisodicChatVo.builder()
                .userId(effectiveUserId)
                .reply(reply)
                .recalledFacts(recalled)
                .build();
        return Result.success(vo);
    }

    /**
     * 只读模式咨询 (只感知注入历史偏好，不触发事后事实抽取)
     *
     * @param request 对话请求 DTO
     * @return 对话响应 VO
     */
    @PostMapping("/consult-readonly")
    public Result<AgentEpisodicChatVo> consultReadOnly(@Valid @RequestBody AgentEpisodicChatRequest request) {
        String effectiveUserId = resolveEffectiveUserId(request.getUserId(), request.getSessionId());
        log.info("[AGENT-EPISODIC-SAMPLE] 接收到只读咨询请求: userId={}, message={}",
                effectiveUserId, request.getMessage());

        List<MemoryFactVo> recalled = getRecalledFactVos(effectiveUserId, request.getMessage());

        String reply = readOnlyConsultantAgent.consult(
                StringUtils.hasText(effectiveUserId) ? effectiveUserId : "default-guest",
                request.getMessage().trim()
        );

        AgentEpisodicChatVo vo = AgentEpisodicChatVo.builder()
                .userId(effectiveUserId)
                .reply(reply)
                .recalledFacts(recalled)
                .build();
        return Result.success(vo);
    }

    /**
     * 查询指定用户的所有长效情景记忆事实清单
     *
     * @param userId 目标用户标识
     * @return 事实清单
     */
    @GetMapping("/facts/{userId}")
    public Result<List<MemoryFactVo>> listFacts(
            @PathVariable("userId") @NotBlank(message = "用户标识 userId 不能为空") String userId) {
        List<MemoryFact> facts = episodicMemoryTemplate.listFacts(userId.trim(), 50);
        if (facts == null || facts.isEmpty()) {
            return Result.success(Collections.emptyList());
        }
        return Result.success(facts.stream().map(this::toVo).toList());
    }

    /**
     * 手动为指定用户预置长效情景事实
     *
     * @param request 事实请求
     * @return 持久化后的事实视图
     */
    @PostMapping("/facts")
    public Result<MemoryFactVo> rememberFact(@Valid @RequestBody MemoryFactRememberRequest request) {
        MemoryFact fact = MemoryFact.builder()
                .subject(request.getSubject())
                .predicate(request.getPredicate())
                .object(request.getObject())
                .statement(request.getStatement())
                .confidence(request.getConfidence())
                .userId(request.getUserId())
                .tenantId(request.getTenantId())
                .metadata(request.getMetadata())
                .build();
        episodicMemoryTemplate.remember(fact);
        return Result.success(toVo(fact));
    }

    /**
     * 删除指定事实
     *
     * @param factId 事实全局唯一标识
     * @return 是否成功
     */
    @DeleteMapping("/facts/{factId}")
    public Result<Boolean> forgetFact(
            @PathVariable("factId") @NotBlank(message = "事实标识 factId 不能为空") String factId) {
        episodicMemoryTemplate.forget(factId.trim());
        return Result.success(true);
    }

    private String resolveEffectiveUserId(String userId, String sessionId) {
        if (StringUtils.hasText(userId)) {
            return userId.trim();
        }
        if (StringUtils.hasText(sessionId)) {
            return sessionId.trim();
        }
        String contextUser = UserContextHolder.getUserId();
        return StringUtils.hasText(contextUser) ? contextUser.trim() : "anonymous";
    }

    private List<MemoryFactVo> getRecalledFactVos(String userId, String query) {
        if (!StringUtils.hasText(userId)) {
            return Collections.emptyList();
        }
        List<MemoryFact> facts = episodicMemoryTemplate.recall(query, userId, 5, 0.6);
        if (facts == null || facts.isEmpty()) {
            facts = episodicMemoryTemplate.listFacts(userId, 5);
        }
        if (facts == null || facts.isEmpty()) {
            return Collections.emptyList();
        }
        return facts.stream().map(this::toVo).toList();
    }

    private MemoryFactVo toVo(MemoryFact fact) {
        if (fact == null) {
            return null;
        }
        return MemoryFactVo.builder()
                .id(fact.getId())
                .userId(fact.getUserId())
                .tenantId(fact.getTenantId())
                .subject(fact.getSubject())
                .predicate(fact.getPredicate())
                .object(fact.getObject())
                .statement(fact.getStatement())
                .slotKey(fact.getSlotKey())
                .confidence(fact.getConfidence())
                .timestamp(fact.getTimestamp())
                .metadata(fact.getMetadata())
                .build();
    }
}

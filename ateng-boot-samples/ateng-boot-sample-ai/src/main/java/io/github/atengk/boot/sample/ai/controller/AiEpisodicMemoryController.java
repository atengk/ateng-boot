package io.github.atengk.boot.sample.ai.controller;

import io.github.atengk.boot.ai.memory.episodic.EpisodicMemoryTemplate;
import io.github.atengk.boot.ai.memory.episodic.MemoryFact;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.ai.dto.MemoryExtractRequest;
import io.github.atengk.boot.sample.ai.dto.MemoryFactRememberRequest;
import io.github.atengk.boot.sample.ai.dto.MemoryRecallRequest;
import io.github.atengk.boot.sample.ai.vo.MemoryFactVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;

/**
 * 跨会话原子化长效情景语义记忆体验控制器
 * <p>
 * 提供长效事实手动持久化与语义槽位覆写、对话历史事实异步/同步抽取入库、
 * 语义向量召回检索、事实清单查询及对称遗忘物理销毁等全生命周期 REST 接口。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Slf4j
@RestController
@RequestMapping("/sample/ai/episodic-memory")
@RequiredArgsConstructor
public class AiEpisodicMemoryController {

    private final EpisodicMemoryTemplate episodicMemoryTemplate;

    /**
     * 手动记录单条原子事实 (自动计算语义槽位键并覆写冲突旧事实)
     *
     * @param request 事实记录请求
     * @return 记录成功的事实视图
     */
    @PostMapping("/remember")
    public Result<MemoryFactVo> remember(@Valid @RequestBody MemoryFactRememberRequest request) {
        log.info("[AI-EPISODIC-SAMPLE] 接收到情景事实持久化请求: subject={}, predicate={}, object={}, userId={}",
                request.getSubject(), request.getPredicate(), request.getObject(), request.getUserId());

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

        String factId = episodicMemoryTemplate.remember(fact);
        fact.setId(factId);

        return Result.success(toVo(fact));
    }

    /**
     * 从多轮对话历史记录中提取原子事实并自动入库
     *
     * @param request 抽取请求
     * @return 抽取并成功记住的事实列表
     */
    @PostMapping("/extract")
    public Result<List<MemoryFactVo>> extract(@Valid @RequestBody MemoryExtractRequest request) {
        log.info("[AI-EPISODIC-SAMPLE] 接收到对话事实抽取入库请求: userId={}, textLength={}",
                request.getUserId(), request.getConversationText().length());

        List<MemoryFact> extracted = episodicMemoryTemplate.extractAndRemember(
                request.getConversationText(),
                request.getUserId(),
                request.getTenantId()
        );

        List<MemoryFactVo> voList = (extracted != null && !extracted.isEmpty())
                ? extracted.stream().map(this::toVo).toList()
                : Collections.emptyList();

        return Result.success(voList);
    }

    /**
     * 依据当前用户问询检索召回语义相关的事实清单 (复合租户与用户物理隔离)
     *
     * @param request 召回检索请求
     * @return 命中的事实清单
     */
    @PostMapping("/recall")
    public Result<List<MemoryFactVo>> recall(@Valid @RequestBody MemoryRecallRequest request) {
        log.info("[AI-EPISODIC-SAMPLE] 接收到语义记忆召回检索请求: query={}, userId={}, maxResults={}",
                request.getQuery(), request.getUserId(), request.getMaxResults());

        int max = request.getMaxResults() != null ? request.getMaxResults() : 5;
        List<MemoryFact> recalled = episodicMemoryTemplate.recall(
                request.getQuery(),
                request.getUserId(),
                max,
                request.getMinScore()
        );

        List<MemoryFactVo> voList = (recalled != null && !recalled.isEmpty())
                ? recalled.stream().map(this::toVo).toList()
                : Collections.emptyList();

        return Result.success(voList);
    }

    /**
     * 查询指定用户或当前上下文用户的事实清单
     *
     * @param userId 目标用户标识 (可选)
     * @param limit  最大查询条数 (可选，默认 50)
     * @return 事实清单
     */
    @GetMapping("/facts")
    public Result<List<MemoryFactVo>> listFacts(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false, defaultValue = "50") Integer limit) {
        List<MemoryFact> facts = episodicMemoryTemplate.listFacts(userId, limit != null ? limit : 50);
        List<MemoryFactVo> voList = (facts != null && !facts.isEmpty())
                ? facts.stream().map(this::toVo).toList()
                : Collections.emptyList();
        return Result.success(voList);
    }

    /**
     * 依据事实唯一标识物理遗忘 (对称销毁)
     *
     * @param factId 事实标识
     * @return 成功状态
     */
    @DeleteMapping("/facts/{factId}")
    public Result<Boolean> forgetFact(@PathVariable String factId) {
        log.info("[AI-EPISODIC-SAMPLE] 遗忘单条事实 [factId={}]", factId);
        episodicMemoryTemplate.forget(factId);
        return Result.success(true);
    }

    /**
     * 依据语义槽位键遗忘事实 (如 "user:preferred_language")
     *
     * @param slotKey 语义槽位键
     * @param userId  用户标识 (可选)
     * @return 成功状态
     */
    @DeleteMapping("/slots")
    public Result<Boolean> forgetBySlot(
            @RequestParam String slotKey,
            @RequestParam(required = false) String userId) {
        log.info("[AI-EPISODIC-SAMPLE] 依据槽位遗忘事实 [slotKey={}, userId={}]", slotKey, userId);
        episodicMemoryTemplate.forgetBySlot(userId, slotKey);
        return Result.success(true);
    }

    /**
     * 遗忘目标用户所有情景记忆事实
     *
     * @param userId 用户标识
     * @return 成功状态
     */
    @DeleteMapping("/users/{userId}")
    public Result<Boolean> forgetAllUserFacts(@PathVariable String userId) {
        log.info("[AI-EPISODIC-SAMPLE] 遗忘目标用户所有情景记忆 [userId={}]", userId);
        episodicMemoryTemplate.forgetAll(userId);
        return Result.success(true);
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

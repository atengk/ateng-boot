package io.github.atengk.boot.sample.liteflow.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.liteflow.core.LiteflowTemplate;
import io.github.atengk.boot.liteflow.route.LiteflowRouteResult;
import io.github.atengk.boot.sample.liteflow.context.PromotionContext;
import io.github.atengk.boot.sample.liteflow.model.PromotionMatchRequest;
import io.github.atengk.boot.sample.liteflow.model.PromotionMatchResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;

/**
 * LiteFlow 决策路由表（Route Chain）促销决策接口控制器
 * 演示：在业务命名空间（PROMOTION）下并行/串行执行多规则条件匹配，并将所有命中规则累加更新至单次请求共享强类型上下文
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Slf4j
@RestController
@RequestMapping("/sample/liteflow/routing")
@RequiredArgsConstructor
public class LiteflowPromotionRoutingController {

    private final LiteflowTemplate liteflowTemplate;

    /**
     * 匹配促销决策路由表并计算最终优惠金额
     *
     * @param request 促销决策匹配请求入参
     * @return 聚合计算后的最终优惠响应
     */
    @PostMapping("/match-promotions")
    public Result<PromotionMatchResponse> matchPromotions(@Valid @RequestBody PromotionMatchRequest request) {
        log.info("【促销决策路由匹配】接收请求: userId={}, userLevel={}, cartAmount={}, category={}",
                request.getUserId(), request.getUserLevel(), request.getCartAmount(), request.getCategory());

        PromotionContext context = new PromotionContext();
        context.setUserId(request.getUserId());
        context.setUserLevel(request.getUserLevel());
        context.setCartAmount(request.getCartAmount());
        context.setFinalAmount(request.getCartAmount());
        context.setCategory(request.getCategory());

        // 执行 PROMOTION 命名空间下的所有决策路由链
        LiteflowRouteResult<PromotionContext> routeResult = liteflowTemplate.executeRouteWithTrace(
                "PROMOTION", request, context);

        PromotionContext finalContext = routeResult.getContext();
        PromotionMatchResponse response = PromotionMatchResponse.builder()
                .userId(finalContext.getUserId())
                .userLevel(finalContext.getUserLevel())
                .originalAmount(finalContext.getCartAmount())
                .totalDiscount(finalContext.getTotalDiscount())
                .finalAmount(finalContext.getFinalAmount())
                .appliedPromotions(new ArrayList<>(finalContext.getAppliedPromotions()))
                .matchedChainIds(new ArrayList<>(routeResult.getMatchedChainIds()))
                .matchedCount(routeResult.getMatchedCount())
                .auditLogs(new ArrayList<>(finalContext.getAuditLogs()))
                .totalDurationMs(routeResult.getTotalDurationMs())
                .build();

        return Result.success(response);
    }
}

package io.github.atengk.boot.liteflow.rule.event;

import io.github.atengk.boot.liteflow.core.LiteflowTemplate;
import io.github.atengk.boot.liteflow.rule.LiteflowRuleSourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;

import java.util.Objects;

/**
 * LiteFlow 规则动态热更新事件监听器
 * 监听 {@link LiteflowRuleRefreshEvent} Spring 领域事件，
 * 协调多源规则加载器与 LiteflowTemplate 实现无停机零感知的动态热重载
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class LiteflowRuleRefreshListener implements ApplicationListener<LiteflowRuleRefreshEvent> {

    private static final Logger log = LoggerFactory.getLogger(LiteflowRuleRefreshListener.class);

    private final LiteflowRuleSourceManager ruleSourceManager;
    private final LiteflowTemplate liteflowTemplate;

    public LiteflowRuleRefreshListener(LiteflowRuleSourceManager ruleSourceManager,
                                       LiteflowTemplate liteflowTemplate) {
        this.ruleSourceManager = ruleSourceManager;
        this.liteflowTemplate = Objects.requireNonNull(liteflowTemplate, "liteflowTemplate 不能为空");
    }

    @Override
    public void onApplicationEvent(LiteflowRuleRefreshEvent event) {
        if (event == null || event.getRefreshType() == null) {
            return;
        }

        LiteflowRuleRefreshEvent.RefreshType type = event.getRefreshType();
        log.info("ateng-boot-liteflow: 接收到 LiteFlow 规则热更新领域事件 [type={}]", type);

        switch (type) {
            case ALL -> handleRefreshAll();
            case CHAIN -> handleRefreshChain(event);
            case RAW_CONTENT -> handleRefreshRawContent(event);
        }
    }

    private void handleRefreshAll() {
        if (this.ruleSourceManager != null) {
            this.ruleSourceManager.refreshAll();
        }
        try {
            this.liteflowTemplate.reloadRule();
        } catch (Exception e) {
            log.warn("ateng-boot-liteflow: 重载静态规则配置时提示: {}", e.getMessage());
        }
        log.info("ateng-boot-liteflow: 已成功完成全量规则热重载");
    }

    private void handleRefreshChain(LiteflowRuleRefreshEvent event) {
        String chainId = event.getChainId();
        String el = event.getEl();

        if (el != null && !el.isBlank()) {
            this.liteflowTemplate.reloadChain(chainId, el);
            log.info("ateng-boot-liteflow: 已依据事件显式 EL 完成规则链 [{}] 热更新", chainId);
        } else if (this.ruleSourceManager != null) {
            boolean success = this.ruleSourceManager.refreshChain(chainId);
            if (success) {
                log.info("ateng-boot-liteflow: 已从规则源拉取并完成规则链 [{}] 热更新", chainId);
            } else {
                log.warn("ateng-boot-liteflow: 规则源中未检索到规则链 [{}]，更新已忽略", chainId);
            }
        } else {
            log.warn("ateng-boot-liteflow: 未提供显式 EL 且未装配规则源管理器，无法刷新规则链 [{}]", chainId);
        }
    }

    private void handleRefreshRawContent(LiteflowRuleRefreshEvent event) {
        String content = event.getRuleContent();
        if (content != null && !content.isBlank()) {
            this.liteflowTemplate.reloadRule(content);
            log.info("ateng-boot-liteflow: 已完成原始规则文本内容热重载");
        }
    }
}

package io.github.atengk.boot.liteflow.rule;

import io.github.atengk.boot.liteflow.core.LiteflowTemplate;
import io.github.atengk.boot.liteflow.properties.AtengLiteflowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.OrderComparator;

import java.util.*;

/**
 * LiteFlow 多源动态规则管理器
 * 统一纳管所有 {@link LiteflowRuleSourceProvider} SPI 实现，负责应用启动预热加载、
 * 多源规则链去重与优先级合并、按需精准重载与全量热刷新协调
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class LiteflowRuleSourceManager implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(LiteflowRuleSourceManager.class);

    private final List<LiteflowRuleSourceProvider> providers;
    private final LiteflowTemplate liteflowTemplate;
    private final AtengLiteflowProperties properties;

    public LiteflowRuleSourceManager(List<LiteflowRuleSourceProvider> providers,
                                     LiteflowTemplate liteflowTemplate,
                                     AtengLiteflowProperties properties) {
        this.liteflowTemplate = Objects.requireNonNull(liteflowTemplate, "liteflowTemplate 不能为空");
        this.properties = properties != null ? properties : new AtengLiteflowProperties();

        List<LiteflowRuleSourceProvider> providerList = new ArrayList<>(providers != null ? providers : Collections.emptyList());
        providerList.sort(OrderComparator.INSTANCE);
        this.providers = Collections.unmodifiableList(providerList);
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!properties.getDynamicRule().isEnabled()) {
            log.info("ateng-boot-liteflow: 多源动态规则管理器已禁用");
            return;
        }

        if (properties.getDynamicRule().isAutoLoadOnStartup()) {
            if (this.providers.isEmpty()) {
                log.debug("ateng-boot-liteflow: 未探测到任何 LiteflowRuleSourceProvider SPI 实现，跳过启动自动加载");
                return;
            }
            log.info("ateng-boot-liteflow: 正在初始化多源动态规则预热加载 (已注册规则源数量: {})...", this.providers.size());
            refreshAll();
        }
    }

    /**
     * 从所有规则源聚合加载全量规则定义（相同 chainId 遵循优先级覆盖）
     *
     * @return 聚合后的全量规则定义列表
     */
    public List<RuleSourceDefinition> loadAllRules() {
        if (this.providers.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, RuleSourceDefinition> map = new LinkedHashMap<>();
        for (LiteflowRuleSourceProvider provider : this.providers) {
            try {
                List<RuleSourceDefinition> list = provider.loadRules();
                if (list != null && !list.isEmpty()) {
                    for (RuleSourceDefinition def : list) {
                        if (def != null && def.getChainId() != null && !def.getChainId().isBlank()) {
                            // 低数值优先：首次放入的优先级最高，后续同名 chainId 自动被忽略
                            map.putIfAbsent(def.getChainId(), def);
                        }
                    }
                }
            } catch (Exception e) {
                if (properties.getDynamicRule().isFailFast()) {
                    throw new IllegalStateException("从规则源 [" + provider.getName() + "] 加载规则列表失败", e);
                }
                log.error("ateng-boot-liteflow: 从规则源 [{}] 加载规则列表失败: {}", provider.getName(), e.getMessage(), e);
            }
        }
        return new ArrayList<>(map.values());
    }

    /**
     * 按优先级从所有规则源中检索指定的单个规则定义
     *
     * @param chainId 流程链 ID
     * @return 规则定义；若未匹配到则返回 null
     */
    public RuleSourceDefinition loadRule(String chainId) {
        if (chainId == null || chainId.isBlank() || this.providers.isEmpty()) {
            return null;
        }
        for (LiteflowRuleSourceProvider provider : this.providers) {
            try {
                RuleSourceDefinition def = provider.loadRule(chainId);
                if (def != null) {
                    return def;
                }
            } catch (Exception e) {
                if (properties.getDynamicRule().isFailFast()) {
                    throw new IllegalStateException("从规则源 [" + provider.getName() + "] 查询规则 [" + chainId + "] 失败", e);
                }
                log.warn("ateng-boot-liteflow: 从规则源 [{}] 查询规则 [{}] 异常: {}", provider.getName(), chainId, e.getMessage());
            }
        }
        return null;
    }

    /**
     * 全量刷新所有规则源并同步热生效至 LiteFlow 执行器
     *
     * @return 成功加载/更新的规则链数量
     */
    public synchronized int refreshAll() {
        List<RuleSourceDefinition> definitions = loadAllRules();
        int activeCount = 0;
        int disabledCount = 0;

        for (RuleSourceDefinition def : definitions) {
            String chainId = def.getChainId();
            try {
                if (def.isEnabled() && def.getEl() != null && !def.getEl().isBlank()) {
                    if (def.getRouteEl() != null && !def.getRouteEl().isBlank()) {
                        String ns = (def.getNamespace() != null && !def.getNamespace().isBlank())
                                ? def.getNamespace() : def.getRouteGroup();
                        this.liteflowTemplate.reloadRouteChain(chainId, ns, def.getRouteEl(), def.getEl());
                    } else {
                        this.liteflowTemplate.reloadChain(chainId, def.getEl());
                    }
                    activeCount++;
                } else if (!def.isEnabled()) {
                    if (this.liteflowTemplate.containChain(chainId)) {
                        this.liteflowTemplate.removeChain(chainId);
                        disabledCount++;
                    }
                }
            } catch (Exception e) {
                if (properties.getDynamicRule().isFailFast()) {
                    throw e instanceof RuntimeException ? (RuntimeException) e : new IllegalStateException("刷新规则链 [" + chainId + "] 失败", e);
                }
                log.error("ateng-boot-liteflow: 刷新规则链 [{}] 失败: {}", chainId, e.getMessage(), e);
            }
        }

        log.info("ateng-boot-liteflow: 多源动态规则全量刷新完成，生效启用 [{}] 条规则链，下线停用 [{}] 条规则链",
                activeCount, disabledCount);
        return activeCount;
    }

    /**
     * 根据流程链 ID 从规则源重新拉取定义并执行热更新
     *
     * @param chainId 流程链 ID
     * @return 是否成功更新
     */
    public synchronized boolean refreshChain(String chainId) {
        if (chainId == null || chainId.isBlank()) {
            return false;
        }
        RuleSourceDefinition def = loadRule(chainId);
        if (def == null) {
            log.warn("ateng-boot-liteflow: 未在已注册的规则源中检索到流程链 [{}]，无法热更新", chainId);
            return false;
        }
        try {
            if (def.isEnabled() && def.getEl() != null && !def.getEl().isBlank()) {
                if (def.getRouteEl() != null && !def.getRouteEl().isBlank()) {
                    String ns = (def.getNamespace() != null && !def.getNamespace().isBlank())
                            ? def.getNamespace() : def.getRouteGroup();
                    this.liteflowTemplate.reloadRouteChain(chainId, ns, def.getRouteEl(), def.getEl());
                } else {
                    this.liteflowTemplate.reloadChain(chainId, def.getEl());
                }
                return true;
            } else {
                this.liteflowTemplate.removeChain(chainId);
                return true;
            }
        } catch (Exception e) {
            if (properties.getDynamicRule().isFailFast()) {
                throw e instanceof RuntimeException ? (RuntimeException) e : new IllegalStateException("热更新流程链 [" + chainId + "] 失败", e);
            }
            log.error("ateng-boot-liteflow: 热更新流程链 [{}] 失败: {}", chainId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 编程式显式热更新单条规则流程链
     *
     * @param chainId 流程链 ID
     * @param el      EL 表达式
     */
    public void refreshChain(String chainId, String el) {
        this.liteflowTemplate.reloadChain(chainId, el);
    }

    /**
     * 编程式显式热更新单条决策路由流程链
     *
     * @param chainId   流程链 ID
     * @param namespace 业务路由命名空间（可选）
     * @param routeEl   决策路由条件表达式
     * @param bodyEl    规则流程主体 EL 表达式
     */
    public void refreshRouteChain(String chainId, String namespace, String routeEl, String bodyEl) {
        this.liteflowTemplate.reloadRouteChain(chainId, namespace, routeEl, bodyEl);
    }

    /**
     * 将流程链一键故障回退至上一个历史版本快照
     *
     * @param chainId 流程链 ID
     * @return true 表示成功回退；false 表示无历史快照
     */
    public boolean rollbackChain(String chainId) {
        return this.liteflowTemplate.rollbackChain(chainId);
    }

    /**
     * 获取指定流程链的历史版本 EL 快照列表（按时间倒序排列）
     *
     * @param chainId 流程链 ID
     * @return 历史快照列表，若无快照则保底返回空列表
     */
    public List<String> getChainHistorySnapshots(String chainId) {
        return this.liteflowTemplate.getChainHistorySnapshots(chainId);
    }

    /**
     * 获取所有已注册的规则源提供者列表（已按优先级排序）
     *
     * @return 规则源列表
     */
    public List<LiteflowRuleSourceProvider> getProviders() {
        return this.providers;
    }
}

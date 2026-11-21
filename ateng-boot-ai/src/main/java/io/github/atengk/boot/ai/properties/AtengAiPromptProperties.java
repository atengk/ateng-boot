package io.github.atengk.boot.ai.properties;

import lombok.Data;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 动态提示词资产托管与版本路由配置属性
 * <p>
 * 挂载于 {@code ateng.boot.ai.prompt.*}。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-22
 */
@Data
public class AtengAiPromptProperties {

    /**
     * 是否启用动态提示词注册表与资产加载能力
     */
    private boolean enabled = true;

    /**
     * 外部提示词资产扫描根目录列表（支持 classpath:/... 与文件系统路径）
     */
    private List<String> locations = new ArrayList<>(List.of("classpath:/prompts/"));

    /**
     * 通过 promptCode 调用时是否默认启用严格变量校验（缺失或为 null 抛出 AI_INVALID_PAYLOAD）
     */
    private boolean strictValidation = true;

    /**
     * 提示词定义缓存刷新 TTL（0s 表示仅启动加载与显式调用 refresh 时刷新，> 0s 时到期自动回源拉取）
     */
    private Duration cacheTtl = Duration.ZERO;
}

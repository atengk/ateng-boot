package io.github.atengk.boot.sample.web.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.web.ready.ApplicationReadyCardListener;
import io.github.atengk.boot.web.ready.ProductionSanityChecker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 云原生生命周期治理、启动就绪总览卡片与生产安全防呆场景控制器
 * 配合框架内置 /pre-stop 端点，提供 K8s 就绪探针流量排空、ASCII 启动就绪总览卡片预览与生产环境高危配置防呆自检
 *
 * @author Ateng
 * @since 2026-11-08
 */
@RestController
@RequestMapping("/sample/web/lifecycle")
public class WebCloudLifecycleController {

    private final ObjectProvider<ApplicationReadyCardListener> readyCardListenerProvider;
    private final ObjectProvider<ProductionSanityChecker> sanityCheckerProvider;
    private final ApplicationContext applicationContext;
    private final Environment environment;

    public WebCloudLifecycleController(ObjectProvider<ApplicationReadyCardListener> readyCardListenerProvider,
                                       ObjectProvider<ProductionSanityChecker> sanityCheckerProvider,
                                       ApplicationContext applicationContext,
                                       Environment environment) {
        this.readyCardListenerProvider = readyCardListenerProvider;
        this.sanityCheckerProvider = sanityCheckerProvider;
        this.applicationContext = applicationContext;
        this.environment = environment;
    }

    /**
     * 一体化获取启动就绪总览卡片与生产环境高危配置防呆自检综合报告
     *
     * @return 就绪卡片与防呆报告复合响应载荷
     */
    @GetMapping("/ready-and-sanity")
    public Result<Map<String, Object>> getReadyCardAndSanityReport() {
        Map<String, Object> data = new LinkedHashMap<>();

        // 1. 提取启动就绪 ASCII 卡片实时渲染快照
        ApplicationReadyCardListener readyCardListener = readyCardListenerProvider.getIfAvailable();
        if (readyCardListener != null) {
            String card = readyCardListener.buildCard(applicationContext, environment, Duration.ofMillis(1200));
            data.put("readyCard", card);
        } else {
            data.put("readyCard", "ApplicationReadyCardListener 未装配");
        }

        // 2. 提取生产环境高危配置防呆自检告警清单
        ProductionSanityChecker sanityChecker = sanityCheckerProvider.getIfAvailable();
        if (sanityChecker != null) {
            boolean isProd = sanityChecker.isProductionProfile(environment);
            List<String> warnings = sanityChecker.checkSanity(environment, applicationContext);
            data.put("isProductionProfile", isProd);
            data.put("sanityWarnings", warnings);
            data.put("warningCount", warnings.size());
        } else {
            data.put("isProductionProfile", false);
            data.put("sanityWarnings", Collections.emptyList());
            data.put("warningCount", 0);
        }

        return Result.success(data);
    }

    /**
     * 单独获取启动就绪 ASCII 总览卡片渲染快照
     *
     * @return ASCII 总览卡片纯文本
     */
    @GetMapping("/ready-card")
    public Result<String> getReadyCard() {
        ApplicationReadyCardListener readyCardListener = readyCardListenerProvider.getIfAvailable();
        if (readyCardListener != null) {
            return Result.success(readyCardListener.buildCard(applicationContext, environment, Duration.ofMillis(1200)));
        }
        return Result.success("ApplicationReadyCardListener 未装配");
    }

    /**
     * 单独获取当前运行环境的高危安全防呆自检报告
     *
     * @return 安全防呆告警列表
     */
    @GetMapping("/sanity-check")
    public Result<List<String>> getSanityCheckReport() {
        ProductionSanityChecker sanityChecker = sanityCheckerProvider.getIfAvailable();
        if (sanityChecker != null) {
            return Result.success(sanityChecker.checkSanity(environment, applicationContext));
        }
        return Result.success(Collections.emptyList());
    }
}

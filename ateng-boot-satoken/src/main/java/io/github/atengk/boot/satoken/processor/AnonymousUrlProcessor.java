package io.github.atengk.boot.satoken.processor;

import io.github.atengk.boot.satoken.annotation.Anonymous;
import io.github.atengk.boot.satoken.interceptor.AtengSaTokenInterceptor;
import io.github.atengk.boot.satoken.properties.SaTokenProperties;
import io.github.atengk.boot.satoken.spi.SaTokenWhitelistProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Sa-Token 匿名公开访问与动态白名单自动汇聚处理器
 * <p>
 * 监听 {@link ApplicationReadyEvent}，在应用启动就绪阶段自动通过 {@link RequestMappingHandlerMapping}
 * 扫描带有 {@link Anonymous} 注解的 Controller 类与方法，将路径变量（如 {@code {id}}）标准化转译为 AntPath 通配符（{@code *}），
 * 并与 {@link SaTokenWhitelistProvider} SPI 扩展及 YAML 静态白名单合并去重后动态注入 {@link AtengSaTokenInterceptor}。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-20
 */
@Slf4j
@RequiredArgsConstructor
public class AnonymousUrlProcessor implements ApplicationListener<ApplicationReadyEvent> {

    private final ApplicationContext applicationContext;
    private final SaTokenProperties properties;
    private final AtengSaTokenInterceptor saTokenInterceptor;
    private final ObjectProvider<SaTokenWhitelistProvider> whitelistProviders;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        process();
    }

    /**
     * 执行白名单汇聚与注入逻辑
     */
    public synchronized void process() {
        if (saTokenInterceptor == null) {
            log.debug("AtengSaTokenInterceptor 未初始化，跳过动态白名单汇聚");
            return;
        }

        Set<String> mergedWhitelist = new LinkedHashSet<>();

        // 1. 汇聚 YAML 静态白名单
        if (properties != null) {
            if (properties.getWhiteList() != null) {
                mergedWhitelist.addAll(properties.getWhiteList());
            }
            if (properties.getInterceptor() != null && properties.getInterceptor().getExcludes() != null) {
                mergedWhitelist.addAll(properties.getInterceptor().getExcludes());
            }
        }

        // 2. 汇聚 SaTokenWhitelistProvider SPI 动态白名单
        if (whitelistProviders != null) {
            for (SaTokenWhitelistProvider provider : whitelistProviders) {
                if (provider != null) {
                    try {
                        Set<String> customList = provider.getWhitelist();
                        if (customList != null && !customList.isEmpty()) {
                            mergedWhitelist.addAll(customList);
                            log.info("Sa-Token 汇聚 SPI 扩展白名单 [{}]: {}", provider.getClass().getSimpleName(), customList);
                        }
                    } catch (Exception e) {
                        log.error("Sa-Token 执行动态白名单 Provider [{}] 异常: ", provider.getClass().getName(), e);
                    }
                }
            }
        }

        // 3. 扫描 @Anonymous 声明式注解接口
        Set<String> anonymousUrls = scanAnonymousUrls();
        if (!anonymousUrls.isEmpty()) {
            mergedWhitelist.addAll(anonymousUrls);
            log.info("Sa-Token 扫描到 @Anonymous 声明式免鉴权路径 ({} 条): {}", anonymousUrls.size(), anonymousUrls);
        }

        // 4. 动态注入拦截器
        saTokenInterceptor.setWhitelist(mergedWhitelist);
        log.info("Sa-Token 全域路由白名单汇聚完成，生效总规则数: {}", mergedWhitelist.size());
    }

    /**
     * 扫描 Spring MVC 中所有标有 @Anonymous 的 Controller 与方法映射路径
     *
     * @return 转译为 AntPath 格式的路径集合
     */
    public Set<String> scanAnonymousUrls() {
        Set<String> urls = new LinkedHashSet<>();
        if (applicationContext == null) {
            return urls;
        }

        Map<String, RequestMappingHandlerMapping> handlerMappings = applicationContext.getBeansOfType(RequestMappingHandlerMapping.class);
        if (handlerMappings.isEmpty()) {
            return urls;
        }

        for (RequestMappingHandlerMapping handlerMapping : handlerMappings.values()) {
            Map<RequestMappingInfo, HandlerMethod> handlerMethods = handlerMapping.getHandlerMethods();
            for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMethods.entrySet()) {
                RequestMappingInfo mappingInfo = entry.getKey();
                HandlerMethod handlerMethod = entry.getValue();

                Class<?> beanType = handlerMethod.getBeanType();
                Method method = handlerMethod.getMethod();

                Anonymous methodAnno = AnnotatedElementUtils.findMergedAnnotation(method, Anonymous.class);
                Anonymous classAnno = AnnotatedElementUtils.findMergedAnnotation(beanType, Anonymous.class);

                boolean isAnonymous = false;
                if (methodAnno != null) {
                    isAnonymous = methodAnno.value();
                } else if (classAnno != null) {
                    isAnonymous = classAnno.value();
                }

                if (isAnonymous) {
                    Set<String> patterns = mappingInfo.getPatternValues();
                    if (patterns != null && !patterns.isEmpty()) {
                        for (String pattern : patterns) {
                            String antPath = convertToAntPath(pattern);
                            if (!antPath.isBlank()) {
                                urls.add(antPath);
                            }
                        }
                    }
                }
            }
        }
        return urls;
    }

    /**
     * 将 Spring MVC 路径模式转译为 AntPath 通配符
     * 例如：/users/{userId} -> /users/*
     * /files/{*path} -> /files/**
     *
     * @param pattern 原始模式
     * @return AntPath 模式
     */
    public static String convertToAntPath(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return "";
        }
        String result = pattern.trim();
        if (!result.startsWith("/")) {
            result = "/" + result;
        }
        // 将 Spring PathPattern 尾部贪婪通配符 {*path} 转为 AntPath 的 /**
        result = result.replaceAll("\\{\\*[^{}]*\\}", "**");
        // 将路径变量 {id} 或 {id:[0-9]+} 转为 AntPath 的 *
        result = result.replaceAll("\\{[^{}]*\\}", "*");
        return result;
    }
}

package io.github.atengk.boot.core.util;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEvent;
import org.springframework.core.env.Environment;

import java.util.Map;
import java.util.Objects;

/**
 * Spring 容器与上下文全局静态门面
 * 支持在非受管 Bean、静态工具类或跨线程闭包中安全获取 Spring Bean、环境变量与发布事件
 *
 * @author Ateng
 * @since 2026-10-17
 */
public class SpringUtil implements ApplicationContextAware, BeanFactoryPostProcessor {

    private static volatile ApplicationContext applicationContext;
    private static volatile ConfigurableListableBeanFactory beanFactory;

    @Override
    public void setApplicationContext(ApplicationContext context) throws BeansException {
        SpringUtil.applicationContext = context;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory factory) throws BeansException {
        SpringUtil.beanFactory = factory;
    }

    /**
     * 手动静态注入 ApplicationContext（便于非 SpringBoot 环境、轻量单测或独立上下文场景绑定）
     *
     * @param context Spring 上下文对象
     */
    public static void setContext(ApplicationContext context) {
        SpringUtil.applicationContext = context;
    }

    /**
     * 清理持有的静态引用，防止测试或容器销毁后内存泄漏
     */
    public static void clear() {
        SpringUtil.applicationContext = null;
        SpringUtil.beanFactory = null;
    }

    /**
     * 检查 Spring 容器是否已初始化并注入 SpringUtil
     *
     * @return 若已初始化则返回 true，否则返回 false
     */
    public static boolean isInitialized() {
        return applicationContext != null;
    }

    /**
     * 获取 ApplicationContext 上下文实例
     *
     * @return ApplicationContext
     * @throws IllegalStateException 若容器尚未初始化或未注入
     */
    public static ApplicationContext getApplicationContext() {
        assertApplicationContext();
        return applicationContext;
    }

    /**
     * 获取 ConfigurableListableBeanFactory 实例
     *
     * @return ConfigurableListableBeanFactory
     */
    public static ConfigurableListableBeanFactory getBeanFactory() {
        return beanFactory;
    }

    /**
     * 根据类型获取 Bean 实例
     *
     * @param clazz Bean 类型
     * @param <T>   Bean 泛型
     * @return Bean 实例
     */
    public static <T> T getBean(Class<T> clazz) {
        Objects.requireNonNull(clazz, "clazz 不能为空");
        return getApplicationContext().getBean(clazz);
    }

    /**
     * 根据 Bean 名称获取实例
     *
     * @param name Bean 名称
     * @return Bean 实例
     */
    public static Object getBean(String name) {
        Objects.requireNonNull(name, "name 不能为空");
        return getApplicationContext().getBean(name);
    }

    /**
     * 判断容器中是否包含指定名称的 Bean
     *
     * @param name Bean 名称
     * @return 若包含则返回 true，否则返回 false
     */
    public static boolean containsBean(String name) {
        Objects.requireNonNull(name, "name 不能为空");
        return getApplicationContext().containsBean(name);
    }

    /**
     * 根据 Bean 名称与类型获取实例
     *
     * @param name  Bean 名称
     * @param clazz Bean 类型
     * @param <T>   Bean 泛型
     * @return Bean 实例
     */
    public static <T> T getBean(String name, Class<T> clazz) {
        Objects.requireNonNull(name, "name 不能为空");
        Objects.requireNonNull(clazz, "clazz 不能为空");
        return getApplicationContext().getBean(name, clazz);
    }

    /**
     * 根据类型获取该类型所有已注册的 Bean 映射表
     *
     * @param type 类型
     * @param <T>  泛型
     * @return Map<BeanName, BeanInstance>
     */
    public static <T> Map<String, T> getBeansOfType(Class<T> type) {
        Objects.requireNonNull(type, "type 不能为空");
        return getApplicationContext().getBeansOfType(type);
    }

    /**
     * 获取环境配置 Environment
     *
     * @return Environment
     */
    public static Environment getEnvironment() {
        return getApplicationContext().getEnvironment();
    }

    /**
     * 获取配置属性值（字符串）
     *
     * @param key 配置键
     * @return 属性值，不存在时返回 null
     */
    public static String getProperty(String key) {
        return getEnvironment().getProperty(key);
    }

    /**
     * 获取配置属性值并提供默认兜底值
     *
     * @param key          配置键
     * @param defaultValue 兜底值
     * @return 属性值或默认值
     */
    public static String getProperty(String key, String defaultValue) {
        return getEnvironment().getProperty(key, defaultValue);
    }

    /**
     * 获取指定类型的配置属性值
     *
     * @param key        配置键
     * @param targetType 目标类型
     * @param <T>        泛型
     * @return 目标类型属性值
     */
    public static <T> T getProperty(String key, Class<T> targetType) {
        return getEnvironment().getProperty(key, targetType);
    }

    /**
     * 获取指定类型的配置属性值并提供默认兜底值
     *
     * @param key          配置键
     * @param targetType   目标类型
     * @param defaultValue 兜底值
     * @param <T>          泛型
     * @return 目标类型属性值或默认值
     */
    public static <T> T getProperty(String key, Class<T> targetType, T defaultValue) {
        return getEnvironment().getProperty(key, targetType, defaultValue);
    }

    /**
     * 发布 Spring 事件（支持任意 POJO 事件）
     *
     * @param event 事件对象
     */
    public static void publishEvent(Object event) {
        Objects.requireNonNull(event, "event 不能为空");
        getApplicationContext().publishEvent(event);
    }

    /**
     * 发布标准 ApplicationEvent 事件
     *
     * @param event 标准应用事件
     */
    public static void publishEvent(ApplicationEvent event) {
        Objects.requireNonNull(event, "event 不能为空");
        getApplicationContext().publishEvent(event);
    }

    /**
     * 获取当前激活的主 Profile，无激活时返回默认 profile
     *
     * @return 激活的 Profile 名称
     */
    public static String getActiveProfile() {
        String[] profiles = getActiveProfiles();
        return (profiles != null && profiles.length > 0) ? profiles[0] : null;
    }

    /**
     * 获取当前所有激活的 Profile 列表
     *
     * @return 激活的 Profile 数组
     */
    public static String[] getActiveProfiles() {
        return getEnvironment().getActiveProfiles();
    }

    private static void assertApplicationContext() {
        if (applicationContext == null) {
            throw new IllegalStateException("ApplicationContext 尚未初始化或未注入 SpringUtil，请确保 Spring 容器已正常启动并注入 SpringUtil");
        }
    }
}

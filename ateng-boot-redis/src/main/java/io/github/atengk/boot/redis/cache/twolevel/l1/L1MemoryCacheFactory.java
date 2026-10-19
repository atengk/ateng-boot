package io.github.atengk.boot.redis.cache.twolevel.l1;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.ClassUtils;

import java.time.Duration;

/**
 * L1 本地内存缓存工厂
 * 自动探测类路径 Caffeine 依赖是否存在：存在时装配高性能 CaffeineL1MemoryCache，缺失时平滑退化为 ConcurrentLruMemoryCache
 *
 * @author Ateng
 * @since 2026-10-19
 */
public final class L1MemoryCacheFactory {

    private static final Logger log = LoggerFactory.getLogger(L1MemoryCacheFactory.class);

    private static final boolean CAFFEINE_PRESENT = ClassUtils.isPresent(
            "com.github.benmanes.caffeine.cache.Caffeine",
            L1MemoryCacheFactory.class.getClassLoader()
    );

    static {
        if (CAFFEINE_PRESENT) {
            log.info("L1MemoryCacheFactory 成功探测到 Caffeine 运行时依赖，启用高性能 W-TinyLFU 本地缓存");
        } else {
            log.warn("L1MemoryCacheFactory 未检测到 Caffeine 运行时依赖，平滑退化为原生 JVM ConcurrentLruMemoryCache 本地缓存");
        }
    }

    private L1MemoryCacheFactory() {
    }

    /**
     * 判断当前运行时环境是否包含 Caffeine
     *
     * @return true 表示存在 Caffeine
     */
    public static boolean isCaffeinePresent() {
        return isCaffeinePresent(L1MemoryCacheFactory.class.getClassLoader());
    }

    /**
     * 判断指定类加载器上下文中是否包含 Caffeine
     *
     * @param classLoader 类加载器
     * @return true 表示存在 Caffeine
     */
    public static boolean isCaffeinePresent(ClassLoader classLoader) {
        return ClassUtils.isPresent("com.github.benmanes.caffeine.cache.Caffeine", classLoader);
    }

    /**
     * 创建 L1 本地内存缓存实例
     *
     * @param maxSize 最大条数
     * @param ttl 写入后过期时长
     * @return L1 内存缓存实例
     */
    public static L1MemoryCache create(long maxSize, Duration ttl) {
        return create(maxSize, ttl, CAFFEINE_PRESENT);
    }

    /**
     * 创建 L1 本地内存缓存实例（支持指定依赖探测状态，便于单测覆盖平滑退化路径）
     *
     * @param maxSize 最大条数
     * @param ttl 写入后过期时长
     * @param caffeinePresent 是否存在 Caffeine 运行时依赖
     * @return L1 内存缓存实例
     */
    public static L1MemoryCache create(long maxSize, Duration ttl, boolean caffeinePresent) {
        if (caffeinePresent) {
            return CaffeineHelper.create(maxSize, ttl);
        }
        return new ConcurrentLruMemoryCache(maxSize, ttl);
    }

    /**
     * 内部助手类：隔离 Caffeine 字节码加载，防止在缺少 Caffeine 类时引发 NoClassDefFoundError
     */
    private static class CaffeineHelper {
        static L1MemoryCache create(long maxSize, Duration ttl) {
            return new CaffeineL1MemoryCache(maxSize, ttl);
        }
    }
}

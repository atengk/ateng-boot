package io.github.atengk.boot.redis.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.redis.serializer.RedisSerializerFactory;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metric;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.ZSetOperations;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 全能 Redis 统一操作门面与生产级防呆工具类
 * 支持 Spring Bean 依赖注入与全局静态门面双重调用范式，
 * 严格收敛游标 scan 分批扫描，彻底杜绝裸 keys * 生产事故。
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class RedisUtil {

    private static volatile RedisTemplate<String, Object> staticTemplate;
    private static final ObjectMapper OBJECT_MAPPER = RedisSerializerFactory.createSafeObjectMapper();

    /**
     * 容器实例化构造器，自动同步静态门面引用
     *
     * @param template RedisTemplate
     */
    public RedisUtil(RedisTemplate<String, Object> template) {
        RedisUtil.staticTemplate = template;
    }

    /**
     * 获取受管的 RedisTemplate 实例
     *
     * @return RedisTemplate
     */
    public static RedisTemplate<String, Object> getTemplate() {
        if (staticTemplate == null) {
            throw new IllegalStateException("RedisUtil 尚未完成初始化，请确认 Spring 上下文已启动且 RedisTemplate 已装配。");
        }
        return staticTemplate;
    }

    /**
     * 获取用于类型转换的安全 ObjectMapper 实例
     *
     * @return ObjectMapper
     */
    public static ObjectMapper getObjectMapper() {
        return OBJECT_MAPPER;
    }

    // ==================== 1. Key 基础与安全游标扫描 ====================

    /**
     * 判断 key 是否存在
     *
     * @param key 键名
     * @return 是否存在
     */
    public static boolean hasKey(String key) {
        Boolean exists = getTemplate().hasKey(key);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * 设置 key 过期时间
     *
     * @param key 键名
     * @param timeout 过期时长
     * @param unit 时间单位
     * @return 是否设置成功
     */
    public static boolean expire(String key, long timeout, TimeUnit unit) {
        Boolean success = getTemplate().expire(key, timeout, unit);
        return Boolean.TRUE.equals(success);
    }

    /**
     * 设置 key 过期时间
     *
     * @param key 键名
     * @param timeout 过期时长
     * @return 是否设置成功
     */
    public static boolean expire(String key, Duration timeout) {
        if (timeout == null) {
            return false;
        }
        Boolean success = getTemplate().expire(key, timeout.toMillis(), TimeUnit.MILLISECONDS);
        return Boolean.TRUE.equals(success);
    }

    /**
     * 获取 key 剩余存活时间（单位：秒）
     *
     * @param key 键名
     * @return 剩余时间（秒）
     */
    public static long getExpire(String key) {
        return getExpire(key, TimeUnit.SECONDS);
    }

    /**
     * 获取 key 剩余存活时间
     *
     * @param key 键名
     * @param unit 时间单位
     * @return 剩余时间
     */
    public static long getExpire(String key, TimeUnit unit) {
        Long expire = getTemplate().getExpire(key, unit);
        return expire != null ? expire : -1L;
    }

    /**
     * 移除 key 的过期时间，使其永久保持有效
     *
     * @param key 键名
     * @return 是否移除成功
     */
    public static boolean persist(String key) {
        Boolean success = getTemplate().persist(key);
        return Boolean.TRUE.equals(success);
    }

    /**
     * 删除单一 key
     *
     * @param key 键名
     * @return 是否删除成功
     */
    public static boolean delete(String key) {
        Boolean success = getTemplate().delete(key);
        return Boolean.TRUE.equals(success);
    }

    /**
     * 批量删除 key 集合
     *
     * @param keys 键名集合
     * @return 成功删除的数量
     */
    public static long delete(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return 0L;
        }
        Long count = getTemplate().delete(keys);
        return count != null ? count : 0L;
    }

    /**
     * 生产级安全游标扫描（严格替代 keys *，分批安全迭代）
     *
     * @param pattern 匹配模式串（如 order:*）
     * @param count 每次游标拉取批次数量
     * @return 匹配的键集合
     */
    public static Set<String> scan(String pattern, long count) {
        ScanOptions options = ScanOptions.scanOptions()
                .match(pattern)
                .count(count > 0 ? count : 1000L)
                .build();
        Set<String> keys = new HashSet<>();
        try (Cursor<String> cursor = getTemplate().scan(options)) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        }
        return keys;
    }

    /**
     * 生产级安全游标扫描（scanKeys 别名方法，严格替代 keys *，分批安全迭代）
     *
     * @param pattern 匹配模式串（如 order:*）
     * @param count 每次游标拉取批次数量
     * @return 匹配的键集合
     */
    public static Set<String> scanKeys(String pattern, long count) {
        return scan(pattern, count);
    }

    /**
     * 流式游标扫描（调用方自行 try-with-resources 遍历）
     *
     * @param pattern 匹配模式串
     * @param count 每次游标拉取批次数量
     * @return 游标迭代器
     */
    public static Cursor<String> scanCursor(String pattern, long count) {
        ScanOptions options = ScanOptions.scanOptions()
                .match(pattern)
                .count(count > 0 ? count : 1000L)
                .build();
        return getTemplate().scan(options);
    }

    // ==================== 2. String (Value) 基础操作 ====================

    /**
     * 写入缓存
     *
     * @param key 键名
     * @param value 缓存值
     */
    public static void set(String key, Object value) {
        getTemplate().opsForValue().set(key, value);
    }

    /**
     * 写入带过期时间的缓存
     *
     * @param key 键名
     * @param value 缓存值
     * @param timeout 过期时长
     * @param unit 时间单位
     */
    public static void set(String key, Object value, long timeout, TimeUnit unit) {
        getTemplate().opsForValue().set(key, value, timeout, unit);
    }

    /**
     * 写入带过期时间的缓存
     *
     * @param key 键名
     * @param value 缓存值
     * @param timeout 过期时长
     */
    public static void set(String key, Object value, Duration timeout) {
        if (timeout != null) {
            set(key, value, timeout.toMillis(), TimeUnit.MILLISECONDS);
        } else {
            set(key, value);
        }
    }

    /**
     * 仅当不存在时写入缓存 (SETNX)
     *
     * @param key 键名
     * @param value 缓存值
     * @return 是否写入成功
     */
    public static boolean setIfAbsent(String key, Object value) {
        Boolean success = getTemplate().opsForValue().setIfAbsent(key, value);
        return Boolean.TRUE.equals(success);
    }

    /**
     * 仅当不存在时写入带过期时间的缓存
     *
     * @param key 键名
     * @param value 缓存值
     * @param timeout 过期时长
     * @param unit 时间单位
     * @return 是否写入成功
     */
    public static boolean setIfAbsent(String key, Object value, long timeout, TimeUnit unit) {
        Boolean success = getTemplate().opsForValue().setIfAbsent(key, value, timeout, unit);
        return Boolean.TRUE.equals(success);
    }

    /**
     * 仅当不存在时写入带过期时间的缓存
     *
     * @param key 键名
     * @param value 缓存值
     * @param timeout 过期时长
     * @return 是否写入成功
     */
    public static boolean setIfAbsent(String key, Object value, Duration timeout) {
        if (timeout != null) {
            return setIfAbsent(key, value, timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        return setIfAbsent(key, value);
    }

    /**
     * 读取通用对象缓存
     *
     * @param key 键名
     * @return 缓存对象
     */
    public static Object get(String key) {
        return getTemplate().opsForValue().get(key);
    }

    /**
     * 读取缓存并安全转换为目标类型
     *
     * @param key 键名
     * @param clazz 目标类型 Class
     * @param <T> 泛型
     * @return 强类型对象
     */
    public static <T> T get(String key, Class<T> clazz) {
        Object val = get(key);
        if (val == null) {
            return null;
        }
        if (clazz.isInstance(val)) {
            return clazz.cast(val);
        }
        return OBJECT_MAPPER.convertValue(val, clazz);
    }

    /**
     * 读取缓存并安全转换为复杂泛型类型（如 List<UserVO>）
     *
     * @param key 键名
     * @param typeReference 泛型类型引用
     * @param <T> 泛型
     * @return 强类型对象
     */
    public static <T> T get(String key, TypeReference<T> typeReference) {
        Object val = get(key);
        if (val == null) {
            return null;
        }
        return OBJECT_MAPPER.convertValue(val, typeReference);
    }

    /**
     * 设置新值并返回旧值
     *
     * @param key 键名
     * @param value 新值
     * @return 旧值
     */
    public static Object getAndSet(String key, Object value) {
        return getTemplate().opsForValue().getAndSet(key, value);
    }

    /**
     * 设置新值并返回旧值并转换为目标类型
     *
     * @param key 键名
     * @param value 新值
     * @param clazz 目标类型 Class
     * @param <T> 泛型
     * @return 转换后的旧值
     */
    public static <T> T getAndSet(String key, Object value, Class<T> clazz) {
        Object oldVal = getAndSet(key, value);
        if (oldVal == null) {
            return null;
        }
        if (clazz.isInstance(oldVal)) {
            return clazz.cast(oldVal);
        }
        return OBJECT_MAPPER.convertValue(oldVal, clazz);
    }

    /**
     * 原子自增
     *
     * @param key 键名
     * @param delta 增量步长
     * @return 递增后的最新值
     */
    public static long incr(String key, long delta) {
        Long result = getTemplate().opsForValue().increment(key, delta);
        return result != null ? result : 0L;
    }

    /**
     * 原子自减
     *
     * @param key 键名
     * @param delta 减量步长
     * @return 递减后的最新值
     */
    public static long decr(String key, long delta) {
        Long result = getTemplate().opsForValue().decrement(key, delta);
        return result != null ? result : 0L;
    }

    /**
     * 浮点数自增
     *
     * @param key 键名
     * @param delta 增量
     * @return 递增后的最新值
     */
    public static double incrByFloat(String key, double delta) {
        Double result = getTemplate().opsForValue().increment(key, delta);
        return result != null ? result : 0.0;
    }

    /**
     * 批量获取
     *
     * @param keys 键集合
     * @return 值列表
     */
    public static List<Object> mGet(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyList();
        }
        List<Object> list = getTemplate().opsForValue().multiGet(keys);
        return list != null ? list : Collections.emptyList();
    }

    /**
     * 批量设置
     *
     * @param map 键值对映射
     */
    public static void mSet(Map<String, Object> map) {
        if (map != null && !map.isEmpty()) {
            getTemplate().opsForValue().multiSet(map);
        }
    }

    // ==================== 3. Hash 散列操作 ====================

    /**
     * 写入 Hash 字段
     *
     * @param key 键名
     * @param hashKey 散列字段名
     * @param value 散列值
     */
    public static void hSet(String key, String hashKey, Object value) {
        getTemplate().opsForHash().put(key, hashKey, value);
    }

    /**
     * 读取 Hash 字段
     *
     * @param key 键名
     * @param hashKey 散列字段名
     * @return 散列值
     */
    public static Object hGet(String key, String hashKey) {
        return getTemplate().opsForHash().get(key, hashKey);
    }

    /**
     * 读取 Hash 字段并安全转换为目标类型
     *
     * @param key 键名
     * @param hashKey 散列字段名
     * @param clazz 目标类型 Class
     * @param <T> 泛型
     * @return 目标类型对象
     */
    public static <T> T hGet(String key, String hashKey, Class<T> clazz) {
        Object val = hGet(key, hashKey);
        if (val == null) {
            return null;
        }
        if (clazz.isInstance(val)) {
            return clazz.cast(val);
        }
        return OBJECT_MAPPER.convertValue(val, clazz);
    }

    /**
     * 获取整个 Hash 字典
     *
     * @param key 键名
     * @return 字典 Map
     */
    public static Map<Object, Object> hGetAll(String key) {
        Map<Object, Object> entries = getTemplate().opsForHash().entries(key);
        return entries != null ? entries : Collections.emptyMap();
    }

    /**
     * 批量写入 Hash
     *
     * @param key 键名
     * @param map 键值对映射
     */
    public static void hMSet(String key, Map<String, Object> map) {
        if (map != null && !map.isEmpty()) {
            getTemplate().opsForHash().putAll(key, map);
        }
    }

    /**
     * 批量获取 Hash 字段值
     *
     * @param key 键名
     * @param hashKeys 字段集合
     * @return 值列表
     */
    public static List<Object> hMGet(String key, Collection<Object> hashKeys) {
        if (hashKeys == null || hashKeys.isEmpty()) {
            return Collections.emptyList();
        }
        List<Object> list = getTemplate().opsForHash().multiGet(key, hashKeys);
        return list != null ? list : Collections.emptyList();
    }

    /**
     * 删除 Hash 中的字段
     *
     * @param key 键名
     * @param hashKeys 字段名数组
     * @return 成功删除的数量
     */
    public static long hDel(String key, Object... hashKeys) {
        Long count = getTemplate().opsForHash().delete(key, hashKeys);
        return count != null ? count : 0L;
    }

    /**
     * 判断 Hash 字段是否存在
     *
     * @param key 键名
     * @param hashKey 字段名
     * @return 是否存在
     */
    public static boolean hHasKey(String key, String hashKey) {
        return getTemplate().opsForHash().hasKey(key, hashKey);
    }

    /**
     * Hash 字段原子自增
     *
     * @param key 键名
     * @param hashKey 字段名
     * @param delta 步长
     * @return 最新值
     */
    public static long hIncr(String key, String hashKey, long delta) {
        return getTemplate().opsForHash().increment(key, hashKey, delta);
    }

    /**
     * 获取 Hash 所有的键集合
     *
     * @param key 键名
     * @return 散列键集合
     */
    public static Set<Object> hKeys(String key) {
        Set<Object> keys = getTemplate().opsForHash().keys(key);
        return keys != null ? keys : Collections.emptySet();
    }

    /**
     * 获取 Hash 所有的值列表
     *
     * @param key 键名
     * @return 散列值列表
     */
    public static List<Object> hValues(String key) {
        List<Object> values = getTemplate().opsForHash().values(key);
        return values != null ? values : Collections.emptyList();
    }

    /**
     * 渐进式游标遍历 Hash 键值对，避免阻塞 Redis
     *
     * @param key 键名
     * @param pattern 匹配模式（如 "*"）
     * @param count 每次遍历批次大小
     * @param action 回调处理函数
     */
    public static void hScan(String key, String pattern, long count, Consumer<Map.Entry<Object, Object>> action) {
        ScanOptions options = ScanOptions.scanOptions()
                .match(pattern)
                .count(count)
                .build();
        try (Cursor<Map.Entry<Object, Object>> cursor = getTemplate().opsForHash().scan(key, options)) {
            while (cursor.hasNext()) {
                action.accept(cursor.next());
            }
        }
    }

    /**
     * 删除 Hash 中的字段（hDel 别名）
     *
     * @param key 键名
     * @param hashKeys 字段名数组
     * @return 成功删除的数量
     */
    @Deprecated
    public static long hDelete(String key, Object... hashKeys) {
        return hDel(key, hashKeys);
    }

    // ==================== 4. List 列表操作 ====================

    /**
     * 从左侧推入列表
     *
     * @param key 键名
     * @param value 元素
     * @return 列表长度
     */
    public static long lPush(String key, Object value) {
        Long len = getTemplate().opsForList().leftPush(key, value);
        return len != null ? len : 0L;
    }

    /**
     * 从左侧批量推入列表
     *
     * @param key 键名
     * @param values 元素数组
     * @return 列表长度
     */
    public static long lPushAll(String key, Object... values) {
        Long len = getTemplate().opsForList().leftPushAll(key, values);
        return len != null ? len : 0L;
    }

    /**
     * 从右侧推入列表
     *
     * @param key 键名
     * @param value 元素
     * @return 列表长度
     */
    public static long rPush(String key, Object value) {
        Long len = getTemplate().opsForList().rightPush(key, value);
        return len != null ? len : 0L;
    }

    /**
     * 从右侧批量推入列表
     *
     * @param key 键名
     * @param values 元素数组
     * @return 列表长度
     */
    public static long rPushAll(String key, Object... values) {
        Long len = getTemplate().opsForList().rightPushAll(key, values);
        return len != null ? len : 0L;
    }

    /**
     * 从左侧弹出列表首元素
     *
     * @param key 键名
     * @return 元素
     */
    public static Object lPop(String key) {
        return getTemplate().opsForList().leftPop(key);
    }

    /**
     * 从左侧弹出并安全转换为目标类型
     *
     * @param key 键名
     * @param clazz 目标类型 Class
     * @param <T> 泛型
     * @return 元素
     */
    public static <T> T lPop(String key, Class<T> clazz) {
        Object val = lPop(key);
        if (val == null) {
            return null;
        }
        if (clazz.isInstance(val)) {
            return clazz.cast(val);
        }
        return OBJECT_MAPPER.convertValue(val, clazz);
    }

    /**
     * 从右侧弹出列表尾元素
     *
     * @param key 键名
     * @return 元素
     */
    public static Object rPop(String key) {
        return getTemplate().opsForList().rightPop(key);
    }

    /**
     * 从右侧弹出并安全转换为目标类型
     *
     * @param key 键名
     * @param clazz 目标类型 Class
     * @param <T> 泛型
     * @return 元素
     */
    public static <T> T rPop(String key, Class<T> clazz) {
        Object val = rPop(key);
        if (val == null) {
            return null;
        }
        if (clazz.isInstance(val)) {
            return clazz.cast(val);
        }
        return OBJECT_MAPPER.convertValue(val, clazz);
    }

    /**
     * 获取列表指定区间内的元素
     *
     * @param key 键名
     * @param start 起始索引
     * @param end 终止索引
     * @return 元素列表
     */
    public static List<Object> lRange(String key, long start, long end) {
        List<Object> range = getTemplate().opsForList().range(key, start, end);
        return range != null ? range : Collections.emptyList();
    }

    /**
     * 获取列表长度
     *
     * @param key 键名
     * @return 长度
     */
    public static long lLen(String key) {
        Long len = getTemplate().opsForList().size(key);
        return len != null ? len : 0L;
    }

    /**
     * 获取列表长度（lLen 别名）
     *
     * @param key 键名
     * @return 长度
     */
    public static long lSize(String key) {
        return lLen(key);
    }

    /**
     * 根据索引获取元素
     *
     * @param key 键名
     * @param index 索引
     * @return 元素
     */
    public static Object lIndex(String key, long index) {
        return getTemplate().opsForList().index(key, index);
    }

    /**
     * 裁剪列表，仅保留指定范围
     *
     * @param key 键名
     * @param start 起始
     * @param end 终止
     */
    public static void lTrim(String key, long start, long end) {
        getTemplate().opsForList().trim(key, start, end);
    }

    // ==================== 5. Set 无序集合操作 ====================

    /**
     * 向集合添加元素
     *
     * @param key 键名
     * @param values 元素
     * @return 成功添加的数量
     */
    public static long sAdd(String key, Object... values) {
        Long count = getTemplate().opsForSet().add(key, values);
        return count != null ? count : 0L;
    }

    /**
     * 获取集合所有成员
     *
     * @param key 键名
     * @return 集合元素
     */
    public static Set<Object> sMembers(String key) {
        Set<Object> members = getTemplate().opsForSet().members(key);
        return members != null ? members : Collections.emptySet();
    }

    /**
     * 判断是否为集合成员
     *
     * @param key 键名
     * @param value 元素
     * @return 是否存在
     */
    public static boolean sIsMember(String key, Object value) {
        Boolean member = getTemplate().opsForSet().isMember(key, value);
        return Boolean.TRUE.equals(member);
    }

    /**
     * 从集合中移除元素
     *
     * @param key 键名
     * @param values 待移除元素
     * @return 移除成功的数量
     */
    public static long sRem(String key, Object... values) {
        Long count = getTemplate().opsForSet().remove(key, values);
        return count != null ? count : 0L;
    }

    /**
     * 获取集合大小
     *
     * @param key 键名
     * @return 大小
     */
    public static long sCard(String key) {
        Long card = getTemplate().opsForSet().size(key);
        return card != null ? card : 0L;
    }

    /**
     * 从集合中移除元素（sRem 别名）
     *
     * @param key 键名
     * @param values 待移除元素
     * @return 移除成功的数量
     */
    @Deprecated
    public static long sRemove(String key, Object... values) {
        return sRem(key, values);
    }

    /**
     * 获取集合大小（sCard 别名）
     *
     * @param key 键名
     * @return 大小
     */
    public static long sSize(String key) {
        return sCard(key);
    }

    /**
     * 渐进式游标遍历 Set 成员
     *
     * @param key 键名
     * @param pattern 匹配模式
     * @param count 批次大小
     * @param action 回调处理
     */
    public static void sScan(String key, String pattern, long count, Consumer<Object> action) {
        ScanOptions options = ScanOptions.scanOptions()
                .match(pattern)
                .count(count)
                .build();
        try (Cursor<Object> cursor = getTemplate().opsForSet().scan(key, options)) {
            while (cursor.hasNext()) {
                action.accept(cursor.next());
            }
        }
    }

    /**
     * 求两集合交集
     *
     * @param key 键 1
     * @param otherKey 键 2
     * @return 交集集合
     */
    public static Set<Object> sIntersect(String key, String otherKey) {
        Set<Object> intersect = getTemplate().opsForSet().intersect(key, otherKey);
        return intersect != null ? intersect : Collections.emptySet();
    }

    /**
     * 求两集合并集
     *
     * @param key 键 1
     * @param otherKey 键 2
     * @return 并集集合
     */
    public static Set<Object> sUnion(String key, String otherKey) {
        Set<Object> union = getTemplate().opsForSet().union(key, otherKey);
        return union != null ? union : Collections.emptySet();
    }

    /**
     * 求两集合差集
     *
     * @param key 键 1
     * @param otherKey 键 2
     * @return 差集集合
     */
    public static Set<Object> sDifference(String key, String otherKey) {
        Set<Object> difference = getTemplate().opsForSet().difference(key, otherKey);
        return difference != null ? difference : Collections.emptySet();
    }

    // ==================== 6. ZSet 有序集合操作 ====================

    /**
     * 向有序集合添加成员
     *
     * @param key 键名
     * @param value 成员
     * @param score 分数
     * @return 是否添加成功
     */
    public static boolean zAdd(String key, Object value, double score) {
        Boolean added = getTemplate().opsForZSet().add(key, value, score);
        return Boolean.TRUE.equals(added);
    }

    /**
     * 获取指定排名区间的成员（从小到大升序）
     *
     * @param key 键名
     * @param start 起始名次
     * @param end 终止名次
     * @return 成员集合
     */
    public static Set<Object> zRange(String key, long start, long end) {
        Set<Object> range = getTemplate().opsForZSet().range(key, start, end);
        return range != null ? range : Collections.emptySet();
    }

    /**
     * 获取指定排名区间的成员（从大到小降序，常用于排行榜）
     *
     * @param key 键名
     * @param start 起始名次
     * @param end 终止名次
     * @return 成员集合
     */
    public static Set<Object> zRevRange(String key, long start, long end) {
        Set<Object> range = getTemplate().opsForZSet().reverseRange(key, start, end);
        return range != null ? range : Collections.emptySet();
    }

    /**
     * 按分值区间查询成员
     *
     * @param key 键名
     * @param min 最小分
     * @param max 最大分
     * @return 成员集合
     */
    public static Set<Object> zRangeByScore(String key, double min, double max) {
        Set<Object> range = getTemplate().opsForZSet().rangeByScore(key, min, max);
        return range != null ? range : Collections.emptySet();
    }

    /**
     * 获取成员的分值
     *
     * @param key 键名
     * @param value 成员
     * @return 分值
     */
    public static Double zScore(String key, Object value) {
        return getTemplate().opsForZSet().score(key, value);
    }

    /**
     * 获取成员在集合中的排名（升序）
     *
     * @param key 键名
     * @param value 成员
     * @return 排名（0-based）
     */
    public static Long zRank(String key, Object value) {
        return getTemplate().opsForZSet().rank(key, value);
    }

    /**
     * 获取成员在集合中的逆序排名（降序，常用于榜首查询）
     *
     * @param key 键名
     * @param value 成员
     * @return 排名（0-based）
     */
    public static Long zRevRank(String key, Object value) {
        return getTemplate().opsForZSet().reverseRank(key, value);
    }

    /**
     * 移除有序集合中的成员
     *
     * @param key 键名
     * @param values 待移除成员
     * @return 移除成功的数量
     */
    public static long zRem(String key, Object... values) {
        Long count = getTemplate().opsForZSet().remove(key, values);
        return count != null ? count : 0L;
    }

    /**
     * 获取有序集合的基数大小
     *
     * @param key 键名
     * @return 元素数量
     */
    public static long zCard(String key) {
        Long card = getTemplate().opsForZSet().zCard(key);
        return card != null ? card : 0L;
    }

    /**
     * 统计指定分值区间内的成员数
     *
     * @param key 键名
     * @param min 最小分
     * @param max 最大分
     * @return 成员数量
     */
    public static long zCount(String key, double min, double max) {
        Long count = getTemplate().opsForZSet().count(key, min, max);
        return count != null ? count : 0L;
    }

    /**
     * 获取指定排名区间的成员及分值
     *
     * @param key 键名
     * @param start 起始名次
     * @param end 终止名次
     * @return 带分值的成员集合
     */
    public static Set<ZSetOperations.TypedTuple<Object>> zRangeWithScores(String key, long start, long end) {
        Set<ZSetOperations.TypedTuple<Object>> range = getTemplate().opsForZSet().rangeWithScores(key, start, end);
        return range != null ? range : Collections.emptySet();
    }

    /**
     * 移除有序集合中的成员（zRem 别名）
     *
     * @param key 键名
     * @param values 待移除成员
     * @return 移除成功的数量
     */
    @Deprecated
    public static long zRemove(String key, Object... values) {
        return zRem(key, values);
    }

    /**
     * 渐进式游标遍历 ZSet 成员
     *
     * @param key 键名
     * @param pattern 匹配模式
     * @param count 批次大小
     * @param action 回调处理
     */
    public static void zScan(String key, String pattern, long count, Consumer<ZSetOperations.TypedTuple<Object>> action) {
        ScanOptions options = ScanOptions.scanOptions()
                .match(pattern)
                .count(count)
                .build();
        try (Cursor<ZSetOperations.TypedTuple<Object>> cursor = getTemplate().opsForZSet().scan(key, options)) {
            while (cursor.hasNext()) {
                action.accept(cursor.next());
            }
        }
    }

    // ==================== 7. Geo 空间位置计算 ====================

    /**
     * 添加地理位置坐标
     *
     * @param key 键名
     * @param point 经纬度坐标
     * @param member 成员标识
     * @return 成功添加的数量
     */
    public static long geoAdd(String key, Point point, Object member) {
        Long count = getTemplate().opsForGeo().add(key, point, member);
        return count != null ? count : 0L;
    }

    /**
     * 添加地理位置坐标（经纬度数值）
     *
     * @param key 键名
     * @param longitude 经度
     * @param latitude 纬度
     * @param member 成员标识
     * @return 成功添加的数量
     */
    public static long geoAdd(String key, double longitude, double latitude, Object member) {
        return geoAdd(key, new Point(longitude, latitude), member);
    }

    /**
     * 计算两成员之间的地理距离
     *
     * @param key 键名
     * @param member1 成员 1
     * @param member2 成员 2
     * @param metric 距离度量单位（如 Metrics.KILOMETERS）
     * @return 距离
     */
    public static Distance geoDist(String key, Object member1, Object member2, Metric metric) {
        return getTemplate().opsForGeo().distance(key, member1, member2, metric);
    }

    /**
     * 获取成员坐标位置
     *
     * @param key 键名
     * @param members 成员列表
     * @return 坐标列表
     */
    public static List<Point> geoPos(String key, Object... members) {
        List<Point> position = getTemplate().opsForGeo().position(key, members);
        return position != null ? position : Collections.emptyList();
    }

    /**
     * 搜索圆形范围内的成员
     *
     * @param key 键名
     * @param within 圆形区域
     * @param args 附加参数（如包含距离、排序）
     * @return 搜索结果集合
     */
    public static GeoResults<RedisGeoCommands.GeoLocation<Object>> geoRadius(String key, Circle within, RedisGeoCommands.GeoRadiusCommandArgs args) {
        return getTemplate().opsForGeo().radius(key, within, args);
    }

    // ==================== 8. BitMap 位图计算 ====================

    /**
     * 设置位图布尔值（如用户签到）
     *
     * @param key 键名
     * @param offset 偏移量（如天数或用户 ID）
     * @param value 布尔值
     * @return 原布尔值
     */
    public static boolean setBit(String key, long offset, boolean value) {
        Boolean previous = getTemplate().opsForValue().setBit(key, offset, value);
        return Boolean.TRUE.equals(previous);
    }

    /**
     * 读取位图指定偏移量的值
     *
     * @param key 键名
     * @param offset 偏移量
     * @return 布尔值
     */
    public static boolean getBit(String key, long offset) {
        Boolean bit = getTemplate().opsForValue().getBit(key, offset);
        return Boolean.TRUE.equals(bit);
    }

    /**
     * 统计位图中为 1 的总数量
     *
     * @param key 键名
     * @return 为 1 的数量
     */
    public static long bitCount(String key) {
        Long count = getTemplate().execute((RedisCallback<Long>) connection ->
                connection.stringCommands().bitCount(key.getBytes(StandardCharsets.UTF_8)));
        return count != null ? count : 0L;
    }

    // ==================== 9. HyperLogLog 基数统计 ====================

    /**
     * 向 HyperLogLog 结构添加元素
     *
     * @param key 键名
     * @param values 元素
     * @return 是否对基数产生变化
     */
    public static long pfAdd(String key, Object... values) {
        Long added = getTemplate().opsForHyperLogLog().add(key, values);
        return added != null ? added : 0L;
    }

    /**
     * 获取一个或多个 HyperLogLog 键的近似去重基数大小（UV 统计）
     *
     * @param keys 键数组
     * @return 基数大小
     */
    public static long pfCount(String... keys) {
        return getTemplate().opsForHyperLogLog().size(keys);
    }

    // ==================== 10. Pipeline 批量执行 ====================

    /**
     * 在单个网络往返中执行 Pipeline 流式批处理
     *
     * @param session 回调会话
     * @return 批量结果列表
     */
    public static List<Object> executePipelined(SessionCallback<?> session) {
        List<Object> results = getTemplate().executePipelined(session);
        return results != null ? results : Collections.emptyList();
    }

    /**
     * 基于 Consumer 闭包执行极简 Pipeline
     *
     * @param action 批量操作闭包
     * @return 批量结果列表
     */
    public static List<Object> executePipelined(Consumer<RedisOperations<String, Object>> action) {
        List<Object> results = getTemplate().executePipelined(new SessionCallback<>() {
            @Override
            public <K, V> Object execute(RedisOperations<K, V> operations) {
                @SuppressWarnings("unchecked")
                RedisOperations<String, Object> ops = (RedisOperations<String, Object>) operations;
                action.accept(ops);
                return null;
            }
        });
        return results != null ? results : Collections.emptyList();
    }

    // ==================== 11. 历史兼容性别名（所有静态方法均支持通过 RedisUtil Bean 实例直接调用） ====================

    // --- 兼容性别名（推荐使用同名实例方法） ---
    @Deprecated
    public boolean isKeyExists(String key) {
        return hasKey(key);
    }

    @Deprecated
    public boolean setKeyExpire(String key, long timeout, TimeUnit unit) {
        return expire(key, timeout, unit);
    }

    @Deprecated
    public long getKeyExpire(String key, TimeUnit unit) {
        return getExpire(key, unit);
    }

    @Deprecated
    public boolean deleteKey(String key) {
        return delete(key);
    }

    @Deprecated
    public long deleteKeys(Collection<String> keys) {
        return delete(keys);
    }

    @Deprecated
    public void setVal(String key, Object value) {
        set(key, value);
    }

    @Deprecated
    public void setVal(String key, Object value, long timeout, TimeUnit unit) {
        set(key, value, timeout, unit);
    }

    @Deprecated
    public Object getVal(String key) {
        return get(key);
    }

    @Deprecated
    public <T> T getVal(String key, Class<T> clazz) {
        return get(key, clazz);
    }
}

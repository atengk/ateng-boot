package io.github.atengk.boot.satoken.cache;

import cn.dev33.satoken.stp.StpInterface;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.satoken.properties.SaTokenProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Sa-Token 两级高并发权限与角色缓存数据源适配器
 * 实现 [用户ID -> 角色编码集] 与 [角色编码 -> 权限码集] 两级缓存切片；
 * 配套空哨兵短 TTL 防穿透机制与 Jitter 随机抖动防雪崩机制，杜绝鉴权对底层数据库的周期性风暴冲击
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Slf4j
@RequiredArgsConstructor
public class CachedStpInterface implements StpInterface {

    /**
     * 空集合哨兵常量，用于防缓存穿透
     */
    public static final String EMPTY_SENTINEL = "__EMPTY__";

    private final UserRolePermissionLoader loader;
    private final PermissionCacheService cacheService;
    private final SaTokenProperties properties;

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        if (loginId == null) {
            return Collections.emptyList();
        }
        String userId = String.valueOf(loginId);
        String cacheKey = PermissionCacheService.buildUserRolesKey(userId, loginType);
        return getOrLoadCachedList(cacheKey, () -> loader.loadRolesByUserId(userId, loginType));
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        if (loginId == null) {
            return Collections.emptyList();
        }
        String userId = String.valueOf(loginId);

        // 1. 获取用户所属角色列表
        List<String> roles = getRoleList(loginId, loginType);

        // 2. 汇总各角色关联的权限集
        Set<String> permissionSet = new LinkedHashSet<>();
        if (roles != null && !roles.isEmpty()) {
            for (String roleCode : roles) {
                if (roleCode == null || roleCode.isBlank()) {
                    continue;
                }
                permissionSet.addAll(getPermissionsByRole(roleCode, loginType));
            }
        }

        // 3. 汇总用户个人特有的额外直属权限集
        permissionSet.addAll(getDirectPermissions(userId, loginType));

        return new ArrayList<>(permissionSet);
    }

    /**
     * 根据角色编码从二级缓存或业务数据源获取权限码集合
     *
     * @param roleCode 角色编码
     * @return 权限码集合
     */
    public List<String> getPermissionsByRole(String roleCode) {
        return getPermissionsByRole(roleCode, null);
    }

    /**
     * 根据角色编码与账号体系标识从二级缓存或业务数据源获取权限码集合
     *
     * @param roleCode  角色编码
     * @param loginType 账号体系类型标识
     * @return 权限码集合
     */
    public List<String> getPermissionsByRole(String roleCode, String loginType) {
        if (roleCode == null || roleCode.isBlank()) {
            return Collections.emptyList();
        }
        String cacheKey = PermissionCacheService.buildRolePermsKey(roleCode, loginType);
        return getOrLoadCachedList(cacheKey, () ->
                (loginType == null || loginType.isBlank())
                        ? loader.loadPermissionsByRole(roleCode)
                        : loader.loadPermissionsByRole(roleCode, loginType));
    }

    /**
     * 根据用户 ID 从辅助缓存或业务数据源获取个人直属权限码集合
     *
     * @param userId 用户唯一标识
     * @return 直属权限码集合
     */
    public List<String> getDirectPermissions(String userId) {
        return getDirectPermissions(userId, null);
    }

    /**
     * 根据用户 ID 与账号体系标识从辅助缓存或业务数据源获取个人直属权限码集合
     *
     * @param userId    用户唯一标识
     * @param loginType 账号体系类型标识
     * @return 直属权限码集合
     */
    public List<String> getDirectPermissions(String userId, String loginType) {
        if (userId == null || userId.isBlank()) {
            return Collections.emptyList();
        }
        String cacheKey = PermissionCacheService.buildUserPermsKey(userId, loginType);
        return getOrLoadCachedList(cacheKey, () -> loader.loadDirectPermissionsByUserId(userId, loginType));
    }

    private List<String> getOrLoadCachedList(String cacheKey, Supplier<List<String>> sourceSupplier) {
        // 1. 查询缓存
        String cachedVal = cacheService.get(cacheKey);
        if (cachedVal != null) {
            if (EMPTY_SENTINEL.equals(cachedVal)) {
                return Collections.emptyList();
            }
            return deserializeList(cachedVal);
        }

        // 2. 缓存未命中，回源业务加载器
        List<String> loaded = sourceSupplier.get();
        if (loaded == null || loaded.isEmpty()) {
            cacheService.set(cacheKey, EMPTY_SENTINEL, properties.getPermissionCache().getEmptyTtlSeconds());
            return Collections.emptyList();
        }

        // 3. 过滤清洗并写入缓存（带 Jitter 随机抖动防雪崩）
        List<String> cleanList = loaded.stream()
                .filter(item -> item != null && !item.isBlank())
                .distinct()
                .toList();
        if (cleanList.isEmpty()) {
            cacheService.set(cacheKey, EMPTY_SENTINEL, properties.getPermissionCache().getEmptyTtlSeconds());
            return Collections.emptyList();
        }
        long ttl = calculateTtlWithJitter(properties.getPermissionCache().getTimeoutSeconds());
        cacheService.set(cacheKey, serializeList(cleanList), ttl);

        return cleanList;
    }

    /**
     * 计算带 10%~20% 动态随机 Jitter 浮动的防雪崩 TTL
     *
     * @param baseTimeoutSeconds 基础 TTL（秒）
     * @return 带 Jitter 浮动的 TTL（秒）
     */
    private long calculateTtlWithJitter(long baseTimeoutSeconds) {
        if (baseTimeoutSeconds <= 0) {
            return 300L;
        }
        long minJitter = Math.max(1, baseTimeoutSeconds / 10);
        long maxJitter = Math.max(minJitter + 1, baseTimeoutSeconds / 5);
        long jitter = ThreadLocalRandom.current().nextLong(minJitter, maxJitter + 1);
        return baseTimeoutSeconds + jitter;
    }

    /**
     * 序列化字符串列表为纯净 JSON 字符串
     */
    private String serializeList(List<String> list) {
        try {
            return JsonUtil.toJsonString(list);
        } catch (Exception e) {
            log.warn("序列化权限列表异常: ", e);
            return "[]";
        }
    }

    /**
     * 反序列化纯净 JSON 字符串为字符串列表
     */
    private List<String> deserializeList(String json) {
        try {
            return JsonUtil.parseList(json, String.class);
        } catch (Exception e) {
            log.warn("反序列化权限列表异常: json={}", json, e);
            return Collections.emptyList();
        }
    }
}

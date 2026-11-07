package io.github.atengk.boot.sample.social.service;

import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.social.model.SocialBoundAccount;
import io.github.atengk.boot.social.model.SocialUser;
import io.github.atengk.boot.social.spi.SocialUserDetailsService;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 社交登录持久化与用户身份映射业务 SPI 样例实现
 * <p>
 * 模拟企业用户中心与社交身份映射表结构，演示微信三阶识别、两阶段绑定落库与孤儿死号前置安全防卫。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Slf4j
@Service
public class SampleSocialUserDetailsService implements SocialUserDetailsService {

    /**
     * 模拟用户数据库表 (userId -> UserProfile)
     */
    private final Map<String, UserProfile> userDatabase = new ConcurrentHashMap<>();

    /**
     * 模拟第三方账号绑定关系表 (appKey + ":" + openId -> userId)
     */
    private final Map<String, String> bindingDatabase = new ConcurrentHashMap<>();

    @PostConstruct
    public void initMockData() {
        // 初始化用户 A：已有手机号和独立密码，已绑定 GitHub 账号（具备备用凭据，允许安全解绑）
        UserProfile userA = UserProfile.builder()
                .userId("user_bound_1001")
                .username("ateng_normal")
                .hasPassword(true)
                .hasMobile(true)
                .boundAppKeys(ConcurrentHashMap.newKeySet())
                .build();
        userA.getBoundAppKeys().add("github_app");
        userA.getBoundAppKeys().add("wechat_mp_app");
        userDatabase.put(userA.getUserId(), userA);
        bindingDatabase.put("github_app:gh_open_1001", userA.getUserId());
        bindingDatabase.put("github_app:1001", userA.getUserId());
        bindingDatabase.put("wechat_mp_app:wx_open_bound_1001", userA.getUserId());

        // 初始化用户 B：无独立密码、无绑定手机号（社交登录为唯一登入凭据，解绑时必须阻断）
        UserProfile userB = UserProfile.builder()
                .userId("user_orphan_2002")
                .username("orphan_candidate")
                .hasPassword(false)
                .hasMobile(false)
                .boundAppKeys(ConcurrentHashMap.newKeySet())
                .build();
        userB.getBoundAppKeys().add("github_app");
        userDatabase.put(userB.getUserId(), userB);
        bindingDatabase.put("github_app:gh_open_orphan", userB.getUserId());

        log.info("Initialized SampleSocialUserDetailsService with mock users: [user_bound_1001, user_orphan_2002]");
    }

    @Override
    public String loadUserIdBySocialUser(String appKey, SocialUser socialUser) {
        if (socialUser == null) {
            return null;
        }

        // 1. 若 unionId 非空，优先在全局联合身份中检索
        if (socialUser.getUnionId() != null && !socialUser.getUnionId().isBlank()) {
            String userId = bindingDatabase.get("unionId:" + socialUser.getUnionId());
            if (userId != null) {
                log.info("Matched local user by unionId: [{} -> {}]", socialUser.getUnionId(), userId);
                return userId;
            }
        }

        // 2. 降级通过 appKey + openId 匹配
        String bindingKey = appKey + ":" + socialUser.getOpenId();
        String userId = bindingDatabase.get(bindingKey);
        if (userId != null) {
            log.info("Matched local user by openId: [{} -> {}]", bindingKey, userId);
            return userId;
        }

        // 3. 未匹配到绑定记录，返回 null 触发两阶段绑定
        return null;
    }

    @Override
    public boolean canUnbind(String userId, String appKey) {
        UserProfile user = userDatabase.get(userId);
        if (user == null) {
            return true;
        }

        // 孤儿账号防卫核心业务不变量：若未设密码且未绑手机号，禁止解绑唯一社交凭据
        if (!user.isHasPassword() && !user.isHasMobile()) {
            log.warn("Rejected unbind for orphan account: userId=[{}], appKey=[{}] (No password & no mobile)",
                    userId, appKey);
            return false;
        }

        return true;
    }

    @Override
    public void bind(String userId, String appKey, SocialUser socialUser) {
        UserProfile user = userDatabase.computeIfAbsent(userId, id -> UserProfile.builder()
                .userId(id)
                .username("user_" + id)
                .hasPassword(true)
                .hasMobile(true)
                .boundAppKeys(ConcurrentHashMap.newKeySet())
                .build());

        user.getBoundAppKeys().add(appKey);

        String bindingKey = appKey + ":" + socialUser.getOpenId();
        bindingDatabase.put(bindingKey, userId);

        if (socialUser.getUnionId() != null && !socialUser.getUnionId().isBlank()) {
            bindingDatabase.put("unionId:" + socialUser.getUnionId(), userId);
        }

        log.info("Persisted social binding: userId=[{}], appKey=[{}], openId=[{}]",
                userId, appKey, socialUser.getOpenId());
    }

    @Override
    public void unbind(String userId, String appKey) {
        UserProfile user = userDatabase.get(userId);
        if (user != null) {
            user.getBoundAppKeys().remove(appKey);
        }

        // 清理绑定记录
        bindingDatabase.entrySet().removeIf(entry ->
                entry.getKey().startsWith(appKey + ":") && userId.equals(entry.getValue())
        );

        log.info("Removed social binding: userId=[{}], appKey=[{}]", userId, appKey);
    }

    @Override
    public String autoRegisterUser(String appKey, SocialUser socialUser) {
        String newUserId = "auto_user_" + IdUtil.fastSimpleUUID().substring(0, 8);
        UserProfile profile = UserProfile.builder()
                .userId(newUserId)
                .username("auto_" + (socialUser.getUsername() != null ? socialUser.getUsername() : socialUser.getOpenId()))
                .hasPassword(false)
                .hasMobile(false)
                .boundAppKeys(ConcurrentHashMap.newKeySet())
                .build();
        profile.getBoundAppKeys().add(appKey);
        userDatabase.put(newUserId, profile);

        String bindingKey = appKey + ":" + socialUser.getOpenId();
        bindingDatabase.put(bindingKey, newUserId);
        if (socialUser.getUnionId() != null && !socialUser.getUnionId().isBlank()) {
            bindingDatabase.put("unionId:" + socialUser.getUnionId(), newUserId);
        }
        log.info("Auto-registered new local user: userId=[{}], appKey=[{}], openId=[{}]",
                newUserId, appKey, socialUser.getOpenId());
        return newUserId;
    }

    @Override
    public List<SocialBoundAccount> listBoundAccounts(String userId) {
        UserProfile user = userDatabase.get(userId);
        if (user == null || user.getBoundAppKeys() == null || user.getBoundAppKeys().isEmpty()) {
            return Collections.emptyList();
        }
        List<SocialBoundAccount> list = new ArrayList<>();
        for (String appKey : user.getBoundAppKeys()) {
            String openId = null;
            for (Map.Entry<String, String> entry : bindingDatabase.entrySet()) {
                if (entry.getValue().equals(userId) && entry.getKey().startsWith(appKey + ":")) {
                    openId = entry.getKey().substring(appKey.length() + 1);
                    break;
                }
            }
            list.add(SocialBoundAccount.builder()
                    .appKey(appKey)
                    .source(appKey.contains("github") ? "GITHUB" : "WECHAT_MP")
                    .openId(openId != null ? openId : "mock_openid")
                    .username(user.getUsername())
                    .nickname(user.getUsername())
                    .boundAt(Instant.now())
                    .build());
        }
        return list;
    }

    /**
     * 重置业务数据库（供测试用例自洽清理）
     */
    public void reset() {
        userDatabase.clear();
        bindingDatabase.clear();
        initMockData();
    }

    /**
     * 本地用户画像数据载体
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserProfile {
        private String userId;
        private String username;
        private boolean hasPassword;
        private boolean hasMobile;
        private Set<String> boundAppKeys;
    }
}

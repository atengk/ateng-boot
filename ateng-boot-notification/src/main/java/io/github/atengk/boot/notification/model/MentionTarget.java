package io.github.atengk.boot.notification.model;

import lombok.Data;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 协同群机器人 @人 目标领域模型
 * 统一封装 @所有人、按手机号 @人 及按工号/OpenID @人 语义
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
public class MentionTarget implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 是否 @所有人
     */
    private boolean isAtAll = false;

    /**
     * 目标成员手机号集合
     */
    private Set<String> atMobiles = new LinkedHashSet<>();

    /**
     * 目标成员工号 / 用户 ID / OpenID 集合
     */
    private Set<String> atUserIds = new LinkedHashSet<>();

    public MentionTarget() {
    }

    public MentionTarget(boolean isAtAll) {
        this.isAtAll = isAtAll;
    }

    public static MentionTarget all() {
        return new MentionTarget(true);
    }

    public static MentionTarget ofMobiles(String... mobiles) {
        MentionTarget target = new MentionTarget();
        target.addMobiles(mobiles);
        return target;
    }

    public static MentionTarget ofUserIds(String... userIds) {
        MentionTarget target = new MentionTarget();
        target.addUserIds(userIds);
        return target;
    }

    public MentionTarget atAll(boolean isAtAll) {
        this.isAtAll = isAtAll;
        return this;
    }

    public MentionTarget atAll() {
        this.isAtAll = true;
        return this;
    }

    public MentionTarget addMobile(String mobile) {
        if (mobile != null && !mobile.isBlank()) {
            this.atMobiles.add(mobile.trim());
        }
        return this;
    }

    public MentionTarget addMobiles(String... mobiles) {
        if (mobiles != null) {
            Arrays.stream(mobiles).forEach(this::addMobile);
        }
        return this;
    }

    public MentionTarget addMobiles(Collection<String> mobiles) {
        if (mobiles != null) {
            mobiles.forEach(this::addMobile);
        }
        return this;
    }

    public MentionTarget addUserId(String userId) {
        if (userId != null && !userId.isBlank()) {
            this.atUserIds.add(userId.trim());
        }
        return this;
    }

    public MentionTarget addUserIds(String... userIds) {
        if (userIds != null) {
            Arrays.stream(userIds).forEach(this::addUserId);
        }
        return this;
    }

    public MentionTarget addUserIds(Collection<String> userIds) {
        if (userIds != null) {
            userIds.forEach(this::addUserId);
        }
        return this;
    }

    /**
     * 判断当前是否未配置任何 @人 目标
     */
    public boolean isEmpty() {
        return !isAtAll && atMobiles.isEmpty() && atUserIds.isEmpty();
    }

    /**
     * 判断当前是否存在有效的 @人 目标
     */
    public boolean hasMentions() {
        return !isEmpty();
    }

    public Set<String> getAtMobiles() {
        return atMobiles != null ? atMobiles : Collections.emptySet();
    }

    public Set<String> getAtUserIds() {
        return atUserIds != null ? atUserIds : Collections.emptySet();
    }
}

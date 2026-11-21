package io.github.atengk.boot.social.exception;

import io.github.atengk.boot.social.code.SocialResultCode;

/**
 * 第三方平台快照虚拟账号拒绝受控异常
 * <p>
 * 当微信网页授权返回未关注弹窗的虚拟快照页账号（{@code is_snapshotuser=1}）或钉钉返回临时访客账号，
 * 且当前应用或全局配置开启了 {@code rejectSnapshotUser=true} 时触发刚性拦截，
 * 防止虚拟 {@code openId} 签发绑定票据或污染生产真实用户绑定表。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
public class SocialSnapshotUserException extends SocialException {

    private static final long serialVersionUID = 1L;

    public SocialSnapshotUserException(String message) {
        super(SocialResultCode.SOCIAL_SNAPSHOT_USER_REJECTED, message);
    }

    public SocialSnapshotUserException(String appKey, String openId) {
        super(SocialResultCode.SOCIAL_SNAPSHOT_USER_REJECTED,
                String.format("拒绝第三方平台快照虚拟账号登录或绑定: appKey=[%s], openId=[%s]，请完成完整授权后再试", appKey, openId));
    }

    public SocialSnapshotUserException(String message, Throwable cause) {
        super(SocialResultCode.SOCIAL_SNAPSHOT_USER_REJECTED, message, cause);
    }
}

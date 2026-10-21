package io.github.atengk.boot.realtime.satoken;

import cn.dev33.satoken.listener.SaTokenListener;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import io.github.atengk.boot.realtime.constant.RealtimeCloseStatus;
import io.github.atengk.boot.realtime.template.RealtimeTemplate;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

/**
 * Sa-Token 安全事件联动侦听器
 * <p>
 * 监听 Sa-Token 的账号登出、强退与封禁事件，当用户身份凭据失效或被管理员封禁时，
 * 触发本地 WebSocket/SSE 会话排空关闭（4002 状态码），并联动全集群广播 KICKOUT 信令，
 * 彻底消除跨节点长连接绕过权限控制继续推送敏感业务数据的等保安全漏洞。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
public class RealtimeSaTokenListener implements SaTokenListener {

    private final RealtimeTemplate realtimeTemplate;

    public RealtimeSaTokenListener(RealtimeTemplate realtimeTemplate) {
        this.realtimeTemplate = Objects.requireNonNull(realtimeTemplate, "realtimeTemplate must not be null");
    }

    @Override
    public void doLogin(String loginType, Object loginId, String tokenValue, SaLoginParameter loginParameter) {
        // 登录事件无需切断连接
    }

    @Override
    public void doLogout(String loginType, Object loginId, String tokenValue) {
        handleKickout(loginId, "账号登出 (doLogout)");
    }

    @Override
    public void doKickout(String loginType, Object loginId, String tokenValue) {
        handleKickout(loginId, "管理端强制踢人 (doKickout)");
    }

    @Override
    public void doReplaced(String loginType, Object loginId, String tokenValue) {
        handleKickout(loginId, "顶人下线 (doReplaced)");
    }

    @Override
    public void doDisable(String loginType, Object loginId, String service, int level, long disableTime) {
        handleKickout(loginId, "账号封禁 (doDisable, service=" + service + ", level=" + level + ")");
    }

    @Override
    public void doUntieDisable(String loginType, Object loginId, String service) {
        // 解封事件无需切断
    }

    @Override
    public void doOpenSafe(String loginType, String tokenValue, String service, long safeTime) {
        // 二级认证开启
    }

    @Override
    public void doCloseSafe(String loginType, String tokenValue, String service) {
        // 二级认证关闭
    }

    @Override
    public void doCreateSession(String id) {
        // Session 创建
    }

    @Override
    public void doLogoutSession(String id) {
        // Session 注销
    }

    @Override
    public void doRenewTimeout(String tokenValue, Object loginId, String loginType, long timeout) {
        // Token 续期
    }

    private void handleKickout(Object loginId, String reason) {
        if (loginId == null) {
            return;
        }
        try {
            log.info("[Realtime] 捕获 Sa-Token 安全下线事件，触发跨集群切断: loginId={}, reason={}", loginId, reason);
            realtimeTemplate.kickoutUser(loginId, RealtimeCloseStatus.USER_KICKED_OUT);
        } catch (Throwable t) {
            log.warn("[Realtime] 处理 Sa-Token 安全下线联动切断异常，受控防御防止阻断业务事务: loginId={}, reason={}, error={}",
                    loginId, reason, t.getMessage());
        }
    }
}

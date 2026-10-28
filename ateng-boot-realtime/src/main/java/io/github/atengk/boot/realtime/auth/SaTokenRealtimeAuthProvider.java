package io.github.atengk.boot.realtime.auth;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.realtime.model.SessionKey;
import io.github.atengk.boot.satoken.constant.SaTokenConstant;
import io.github.atengk.boot.satoken.model.LoginUser;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * 基于 Sa-Token 框架的实时通信握手鉴权提供者
 * <p>
 * 根据传入的 Token 验证登录态，自适应提取 userId、tenantId、deviceType 及 LoginUser 上下文。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
public class SaTokenRealtimeAuthProvider implements RealtimeAuthHandshakeProvider {

    @Override
    public RealtimeAuthPayload authenticate(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            // 1. 根据 Token 获取对应账号 LoginId
            Object loginId = StpUtil.getLoginIdByToken(token);
            if (loginId == null) {
                log.debug("[Realtime] Sa-Token 凭据无效或已过期: token={}", token);
                return null;
            }

            // 2. 获取设备端类型
            String deviceType = StpUtil.getLoginDeviceByToken(token);
            if (deviceType == null || deviceType.isBlank()) {
                deviceType = SessionKey.DEFAULT_DEVICE_TYPE;
            }

            // 3. 尝试从 TokenSession 获取存储的 LoginUser 载荷
            String userId = String.valueOf(loginId);
            Long tenantId = null;
            Map<String, Object> extraAttributes = new HashMap<>();

            try {
                SaSession session = StpUtil.getTokenSessionByToken(token);
                if (session != null) {
                    Object rawUser = session.get(SaTokenConstant.LOGIN_USER_KEY);
                    if (rawUser != null) {
                        LoginUser loginUser = null;
                        if (rawUser instanceof LoginUser u) {
                            loginUser = u;
                        } else if (rawUser instanceof String jsonStr) {
                            loginUser = JsonUtil.parseObject(jsonStr, LoginUser.class);
                        }
                        if (loginUser != null) {
                            userId = loginUser.getUserId();
                            if (loginUser.getTenantId() != null && !loginUser.getTenantId().isBlank()) {
                                try {
                                    tenantId = Long.parseLong(loginUser.getTenantId());
                                } catch (NumberFormatException ignored) {
                                }
                            }
                            extraAttributes.put("loginUser", loginUser);
                            extraAttributes.put("userName", loginUser.getUserName());
                            extraAttributes.put("userAccount", loginUser.getUserAccount());
                        }
                    }
                }
            } catch (Throwable t) {
                log.debug("[Realtime] 读取 TokenSession LoginUser 异常: {}", t.getMessage());
            }

            // 4. 若 tenantId 仍为 null，检查复合 LoginId (tenantId_userId)
            if (tenantId == null && userId.contains(SaTokenConstant.TENANT_DELIMITER)) {
                String[] parts = userId.split(SaTokenConstant.TENANT_DELIMITER, 2);
                try {
                    tenantId = Long.parseLong(parts[0]);
                    userId = parts[1];
                } catch (NumberFormatException ignored) {
                }
            }

            return RealtimeAuthPayload.builder()
                    .userId(userId)
                    .deviceType(deviceType)
                    .tenantId(tenantId)
                    .anonymous(false)
                    .extraAttributes(extraAttributes)
                    .build();

        } catch (Throwable t) {
            log.warn("[Realtime] Sa-Token 握手鉴权解析异常: token={}, error={}", token, t.getMessage());
            return null;
        }
    }
}

package io.github.atengk.boot.satoken.model;

import cn.dev33.satoken.json.SaJsonType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 企业级在线会话聚合模型
 * 封装在线用户基础信息、Token 凭据、登录终端设备、客户端真实 IP、登录时间与剩余有效时长，
 * 专供管理后台在线用户列表监控与强退治理
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class OnlineUser implements SaJsonType, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户唯一标识（主键 ID）
     */
    private String userId;

    /**
     * 用户登录账号
     */
    private String userAccount;

    /**
     * 用户姓名或昵称
     */
    private String userName;

    /**
     * 当前会话绑定的 Token 凭据值
     */
    private String tokenValue;

    /**
     * 登录终端设备标识（如 PC, APP, H5 等）
     */
    private String loginDevice;

    /**
     * 登录客户端 IP 地址
     */
    private String loginIp;

    /**
     * 登录时间戳（毫秒）
     */
    private Long loginTime;

    /**
     * Token 剩余有效时长（秒）
     */
    private Long tokenTimeout;
}

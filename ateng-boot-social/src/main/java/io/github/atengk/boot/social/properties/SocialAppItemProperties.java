package io.github.atengk.boot.social.properties;

import io.github.atengk.boot.social.http.SocialProxyConfig;
import lombok.Data;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;
import java.util.List;

/**
 * 单个社交应用实例配置属性
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
public class SocialAppItemProperties {

    /**
     * 社交平台标识 (如 GITHUB, GITEE, WECHAT_OPEN, WECHAT_MP, DINGTALK, FEISHU 等)
     */
    private String source;

    /**
     * 客户端 AppId / Key / ClientId
     */
    private String clientId;

    /**
     * 客户端密钥 ClientSecret / AppSecret
     */
    private String clientSecret;

    /**
     * OAuth2 授权回调地址
     */
    private String redirectUri;

    /**
     * 自托管授权服务器地址 (用于 Gitea 等支持私有化部署的平台)
     */
    private String serverUrl;

    /**
     * 授权作用域列表 (可选)
     */
    private List<String> scopes;

    /**
     * 是否忽略校验 state 参数，默认 false
     */
    private boolean ignoreCheckState = false;

    /**
     * 是否忽略校验 redirectUri 回调地址，默认 false（当未配置 redirectUri 时自动忽略）
     */
    private boolean ignoreCheckRedirectUri = false;

    /**
     * 企业微信授权方的网页应用 ID / AgentId (仅企微需要)
     */
    private String agentId;

    /**
     * 支付宝公钥 (仅支付宝需要)
     */
    private String alipayPublicKey;

    /**
     * 是否申请 unionid (仅 QQ 登录需要)
     */
    private boolean unionId = false;

    /**
     * 应用专有出站网络代理（可选，若配置则覆盖全局代理）
     */
    @NestedConfigurationProperty
    private SocialProxyConfig proxy;

    /**
     * 应用专有连接超时（可选，若配置则覆盖全局）
     */
    private Duration connectTimeout;

    /**
     * 应用专有读取超时（可选，若配置则覆盖全局）
     */
    private Duration readTimeout;
}

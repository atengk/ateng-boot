package io.github.atengk.boot.social.config;

import io.github.atengk.boot.social.http.SocialProxyConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.dromara.anduin.config.AuthConfig;

import java.io.Serializable;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * 社交应用实例配置标准领域对象
 * <p>
 * 承载单个社交平台应用（如特定租户下的 GitHub、微信公众号、企业微信）的全部核心参数，
 * 既支持从 YAML 静态配置解析装填，亦支持通过动态 SPI 在多租户环境下按需构建。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SocialAppConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 社交应用唯一标识（如 github-main, wechat-mp, gitee-tenant1）
     */
    private String appKey;

    /**
     * 社交授权平台来源标识（如 GITHUB, GITEE, WECHAT_OPEN, WECHAT_MP, DINGTALK, FEISHU 等）
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
     * 自托管授权服务器地址（用于 Gitea 等支持私有化部署的平台）
     */
    private String serverUrl;

    /**
     * 授权作用域列表（可选）
     */
    @Builder.Default
    private List<String> scopes = Collections.emptyList();

    /**
     * 是否忽略校验 state 参数，默认 false
     */
    private boolean ignoreCheckState;

    /**
     * 是否忽略校验 redirectUri 回调地址（针对微信小程序、App直传或不需要回调跳转的场景自动开启）
     */
    private boolean ignoreCheckRedirectUri;

    /**
     * 企业微信授权方的网页应用 ID / AgentId
     */
    private String agentId;

    /**
     * 支付宝公钥
     */
    private String alipayPublicKey;

    /**
     * 是否申请 unionid（针对 QQ 登录）
     */
    private boolean unionId;

    /**
     * 是否开启 OAuth 2.1 PKCE 增强模式，默认 false
     */
    private boolean pkce;

    /**
     * Microsoft Entra ID（原微软 Azure AD）目录租户 ID（优先于 tenantId）
     */
    private String microsoftTenantId;

    /**
     * Microsoft Entra ID 目录租户 ID 兼容字段
     */
    private String tenantId;

    /**
     * 苹果开发者账号密钥标识符 (Key ID)
     */
    private String kid;

    /**
     * 苹果开发者账号团队 ID (Team ID)
     */
    private String teamId;

    /**
     * 新版企业微信 Web 登录类型（CorpApp / ServiceApp，默认 CorpApp）
     */
    @Builder.Default
    private String loginType = "CorpApp";

    /**
     * 企业微信语言编码（默认 zh）
     */
    @Builder.Default
    private String lang = "zh";

    /**
     * 企业微信第三方授权用户类型（member|admin）
     */
    private String usertype;

    /**
     * 钉钉组织类型（如 management）
     */
    private String dingTalkOrgType;

    /**
     * 钉钉指定组织 corpId
     */
    private String dingTalkCorpId;

    /**
     * 钉钉是否专属账号登录
     */
    private boolean dingTalkExclusiveLogin;

    /**
     * 钉钉专属账号组织 corpId
     */
    private String dingTalkExclusiveCorpId;

    /**
     * 域名前缀（Coding / Okta）
     */
    private String domainPrefix;

    /**
     * Okta 授权服务器 ID（默认 default）
     */
    @Builder.Default
    private String authServerId = "default";

    /**
     * 设备唯一标识 ID
     */
    private String deviceId;

    /**
     * 客户端操作系统类型（1-iOS，2-Android，3-Web）
     */
    private Integer clientOsType;

    /**
     * 客户端包名或 Bundle ID
     */
    private String packId;

    /**
     * Stack Overflow Key
     */
    private String stackOverflowKey;

    /**
     * 应用专有出站网络代理（可选，若配置则覆盖全局代理）
     */
    private SocialProxyConfig proxy;

    /**
     * 应用专有连接超时（可选）
     */
    private Duration connectTimeout;

    /**
     * 应用专有读取超时（可选）
     */
    private Duration readTimeout;

    /**
     * 当前应用首次社交登录未匹配本地用户时是否自动注册开户（可选，若配置则覆盖全局 autoSignUp）
     */
    private Boolean autoSignUp;

    /**
     * 当前应用是否拒绝第三方平台快照虚拟账号（可选，若配置则覆盖全局 rejectSnapshotUser）
     */
    private Boolean rejectSnapshotUser;

    /**
     * 将本配置转换为 Anduin 原生所需的 AuthConfig 对象
     *
     * @return Anduin AuthConfig
     */
    public AuthConfig toAuthConfig() {
        boolean ignoreRedirect = this.ignoreCheckRedirectUri
                || this.redirectUri == null
                || this.redirectUri.isBlank();

        String resolvedMsTenantId = (this.microsoftTenantId != null && !this.microsoftTenantId.isBlank())
                ? this.microsoftTenantId
                : this.tenantId;
        String resolvedLoginType = (this.loginType != null && !this.loginType.isBlank())
                ? this.loginType
                : "CorpApp";
        String resolvedLang = (this.lang != null && !this.lang.isBlank())
                ? this.lang
                : "zh";
        String resolvedAuthServerId = (this.authServerId != null && !this.authServerId.isBlank())
                ? this.authServerId
                : "default";

        return AuthConfig.builder()
                .clientId(this.clientId)
                .clientSecret(this.clientSecret)
                .redirectUri(this.redirectUri)
                .serverUrl(this.serverUrl)
                .scopes(this.scopes != null ? this.scopes : Collections.emptyList())
                .ignoreCheckState(this.ignoreCheckState)
                .ignoreCheckRedirectUri(ignoreRedirect)
                .agentId(this.agentId)
                .alipayPublicKey(this.alipayPublicKey)
                .unionId(this.unionId)
                .pkce(this.pkce)
                .tenantId(resolvedMsTenantId)
                .kid(this.kid)
                .teamId(this.teamId)
                .loginType(resolvedLoginType)
                .lang(resolvedLang)
                .usertype(this.usertype)
                .dingTalkOrgType(this.dingTalkOrgType)
                .dingTalkCorpId(this.dingTalkCorpId)
                .dingTalkExclusiveLogin(this.dingTalkExclusiveLogin)
                .dingTalkExclusiveCorpId(this.dingTalkExclusiveCorpId)
                .domainPrefix(this.domainPrefix)
                .authServerId(resolvedAuthServerId)
                .deviceId(this.deviceId)
                .clientOsType(this.clientOsType)
                .packId(this.packId)
                .stackOverflowKey(this.stackOverflowKey)
                .build();
    }
}

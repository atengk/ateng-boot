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
     * 是否开启 OAuth 2.1 PKCE 增强模式，默认 false
     */
    private boolean pkce = false;

    /**
     * Microsoft Entra ID（原微软 Azure AD）目录租户 ID（优先于 tenantId，避免与本地 SaaS 多租户混淆）
     */
    private String microsoftTenantId;

    /**
     * Microsoft Entra ID 目录租户 ID 兼容别名
     */
    private String tenantId;

    /**
     * 苹果开发者账号中的密钥标识符 (Key ID, 仅 APPLE 登录需要)
     */
    private String kid;

    /**
     * 苹果开发者账号中的团队 ID (Team ID, 仅 APPLE 登录需要)
     */
    private String teamId;

    /**
     * 新版企业微信 Web 登录类型 (CorpApp: 企业自建/代开发应用登录; ServiceApp: 服务商登录，默认 CorpApp)
     */
    private String loginType = "CorpApp";

    /**
     * 企业微信平台语言编码，默认 zh
     */
    private String lang = "zh";

    /**
     * 企业微信第三方授权用户类型 (member|admin)
     */
    private String usertype;

    /**
     * 钉钉平台参数：控制输出特定类型的组织列表（如 management）
     */
    private String dingTalkOrgType;

    /**
     * 钉钉平台参数：指定用户需要选择的组织 corpId
     */
    private String dingTalkCorpId;

    /**
     * 钉钉平台参数：是否开启专属帐号登录，默认 false
     */
    private boolean dingTalkExclusiveLogin = false;

    /**
     * 钉钉平台参数：开启了专属帐号功能的组织 corpId
     */
    private String dingTalkExclusiveCorpId;

    /**
     * 团队或组织域名前缀（用于 Coding、Okta 等企业平台）
     */
    private String domainPrefix;

    /**
     * Okta 授权服务器 ID，默认 default
     */
    private String authServerId = "default";

    /**
     * 设备唯一标识 ID（可选）
     */
    private String deviceId;

    /**
     * 客户端操作系统类型（1-iOS，2-Android，3-Web，喜马拉雅等平台可选）
     */
    private Integer clientOsType;

    /**
     * 客户端包名或 Bundle ID（喜马拉雅等平台可选）
     */
    private String packId;

    /**
     * Stack Overflow Key（仅 Stack Overflow 登录需要）
     */
    private String stackOverflowKey;

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

    /**
     * 当前应用首次社交登录未匹配本地用户时是否自动注册开户（可选，若配置则覆盖全局 autoSignUp）
     */
    private Boolean autoSignUp;

    /**
     * 当前应用是否拒绝第三方平台快照虚拟账号（可选，若配置则覆盖全局 rejectSnapshotUser）
     */
    private Boolean rejectSnapshotUser;
}

package io.github.atengk.boot.social.spi;

import io.github.atengk.boot.social.model.SocialBoundAccount;
import io.github.atengk.boot.social.model.SocialUser;

import java.util.Collections;
import java.util.List;

/**
 * 社交登录用户持久化与身份绑定业务 SPI 扩展点
 * <p>
 * ateng-boot-social 底座坚决秉持“协议门面与持久化 100% 解耦”原则，框架层不维护任何物理数据表。
 * 由宿主应用实现本 SPI，负责将标准化 {@link SocialUser} 画像映射为本地用户系统。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
public interface SocialUserDetailsService {

    /**
     * 根据第三方用户标准化画像查找本地系统绑定的用户 ID。
     * <p>
     * 针对微信生态推荐推行三阶判定指引：
     * 1. 若 {@link SocialUser#getUnionId()} 非空，优先在全局绑定表中检索 unionId；
     * 2. 若未匹配或 unionId 为空，再通过 openId + source 在当前应用维度进行匹配；
     * 3. 若均未检索到本地绑定记录，则返回 null，由底座无缝转入两阶段绑定流程。
     * </p>
     *
     * @param appKey     发起登录请求的应用唯一标识
     * @param socialUser 第三方授权获取的标准化用户画像
     * @return 本地绑定的用户 ID（如主键或用户唯一编码）；若未绑定则返回 null
     */
    String loadUserIdBySocialUser(String appKey, SocialUser socialUser);

    /**
     * 首次社交登录未匹配到本地用户时，静默自动注册创建本地用户并完成绑定
     * <p>
     * 仅当应用或全局开启 {@code autoSignUp=true} 且 {@link #loadUserIdBySocialUser(String, SocialUser)} 返回 null 时调用。
     * 由宿主应用实现自动创建本地用户账号（如生成用户主数据、分配初始角色、落库第三方绑定关系），并返回新用户的唯一标识。
     * 若返回非空 userId，框架将直接为其签发登录会话；若返回 null，框架将平滑回退至两阶段绑定流程并签发 BindTicket。
     * </p>
     *
     * @param appKey     发起登录请求的应用唯一标识
     * @param socialUser 第三方授权获取的标准化用户画像
     * @return 新创建并绑定的本地用户 ID；若不执行自动注册则返回 null
     */
    default String autoRegisterUser(String appKey, SocialUser socialUser) {
        return null;
    }

    /**
     * 孤儿账号解绑前置守卫校验
     * <p>
     * 检查当前本地用户是否允许解除指定的第三方应用绑定。
     * 若该用户尚未绑定手机号、尚未设置密码，或者当前解绑的第三方是其唯一的登入途径，
     * 必须返回 false 以阻断解绑，防止本地账号沦为无法登入的“孤儿死号”。
     * </p>
     *
     * @param userId 本地用户 ID
     * @param appKey 发起解绑的应用标识
     * @return true 允许解绑；false 拦截解绑并由框架抛出受控业务异常
     */
    default boolean canUnbind(String userId, String appKey) {
        return true;
    }

    /**
     * 完成本地用户与第三方账号绑定关系的持久化落库
     *
     * @param userId     本地用户 ID
     * @param appKey     发起绑定的应用唯一标识
     * @param socialUser 第三方标准化用户画像（包含 openId 与 unionId）
     */
    void bind(String userId, String appKey, SocialUser socialUser);

    /**
     * 完成本地用户与第三方账号绑定关系的持久化落库（与 {@link #bind(String, String, SocialUser)} 语义等价）
     *
     * @param userId     本地用户 ID
     * @param appKey     发起绑定的应用唯一标识
     * @param socialUser 第三方标准化用户画像
     */
    default void bindSocialUser(String userId, String appKey, SocialUser socialUser) {
        bind(userId, appKey, socialUser);
    }

    /**
     * 解除本地用户与第三方账号的持久化绑定关系
     *
     * @param userId 本地用户 ID
     * @param appKey 发起解绑的应用唯一标识
     */
    void unbind(String userId, String appKey);

    /**
     * 查询指定本地用户已绑定的第三方社交账号列表
     * <p>
     * 支撑个人中心展示已绑定的第三方社交账号（如微信、GitHub、QQ 等），
     * 便于用户查看绑定状态、昵称、绑定时间及进行解绑或换绑操作。
     * 默认返回空集合。
     * </p>
     *
     * @param userId 本地用户唯一标识
     * @return 已绑定的第三方账号视图列表；若未绑定任何第三方或未提供实现则返回空列表（严禁返回 null）
     */
    default List<SocialBoundAccount> listBoundAccounts(String userId) {
        return Collections.emptyList();
    }
}

package io.github.atengk.boot.social.ticket;

import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.social.model.SocialUser;

import java.time.Duration;

/**
 * 社交登录两阶段绑定票据缓存仓库契约
 * <p>
 * 当第三方账号尚未绑定系统本地用户时，底座签发高熵唯一 BindTicket 并暂存第三方标准化画像，
 * 在两阶段完成身份认证与绑定时，执行原子消费与一次性物理销毁（阅后即焚防重放），
 * 彻底防御中间人劫持 BindTicket 实施多次恶意绑定的安全漏洞。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
public interface BindTicketStore {

    /**
     * 默认两阶段绑定票据有效时长（5 分钟）
     */
    Duration DEFAULT_TICKET_TIMEOUT = Duration.ofMinutes(5);

    /**
     * 生成高熵 32 位绑定票据并缓存第三方用户画像（使用默认 5 分钟超时）
     *
     * @param socialUser 第三方标准化用户画像
     * @return 高熵 32 位 BindTicket
     */
    default String createTicket(SocialUser socialUser) {
        return createTicket(socialUser, DEFAULT_TICKET_TIMEOUT);
    }

    /**
     * 生成高熵 32 位绑定票据并缓存第三方用户画像
     *
     * @param socialUser 第三方标准化用户画像
     * @param timeout    有效时长
     * @return 高熵 32 位 BindTicket
     */
    default String createTicket(SocialUser socialUser, Duration timeout) {
        String bindTicket = IdUtil.fastSimpleUUID();
        save(bindTicket, socialUser, timeout);
        return bindTicket;
    }

    /**
     * 保存绑定票据与用户画像
     *
     * @param bindTicket 绑定票据凭证
     * @param socialUser 第三方标准化用户画像
     * @param timeout    有效时长
     */
    void save(String bindTicket, SocialUser socialUser, Duration timeout);

    /**
     * 原子消费并物理删除绑定票据（阅后即焚防重放）
     * <p>
     * 一旦读取成功，底层缓存立即物理销毁该票据；后续再次传入相同票据必定返回 null。
     * </p>
     *
     * @param bindTicket 绑定票据凭证
     * @return 绑定的第三方用户画像；若票据不存在、已过期或已被消费则返回 null
     */
    SocialUser remove(String bindTicket);

    /**
     * 根据票据获取第三方用户画像（不消费/不删除）
     *
     * @param bindTicket 绑定票据凭证
     * @return 第三方用户画像；不存在或过期时返回 null
     */
    SocialUser get(String bindTicket);

    /**
     * 检查票据是否存在且有效（不删除）
     *
     * @param bindTicket 绑定票据凭证
     * @return true 存在且有效；false 不存在或已过期
     */
    boolean contains(String bindTicket);
}

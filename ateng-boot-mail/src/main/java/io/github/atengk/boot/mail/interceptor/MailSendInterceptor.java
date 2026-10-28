package io.github.atengk.boot.mail.interceptor;

import io.github.atengk.boot.mail.model.MailMessage;
import org.springframework.core.Ordered;

/**
 * 邮件物理发信拦截器扩展点 (SPI)
 * <p>
 * 支持在物理发信与账号路由前对待发送邮件执行前置校验、收件人黑名单过滤、
 * 动态修改自定义邮件标头或执行发信配额频控拦截。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@FunctionalInterface
public interface MailSendInterceptor extends Ordered {

    /**
     * 发信前置拦截钩子
     *
     * @param message 待发送邮件消息领域模型（允许在此方法中修改标头或执行校验拦截）
     * @return 返回 true 表示放行继续执行后续发信链路；返回 false 或抛出异常表示阻断发信
     */
    boolean preSend(MailMessage message);

    /**
     * 拦截器排序执行优先级，数值越小越优先执行，默认 0
     *
     * @return 优先级数值
     */
    @Override
    default int getOrder() {
        return 0;
    }
}

package io.github.atengk.boot.tcp.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 注入 TCP 设备唯一标识注解
 * <p>
 * 标注在 {@link TcpMapping} 方法的参数上，用于自动绑定并注入当前数据包或会话关联的 {@code deviceId}。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TcpDeviceId {
}

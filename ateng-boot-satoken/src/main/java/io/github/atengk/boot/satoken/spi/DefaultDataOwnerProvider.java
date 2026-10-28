package io.github.atengk.boot.satoken.spi;

/**
 * 默认数据所有者直等反查提供者
 * <p>
 * 当 @CheckDataOwner 注解未指定自定义 provider 时作为默认直等实现，
 * 直接将入参标识转换为字符串作为归属人标识进行直等校验。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-20
 */
public final class DefaultDataOwnerProvider implements DataOwnerProvider<Object> {

    @Override
    public String getOwnerId(Object resourceId) {
        return resourceId != null ? String.valueOf(resourceId) : null;
    }
}

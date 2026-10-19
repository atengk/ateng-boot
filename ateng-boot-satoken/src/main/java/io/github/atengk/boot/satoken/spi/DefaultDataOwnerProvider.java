package io.github.atengk.boot.satoken.spi;

/**
 * 默认空数据所有者反查提供者
 * 用于 @CheckDataOwner 注解未指定 provider 时的占位标识
 *
 * @author Ateng
 * @since 2026-10-20
 */
public final class DefaultDataOwnerProvider implements DataOwnerProvider<Object> {

    @Override
    public String getOwnerId(Object resourceId) {
        return null;
    }
}

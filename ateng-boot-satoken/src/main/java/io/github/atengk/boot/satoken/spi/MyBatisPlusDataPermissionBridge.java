package io.github.atengk.boot.satoken.spi;

import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import org.springframework.util.ClassUtils;

/**
 * 基于 {@code ateng-boot-mybatis-plus} 的 {@link DataPermissionContextHolder} 弱依赖桥接实现
 * 通过内部静态代理类隔离对可选依赖的字节码加载，确保未引入持久层模块时零 {@link NoClassDefFoundError} 风险。
 *
 * @author Ateng
 * @since 2026-11-19
 */
public final class MyBatisPlusDataPermissionBridge implements DataPermissionBridge {

    private static final String HOLDER_CLASS_NAME =
            "io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder";

    private static final boolean PRESENT =
            ClassUtils.isPresent(HOLDER_CLASS_NAME, MyBatisPlusDataPermissionBridge.class.getClassLoader());

    private static final MyBatisPlusDataPermissionBridge INSTANCE = new MyBatisPlusDataPermissionBridge();

    private MyBatisPlusDataPermissionBridge() {
    }

    /**
     * 获取单例桥接器实例
     *
     * @return 桥接器实例
     */
    public static MyBatisPlusDataPermissionBridge getInstance() {
        return INSTANCE;
    }

    /**
     * 判断当前类路径是否具备 {@code DataPermissionContextHolder}
     *
     * @return true 表示存在可选持久层数据权限上下文
     */
    public static boolean isAvailable() {
        return PRESENT;
    }

    @Override
    public void setSuperAdmin(boolean superAdmin) {
        if (PRESENT) {
            Delegate.setSuperAdmin(superAdmin);
        }
    }

    @Override
    public void clear() {
        if (PRESENT) {
            Delegate.clear();
        }
    }

    private static final class Delegate {
        private static void setSuperAdmin(boolean superAdmin) {
            DataPermissionContextHolder.setSuperAdmin(superAdmin);
        }

        private static void clear() {
            DataPermissionContextHolder.clear();
        }
    }
}

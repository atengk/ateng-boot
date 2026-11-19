package io.github.atengk.boot.satoken.strategy;

import cn.dev33.satoken.annotation.SaCheckDisable;
import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaCheckSafe;
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.annotation.handler.SaAnnotationHandlerInterface;
import cn.dev33.satoken.annotation.handler.SaCheckDisableHandler;
import cn.dev33.satoken.annotation.handler.SaCheckLoginHandler;
import cn.dev33.satoken.annotation.handler.SaCheckPermissionHandler;
import cn.dev33.satoken.annotation.handler.SaCheckRoleHandler;
import cn.dev33.satoken.annotation.handler.SaCheckSafeHandler;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.strategy.SaAnnotationStrategy;
import cn.dev33.satoken.util.SaFoxUtil;
import io.github.atengk.boot.satoken.context.SaTokenRouteContextHolder;
import io.github.atengk.boot.satoken.util.LoginHelper;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.util.Map;

/**
 * Sa-Token 鉴权注解多账号自动路由注册器
 * <p>
 * 替换 {@link SaAnnotationStrategy#instance} 中 5 个核心鉴权注解处理器
 * （{@link SaCheckPermission}、{@link SaCheckRole}、{@link SaCheckLogin}、{@link SaCheckSafe}、{@link SaCheckDisable}，
 * 并因 Sa-Token 1.46.0 {@code SaCheckOrHandler} 经由 {@code annotationHandlerMap} 分发而天然覆盖 {@code @SaCheckOr}）。
 * 当注解未显式声明 {@code type()} 时，按
 * {@code 当前请求路由命中 loginType > primaryLoginType > "login"} 顺序解析目标账号体系，
 * 并统一通过 {@link LoginHelper#getStpLogic(String)} 获取实例以保障冷启动懒加载注册。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-19
 */
public final class SaTokenAnnotationAutoRouteRegistrar {

    private static final AutoRoutePermissionHandler PERMISSION_HANDLER = new AutoRoutePermissionHandler();
    private static final AutoRouteRoleHandler ROLE_HANDLER = new AutoRouteRoleHandler();
    private static final AutoRouteLoginHandler LOGIN_HANDLER = new AutoRouteLoginHandler();
    private static final AutoRouteSafeHandler SAFE_HANDLER = new AutoRouteSafeHandler();
    private static final AutoRouteDisableHandler DISABLE_HANDLER = new AutoRouteDisableHandler();

    private SaTokenAnnotationAutoRouteRegistrar() {
    }

    /**
     * 向 {@link SaAnnotationStrategy#instance} 注册 5 个多账号自动路由注解处理器
     */
    public static void register() {
        SaAnnotationStrategy strategy = SaAnnotationStrategy.instance;
        strategy.registerAnnotationHandler(PERMISSION_HANDLER);
        strategy.registerAnnotationHandler(ROLE_HANDLER);
        strategy.registerAnnotationHandler(LOGIN_HANDLER);
        strategy.registerAnnotationHandler(SAFE_HANDLER);
        strategy.registerAnnotationHandler(DISABLE_HANDLER);
    }

    /**
     * 仅当当前挂载的是本注册器托管的自动路由处理器时，精确恢复为 Sa-Token 默认注解处理器
     */
    public static void restoreDefaultsIfManaged() {
        Map<Class<?>, SaAnnotationHandlerInterface<?>> map = SaAnnotationStrategy.instance.annotationHandlerMap;
        restoreIfManaged(map, SaCheckPermission.class, new SaCheckPermissionHandler());
        restoreIfManaged(map, SaCheckRole.class, new SaCheckRoleHandler());
        restoreIfManaged(map, SaCheckLogin.class, new SaCheckLoginHandler());
        restoreIfManaged(map, SaCheckSafe.class, new SaCheckSafeHandler());
        restoreIfManaged(map, SaCheckDisable.class, new SaCheckDisableHandler());
    }

    /**
     * 判断当前是否已挂载自动路由注解处理器
     *
     * @return true 表示 5 个托管处理器均处于生效状态
     */
    public static boolean isRegistered() {
        Map<Class<?>, SaAnnotationHandlerInterface<?>> map = SaAnnotationStrategy.instance.annotationHandlerMap;
        return map.get(SaCheckPermission.class) instanceof ManagedAutoRouteHandler
                && map.get(SaCheckRole.class) instanceof ManagedAutoRouteHandler
                && map.get(SaCheckLogin.class) instanceof ManagedAutoRouteHandler
                && map.get(SaCheckSafe.class) instanceof ManagedAutoRouteHandler
                && map.get(SaCheckDisable.class) instanceof ManagedAutoRouteHandler;
    }

    /**
     * 解析注解最终生效的目标 {@code loginType}
     *
     * @param explicitType 注解显式声明的 {@code type()} 值
     * @return 解析后的非空 {@code loginType}
     */
    public static String resolveLoginType(String explicitType) {
        if (explicitType != null && !explicitType.isBlank()) {
            return explicitType.trim();
        }
        String routeType = SaTokenRouteContextHolder.get();
        if (routeType != null && !routeType.isBlank()) {
            return routeType.trim();
        }
        String primaryType = LoginHelper.getPrimaryLoginType();
        if (primaryType != null && !primaryType.isBlank()) {
            return primaryType.trim();
        }
        return StpUtil.TYPE;
    }

    private static void restoreIfManaged(
            Map<Class<?>, SaAnnotationHandlerInterface<?>> map,
            Class<? extends Annotation> annotationClass,
            SaAnnotationHandlerInterface<?> defaultHandler) {
        if (map.get(annotationClass) instanceof ManagedAutoRouteHandler) {
            SaAnnotationStrategy.instance.registerAnnotationHandler(defaultHandler);
        }
    }

    private interface ManagedAutoRouteHandler {
    }

    private static final class AutoRoutePermissionHandler
            implements SaAnnotationHandlerInterface<SaCheckPermission>, ManagedAutoRouteHandler {

        @Override
        public Class<SaCheckPermission> getHandlerAnnotationClass() {
            return SaCheckPermission.class;
        }

        @Override
        public void checkMethod(SaCheckPermission at, AnnotatedElement element) {
            StpLogic logic = LoginHelper.getStpLogic(resolveLoginType(at.type()));
            try {
                if (at.mode() == SaMode.AND) {
                    logic.checkPermissionAnd(at.value());
                } else {
                    logic.checkPermissionOr(at.value());
                }
            } catch (NotPermissionException e) {
                for (String role : at.orRole()) {
                    String[] roleArray = SaFoxUtil.convertStringToArray(role);
                    if (logic.hasRoleAnd(roleArray)) {
                        return;
                    }
                }
                throw e;
            }
        }
    }

    private static final class AutoRouteRoleHandler
            implements SaAnnotationHandlerInterface<SaCheckRole>, ManagedAutoRouteHandler {

        @Override
        public Class<SaCheckRole> getHandlerAnnotationClass() {
            return SaCheckRole.class;
        }

        @Override
        public void checkMethod(SaCheckRole at, AnnotatedElement element) {
            StpLogic logic = LoginHelper.getStpLogic(resolveLoginType(at.type()));
            if (at.mode() == SaMode.AND) {
                logic.checkRoleAnd(at.value());
            } else {
                logic.checkRoleOr(at.value());
            }
        }
    }

    private static final class AutoRouteLoginHandler
            implements SaAnnotationHandlerInterface<SaCheckLogin>, ManagedAutoRouteHandler {

        @Override
        public Class<SaCheckLogin> getHandlerAnnotationClass() {
            return SaCheckLogin.class;
        }

        @Override
        public void checkMethod(SaCheckLogin at, AnnotatedElement element) {
            LoginHelper.getStpLogic(resolveLoginType(at.type())).checkLogin();
        }
    }

    private static final class AutoRouteSafeHandler
            implements SaAnnotationHandlerInterface<SaCheckSafe>, ManagedAutoRouteHandler {

        @Override
        public Class<SaCheckSafe> getHandlerAnnotationClass() {
            return SaCheckSafe.class;
        }

        @Override
        public void checkMethod(SaCheckSafe at, AnnotatedElement element) {
            LoginHelper.getStpLogic(resolveLoginType(at.type())).checkSafe(at.value());
        }
    }

    private static final class AutoRouteDisableHandler
            implements SaAnnotationHandlerInterface<SaCheckDisable>, ManagedAutoRouteHandler {

        @Override
        public Class<SaCheckDisable> getHandlerAnnotationClass() {
            return SaCheckDisable.class;
        }

        @Override
        public void checkMethod(SaCheckDisable at, AnnotatedElement element) {
            StpLogic logic = LoginHelper.getStpLogic(resolveLoginType(at.type()));
            Object loginId = logic.getLoginId();
            for (String service : at.value()) {
                logic.checkDisableLevel(loginId, service, at.level());
            }
        }
    }
}

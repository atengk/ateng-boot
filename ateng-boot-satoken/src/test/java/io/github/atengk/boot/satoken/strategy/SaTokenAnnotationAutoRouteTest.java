package io.github.atengk.boot.satoken.strategy;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.annotation.SaCheckDisable;
import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckOr;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaCheckSafe;
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.exception.DisableServiceException;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import cn.dev33.satoken.exception.NotSafeException;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.strategy.SaAnnotationStrategy;
import io.github.atengk.boot.satoken.context.SaTokenRouteContextHolder;
import io.github.atengk.boot.satoken.util.LoginHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sa-Token 注解多账号自动路由策略单元测试
 * 验证 5 大核心鉴权注解及 @SaCheckOr 按 [显式 type > 路由上下文 > primaryLoginType > login] 优先级自动路由
 *
 * @author Ateng
 * @since 2026-11-19
 */
@DisplayName("SaTokenAnnotationAutoRouteRegistrar 注解多账号自动路由单元测试")
class SaTokenAnnotationAutoRouteTest {

    static class DemoSecuredController {

        @SaCheckPermission("system:user:create")
        public void createUser() {
        }

        @SaCheckPermission(value = {"system:user:view", "system:user:list"}, mode = SaMode.OR)
        public void viewUserOr() {
        }

        @SaCheckPermission(value = {"system:user:edit", "system:user:audit"}, mode = SaMode.AND, orRole = "super_admin")
        public void auditUser() {
        }

        @SaCheckPermission(value = "member:order:view", type = "member")
        public void explicitMemberPerm() {
        }

        @SaCheckRole("ops_admin")
        public void opsOnly() {
        }

        @SaCheckRole(value = {"ops_admin", "sec_admin"}, mode = SaMode.OR)
        public void opsOrSec() {
        }

        @SaCheckLogin
        public void loginOnly() {
        }

        @SaCheckSafe("pay")
        public void paySafe() {
        }

        @SaCheckDisable(value = "comment", level = 2)
        public void postComment() {
        }

        @SaCheckOr(
                role = @SaCheckRole("auditor"),
                permission = @SaCheckPermission("system:audit:run")
        )
        public void checkOrMethod() {
        }
    }

    @BeforeEach
    void setUp() {
        SaTokenRouteContextHolder.clear();
        LoginHelper.setPrimaryLoginType(StpUtil.TYPE);
        SaTokenAnnotationAutoRouteRegistrar.register();
    }

    @AfterEach
    void tearDown() {
        SaTokenRouteContextHolder.clear();
        LoginHelper.setPrimaryLoginType(StpUtil.TYPE);
        SaTokenAnnotationAutoRouteRegistrar.restoreDefaultsIfManaged();
    }

    @Test
    @DisplayName("1. 命中路由上下文时，5 个无 type 注解与 @SaCheckOr 全部自动路由至目标 StpLogic")
    void shouldRouteAllFiveAnnotationsAndCheckOrByRouteContext() throws Exception {
        SaTokenRouteContextHolder.set("admin");

        try (MockedStatic<LoginHelper> helperMock = mockStatic(LoginHelper.class, CALLS_REAL_METHODS)) {
            StpLogic adminLogic = mock(StpLogic.class);
            helperMock.when(() -> LoginHelper.getStpLogic("admin")).thenReturn(adminLogic);
            when(adminLogic.getLoginId()).thenReturn("admin-1");

            // @SaCheckPermission（默认 AND）与显式 OR
            checkMethod("createUser");
            verify(adminLogic).checkPermissionAnd("system:user:create");

            checkMethod("viewUserOr");
            verify(adminLogic).checkPermissionOr("system:user:view", "system:user:list");

            // @SaCheckPermission(mode=AND, orRole)
            doThrow(new NotPermissionException("system:user:audit")).when(adminLogic)
                    .checkPermissionAnd("system:user:edit", "system:user:audit");
            when(adminLogic.hasRoleAnd("super_admin")).thenReturn(true);
            checkMethod("auditUser");
            verify(adminLogic).hasRoleAnd("super_admin");

            // @SaCheckRole（默认 AND）与显式 OR
            checkMethod("opsOnly");
            verify(adminLogic).checkRoleAnd("ops_admin");

            checkMethod("opsOrSec");
            verify(adminLogic).checkRoleOr("ops_admin", "sec_admin");

            // @SaCheckLogin
            checkMethod("loginOnly");
            verify(adminLogic).checkLogin();

            // @SaCheckSafe
            checkMethod("paySafe");
            verify(adminLogic).checkSafe("pay");

            // @SaCheckDisable
            checkMethod("postComment");
            verify(adminLogic).checkDisableLevel("admin-1", "comment", 2);

            // @SaCheckOr：角色失败但权限通过时放行
            doThrow(new NotRoleException("auditor")).when(adminLogic).checkRoleAnd("auditor");
            checkMethod("checkOrMethod");
            verify(adminLogic).checkPermissionAnd("system:audit:run");
        }
    }

    @Test
    @DisplayName("2. 注解显式声明 type 时严格优先生效，不被路由上下文覆盖")
    void shouldRespectExplicitAnnotationTypeOverRouteContext() throws Exception {
        SaTokenRouteContextHolder.set("admin");

        try (MockedStatic<LoginHelper> helperMock = mockStatic(LoginHelper.class, CALLS_REAL_METHODS)) {
            StpLogic memberLogic = mock(StpLogic.class);
            helperMock.when(() -> LoginHelper.getStpLogic("member")).thenReturn(memberLogic);

            checkMethod("explicitMemberPerm");
            verify(memberLogic).checkPermissionAnd("member:order:view");
        }
    }

    @Test
    @DisplayName("3. 无路由上下文时按 primaryLoginType -> login 顺序安全回退")
    void shouldFallbackToPrimaryLoginTypeAndDefaultLogin() throws Exception {
        LoginHelper.setPrimaryLoginType("admin");

        try (MockedStatic<LoginHelper> helperMock = mockStatic(LoginHelper.class, CALLS_REAL_METHODS)) {
            StpLogic adminLogic = mock(StpLogic.class);
            helperMock.when(() -> LoginHelper.getStpLogic("admin")).thenReturn(adminLogic);

            checkMethod("loginOnly");
            verify(adminLogic).checkLogin();
        }
    }

    @Test
    @DisplayName("4. 冷启动未预注册 loginType 时通过 LoginHelper.getStpLogic 触发懒加载并校验")
    void shouldLazyRegisterColdStartLoginTypeViaLoginHelper() throws Exception {
        String coldType = "cold_route_" + System.nanoTime();
        SaTokenRouteContextHolder.set(coldType);

        try {
            assertThat(SaManager.stpLogicMap).doesNotContainKey(coldType);
            // 无请求上下文时抛出 SaTokenException 子类，但应先通过 LoginHelper.getStpLogic 完成懒加载注册
            assertThatThrownBy(() -> checkMethod("loginOnly"))
                    .isInstanceOf(cn.dev33.satoken.exception.SaTokenException.class);
            assertThat(SaManager.stpLogicMap).containsKey(coldType);
            assertThat(SaManager.stpLogicMap.get(coldType).splicingKeyTokenName()).endsWith("-" + coldType);
        } finally {
            SaManager.stpLogicMap.remove(coldType);
        }
    }

    @Test
    @DisplayName("5. 关闭或恢复默认处理器后，无 type 注解回归 Sa-Token 默认 login 体系")
    void shouldRestoreDefaultHandlersWhenDisabled() throws Exception {
        SaTokenAnnotationAutoRouteRegistrar.restoreDefaultsIfManaged();
        SaTokenRouteContextHolder.set("admin");

        assertThat(SaAnnotationStrategy.instance.annotationHandlerMap.get(SaCheckPermission.class).getClass().getName())
                .isEqualTo("cn.dev33.satoken.annotation.handler.SaCheckPermissionHandler");
        assertThat(SaAnnotationStrategy.instance.annotationHandlerMap.get(SaCheckRole.class).getClass().getName())
                .isEqualTo("cn.dev33.satoken.annotation.handler.SaCheckRoleHandler");
        assertThat(SaAnnotationStrategy.instance.annotationHandlerMap.get(SaCheckLogin.class).getClass().getName())
                .isEqualTo("cn.dev33.satoken.annotation.handler.SaCheckLoginHandler");
        assertThat(SaAnnotationStrategy.instance.annotationHandlerMap.get(SaCheckSafe.class).getClass().getName())
                .isEqualTo("cn.dev33.satoken.annotation.handler.SaCheckSafeHandler");
        assertThat(SaAnnotationStrategy.instance.annotationHandlerMap.get(SaCheckDisable.class).getClass().getName())
                .isEqualTo("cn.dev33.satoken.annotation.handler.SaCheckDisableHandler");
    }

    @Test
    @DisplayName("6. 异常语义保持原样抛出（NotPermission/NotRole/NotSafe/DisableService）")
    void shouldPropagateOriginalSaTokenExceptions() throws Exception {
        SaTokenRouteContextHolder.set("admin");

        try (MockedStatic<LoginHelper> helperMock = mockStatic(LoginHelper.class, CALLS_REAL_METHODS)) {
            StpLogic adminLogic = mock(StpLogic.class);
            helperMock.when(() -> LoginHelper.getStpLogic("admin")).thenReturn(adminLogic);

            doThrow(new NotPermissionException("system:user:create")).when(adminLogic)
                    .checkPermissionAnd("system:user:create");
            assertThatThrownBy(() -> checkMethod("createUser")).isInstanceOf(NotPermissionException.class);

            doThrow(new NotSafeException("admin", "token-1", "pay")).when(adminLogic).checkSafe("pay");
            assertThatThrownBy(() -> checkMethod("paySafe")).isInstanceOf(NotSafeException.class);

            when(adminLogic.getLoginId()).thenReturn("admin-1");
            doThrow(new DisableServiceException("admin", "admin-1", "comment", 2, 2, 100L))
                    .when(adminLogic).checkDisableLevel("admin-1", "comment", 2);
            assertThatThrownBy(() -> checkMethod("postComment")).isInstanceOf(DisableServiceException.class);
        }
    }

    private void checkMethod(String methodName) throws NoSuchMethodException {
        Method method = DemoSecuredController.class.getMethod(methodName);
        SaAnnotationStrategy.instance.checkMethodAnnotation.accept(method);
    }
}

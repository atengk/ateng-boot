package io.github.atengk.boot.openapi.customizer;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.annotation.SaMode;
import io.github.atengk.boot.openapi.properties.OpenApiProperties;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨模块接口治理契约内省定制器测试
 *
 * @author Ateng
 * @since 2026-11-16
 */
@DisplayName("OperationGovernanceCustomizer 跨模块治理契约测试")
class OperationGovernanceCustomizerTest {

    static class NormalController {
        @SaCheckLogin
        @SaCheckRole(value = {"admin", "auditor"}, mode = SaMode.OR)
        @SaCheckPermission(value = {"user:create"}, mode = SaMode.AND)
        public void securedAction() {
        }

        @SaIgnore
        public void publicAction() {
        }

        @SaIgnore
        @SaCheckLogin
        public void conflictedMethodAuth() {
        }
    }

    @SaIgnore
    static class IgnoredClassController {
        public void openEndpoint() {
        }

        @SaCheckLogin
        @SaCheckPermission("admin:override")
        public void methodOverrideAuth() {
        }
    }

    @Test
    @DisplayName("测试 @SaIgnore 公开方法自动摘除锁头（security 置空列表）且在缺省 Web 依赖环境下安全降级")
    void testSaIgnoreClearsSecurityAndSafeWithoutWebAnnotations() throws NoSuchMethodException {
        OpenApiProperties properties = new OpenApiProperties();
        OperationGovernanceCustomizer customizer = new OperationGovernanceCustomizer(properties);

        Operation operation = new Operation()
                .description("公开健康探针")
                .security(List.of(new SecurityRequirement().addList("satoken")));

        Method method = NormalController.class.getMethod("publicAction");
        HandlerMethod handlerMethod = new HandlerMethod(new NormalController(), method);

        customizer.customize(operation, handlerMethod);

        assertThat(operation.getSecurity()).isNotNull().isEmpty();
        assertThat(operation.getDescription()).contains("免鉴权公开访问");
    }

    @Test
    @DisplayName("测试类级 @SaIgnore 与方法级 @SaCheckLogin 覆盖仲裁：方法鉴权覆盖时保留锁头")
    void testMethodAuthOverridesClassSaIgnore() throws NoSuchMethodException {
        OpenApiProperties properties = new OpenApiProperties();
        OperationGovernanceCustomizer customizer = new OperationGovernanceCustomizer(properties);
        IgnoredClassController controller = new IgnoredClassController();

        // 1. 继承类级 @SaIgnore -> 摘除锁头
        Operation openOp = new Operation().security(List.of(new SecurityRequirement().addList("satoken")));
        customizer.customize(openOp, new HandlerMethod(controller, IgnoredClassController.class.getMethod("openEndpoint")));
        assertThat(openOp.getSecurity()).isEmpty();

        // 2. 方法显式标注 @SaCheckLogin -> 不摘除锁头，并追加权限说明
        Operation overrideOp = new Operation().security(List.of(new SecurityRequirement().addList("satoken")));
        customizer.customize(overrideOp, new HandlerMethod(controller, IgnoredClassController.class.getMethod("methodOverrideAuth")));
        assertThat(overrideOp.getSecurity()).hasSize(1);
        assertThat(overrideOp.getDescription())
                .contains("**接口治理契约**：")
                .contains("admin:override");

        // 3. 同方法叠标 @SaIgnore 与 @SaCheckLogin -> Fail-Closed 保留锁头
        Operation conflictedOp = new Operation().security(List.of(new SecurityRequirement().addList("satoken")));
        customizer.customize(conflictedOp, new HandlerMethod(new NormalController(), NormalController.class.getMethod("conflictedMethodAuth")));
        assertThat(conflictedOp.getSecurity()).hasSize(1);
        assertThat(conflictedOp.getDescription()).contains("需要登录认证");
    }

    @Test
    @DisplayName("测试 Sa-Token 登录、角色与权限注解渲染标准化 Markdown 治理说明及幂等防重")
    void testSaTokenGovernanceNotesIdempotent() throws NoSuchMethodException {
        OpenApiProperties properties = new OpenApiProperties();
        OperationGovernanceCustomizer customizer = new OperationGovernanceCustomizer(properties);

        Operation operation = new Operation().description("创建系统用户");
        HandlerMethod handlerMethod = new HandlerMethod(
                new NormalController(),
                NormalController.class.getMethod("securedAction")
        );

        customizer.customize(operation, handlerMethod);
        String firstDesc = operation.getDescription();
        assertThat(firstDesc)
                .contains("创建系统用户")
                .contains("**接口治理契约**：")
                .contains("登录认证")
                .contains("角色要求")
                .contains("admin")
                .contains("auditor")
                .contains("权限要求")
                .contains("user:create");

        // 重复调用幂等，不重复追加
        customizer.customize(operation, handlerMethod);
        assertThat(operation.getDescription()).isEqualTo(firstDesc);
    }

    @Test
    @DisplayName("测试关闭 operationGovernanceNotes 开关时跳过描述说明注入，仍保留 @SaIgnore 免锁头语义")
    void testGovernanceNotesDisabledSwitch() throws NoSuchMethodException {
        OpenApiProperties properties = new OpenApiProperties();
        properties.setOperationGovernanceNotes(false);
        OperationGovernanceCustomizer customizer = new OperationGovernanceCustomizer(properties);

        Operation publicOp = new Operation()
                .description("原始描述")
                .security(List.of(new SecurityRequirement().addList("satoken")));
        customizer.customize(publicOp, new HandlerMethod(
                new NormalController(), NormalController.class.getMethod("publicAction")));
        assertThat(publicOp.getSecurity()).isEmpty();
        assertThat(publicOp.getDescription()).isEqualTo("原始描述");

        Operation securedOp = new Operation().description("受保护描述");
        customizer.customize(securedOp, new HandlerMethod(
                new NormalController(), NormalController.class.getMethod("securedAction")));
        assertThat(securedOp.getDescription()).isEqualTo("受保护描述");
    }

    @Test
    @DisplayName("测试空安全防御保护")
    void testNullSafety() {
        OperationGovernanceCustomizer customizer = new OperationGovernanceCustomizer(new OpenApiProperties());
        assertThat(customizer.customize(null, null)).isNull();

        Operation op = new Operation().description("demo");
        assertThat(customizer.customize(op, null)).isSameAs(op);
        assertThat(op.getDescription()).isEqualTo("demo");
    }
}

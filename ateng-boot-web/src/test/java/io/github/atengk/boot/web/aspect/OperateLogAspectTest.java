package io.github.atengk.boot.web.aspect;

import io.github.atengk.boot.core.annotation.FieldSensitive;
import io.github.atengk.boot.core.audit.OperateLogModel;
import io.github.atengk.boot.core.audit.OperateTypeEnum;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.enums.SensitiveType;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.web.annotation.OperateLog;
import io.github.atengk.boot.web.audit.OperateLogStorageProvider;
import io.github.atengk.boot.web.properties.WebProperties;
import io.github.atengk.boot.web.trace.TraceUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 结构化操作审计切面 OperateLogAspect 单元测试
 *
 * @author Ateng
 * @since 2026-10-18
 */
@DisplayName("结构化操作审计切面 OperateLogAspect 单元测试")
class OperateLogAspectTest {

    private final AtomicReference<OperateLogModel> capturedModel = new AtomicReference<>();
    private OperateLogService targetService;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class TestUserVO {
        private String username;
        @FieldSensitive(SensitiveType.MOBILE_PHONE)
        private String phone;
        @FieldSensitive(SensitiveType.ID_CARD)
        private String idCard;
    }

    interface OperateLogService {
        Result<String> createUser(String username, String password);
        Result<TestUserVO> getUser(Long id);
        Result<Void> deleteUser(Long id);
        Result<String> noSaveMethod(String input);
        Result<String> loginWithoutExclude(String username, Map<String, Object> payload);
        Result<String> updateSecurity(Map<String, Object> payload);
        Result<String> submitDtoAndArray(AuthPayloadDTO dto, List<AuthPayloadDTO> items, Object[] extras);
        Result<String> circularArg(CircularTokenNode node);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class AuthPayloadDTO {
        private String account;
        private String accessToken;
        private String oldPassword;
    }

    static class CircularTokenNode {
        public String token = "secret-cycle-token";
        public CircularTokenNode self;
    }

    static class OperateLogServiceImpl implements OperateLogService {

        @Override
        @OperateLog(module = "用户管理", type = OperateTypeEnum.INSERT, description = "新增系统用户", excludeParams = {"password"})
        public Result<String> createUser(String username, String password) {
            return Result.success("created:" + username);
        }

        @Override
        @OperateLog(title = "用户中心", type = OperateTypeEnum.QUERY, description = "查询用户详情")
        public Result<TestUserVO> getUser(Long id) {
            return Result.success(new TestUserVO("ateng", "13800138000", "110101199003072345"));
        }

        @Override
        @OperateLog(module = "用户管理", type = OperateTypeEnum.DELETE, description = "删除系统用户")
        public Result<Void> deleteUser(Long id) {
            throw new BusinessException(403, "当前管理员无权删除根用户");
        }

        @Override
        @OperateLog(module = "性能模块", type = OperateTypeEnum.OTHER, saveParams = false, saveResult = false)
        public Result<String> noSaveMethod(String input) {
            return Result.success("echo:" + input);
        }

        @Override
        @OperateLog(module = "认证中心", type = OperateTypeEnum.OTHER, description = "默认基线脱敏")
        public Result<String> loginWithoutExclude(String username, Map<String, Object> payload) {
            return Result.success("ok:" + username);
        }

        @Override
        @OperateLog(module = "认证中心", type = OperateTypeEnum.UPDATE, description = "叠加排除字段", excludeParams = {"customPin"})
        public Result<String> updateSecurity(Map<String, Object> payload) {
            return Result.success("updated");
        }

        @Override
        @OperateLog(module = "认证中心", type = OperateTypeEnum.INSERT, description = "DTO与集合数组默认脱敏")
        public Result<String> submitDtoAndArray(AuthPayloadDTO dto, List<AuthPayloadDTO> items, Object[] extras) {
            return Result.success("dto-ok");
        }

        @Override
        @OperateLog(module = "认证中心", type = OperateTypeEnum.OTHER, description = "环引用安全降级")
        public Result<String> circularArg(CircularTokenNode node) {
            return Result.success("circular-ok");
        }
    }

    @BeforeEach
    void setUp() {
        capturedModel.set(null);
        OperateLogStorageProvider mockStorage = capturedModel::set;
        WebProperties properties = new WebProperties();
        OperateLogAspect aspect = new OperateLogAspect(mockStorage, null, JsonUtil.getObjectMapper(), null, properties);

        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(new OperateLogServiceImpl());
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAspect(aspect);
        targetService = proxyFactory.getProxy();
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        UserContextHolder.setUserProvider(null);
        TenantContextHolder.clear();
        TraceUtil.clear();
    }

    @Test
    @DisplayName("成功执行业务操作时，正确采集模块、操作动作、耗时、状态码 0 并对排除字段进行 [EXCLUDED] 掩码")
    void shouldCaptureSuccessfulOperationAndExcludeParams() {
        TraceUtil.setTraceId("trace-audit-001");
        UserContextHolder.set("operator-admin");
        TenantContextHolder.setTenantId("tenant-888");

        Result<String> result = targetService.createUser("ateng", "rawPassword123");

        assertThat(result.getCode()).isEqualTo(200);

        OperateLogModel model = capturedModel.get();
        assertThat(model).isNotNull();
        assertThat(model.getTraceId()).isEqualTo("trace-audit-001");
        assertThat(model.getTenantId()).isEqualTo("tenant-888");
        assertThat(model.getUserId()).isEqualTo("operator-admin");
        assertThat(model.getModule()).isEqualTo("用户管理");
        assertThat(model.getOperateType()).isEqualTo(OperateTypeEnum.INSERT);
        assertThat(model.getDescription()).isEqualTo("新增系统用户");
        assertThat(model.getStatus()).isEqualTo(0);
        assertThat(model.getErrorMsg()).isNull();
        assertThat(model.getCostTime()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(model.getOperateTime()).isNotNull();

        // 验证敏感入参排除掩码
        assertThat(model.getRequestParams()).contains("\"username\":\"ateng\"");
        assertThat(model.getRequestParams()).contains("\"password\":\"[EXCLUDED]\"");
        assertThat(model.getRequestParams()).doesNotContain("rawPassword123");
    }

    @Test
    @DisplayName("出参结果序列化时自动联动 @FieldSensitive 执行视图脱敏，同时保障内存实体真明文不变量")
    void shouldDesensitizeResponseResultWhileKeepingPlaintextInMemory() {
        Result<TestUserVO> response = targetService.getUser(1001L);

        // 内存真明文断言（业务层实体对象绝不被污染为星号）
        assertThat(response.getData().getPhone()).isEqualTo("13800138000");
        assertThat(response.getData().getIdCard()).isEqualTo("110101199003072345");

        // 审计日志脱敏断言（出参输出被自动脱敏掩码）
        OperateLogModel model = capturedModel.get();
        assertThat(model).isNotNull();
        assertThat(model.getStatus()).isEqualTo(0);
        assertThat(model.getModule()).isEqualTo("用户中心");
        assertThat(model.getOperateType()).isEqualTo(OperateTypeEnum.QUERY);
        assertThat(model.getResponseResult()).contains("138****8000");
        assertThat(model.getResponseResult()).contains("110101********2345");
        assertThat(model.getResponseResult()).doesNotContain("13800138000");
        assertThat(model.getResponseResult()).doesNotContain("110101199003072345");
    }

    @Test
    @DisplayName("目标方法抛出异常时准确标记 status=1 并记录 errorMsg，同时 100% 原样重抛原始异常")
    void shouldCaptureExceptionMarkFailureAndRethrowIntact() {
        UserContextHolder.set("bad-actor");

        assertThatThrownBy(() -> targetService.deleteUser(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当前管理员无权删除根用户")
                .extracting("code")
                .isEqualTo(403);

        OperateLogModel model = capturedModel.get();
        assertThat(model).isNotNull();
        assertThat(model.getStatus()).isEqualTo(1);
        assertThat(model.getModule()).isEqualTo("用户管理");
        assertThat(model.getOperateType()).isEqualTo(OperateTypeEnum.DELETE);
        assertThat(model.getErrorMsg()).contains("当前管理员无权删除根用户");
    }

    @Test
    @DisplayName("当 saveParams 与 saveResult 设为 false 时，不采集出入参内容")
    void shouldRespectSaveSwitches() {
        Result<String> result = targetService.noSaveMethod("secret-input");
        assertThat(result.getCode()).isEqualTo(200);

        OperateLogModel model = capturedModel.get();
        assertThat(model).isNotNull();
        assertThat(model.getRequestParams()).isEmpty();
        assertThat(model.getResponseResult()).isNull();
    }

    @Test
    @DisplayName("当全局配置 ateng.boot.web.operate-log.enabled=false 时，切面透传放行且不触发审计持久化")
    void shouldBypassWhenGloballyDisabled() {
        WebProperties properties = new WebProperties();
        properties.getOperateLog().setEnabled(false);

        OperateLogAspect aspect = new OperateLogAspect(capturedModel::set, null, JsonUtil.getObjectMapper(), null, properties);
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(new OperateLogServiceImpl());
        proxyFactory.addAspect(aspect);
        OperateLogService disabledService = proxyFactory.getProxy();

        Result<String> result = disabledService.createUser("test", "pwd");
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(capturedModel.get()).isNull();
    }

    @Test
    @DisplayName("处于身份模拟态时，OperateLog 切面自动结构化记录操作人与被模拟人双维身份")
    void shouldCaptureOriginalUserIdAndRunAsFlagWhenImpersonating() {
        // 注册模拟态 SPI
        UserContextHolder.setUserProvider(new io.github.atengk.boot.core.spi.CurrentUserProvider() {
            @Override
            public String getCurrentUser() {
                return "target-user-66002";
            }

            @Override
            public String getOriginalUser() {
                return "admin-90001";
            }

            @Override
            public boolean isRunAs() {
                return true;
            }
        });

        Result<String> result = targetService.createUser("demo", "pwd");
        assertThat(result.getCode()).isEqualTo(200);

        OperateLogModel model = capturedModel.get();
        assertThat(model).isNotNull();
        // 业务被操作人
        assertThat(model.getUserId()).isEqualTo("target-user-66002");
        // 原始管理员
        assertThat(model.getOriginalUserId()).isEqualTo("admin-90001");
        // extra 扩展字典结构化记录
        assertThat(model.getExtra()).isNotNull();
        assertThat(model.getExtra().get("isRunAs")).isEqualTo(true);
        assertThat(model.getExtra().get("originalUserId")).isEqualTo("admin-90001");
        assertThat(model.getExtra().get("targetUserId")).isEqualTo("target-user-66002");
    }

    @Test
    @DisplayName("OperateLogStorageProvider 对称兼容：default save(model) 自动委托 store(model)")
    void shouldDelegateSaveToStoreOnStorageProvider() {
        AtomicReference<OperateLogModel> saved = new AtomicReference<>();
        OperateLogStorageProvider provider = saved::set;
        OperateLogModel model = new OperateLogModel();
        model.setTraceId("trace-save-alias");

        provider.save(model);

        assertThat(saved.get()).isSameAs(model);
    }

    @Test
    @DisplayName("默认敏感参数脱敏基线：未配注解 excludeParams 时自动掩码全部默认敏感字段（忽略大小写及嵌套字段）")
    void shouldMaskDefaultSensitiveParamsWhenAnnotationExcludeEmpty() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("accessToken", "nested-access-token");
        nested.put("remark", "safe-note");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("password", "p1");
        payload.put("passwd", "p2");
        payload.put("pwd", "p3");
        payload.put("oldPassword", "p4");
        payload.put("newPassword", "p5");
        payload.put("confirmPassword", "p6");
        payload.put("secret", "s1");
        payload.put("token", "t1");
        payload.put("accessToken", "t2");
        payload.put("refreshToken", "t3");
        payload.put("AUTHORIZATION", "Bearer xyz");
        payload.put("nested", nested);

        targetService.loginWithoutExclude("alice", payload);

        OperateLogModel model = capturedModel.get();
        assertThat(model).isNotNull();
        String params = model.getRequestParams();
        assertThat(params).contains("\"username\":\"alice\"");
        assertThat(params).contains("\"remark\":\"safe-note\"");
        for (String expectedMaskedKey : List.of(
                "password", "passwd", "pwd", "oldPassword", "newPassword",
                "confirmPassword", "secret", "token", "accessToken", "refreshToken", "AUTHORIZATION"
        )) {
            assertThat(params).contains("\"" + expectedMaskedKey + "\":\"[EXCLUDED]\"");
        }
        assertThat(params).doesNotContain("nested-access-token");
        assertThat(params).doesNotContain("Bearer xyz");
    }

    @Test
    @DisplayName("全局 defaultExcludeParams 与注解 excludeParams 叠加生效，且支持自定义覆盖与 null 安全兜底")
    void shouldMergeAnnotationExcludesAndAllowCustomDefaultOverride() {
        Map<String, Object> mergedPayload = new LinkedHashMap<>();
        mergedPayload.put("password", "raw-pass");
        mergedPayload.put("customPin", "9527");
        mergedPayload.put("region", "cn-hz");

        targetService.updateSecurity(mergedPayload);
        String mergedParams = capturedModel.get().getRequestParams();
        assertThat(mergedParams).contains("\"password\":\"[EXCLUDED]\"");
        assertThat(mergedParams).contains("\"customPin\":\"[EXCLUDED]\"");
        assertThat(mergedParams).contains("\"region\":\"cn-hz\"");

        // 自定义覆盖默认列表：仅屏蔽 apiKey（与注解 customPin 叠加），未纳入的 password 不再被默认基线屏蔽
        WebProperties customProps = new WebProperties();
        customProps.getOperateLog().setDefaultExcludeParams(List.of("apiKey"));
        OperateLogAspect customAspect = new OperateLogAspect(
                capturedModel::set, null, JsonUtil.getObjectMapper(), null, customProps);
        AspectJProxyFactory customProxy = new AspectJProxyFactory(new OperateLogServiceImpl());
        customProxy.setProxyTargetClass(true);
        customProxy.addAspect(customAspect);
        OperateLogService customService = customProxy.getProxy();

        Map<String, Object> overridePayload = new LinkedHashMap<>();
        overridePayload.put("apiKey", "ak-secret");
        overridePayload.put("customPin", "8848");
        overridePayload.put("password", "kept-for-override-check");
        customService.updateSecurity(overridePayload);

        String overrideParams = capturedModel.get().getRequestParams();
        assertThat(overrideParams).contains("\"apiKey\":\"[EXCLUDED]\"");
        assertThat(overrideParams).contains("\"customPin\":\"[EXCLUDED]\"");
        assertThat(overrideParams).contains("\"password\":\"kept-for-override-check\"");

        // null 防御不抛空指针
        customProps.getOperateLog().setDefaultExcludeParams(null);
        customService.loginWithoutExclude("bob", Map.of("plain", "ok"));
        assertThat(capturedModel.get().getRequestParams()).contains("\"plain\":\"ok\"");
    }

    @Test
    @DisplayName("DTO/集合/Object[] 数组与循环引用安全防御：默认基线穿透脱敏且环引用或异常回退绝不泄漏明文")
    void shouldMaskDtoCollectionArrayAndGuardCircularReferences() {
        AuthPayloadDTO dto = new AuthPayloadDTO("u1", "at-dto-secret", "old-pass-dto");
        AuthPayloadDTO itemDto = new AuthPayloadDTO("u2", "at-list-secret", "old-pass-list");
        Map<String, Object> extraMap = Map.of("refreshToken", "rt-array-secret", "tag", "ok");

        targetService.submitDtoAndArray(dto, List.of(itemDto), new Object[]{extraMap});

        String params = capturedModel.get().getRequestParams();
        assertThat(params).contains("\"account\":\"u1\"", "\"account\":\"u2\"", "\"tag\":\"ok\"");
        assertThat(params).doesNotContain("at-dto-secret", "old-pass-dto", "at-list-secret", "old-pass-list", "rt-array-secret");
        assertThat(params).contains("\"accessToken\":\"[EXCLUDED]\"", "\"oldPassword\":\"[EXCLUDED]\"", "\"refreshToken\":\"[EXCLUDED]\"");

        // 自引用对象环检测与异常回退：不抛 StackOverflowError，且绝不通过 raw toString() 泄露 token 明文
        CircularTokenNode cycle = new CircularTokenNode();
        cycle.self = cycle;
        targetService.circularArg(cycle);

        String cycleParams = capturedModel.get().getRequestParams();
        assertThat(cycleParams).isNotBlank();
        assertThat(cycleParams).doesNotContain("secret-cycle-token");
    }
}

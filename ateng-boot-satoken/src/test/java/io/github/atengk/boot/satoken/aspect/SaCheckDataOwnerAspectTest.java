package io.github.atengk.boot.satoken.aspect;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.satoken.annotation.CheckDataOwner;
import io.github.atengk.boot.satoken.constant.SaTokenConstant;
import io.github.atengk.boot.satoken.model.LoginUser;
import io.github.atengk.boot.satoken.spi.DataOwnerProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.context.ApplicationContext;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * SaCheckDataOwnerAspect 水平越权防御切面单元测试
 * 验证 SpEL 入参提取、DataOwnerProvider SPI 间接反查、管理员特权豁免与 403 阻断
 *
 * @author Ateng
 * @since 2026-10-20
 */
@DisplayName("SaCheckDataOwnerAspect 水平越权防御切面单元测试")
class SaCheckDataOwnerAspectTest {

    public record UserProfileDTO(String userId, String nickName) {}

    public interface TestOrderService {
        String updateProfile(UserProfileDTO dto);
        String cancelOrder(Long orderId);
        String strictUpdate(String userId);
    }

    public static class OrderDataOwnerProvider implements DataOwnerProvider<Long> {
        private static final Map<Long, String> ORDER_DB = Map.of(
                1001L, "user-100",
                1002L, "user-200"
        );

        @Override
        public String getOwnerId(Long resourceId) {
            return ORDER_DB.get(resourceId);
        }
    }

    public static class TestOrderServiceImpl implements TestOrderService {

        @Override
        @CheckDataOwner(expr = "#dto.userId")
        public String updateProfile(UserProfileDTO dto) {
            return "SUCCESS_UPDATE_" + dto.userId();
        }

        @Override
        @CheckDataOwner(expr = "#orderId", provider = OrderDataOwnerProvider.class, message = "无权操作该订单")
        public String cancelOrder(Long orderId) {
            return "SUCCESS_CANCEL_" + orderId;
        }

        @Override
        @CheckDataOwner(expr = "#userId", allowAdmin = false)
        public String strictUpdate(String userId) {
            return "SUCCESS_STRICT_" + userId;
        }
    }

    @Test
    @DisplayName("1. 本人访问直接入参：校验通过并成功执行")
    void testDirectOwnerMatchPass() {
        try (MockedStatic<StpUtil> stpUtilMock = mockStatic(StpUtil.class)) {
            stpUtilMock.when(StpUtil::isLogin).thenReturn(true);
            stpUtilMock.when(() -> StpUtil.getSession(false)).thenReturn(null);
            stpUtilMock.when(StpUtil::getLoginIdDefaultNull).thenReturn("user-100");

            TestOrderServiceImpl target = new TestOrderServiceImpl();
            AspectJProxyFactory factory = new AspectJProxyFactory(target);
            factory.addAspect(new SaCheckDataOwnerAspect(mock(ApplicationContext.class)));
            TestOrderService proxy = factory.getProxy();

            String result = proxy.updateProfile(new UserProfileDTO("user-100", "张三"));
            assertThat(result).isEqualTo("SUCCESS_UPDATE_user-100");
        }
    }

    @Test
    @DisplayName("2. 越权访问直接入参：拦截并抛出 BusinessException 403")
    void testDirectOwnerMismatchForbidden() {
        try (MockedStatic<StpUtil> stpUtilMock = mockStatic(StpUtil.class)) {
            stpUtilMock.when(StpUtil::isLogin).thenReturn(true);
            stpUtilMock.when(() -> StpUtil.getSession(false)).thenReturn(null);
            stpUtilMock.when(StpUtil::getLoginIdDefaultNull).thenReturn("user-100");

            TestOrderServiceImpl target = new TestOrderServiceImpl();
            AspectJProxyFactory factory = new AspectJProxyFactory(target);
            factory.addAspect(new SaCheckDataOwnerAspect(mock(ApplicationContext.class)));
            TestOrderService proxy = factory.getProxy();

            assertThatThrownBy(() -> proxy.updateProfile(new UserProfileDTO("user-200", "李四")))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException be = (BusinessException) ex;
                        assertThat(be.getCode()).isEqualTo(CommonResultCode.FORBIDDEN.getCode());
                        assertThat(be.getMessage()).isEqualTo("无权访问该资源");
                    });
        }
    }

    @Test
    @DisplayName("3. 间接资源反查 SPI：订单所有者为本人时放行，为他人时拦截并展示定制 message")
    void testIndirectResourceProvider() {
        try (MockedStatic<StpUtil> stpUtilMock = mockStatic(StpUtil.class)) {
            stpUtilMock.when(StpUtil::isLogin).thenReturn(true);
            stpUtilMock.when(() -> StpUtil.getSession(false)).thenReturn(null);
            stpUtilMock.when(StpUtil::getLoginIdDefaultNull).thenReturn("user-100");

            ApplicationContext applicationContext = mock(ApplicationContext.class);
            when(applicationContext.getBean(OrderDataOwnerProvider.class)).thenReturn(new OrderDataOwnerProvider());

            TestOrderServiceImpl target = new TestOrderServiceImpl();
            AspectJProxyFactory factory = new AspectJProxyFactory(target);
            factory.addAspect(new SaCheckDataOwnerAspect(applicationContext));
            TestOrderService proxy = factory.getProxy();

            // 订单 1001 属于 user-100，放行
            String result = proxy.cancelOrder(1001L);
            assertThat(result).isEqualTo("SUCCESS_CANCEL_1001");

            // 订单 1002 属于 user-200，拦截
            assertThatThrownBy(() -> proxy.cancelOrder(1002L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException be = (BusinessException) ex;
                        assertThat(be.getCode()).isEqualTo(CommonResultCode.FORBIDDEN.getCode());
                        assertThat(be.getMessage()).isEqualTo("无权操作该订单");
                    });
        }
    }

    @Test
    @DisplayName("4. 管理员特权：allowAdmin=true 时豁免放行，allowAdmin=false 时不豁免")
    void testAdminPrivilege() {
        try (MockedStatic<StpUtil> stpUtilMock = mockStatic(StpUtil.class)) {
            SaSession mockSession = mock(SaSession.class);
            stpUtilMock.when(StpUtil::isLogin).thenReturn(true);
            stpUtilMock.when(() -> StpUtil.getSession(false)).thenReturn(mockSession);
            stpUtilMock.when(StpUtil::getLoginIdDefaultNull).thenReturn("admin-001");

            LoginUser adminUser = LoginUser.builder()
                    .userId("admin-001")
                    .roles(Set.of("admin"))
                    .build();
            when(mockSession.get(SaTokenConstant.LOGIN_USER_KEY)).thenReturn(JsonUtil.toJsonString(adminUser));

            TestOrderServiceImpl target = new TestOrderServiceImpl();
            AspectJProxyFactory factory = new AspectJProxyFactory(target);
            factory.addAspect(new SaCheckDataOwnerAspect(mock(ApplicationContext.class)));
            TestOrderService proxy = factory.getProxy();

            // allowAdmin=true，管理员操作他人数据正常放行
            String result = proxy.updateProfile(new UserProfileDTO("user-other", "测试"));
            assertThat(result).isEqualTo("SUCCESS_UPDATE_user-other");

            // allowAdmin=false，管理员亦被拦截
            assertThatThrownBy(() -> proxy.strictUpdate("user-other"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException be = (BusinessException) ex;
                        assertThat(be.getCode()).isEqualTo(CommonResultCode.FORBIDDEN.getCode());
                    });
        }
    }
}

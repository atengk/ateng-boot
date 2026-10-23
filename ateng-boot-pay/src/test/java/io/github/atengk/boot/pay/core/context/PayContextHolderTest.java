package io.github.atengk.boot.pay.core.context;

import io.github.atengk.boot.core.context.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 支付商户上下文持有者单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
class PayContextHolderTest {

    @AfterEach
    void tearDown() {
        PayContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("直接商户设置与获取测试")
    void testDirectMerchantSetting() {
        assertNull(PayContextHolder.getDirectMerchantId());
        assertNull(PayContextHolder.getMerchantId());

        PayContextHolder.setMerchantId("mch_1001");
        assertEquals("mch_1001", PayContextHolder.getDirectMerchantId());
        assertEquals("mch_1001", PayContextHolder.getMerchantId());

        PayContextHolder.clear();
        assertNull(PayContextHolder.getDirectMerchantId());
        assertNull(PayContextHolder.getMerchantId());
    }

    @Test
    @DisplayName("多租户级联无感回退测试")
    void testTenantContextFallback() {
        // 1. 当 PayContext 未设置时，自动回退到 Core 的 TenantContextHolder
        TenantContextHolder.setTenantId("tenant_saas_001");
        assertNull(PayContextHolder.getDirectMerchantId(), "Direct 不应有值");
        assertEquals("tenant_saas_001", PayContextHolder.getMerchantId(), "应自动回退到租户上下文");

        // 2. 当 PayContext 显式设置时，覆盖租户上下文
        PayContextHolder.setMerchantId("mch_explicit_override");
        assertEquals("mch_explicit_override", PayContextHolder.getDirectMerchantId());
        assertEquals("mch_explicit_override", PayContextHolder.getMerchantId(), "显式商户号应优先于租户上下文");

        // 3. 清理 PayContext 后，应再次自动回退到租户上下文
        PayContextHolder.clear();
        assertEquals("tenant_saas_001", PayContextHolder.getMerchantId());
    }

    @Test
    @DisplayName("受控闭包 runWithMerchant 嵌套执行与现场还原")
    void testRunWithMerchantNested() {
        PayContextHolder.setMerchantId("outer_mch");
        assertEquals("outer_mch", PayContextHolder.getMerchantId());

        PayContextHolder.runWithMerchant("inner_mch_1", () -> {
            assertEquals("inner_mch_1", PayContextHolder.getMerchantId());

            String result = PayContextHolder.runWithMerchant("inner_mch_2", () -> {
                assertEquals("inner_mch_2", PayContextHolder.getMerchantId());
                return "SUCCESS_2";
            });

            assertEquals("SUCCESS_2", result);
            assertEquals("inner_mch_1", PayContextHolder.getMerchantId(), "内层2退出后应还原为内层1");
        });

        assertEquals("outer_mch", PayContextHolder.getMerchantId(), "内层全部退出后应还原为外层商户");
    }

    @Test
    @DisplayName("跨线程 ThreadLocal 上下文隔离测试")
    void testThreadLocalIsolation() throws Exception {
        PayContextHolder.setMerchantId("main_thread_mch");

        AtomicReference<String> asyncMerchant = new AtomicReference<>();
        Thread asyncThread = new Thread(() -> {
            asyncMerchant.set(PayContextHolder.getMerchantId());
            PayContextHolder.setMerchantId("async_thread_mch");
        });

        asyncThread.start();
        asyncThread.join();

        assertNull(asyncMerchant.get(), "子线程不应读取到父线程未透传的上下文");
        assertEquals("main_thread_mch", PayContextHolder.getMerchantId(), "主线程上下文不应受子线程篡改");
    }

    @Test
    @DisplayName("AppId 设置与兜底解析测试")
    void testAppIdResolution() {
        assertNull(PayContextHolder.getDirectAppId());
        assertNull(PayContextHolder.getAppId());

        // 当仅设置 MerchantId 时，AppId 默认回退为 MerchantId
        PayContextHolder.setMerchantId("mch_app_fallback");
        assertEquals("mch_app_fallback", PayContextHolder.getAppId());
        assertNull(PayContextHolder.getDirectAppId());

        // 当显式设置 AppId 时，优先返回 AppId
        PayContextHolder.setAppId("wx1234567890");
        assertEquals("wx1234567890", PayContextHolder.getDirectAppId());
        assertEquals("wx1234567890", PayContextHolder.getAppId());
    }
}

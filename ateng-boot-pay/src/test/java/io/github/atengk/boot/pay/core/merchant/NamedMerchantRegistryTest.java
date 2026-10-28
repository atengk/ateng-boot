package io.github.atengk.boot.pay.core.merchant;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.pay.config.properties.*;
import io.github.atengk.boot.pay.core.context.PayContextHolder;
import io.github.atengk.boot.pay.exception.PayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 命名多商户与多应用注册表单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
class NamedMerchantRegistryTest {

    private PayProperties payProperties;
    private NamedMerchantRegistry registry;

    @BeforeEach
    void setUp() {
        payProperties = new PayProperties();

        // 1. 配置微信默认商户与命名商户
        WechatPayProperties wechat = payProperties.getWechat();
        wechat.setDefaultMerchant("default");

        Map<String, WechatMerchantProperties> wechatMap = new HashMap<>();
        WechatMerchantProperties defaultMch = new WechatMerchantProperties();
        defaultMch.setMchId("1900000001");
        defaultMch.setAppId("wx_default_app");
        wechatMap.put("default", defaultMch);

        WechatMerchantProperties tenant1Mch = new WechatMerchantProperties();
        tenant1Mch.setMchId("1900000002");
        tenant1Mch.setAppId("wx_tenant1_app");
        // 服务商特约子商户属性
        tenant1Mch.setSpMchId("1900000000");
        tenant1Mch.setSubMchId("1900000002");
        wechatMap.put("tenant_1", tenant1Mch);
        wechat.setMerchants(wechatMap);

        // 2. 配置支付宝默认应用与命名应用
        AlipayPayProperties alipay = payProperties.getAlipay();
        alipay.setDefaultApp("default");

        Map<String, AlipayAppProperties> alipayMap = new HashMap<>();
        AlipayAppProperties defaultApp = new AlipayAppProperties();
        defaultApp.setAppId("2021000000000001");
        defaultApp.setPrivateKey("mock-private-key-1");
        alipayMap.put("default", defaultApp);

        AlipayAppProperties tenant1App = new AlipayAppProperties();
        tenant1App.setAppId("2021000000000002");
        tenant1App.setPrivateKey("mock-private-key-2");
        tenant1App.setAppAuthToken("auth-token-tenant-1");
        alipayMap.put("tenant_1", tenant1App);
        alipay.setApps(alipayMap);

        registry = new NamedMerchantRegistry(payProperties);
    }

    @AfterEach
    void tearDown() {
        PayContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("微信商户三级级联解析策略测试")
    void testWechatMerchantResolutionCascade() {
        // 1. 显式参数优先解析
        WechatMerchantProperties explicit = registry.resolveWechatMerchant("tenant_1");
        assertEquals("1900000002", explicit.getMchId());
        assertEquals("1900000000", explicit.getSpMchId(), "服务商 spMchId 应正确保留");
        assertEquals("1900000002", explicit.getSubMchId(), "特约商户 subMchId 应正确保留");

        // 也可以通过 mchId 直接解析
        WechatMerchantProperties byMchId = registry.resolveWechatMerchant("1900000002");
        assertEquals("1900000002", byMchId.getMchId());

        // 2. 线程上下文覆盖解析
        PayContextHolder.setMerchantId("tenant_1");
        WechatMerchantProperties viaContext = registry.resolveWechatMerchant(null);
        assertEquals("1900000002", viaContext.getMchId());

        // 3. 租户上下文多租户级联无感回退解析
        PayContextHolder.clear();
        TenantContextHolder.setTenantId("tenant_1");
        WechatMerchantProperties viaTenant = registry.resolveWechatMerchant(null);
        assertEquals("1900000002", viaTenant.getMchId());

        // 4. 上下文未匹配时自动兜底默认商户
        TenantContextHolder.clear();
        WechatMerchantProperties viaDefault = registry.resolveWechatMerchant(null);
        assertEquals("1900000001", viaDefault.getMchId());

        // 5. 显式指定不存在的商户快速失败
        assertThrows(PayException.class, () -> registry.resolveWechatMerchant("non_existing_mch"));
    }

    @Test
    @DisplayName("支付宝应用三级级联解析与 ISV 模式属性测试")
    void testAlipayAppResolutionCascade() {
        // 1. 显式参数优先解析
        AlipayAppProperties app = registry.resolveAlipayApp("tenant_1");
        assertEquals("2021000000000002", app.getAppId());
        assertEquals("auth-token-tenant-1", app.getAppAuthToken(), "ISV 特约商户 appAuthToken 应正确存储");

        // 2. 线程上下文解析
        PayContextHolder.setAppId("tenant_1");
        AlipayAppProperties viaContext = registry.resolveAlipayApp(null);
        assertEquals("2021000000000002", viaContext.getAppId());

        // 3. 兜底默认应用
        PayContextHolder.clear();
        AlipayAppProperties viaDefault = registry.resolveAlipayApp(null);
        assertEquals("2021000000000001", viaDefault.getAppId());

        // 4. 显式指定不存在的应用快速失败
        assertThrows(PayException.class, () -> registry.resolveAlipayApp("non_existing_app"));
    }

    @Test
    @DisplayName("运行态动态注册商户与应用")
    void testDynamicRegistration() {
        assertFalse(registry.hasWechatMerchant("dynamic_mch"));
        assertNull(registry.getWechatMerchant("dynamic_mch"));

        WechatMerchantProperties dynamicMch = new WechatMerchantProperties();
        dynamicMch.setMchId("1900000099");
        dynamicMch.setAppId("wx_dynamic_app");
        registry.registerWechatMerchant("dynamic_mch", dynamicMch);

        assertTrue(registry.hasWechatMerchant("dynamic_mch"));
        assertEquals("1900000099", registry.resolveWechatMerchant("dynamic_mch").getMchId());

        // 动态注册支付宝应用
        assertFalse(registry.hasAlipayApp("dynamic_app"));
        AlipayAppProperties dynamicApp = new AlipayAppProperties();
        dynamicApp.setAppId("2021000000000099");
        registry.registerAlipayApp("dynamic_app", dynamicApp);

        assertTrue(registry.hasAlipayApp("dynamic_app"));
        assertEquals("2021000000000099", registry.resolveAlipayApp("dynamic_app").getAppId());
    }

    @Test
    @DisplayName("无默认配置且上下文未匹配时的异常防御")
    void testNoDefaultMerchantException() {
        NamedMerchantRegistry emptyRegistry = new NamedMerchantRegistry(new PayProperties());
        assertThrows(PayException.class, () -> emptyRegistry.resolveWechatMerchant(null));
        assertThrows(PayException.class, () -> emptyRegistry.resolveAlipayApp(null));
    }
}

package io.github.atengk.boot.pay.core.merchant;

import io.github.atengk.boot.pay.config.properties.AlipayAppProperties;
import io.github.atengk.boot.pay.config.properties.PayProperties;
import io.github.atengk.boot.pay.config.properties.WechatMerchantProperties;
import io.github.atengk.boot.pay.core.context.PayContextHolder;
import io.github.atengk.boot.pay.enums.PayResultCode;
import io.github.atengk.boot.pay.exception.PayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 命名多商户与多应用注册表
 * <p>
 * 负责管理默认商户与命名多商户/多应用配置，原生支持直连商户与服务商/ISV 特约子商户模式，
 * 提供三级级联解析策略（方法参数 -> 线程上下文 {@link PayContextHolder} -> 默认配置），
 * 并提供运行态动态注册能力。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class NamedMerchantRegistry {

    private static final Logger log = LoggerFactory.getLogger(NamedMerchantRegistry.class);
    public static final String DEFAULT_KEY = "default";

    private final PayProperties payProperties;

    /**
     * 微信商户映射表：标识符/商户号 -> WechatMerchantProperties
     */
    private final ConcurrentHashMap<String, WechatMerchantProperties> wechatMerchantMap = new ConcurrentHashMap<>();

    /**
     * 支付宝应用映射表：标识符/AppId -> AlipayAppProperties
     */
    private final ConcurrentHashMap<String, AlipayAppProperties> alipayAppMap = new ConcurrentHashMap<>();

    private volatile WechatMerchantProperties defaultWechatMerchant;
    private volatile AlipayAppProperties defaultAlipayApp;

    public NamedMerchantRegistry(PayProperties payProperties) {
        this.payProperties = Objects.requireNonNull(payProperties, "PayProperties 不能为空");
        initFromProperties();
    }

    /**
     * 从配置属性初始化多商户映射
     */
    private void initFromProperties() {
        // 1. 初始化微信商户
        if (payProperties.getWechat() != null) {
            String defaultKey = payProperties.getWechat().getDefaultMerchant();
            if (defaultKey == null || defaultKey.isBlank()) {
                defaultKey = DEFAULT_KEY;
            }
            Map<String, WechatMerchantProperties> namedMerchants = payProperties.getWechat().getMerchants();
            if (namedMerchants != null && !namedMerchants.isEmpty()) {
                namedMerchants.forEach((key, merchant) -> {
                    registerWechatMerchant(key, merchant);
                    if (merchant.getMchId() != null && !merchant.getMchId().isBlank()) {
                        registerWechatMerchant(merchant.getMchId(), merchant);
                    }
                });
                this.defaultWechatMerchant = wechatMerchantMap.get(defaultKey);
                if (this.defaultWechatMerchant == null) {
                    this.defaultWechatMerchant = namedMerchants.values().iterator().next();
                }
            }
        }

        // 2. 初始化支付宝应用
        if (payProperties.getAlipay() != null) {
            String defaultKey = payProperties.getAlipay().getDefaultApp();
            if (defaultKey == null || defaultKey.isBlank()) {
                defaultKey = DEFAULT_KEY;
            }
            Map<String, AlipayAppProperties> namedApps = payProperties.getAlipay().getApps();
            if (namedApps != null && !namedApps.isEmpty()) {
                namedApps.forEach((key, app) -> {
                    registerAlipayApp(key, app);
                    if (app.getAppId() != null && !app.getAppId().isBlank()) {
                        registerAlipayApp(app.getAppId(), app);
                    }
                });
                this.defaultAlipayApp = alipayAppMap.get(defaultKey);
                if (this.defaultAlipayApp == null) {
                    this.defaultAlipayApp = namedApps.values().iterator().next();
                }
            }
        }

        log.info("Initialized NamedMerchantRegistry with {} WeChat merchants and {} Alipay apps",
                wechatMerchantMap.size(), alipayAppMap.size());
    }

    /**
     * 动态注册或更新微信商户配置
     *
     * @param key        商户配置键或商户号
     * @param properties 商户配置属性
     */
    public void registerWechatMerchant(String key, WechatMerchantProperties properties) {
        if (key == null || key.isBlank() || properties == null) {
            return;
        }
        wechatMerchantMap.put(key, properties);
        if (DEFAULT_KEY.equalsIgnoreCase(key) || defaultWechatMerchant == null) {
            this.defaultWechatMerchant = properties;
        }
    }

    /**
     * 动态注册或更新支付宝应用配置
     *
     * @param key        应用配置键或 AppId
     * @param properties 应用配置属性
     */
    public void registerAlipayApp(String key, AlipayAppProperties properties) {
        if (key == null || key.isBlank() || properties == null) {
            return;
        }
        alipayAppMap.put(key, properties);
        if (DEFAULT_KEY.equalsIgnoreCase(key) || defaultAlipayApp == null) {
            this.defaultAlipayApp = properties;
        }
    }

    /**
     * 获取指定配置键对应的微信商户配置
     *
     * @param key 商户键或商户号
     * @return 商户配置属性，若未配置返回 null
     */
    public WechatMerchantProperties getWechatMerchant(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return wechatMerchantMap.get(key);
    }

    /**
     * 获取指定配置键对应的支付宝应用配置
     *
     * @param key 应用键或 AppId
     * @return 应用配置属性，若未配置返回 null
     */
    public AlipayAppProperties getAlipayApp(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return alipayAppMap.get(key);
    }

    /**
     * 三级级联解析当前生效的微信商户配置
     * <ol>
     *     <li>方法参数显式传入的 explicitMerchantIdOrKey（最高优先级）；</li>
     *     <li>线程级上下文 {@link PayContextHolder#getMerchantId()}（包含租户级级联回退）；</li>
     *     <li>默认微信商户配置。</li>
     * </ol>
     *
     * @param explicitMerchantIdOrKey 显式商户标识（可为 null/空串）
     * @return 解析出的可用商户配置
     * @throws PayException 当所有级联均未匹配到任何有效商户时抛出
     */
    public WechatMerchantProperties resolveWechatMerchant(String explicitMerchantIdOrKey) {
        // 1. 显式参数优先
        if (explicitMerchantIdOrKey != null && !explicitMerchantIdOrKey.isBlank()) {
            WechatMerchantProperties merchant = wechatMerchantMap.get(explicitMerchantIdOrKey);
            if (merchant != null) {
                return merchant;
            }
            if (defaultWechatMerchant != null && explicitMerchantIdOrKey.equals(defaultWechatMerchant.getMchId())) {
                return defaultWechatMerchant;
            }
            throw new PayException(PayResultCode.PAY_MERCHANT_NOT_CONFIGURED,
                    "未配置指定微信商户: [" + explicitMerchantIdOrKey + "]");
        }

        // 2. 线程上下文或租户上下文匹配
        String contextMerchantId = PayContextHolder.getMerchantId();
        if (contextMerchantId != null && !contextMerchantId.isBlank()) {
            WechatMerchantProperties merchant = wechatMerchantMap.get(contextMerchantId);
            if (merchant != null) {
                return merchant;
            }
        }

        // 3. 兜底回退到默认微信商户
        if (defaultWechatMerchant != null) {
            return defaultWechatMerchant;
        }

        throw new PayException(PayResultCode.PAY_MERCHANT_NOT_CONFIGURED,
                "未找到有效微信商户配置，请检查 ateng.boot.pay.wechat.merchants 或上下文设置");
    }

    /**
     * 三级级联解析当前生效的支付宝应用配置
     * <ol>
     *     <li>方法参数显式传入的 explicitAppIdOrKey（最高优先级）；</li>
     *     <li>线程级上下文 {@link PayContextHolder#getAppId()}（包含租户级级联回退）；</li>
     *     <li>默认支付宝应用配置。</li>
     * </ol>
     *
     * @param explicitAppIdOrKey 显式应用标识（可为 null/空串）
     * @return 解析出的可用应用配置
     * @throws PayException 当所有级联均未匹配到任何有效应用时抛出
     */
    public AlipayAppProperties resolveAlipayApp(String explicitAppIdOrKey) {
        // 1. 显式参数优先
        if (explicitAppIdOrKey != null && !explicitAppIdOrKey.isBlank()) {
            AlipayAppProperties app = alipayAppMap.get(explicitAppIdOrKey);
            if (app != null) {
                return app;
            }
            if (defaultAlipayApp != null && explicitAppIdOrKey.equals(defaultAlipayApp.getAppId())) {
                return defaultAlipayApp;
            }
            throw new PayException(PayResultCode.PAY_MERCHANT_NOT_CONFIGURED,
                    "未配置指定支付宝应用: [" + explicitAppIdOrKey + "]");
        }

        // 2. 线程上下文或租户上下文匹配
        String contextAppId = PayContextHolder.getAppId();
        if (contextAppId != null && !contextAppId.isBlank()) {
            AlipayAppProperties app = alipayAppMap.get(contextAppId);
            if (app != null) {
                return app;
            }
        }

        // 3. 兜底回退到默认支付宝应用
        if (defaultAlipayApp != null) {
            return defaultAlipayApp;
        }

        throw new PayException(PayResultCode.PAY_MERCHANT_NOT_CONFIGURED,
                "未找到有效支付宝应用配置，请检查 ateng.boot.pay.alipay.apps 或上下文设置");
    }

    /**
     * 是否存在指定微信商户配置
     */
    public boolean hasWechatMerchant(String key) {
        return key != null && wechatMerchantMap.containsKey(key);
    }

    /**
     * 是否存在指定支付宝应用配置
     */
    public boolean hasAlipayApp(String key) {
        return key != null && alipayAppMap.containsKey(key);
    }

    /**
     * 获取只读微信商户映射表
     */
    public Map<String, WechatMerchantProperties> getWechatMerchantMap() {
        return Collections.unmodifiableMap(wechatMerchantMap);
    }

    /**
     * 获取只读支付宝应用映射表
     */
    public Map<String, AlipayAppProperties> getAlipayAppMap() {
        return Collections.unmodifiableMap(alipayAppMap);
    }

    public WechatMerchantProperties getDefaultWechatMerchant() {
        return defaultWechatMerchant;
    }

    public AlipayAppProperties getDefaultAlipayApp() {
        return defaultAlipayApp;
    }
}

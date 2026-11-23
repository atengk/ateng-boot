package io.github.atengk.boot.web.captcha.properties;

import io.github.atengk.boot.web.captcha.enums.CaptchaCharType;
import io.github.atengk.boot.web.captcha.enums.CaptchaRendererType;
import io.github.atengk.boot.web.captcha.enums.CaptchaType;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;

/**
 * 图形验证码运行时强类型配置属性
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@ConfigurationProperties(prefix = "ateng.boot.web.captcha")
public class CaptchaProperties {

    /**
     * 是否启用图形验证码自动装配（默认 true）
     */
    private boolean enabled = true;

    /**
     * 存储全局 Key 前缀
     */
    private String keyPrefix = "captcha:";

    /**
     * 默认图像渲染引擎类型（默认 SVG 矢量路径，零系统字体依赖）
     */
    private CaptchaRendererType renderer = CaptchaRendererType.SVG;

    /**
     * 默认验证码内容模式（默认 CHAR 随机字符）
     */
    private CaptchaType type = CaptchaType.CHAR;

    /**
     * 随机字符模式字符集策略（默认剔除易混淆字符 0/O/o/1/I/l）
     */
    private CaptchaCharType charType = CaptchaCharType.ALPHANUMERIC_SAFE;

    /**
     * 随机字符模式验证码长度（默认 4 位）
     */
    private int length = 4;

    /**
     * 算术模式支持的运算符集合（默认 +-*，减法保证非负且乘法限于个位数）
     */
    private String mathOperators = "+-*";

    /**
     * 验证码图像宽度（像素，默认 130）
     */
    private int width = 130;

    /**
     * 验证码图像高度（像素，默认 44）
     */
    private int height = 44;

    /**
     * 验证码有效时长（默认 2 分钟）
     */
    private Duration expire = Duration.ofMinutes(2);

    /**
     * 核验时是否忽略大小写（默认 true）
     */
    private boolean ignoreCase = true;

    /**
     * 干扰线数量（默认 4 条）
     */
    private int noiseLineCount = 4;

    /**
     * 干扰噪点数量（默认 30 个）
     */
    private int noiseDotCount = 30;

    /**
     * 底层分布式存储异常时是否降级至单机内存存储（默认 false，Fail-Closed 抛出受管异常防集群状态裂脑）
     */
    private boolean failOpen = false;

    /**
     * 单机内存存储兜底配置
     */
    @NestedConfigurationProperty
    private InMemory inMemory = new InMemory();

    /**
     * 单机内存存储配置参数
     */
    @Data
    public static class InMemory {

        /**
         * 内存最大容量软上限（超过后触发过期扫描与最旧条目淘汰，默认 100,000）
         */
        private int maxCapacity = 100_000;

        /**
         * 后台惰性清理触发周期（默认 60 秒）
         */
        private Duration cleanInterval = Duration.ofSeconds(60);
    }
}

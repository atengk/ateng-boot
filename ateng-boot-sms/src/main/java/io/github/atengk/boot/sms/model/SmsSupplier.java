package io.github.atengk.boot.sms.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.github.atengk.boot.core.enums.IBaseEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 短信服务商标识枚举
 *
 * @author Ateng
 * @since 2026-11-02
 */
@Getter
@AllArgsConstructor
public enum SmsSupplier implements IBaseEnum<String> {

    /**
     * 阿里云短信 (Dysmsapi)
     */
    ALIYUN("aliyun", "阿里云短信", 1000, false),

    /**
     * 腾讯云短信 (SMS 3.0)
     */
    TENCENT("tencent", "腾讯云短信", 200, false),

    /**
     * 华为云消息&短信 (MSGSMS)
     */
    HUAWEI("huawei", "华为云短信", 500, false),

    /**
     * 云片网短信 (Yunpian)
     */
    YUNPIAN("yunpian", "云片网短信", 1000, true),

    /**
     * 天翼云短信 (CTYun)
     */
    CTYUN("ctyun", "天翼云短信", 1000, false),

    /**
     * 百度智能云短信 (BCE SMS v3)
     */
    BAIDU("baidu", "百度智能云短信", 1000, false),

    /**
     * 京东云短信 (JD Cloud)
     */
    JDCLOUD("jdcloud", "京东云短信", 100, false),

    /**
     * 七牛云短信 (Qiniu)
     */
    QINIU("qiniu", "七牛云短信", 1000, false),

    /**
     * 网易云信短信 (Netease)
     */
    NETEASE("netease", "网易云信短信", 100, false),

    /**
     * 容联云短信 (Cloopen)
     */
    CLOOPEN("cloopen", "容联云短信", 200, false),

    /**
     * 创蓝 253 云通讯短信
     */
    CHUANGLAN("chuanglan", "创蓝253短信", 1000, true),

    /**
     * 中国移动云 MAS 短信
     */
    MAS("mas", "中国移动云MAS", 1000, true),

    /**
     * 合一短信 (UniSMS)
     */
    UNISMS("unisms", "合一短信", 1000, false),

    /**
     * 助通科技短信 (Zhutong)
     */
    ZHUTONG("zhutong", "助通科技短信", 1000, true),

    /**
     * 本地测试内存沙箱
     */
    MOCK("mock", "本地Mock沙箱", 1000, true),

    /**
     * 自定义扩展厂商
     */
    CUSTOM("custom", "自定义厂商", 1000, false);

    @JsonValue
    private final String code;

    private final String label;

    /**
     * 单次 API 调用支持的最大手机号切片上限
     */
    private final int batchLimit;

    /**
     * 是否原生支持无模板纯文本正文直发
     */
    private final boolean plainTextSupported;

    /**
     * 判断当前厂商协议是否原生支持无模板纯文本正文直发
     *
     * @return true 支持纯文本直发，false 仅支持模板短信
     */
    public boolean supportsPlainText() {
        return plainTextSupported;
    }

    /**
     * 根据编码安全解析短信服务商枚举（不区分大小写）
     *
     * @param code 服务商编码
     * @return 匹配的枚举对象，无匹配时返回 null
     */
    @JsonCreator
    public static SmsSupplier fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return Arrays.stream(values())
                .filter(item -> item.code.equalsIgnoreCase(code.trim()) || item.name().equalsIgnoreCase(code.trim()))
                .findFirst()
                .orElse(null);
    }
}

package io.github.atengk.boot.web.sign.spi;

/**
 * API 签名与验签算法 SPI 契约接口
 *
 * @author Ateng
 * @since 2026-10-16
 */
public interface ApiSigner {

    /**
     * 对规范化数据待签串生成数字签名
     *
     * @param data               待签名规范化字符串
     * @param privateOrSecretKey 私钥（非对称算法）或对称密钥
     * @return 签名字符串
     */
    String sign(String data, String privateOrSecretKey);

    /**
     * 校验数字签名合法性
     *
     * @param data              原始待签名规范化字符串
     * @param signature         待校验的签名字符串
     * @param publicOrSecretKey 公钥（非对称算法）或对称密钥
     * @return true 签名有效；false 签名不匹配或非法
     */
    boolean verify(String data, String signature, String publicOrSecretKey);
}

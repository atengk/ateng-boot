package io.github.atengk.boot.sample.web.controller;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.DigestUtil;
import io.github.atengk.boot.sample.web.dto.AnnouncementPublishDTO;
import io.github.atengk.boot.sample.web.dto.ArticlePublishDTO;
import io.github.atengk.boot.sample.web.dto.OrderSignDTO;
import io.github.atengk.boot.sample.web.dto.PrepareSignHeadersDTO;
import io.github.atengk.boot.web.ip.IpAccessMode;
import io.github.atengk.boot.web.ip.annotation.IpAccessControl;
import io.github.atengk.boot.web.properties.WebProperties;
import io.github.atengk.boot.web.sign.annotation.ApiSign;
import io.github.atengk.boot.web.sign.aspect.ApiSignAspect;
import io.github.atengk.boot.web.sign.enums.SignAlgorithm;
import io.github.atengk.boot.web.sign.provider.DefaultSignKeyProvider;
import io.github.atengk.boot.web.sign.signer.HmacSha256Signer;
import io.github.atengk.boot.web.sign.signer.RsaSigner;
import io.github.atengk.boot.web.sign.signer.Sm2Signer;
import io.github.atengk.boot.web.sign.spi.ApiSigner;
import io.github.atengk.boot.web.sign.spi.SignKeyProvider;
import io.github.atengk.boot.web.sign.util.SignCanonicalUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 传输安全防护网、双向加签与边界防火墙场景控制器
 * 覆盖 API 双向加签验签与防重放、XSS 跨站脚本双轨无害化清洗与富文本豁免、IP/CIDR/IPv6 访问控制防火墙、
 * 等保三级安全响应头与 Server 头剥离、ETag 304 协商缓存及动态 CORS 跨域凭证反射
 *
 * @author Ateng
 * @since 2026-11-08
 */
@RestController
@RequestMapping("/sample/web/security")
public class WebSecurityAndSignController {

    private final ObjectProvider<ApiSigner> apiSignerProvider;
    private final ObjectProvider<WebProperties> webPropertiesProvider;
    private final ObjectProvider<SignKeyProvider> signKeyProviderProvider;

    public WebSecurityAndSignController(ObjectProvider<ApiSigner> apiSignerProvider,
                                        ObjectProvider<WebProperties> webPropertiesProvider,
                                        ObjectProvider<SignKeyProvider> signKeyProviderProvider) {
        this.apiSignerProvider = apiSignerProvider;
        this.webPropertiesProvider = webPropertiesProvider;
        this.signKeyProviderProvider = signKeyProviderProvider;
    }

    // =========================================================================
    // 1. API 签名与验签 (HMAC-SHA256 / RSA / 国密 SM2/SM3)
    // =========================================================================

    /**
     * 开放平台商户提单双向加验签：接收 JSON 请求体验签，并对响应结果加签回写响应头
     *
     * @param dto 订单载荷
     * @return 订单确认业务结果
     */
    @PostMapping({"/sign/orders", "/sign/order"})
    @ApiSign(verifyRequest = true, signResponse = true, expireSeconds = 10)
    public Result<Map<String, Object>> createOrder(@Valid @RequestBody OrderSignDTO dto) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderNo", dto.getOrderNo());
        data.put("amount", dto.getAmount());
        data.put("status", "CONFIRMED");
        data.put("message", "商户订单验签通过并建单成功");
        return Result.success(data);
    }

    /**
     * 国密 SM2/SM3 非对称双向加验签：接收 JSON 请求体验签，并对响应结果 SM2 私钥加签
     *
     * @param dto 订单载荷
     * @return 国密签名确认业务结果
     */
    @PostMapping("/sign/sm2")
    @ApiSign(verifyRequest = true, signResponse = true, expireSeconds = 10, algorithm = SignAlgorithm.SM2)
    public Result<Map<String, Object>> createOrderSm2(@Valid @RequestBody OrderSignDTO dto) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderNo", dto.getOrderNo());
        data.put("amount", dto.getAmount());
        data.put("algorithm", "SM2");
        data.put("status", "CONFIRMED");
        data.put("message", "国密SM2/SM3双向加验签成功");
        return Result.success(data);
    }

    /**
     * RSA 非对称双向加验签：接收 JSON 请求体验签，并对响应结果 RSA 私钥加签
     *
     * @param dto 订单载荷
     * @return RSA 签名确认业务结果
     */
    @PostMapping("/sign/rsa")
    @ApiSign(verifyRequest = true, signResponse = true, expireSeconds = 10, algorithm = SignAlgorithm.RSA)
    public Result<Map<String, Object>> createOrderRsa(@Valid @RequestBody OrderSignDTO dto) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderNo", dto.getOrderNo());
        data.put("amount", dto.getAmount());
        data.put("algorithm", "RSA");
        data.put("status", "CONFIRMED");
        data.put("message", "RSA双向加验签成功");
        return Result.success(data);
    }

    /**
     * Query 参数双向加验签：验证 GET 请求 URL 查询参数防篡改并出站加签
     *
     * @param code 商户资质编码
     * @return 商户资质核验结果
     */
    @GetMapping("/sign/query")
    @ApiSign(verifyRequest = true, signResponse = true, expireSeconds = 10)
    public Result<Map<String, Object>> queryData(@RequestParam("code") String code) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("merchantCode", code);
        data.put("verified", true);
        data.put("qualificationStatus", "NORMAL");
        return Result.success(data);
    }

    /**
     * 单向验签：仅校验入站数据签名（如第三方支付通知 Webhook 异步回调场景）
     *
     * @param dto 支付回调载荷
     * @return 支付通知处理回执
     */
    @PostMapping("/sign/inbound")
    @ApiSign(verifyRequest = true, signResponse = false, expireSeconds = 10)
    public Result<Map<String, Object>> inboundOnly(@Valid @RequestBody OrderSignDTO dto) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderNo", dto.getOrderNo());
        data.put("inboundStatus", "RECEIVED");
        data.put("message", "第三方支付通知验签成功");
        return Result.success(data);
    }

    /**
     * 单向加签：入站免签，服务端出站数据强制加签（如对账单或高敏感数据查询）
     *
     * @return 敏感结算对账单载荷
     */
    @GetMapping("/sign/outbound")
    @ApiSign(verifyRequest = false, signResponse = true, expireSeconds = 10)
    public Result<Map<String, Object>> outboundOnly() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reportTitle", "2026年第四季度结算报表");
        data.put("settlementAmount", 88888.88);
        data.put("currency", "CNY");
        data.put("securityLevel", "HIGH");
        return Result.success(data);
    }

    /**
     * 实时签名请求头预生成辅助接口：为接入方与测试客户端动态拼装并计算签名请求头
     *
     * @param dto 预计算入参载荷
     * @return 包含时间戳、随机串、签名值及一键体验 cURL 的响应载荷
     */
    @PostMapping("/sign/prepare-headers")
    public Result<Map<String, Object>> prepareHeaders(@RequestBody(required = false) PrepareSignHeadersDTO dto) {
        if (dto == null) {
            dto = new PrepareSignHeadersDTO();
        }
        String method = dto.getMethod() != null ? dto.getMethod().toUpperCase() : "POST";
        String uri = dto.getUri() != null ? dto.getUri() : "/sample/web/security/sign/orders";
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String bodyContent = dto.getBody() != null ? dto.getBody() : "";
        String bodySha256 = DigestUtil.sha256Hex(bodyContent);
        Map<String, String[]> paramMap = new LinkedHashMap<>();
        if (dto.getQueryParams() != null) {
            dto.getQueryParams().forEach((k, v) -> paramMap.put(k, v != null ? new String[]{v} : new String[0]));
        }

        String canonical = SignCanonicalUtil.buildRequestCanonicalString(
                method, uri, timestamp, nonce, paramMap, bodySha256
        );

        WebProperties webProps = webPropertiesProvider.getIfAvailable();
        WebProperties.SignProperties signProps = (webProps != null) ? webProps.getSign() : null;
        boolean hasExplicitAlgo = dto.getAlgorithm() != null && !dto.getAlgorithm().isBlank();
        SignAlgorithm effectiveAlgo = resolvePrepareAlgorithm(dto.getAlgorithm(), signProps);
        String appId = (dto.getAppId() != null && !dto.getAppId().isBlank())
                ? dto.getAppId()
                : (signProps != null ? signProps.getAppId() : null);
        String secretKey = resolvePrepareKey(effectiveAlgo, appId, dto.getSecretOrPrivateKey(), signProps);

        ApiSigner signer = switch (effectiveAlgo) {
            case SM2 -> new Sm2Signer();
            case RSA -> new RsaSigner();
            case HMAC_SHA256, DEFAULT -> hasExplicitAlgo
                    ? new HmacSha256Signer()
                    : apiSignerProvider.getIfAvailable(HmacSha256Signer::new);
        };

        final String signature;
        try {
            signature = signer.sign(canonical, secretKey);
        } catch (RuntimeException e) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "生成签名失败: " + e.getMessage(), e);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(ApiSignAspect.HEADER_TIMESTAMP, timestamp);
        result.put(ApiSignAspect.HEADER_NONCE, nonce);
        result.put(ApiSignAspect.HEADER_SIGNATURE, signature);
        if (appId != null && !appId.isBlank()) {
            result.put(ApiSignAspect.HEADER_APP_ID, appId);
        }
        result.put("canonicalString", canonical);
        result.put("bodySha256", bodySha256);

        StringBuilder curl = new StringBuilder("curl -X ").append(method)
                .append(" \"http://localhost:8080").append(uri).append("\"")
                .append(" -H \"Content-Type: application/json\"")
                .append(" -H \"").append(ApiSignAspect.HEADER_TIMESTAMP).append(": ").append(timestamp).append("\"")
                .append(" -H \"").append(ApiSignAspect.HEADER_NONCE).append(": ").append(nonce).append("\"")
                .append(" -H \"").append(ApiSignAspect.HEADER_SIGNATURE).append(": ").append(signature).append("\"");
        if (appId != null && !appId.isBlank()) {
            curl.append(" -H \"").append(ApiSignAspect.HEADER_APP_ID).append(": ").append(appId).append("\"");
        }
        if (!bodyContent.isBlank()) {
            curl.append(" -d '").append(bodyContent.replace("'", "'\\''")).append("'");
        }
        result.put("curlSample", curl.toString());

        return Result.success(result);
    }

    private static SignAlgorithm resolvePrepareAlgorithm(String rawAlgorithm, WebProperties.SignProperties signProps) {
        if (rawAlgorithm != null && !rawAlgorithm.isBlank()) {
            return switch (rawAlgorithm.trim().toUpperCase()) {
                case "SM2" -> SignAlgorithm.SM2;
                case "RSA" -> SignAlgorithm.RSA;
                default -> SignAlgorithm.HMAC_SHA256;
            };
        }
        SignAlgorithm globalAlgo = (signProps != null) ? signProps.getAlgorithm() : null;
        return (globalAlgo != null && globalAlgo != SignAlgorithm.DEFAULT) ? globalAlgo : SignAlgorithm.HMAC_SHA256;
    }

    private String resolvePrepareKey(SignAlgorithm algorithm,
                                     String appId,
                                     String explicitKey,
                                     WebProperties.SignProperties signProps) {
        if (explicitKey != null && !explicitKey.isBlank()) {
            return explicitKey;
        }
        SignKeyProvider keyProvider = signKeyProviderProvider.getIfAvailable();
        if (keyProvider == null && signProps != null) {
            keyProvider = new DefaultSignKeyProvider(signProps);
        }
        String resolved = (keyProvider != null) ? keyProvider.getSignKey(appId, algorithm) : null;
        if ((resolved == null || resolved.isBlank())
                && (algorithm == SignAlgorithm.HMAC_SHA256 || algorithm == SignAlgorithm.DEFAULT)
                && signProps != null) {
            resolved = signProps.getPrivateKey();
        }
        if (resolved != null && !resolved.isBlank()) {
            return resolved;
        }
        if (algorithm == SignAlgorithm.SM2 || algorithm == SignAlgorithm.RSA) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "请传入 secretOrPrivateKey 或配置对应算法私钥");
        }
        return "TestSecretKey123";
    }

    // =========================================================================
    // 2. XSS 跨站脚本安全清洗与字段级富文本豁免
    // =========================================================================

    /**
     * 演示混合 DTO 资讯发布：普通字段自动清洗，@XssIgnore 标注的 content 字段安全豁免放行
     *
     * @param dto 文章入参
     * @return 发布结果
     */
    @PostMapping("/xss/article")
    public Result<ArticlePublishDTO> publishArticle(@Valid @RequestBody ArticlePublishDTO dto) {
        return Result.success(dto);
    }

    /**
     * 演示类级别 @XssIgnore 豁免：全站公告实体内所有字符串属性均跳过清洗
     *
     * @param dto 全站公告入参
     * @return 发布结果
     */
    @PostMapping("/xss/announcement")
    public Result<AnnouncementPublishDTO> publishAnnouncement(@Valid @RequestBody AnnouncementPublishDTO dto) {
        return Result.success(dto);
    }

    /**
     * 演示 Query 参数 XSS 清洗（基于 XssHttpServletRequestWrapper）
     *
     * @param keyword 搜索关键词
     * @return 清洗后的关键词
     */
    @GetMapping("/xss/query")
    public Result<String> searchByQuery(@RequestParam String keyword) {
        return Result.success(keyword);
    }

    /**
     * 演示 Form 表单参数 XSS 清洗（基于 XssHttpServletRequestWrapper）
     *
     * @param title   表单标题
     * @param summary 表单摘要
     * @return 表单数据映射
     */
    @PostMapping("/xss/form")
    public Result<Map<String, String>> submitForm(@RequestParam String title, @RequestParam String summary) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("title", title);
        map.put("summary", summary);
        return Result.success(map);
    }

    /**
     * 演示 Header XSS 清洗（基于 XssHttpServletRequestWrapper）
     *
     * @param request HTTP 请求
     * @return 请求头映射
     */
    @GetMapping("/xss/header")
    public Result<Map<String, String>> readHeaders(HttpServletRequest request) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("X-Custom-Title", request.getHeader("X-Custom-Title"));
        map.put("Authorization", request.getHeader("Authorization"));
        return Result.success(map);
    }

    /**
     * 演示白名单 URL 排除：整条路径跳过 XSS 清洗
     *
     * @param dto 文章入参
     * @return 发布结果
     */
    @PostMapping("/xss/excluded/article")
    public Result<ArticlePublishDTO> publishExcludedArticle(@Valid @RequestBody ArticlePublishDTO dto) {
        return Result.success(dto);
    }

    // =========================================================================
    // 3. IP/CIDR/IPv6 访问控制防火墙
    // =========================================================================

    /**
     * 演示方法级白名单控制：仅允许 127.0.0.1 及 192.168.1.0/24 访问
     *
     * @return 访问结果
     */
    @GetMapping("/ip/whitelist")
    @IpAccessControl(mode = IpAccessMode.WHITE_LIST, rules = {"127.0.0.1", "192.168.1.0/24"})
    public Result<String> whitelistEndpoint() {
        return Result.success("白名单放行成功");
    }

    /**
     * 演示方法级黑名单控制：拒绝 10.0.0.0/8 与 203.0.113.100 访问
     *
     * @return 访问结果
     */
    @GetMapping("/ip/blacklist")
    @IpAccessControl(mode = IpAccessMode.BLACK_LIST, rules = {"10.0.0.0/8", "203.0.113.100"})
    public Result<String> blacklistEndpoint() {
        return Result.success("黑名单未命中，放行成功");
    }

    /**
     * 演示 CIDR 子网掩码与 IPv6 网段白名单
     *
     * @return 访问结果
     */
    @GetMapping("/ip/cidr-ipv6")
    @IpAccessControl(mode = IpAccessMode.WHITE_LIST, rules = {"172.16.0.0/12", "2001:db8::/32", "::1"})
    public Result<Map<String, String>> cidrIpv6Endpoint() {
        return Result.success(Map.of("network", "CIDR-IPv6-Allowed"));
    }

    /**
     * 演示自定义拒绝文案（遵循统一 HTTP 200 + Result.fail(403) 并附加 X-Denied-Reason 响应头）
     *
     * @return 访问结果
     */
    @GetMapping("/ip/custom-message")
    @IpAccessControl(
            mode = IpAccessMode.WHITE_LIST,
            rules = {"192.168.1.200"},
            message = "内部核心机房特许通道，当前 IP 无权访问"
    )
    public Result<String> customMessageEndpoint() {
        return Result.success("特许通道放行成功");
    }

    /**
     * 演示物理 403 切断模式（直接返回 HTTP 403 状态码）
     *
     * @return 访问结果
     */
    @GetMapping("/ip/http-403")
    @IpAccessControl(
            mode = IpAccessMode.WHITE_LIST,
            rules = {"192.168.1.200"},
            httpStatus403 = true
    )
    public Result<String> http403Endpoint() {
        return Result.success("特许通道放行成功");
    }

    /**
     * 供 URL 路径级过滤器演示拦截的受保护端点
     *
     * @return 访问结果
     */
    @GetMapping("/ip/filter-admin/dashboard")
    public Result<String> filterProtectedDashboard() {
        return Result.success("管理员特权控制台数据");
    }

    /**
     * 供 URL 路径级过滤器演示排除放行的公开端点
     *
     * @return 访问结果
     */
    @GetMapping("/ip/filter-admin/public-ping")
    public Result<String> filterPublicPing() {
        return Result.success("public-pong");
    }

    // =========================================================================
    // 4. 等保三级安全响应头与防覆盖保护
    // =========================================================================

    /**
     * 业务自定义响应头演示端点，验证 SecurityHeadersFilter 的 containsHeader 防覆盖保护机制
     *
     * @param response HTTP 响应
     * @return 业务响应载荷
     */
    @GetMapping("/headers/custom")
    public Result<String> getCustomSecurityHeaders(HttpServletResponse response) {
        response.setHeader("X-Frame-Options", "DENY");
        return Result.success("自定义安全响应头已设置");
    }

    // =========================================================================
    // 5. ETag 304 协商缓存
    // =========================================================================

    /**
     * 静态字典与配置演示端点，用于配合 ShallowEtagHeaderFilter 验证 ETag 协商缓存与 304 Not Modified
     *
     * @return 静态配置载荷
     */
    @GetMapping("/etag/dict")
    public Result<Map<String, String>> getCachedDict() {
        Result<Map<String, String>> result = Result.success(
                Map.of("status", "ACTIVE", "locale", "zh_CN", "app", "ateng-boot-sample-web")
        );
        result.setTimestamp(1700000000000L);
        return result;
    }

    // =========================================================================
    // 6. 动态 CORS 跨域凭证反射
    // =========================================================================

    /**
     * 动态跨域调用体验端点，配合 CorsFilter 与 DynamicCorsConfigurationSource 验证跨域凭证反射
     *
     * @return 跨域响应载荷
     */
    @GetMapping("/cors/ping")
    public Result<String> testCorsPing() {
        return Result.success("CORS 动态跨域凭证校验成功");
    }
}

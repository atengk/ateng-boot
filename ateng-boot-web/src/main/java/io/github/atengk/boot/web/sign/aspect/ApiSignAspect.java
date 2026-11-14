package io.github.atengk.boot.web.sign.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.DigestUtil;
import io.github.atengk.boot.web.idempotent.store.IdempotentKeyStore;
import io.github.atengk.boot.web.idempotent.store.InMemoryIdempotentKeyStore;
import io.github.atengk.boot.web.properties.WebProperties;
import io.github.atengk.boot.web.sign.annotation.ApiSign;
import io.github.atengk.boot.web.sign.enums.SignAlgorithm;
import io.github.atengk.boot.web.filter.CachedBodyHttpServletRequest;
import io.github.atengk.boot.web.sign.signer.HmacSha256Signer;
import io.github.atengk.boot.web.sign.signer.RsaSigner;
import io.github.atengk.boot.web.sign.signer.Sm2Signer;
import io.github.atengk.boot.web.sign.spi.ApiSigner;
import io.github.atengk.boot.web.sign.spi.SignKeyProvider;
import io.github.atengk.boot.web.sign.util.SignCanonicalUtil;
import io.github.atengk.boot.web.util.ServletUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import io.github.atengk.boot.core.util.IdUtil;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * API 双向数据加签验签与防重放切面
 * 收敛加签验签逻辑，严格遵循 ADR-0002 显式契约，拒绝使用全局 ResponseBodyAdvice
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Slf4j
@Aspect
public class ApiSignAspect implements Ordered, DisposableBean {

    /**
     * 切面优先级：置于防重幂等之后，业务审计切面之前
     */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 400;

    public static final String HEADER_APP_ID = "X-App-Id";
    public static final String HEADER_TIMESTAMP = "X-Timestamp";
    public static final String HEADER_NONCE = "X-Nonce";
    public static final String HEADER_SIGNATURE = "X-Signature";

    private final ApiSigner apiSigner;
    private final Map<SignAlgorithm, ApiSigner> builtInSigners;
    private final SignKeyProvider signKeyProvider;
    private final ObjectProvider<IdempotentKeyStore> idempotentKeyStoreProvider;
    private final ObjectMapper objectMapper;
    private final WebProperties webProperties;
    private final AtomicReference<IdempotentKeyStore> fallbackStoreRef = new AtomicReference<>();

    public ApiSignAspect(ApiSigner apiSigner,
                         SignKeyProvider signKeyProvider,
                         ObjectProvider<IdempotentKeyStore> idempotentKeyStoreProvider,
                         ObjectMapper objectMapper,
                         WebProperties webProperties) {
        this.apiSigner = Objects.requireNonNull(apiSigner, "ApiSigner must not be null");
        this.signKeyProvider = Objects.requireNonNull(signKeyProvider, "SignKeyProvider must not be null");
        this.idempotentKeyStoreProvider = Objects.requireNonNull(idempotentKeyStoreProvider, "IdempotentKeyStoreProvider must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper must not be null");
        this.webProperties = Objects.requireNonNull(webProperties, "WebProperties must not be null");
        this.builtInSigners = createBuiltInSigners(apiSigner);
    }

    private IdempotentKeyStore getFallbackStore() {
        IdempotentKeyStore existing = fallbackStoreRef.get();
        if (existing != null) {
            return existing;
        }
        IdempotentKeyStore created = new InMemoryIdempotentKeyStore();
        if (fallbackStoreRef.compareAndSet(null, created)) {
            return created;
        }
        return fallbackStoreRef.get();
    }

    @Override
    public void destroy() throws Exception {
        IdempotentKeyStore fallbackStore = fallbackStoreRef.getAndSet(null);
        if (fallbackStore instanceof DisposableBean disposable) {
            disposable.destroy();
        }
    }

    private static Map<SignAlgorithm, ApiSigner> createBuiltInSigners(ApiSigner primarySigner) {
        Map<SignAlgorithm, ApiSigner> signers = new EnumMap<>(SignAlgorithm.class);
        signers.put(SignAlgorithm.HMAC_SHA256, primarySigner instanceof HmacSha256Signer ? primarySigner : new HmacSha256Signer());
        signers.put(SignAlgorithm.RSA, primarySigner instanceof RsaSigner ? primarySigner : new RsaSigner());
        signers.put(SignAlgorithm.SM2, primarySigner instanceof Sm2Signer ? primarySigner : new Sm2Signer());
        return signers;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Around("@annotation(apiSign) || @within(apiSign)")
    public Object around(ProceedingJoinPoint joinPoint, ApiSign apiSign) throws Throwable {
        ApiSign effectiveSign = resolveAnnotation(joinPoint, apiSign);
        if (effectiveSign == null) {
            return joinPoint.proceed();
        }

        SignAlgorithm effectiveAlgorithm = resolveEffectiveAlgorithm(effectiveSign);
        ApiSigner effectiveSigner = resolveEffectiveSigner(effectiveSign, effectiveAlgorithm);
        HttpServletRequest request = ServletUtil.getRequest();
        String appId = resolveAppId(request);

        // 1. 入站请求防篡改验签与防重放校验
        if (effectiveSign.verifyRequest() && request != null) {
            verifyInboundRequest(joinPoint, request, effectiveSign, effectiveAlgorithm, effectiveSigner, appId);
        }

        // 2. 执行目标 Controller 业务逻辑（强类型显式返回）
        Object proceedResult = joinPoint.proceed();

        // 3. 出站响应数据加签回写
        if (effectiveSign.signResponse()) {
            signOutboundResponse(request, proceedResult, effectiveAlgorithm, effectiveSigner, appId);
        }

        return proceedResult;
    }

    private void verifyInboundRequest(ProceedingJoinPoint joinPoint,
                                      HttpServletRequest request,
                                      ApiSign apiSign,
                                      SignAlgorithm effectiveAlgorithm,
                                      ApiSigner effectiveSigner,
                                      String appId) {
        String headerTimestamp = getHeaderTimestamp();
        String headerNonce = getHeaderNonce();
        String headerSignature = getHeaderSignature();

        String timestampStr = request.getHeader(headerTimestamp);
        String nonce = request.getHeader(headerNonce);
        String signature = request.getHeader(headerSignature);

        // (1) 必备安全请求头校验
        if (!StringUtils.hasText(timestampStr) || !StringUtils.hasText(nonce) || !StringUtils.hasText(signature)) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST,
                    String.format("缺少签名必要请求头 (%s, %s, %s)", headerTimestamp, headerNonce, headerSignature));
        }

        // (2) 请求时间戳时效性校验
        long clientTime;
        try {
            clientTime = Long.parseLong(timestampStr);
            if (timestampStr.length() <= 10) {
                clientTime *= 1000L;
            }
        } catch (NumberFormatException e) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "非法的时间戳格式: " + timestampStr);
        }

        long now = System.currentTimeMillis();
        long expireSeconds = resolveExpireSeconds(apiSign);
        long expireMillis = expireSeconds * 1000L;
        if (Math.abs(now - clientTime) > expireMillis) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "请求签名已过期");
        }

        // (3) Nonce 防重放原子排重校验（深度复用 IdempotentKeyStore，支持 fail-open 内存降级）
        IdempotentKeyStore keyStore = idempotentKeyStoreProvider.getIfAvailable();
        if (keyStore != null) {
            String nonceKey = resolveNonceKey(appId, nonce);
            boolean acquired;
            try {
                acquired = keyStore.tryLock(nonceKey, expireSeconds, TimeUnit.SECONDS);
            } catch (Exception ex) {
                if (webProperties.getSign().isFailOpen()) {
                    log.warn("ApiSign nonce keyStore execution failed, falling back to in-memory store due to failOpen=true, key: {}", nonceKey, ex);
                    acquired = getFallbackStore().tryLock(nonceKey, expireSeconds, TimeUnit.SECONDS);
                } else if (ex instanceof BusinessException be) {
                    throw be;
                } else {
                    throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "API 防重放存储校验失败: " + ex.getMessage(), ex);
                }
            }
            if (!acquired) {
                log.warn("ApiSign detected repeated nonce [{}] for appId [{}]", nonce, appId);
                throw new BusinessException(CommonResultCode.REPEAT_SUBMIT, "请求已被处理或存在重放风险");
            }
        } else {
            log.warn("ApiSign 未配置 IdempotentKeyStore 实例，已跳过 Nonce 防重放校验: appId=[{}], nonce=[{}]", appId, nonce);
        }

        // (4) 提取并计算请求体摘要
        String bodySha256 = resolveRequestBodySha256(joinPoint, request);

        // (5) 拼装标准化待签串
        String canonicalString = SignCanonicalUtil.buildRequestCanonicalString(
                request.getMethod(),
                request.getRequestURI(),
                timestampStr,
                nonce,
                request.getParameterMap(),
                bodySha256
        );

        // (6) 调用 ApiSigner SPI 执行合法性验证
        String verifyKey = signKeyProvider.getVerifyKey(appId, effectiveAlgorithm);
        if (!StringUtils.hasText(verifyKey)) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "未配置对应应用的验签密钥: " + appId);
        }

        boolean valid = effectiveSigner.verify(canonicalString, signature, verifyKey);
        if (!valid) {
            log.warn("ApiSign verification failed for appId [{}], uri [{}]", appId, request.getRequestURI());
            throw new BusinessException(CommonResultCode.BAD_REQUEST, apiSign.message());
        }
    }

    private void signOutboundResponse(HttpServletRequest request,
                                      Object proceedResult,
                                      SignAlgorithm effectiveAlgorithm,
                                      ApiSigner effectiveSigner,
                                      String appId) {
        HttpServletResponse response = ServletUtil.getResponse();
        if (response == null) {
            return;
        }

        try {
            String headerTimestamp = getHeaderTimestamp();
            String headerNonce = getHeaderNonce();
            String headerSignature = getHeaderSignature();

            String reqTimestamp = request != null ? request.getHeader(headerTimestamp) : null;
            String reqNonce = request != null ? request.getHeader(headerNonce) : null;

            String respTimestamp = StringUtils.hasText(reqTimestamp) ? reqTimestamp : String.valueOf(System.currentTimeMillis());
            String respNonce = StringUtils.hasText(reqNonce) ? reqNonce : IdUtil.fastSimpleUUID();

            String responseJson = (proceedResult != null) ? objectMapper.writeValueAsString(proceedResult) : "";
            String responseBodySha256 = DigestUtil.sha256Hex(responseJson);

            String responseCanonical = SignCanonicalUtil.buildResponseCanonicalString(respTimestamp, respNonce, responseBodySha256);
            String signKey = signKeyProvider.getSignKey(appId, effectiveAlgorithm);
            if (!StringUtils.hasText(signKey)) {
                throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "未配置对应应用的加签密钥: " + appId);
            }

            String responseSignature = effectiveSigner.sign(responseCanonical, signKey);
            response.setHeader(headerSignature, responseSignature);
            response.setHeader(headerTimestamp, respTimestamp);
            response.setHeader(headerNonce, respNonce);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "响应报文加签失败: " + e.getMessage(), e);
        }
    }

    private long resolveExpireSeconds(ApiSign apiSign) {
        if (apiSign != null && apiSign.expireSeconds() > 0) {
            return apiSign.expireSeconds();
        }
        long globalExpire = webProperties.getSign().getExpireSeconds();
        return globalExpire > 0 ? globalExpire : 60L;
    }

    private String getHeaderAppId() {
        String h = webProperties.getSign().getHeaderAppId();
        return StringUtils.hasText(h) ? h : HEADER_APP_ID;
    }

    private String getHeaderTimestamp() {
        String h = webProperties.getSign().getHeaderTimestamp();
        return StringUtils.hasText(h) ? h : HEADER_TIMESTAMP;
    }

    private String getHeaderNonce() {
        String h = webProperties.getSign().getHeaderNonce();
        return StringUtils.hasText(h) ? h : HEADER_NONCE;
    }

    private String getHeaderSignature() {
        String h = webProperties.getSign().getHeaderSignature();
        return StringUtils.hasText(h) ? h : HEADER_SIGNATURE;
    }

    private String resolveRequestBodySha256(ProceedingJoinPoint joinPoint, HttpServletRequest request) {
        if ("GET".equalsIgnoreCase(request.getMethod()) || "DELETE".equalsIgnoreCase(request.getMethod())
                || "HEAD".equalsIgnoreCase(request.getMethod()) || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return DigestUtil.sha256Hex("");
        }

        if (request instanceof CachedBodyHttpServletRequest cached) {
            byte[] cachedBody = cached.getCachedBody();
            if (cachedBody != null && cachedBody.length > 0) {
                return DigestUtil.sha256Hex(cachedBody);
            }
            return DigestUtil.sha256Hex("");
        }

        // 备用兜底：若未经过 Filter 包装且为写请求，查找带有 @RequestBody 的实参
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        java.lang.annotation.Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        Object[] args = joinPoint.getArgs();
        if (args != null && parameterAnnotations != null) {
            for (int i = 0; i < args.length; i++) {
                if (i < parameterAnnotations.length) {
                    for (java.lang.annotation.Annotation ann : parameterAnnotations[i]) {
                        if (ann instanceof org.springframework.web.bind.annotation.RequestBody) {
                            try {
                                return DigestUtil.sha256Hex(objectMapper.writeValueAsBytes(args[i]));
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            }
        }

        return DigestUtil.sha256Hex("");
    }

    private String resolveNonceKey(String appId, String nonce) {
        String prefix = webProperties.getSign().getNoncePrefix();
        if (!StringUtils.hasText(prefix)) {
            prefix = WebProperties.SignProperties.DEFAULT_NONCE_PREFIX;
        }
        return prefix + appId + ":" + nonce;
    }

    private SignAlgorithm resolveEffectiveAlgorithm(ApiSign apiSign) {
        if (hasExplicitAlgorithm(apiSign)) {
            return apiSign.algorithm();
        }
        SignAlgorithm globalAlgorithm = webProperties.getSign().getAlgorithm();
        return (globalAlgorithm != null && globalAlgorithm != SignAlgorithm.DEFAULT)
                ? globalAlgorithm
                : SignAlgorithm.HMAC_SHA256;
    }

    private ApiSigner resolveEffectiveSigner(ApiSign apiSign, SignAlgorithm effectiveAlgorithm) {
        // 注解显式覆盖特定内置算法时，按算法路由内置签名器；未覆盖时优先尊重容器注入的 ApiSigner Bean（含自定义 SPI 实现）
        if (hasExplicitAlgorithm(apiSign)) {
            return builtInSigners.getOrDefault(effectiveAlgorithm, apiSigner);
        }
        return apiSigner;
    }

    private static boolean hasExplicitAlgorithm(ApiSign apiSign) {
        return apiSign != null && apiSign.algorithm() != null && apiSign.algorithm() != SignAlgorithm.DEFAULT;
    }

    private String resolveAppId(HttpServletRequest request) {
        if (request != null) {
            String appId = request.getHeader(getHeaderAppId());
            if (StringUtils.hasText(appId)) {
                return appId;
            }
        }
        return webProperties.getSign().getAppId();
    }

    private ApiSign resolveAnnotation(ProceedingJoinPoint joinPoint, ApiSign apiSign) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        // 方法级 @ApiSign 优先级高于类级 @within(ApiSign)
        ApiSign methodAnnotation = AnnotationUtils.findAnnotation(method, ApiSign.class);
        if (methodAnnotation != null) {
            return methodAnnotation;
        }
        if (apiSign != null) {
            return apiSign;
        }
        return AnnotationUtils.findAnnotation(joinPoint.getTarget().getClass(), ApiSign.class);
    }
}

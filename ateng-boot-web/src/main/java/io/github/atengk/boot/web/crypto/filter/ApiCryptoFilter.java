package io.github.atengk.boot.web.crypto.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.web.crypto.annotation.ApiDecrypt;
import io.github.atengk.boot.web.crypto.annotation.ApiEncrypt;
import io.github.atengk.boot.web.crypto.enums.CryptoAlgorithm;
import io.github.atengk.boot.web.crypto.enums.CryptoResponseKeyMode;
import io.github.atengk.boot.web.crypto.model.ApiCipherInput;
import io.github.atengk.boot.web.crypto.model.ApiCipherResult;
import io.github.atengk.boot.web.crypto.model.ApiCryptoContext;
import io.github.atengk.boot.web.crypto.model.ApiEncryptedPayload;
import io.github.atengk.boot.web.crypto.spi.ApiCipher;
import io.github.atengk.boot.web.crypto.spi.ApiCryptoKeyProvider;
import io.github.atengk.boot.web.filter.CachedBodyHttpServletRequest;
import io.github.atengk.boot.web.filter.PayloadLoggingFilter;
import io.github.atengk.boot.web.handler.GlobalExceptionHandler;
import io.github.atengk.boot.web.properties.WebProperties;
import io.github.atengk.boot.web.sign.annotation.ApiSign;
import io.github.atengk.boot.web.sign.aspect.ApiSignAspect;
import io.github.atengk.boot.web.trace.TraceFilter;
import io.github.atengk.boot.web.util.ServletUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.WebUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * API 传输层混合加解密过滤器（按 {@link HandlerMethod} 精准按需介入）
 * 严格遵守 ADR-0002（绝不使用全局 ResponseBodyAdvice）并位于 {@link PayloadLoggingFilter} 与 {@link io.github.atengk.boot.web.xss.XssFilter} 之后，
 * 保障日志采样只见密文、XSS 清洗作用在解密后的明文 JSON 上，并与 {@link ApiSignAspect} 协同实现物理级 Encrypt-then-Sign
 *
 * @author Ateng
 * @since 2026-11-27
 */
@Slf4j
public class ApiCryptoFilter extends OncePerRequestFilter implements Ordered {

    /**
     * 过滤器执行优先级：置于 XssFilter(+80) 之后，进入 Spring MVC 分发之前
     */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 90;

    public static final String HEADER_APP_ID = "X-App-Id";
    public static final String HEADER_ENCRYPT_KEY = "X-Encrypt-Key";
    public static final String HEADER_ENCRYPT_IV = "X-Encrypt-Iv";
    public static final String HEADER_KEY_ID = "X-Key-Id";
    public static final String HEADER_ENCRYPT_ALG = "X-Encrypt-Alg";

    private static final String REQUEST_RESOLVED_ALG_ATTR = ApiCryptoFilter.class.getName() + ".RESOLVED_ALG";

    private final WebProperties webProperties;
    private final ApiCryptoKeyProvider keyProvider;
    private final List<ApiCipher> ciphers;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<HandlerMapping> handlerMappingProvider;
    private final ObjectProvider<ApiSignAspect> apiSignAspectProvider;
    private final List<HandlerMapping> explicitHandlerMappings;
    private volatile List<HandlerMapping> cachedHandlerMappings;

    public ApiCryptoFilter(WebProperties webProperties,
                           ApiCryptoKeyProvider keyProvider,
                           List<ApiCipher> ciphers,
                           ObjectMapper objectMapper,
                           ObjectProvider<HandlerMapping> handlerMappingProvider,
                           ObjectProvider<ApiSignAspect> apiSignAspectProvider) {
        this.webProperties = Objects.requireNonNull(webProperties, "WebProperties must not be null");
        this.keyProvider = Objects.requireNonNull(keyProvider, "ApiCryptoKeyProvider must not be null");
        this.ciphers = ciphers != null ? List.copyOf(ciphers) : Collections.emptyList();
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper must not be null");
        this.handlerMappingProvider = Objects.requireNonNull(handlerMappingProvider, "HandlerMappingProvider must not be null");
        this.apiSignAspectProvider = apiSignAspectProvider;
        this.explicitHandlerMappings = null;
    }

    public ApiCryptoFilter(WebProperties webProperties,
                           ApiCryptoKeyProvider keyProvider,
                           List<ApiCipher> ciphers,
                           ObjectMapper objectMapper,
                           List<HandlerMapping> handlerMappings,
                           ObjectProvider<ApiSignAspect> apiSignAspectProvider) {
        this.webProperties = Objects.requireNonNull(webProperties, "WebProperties must not be null");
        this.keyProvider = Objects.requireNonNull(keyProvider, "ApiCryptoKeyProvider must not be null");
        this.ciphers = ciphers != null ? List.copyOf(ciphers) : Collections.emptyList();
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper must not be null");
        this.handlerMappingProvider = null;
        this.apiSignAspectProvider = apiSignAspectProvider;
        this.explicitHandlerMappings = handlerMappings != null ? List.copyOf(handlerMappings) : Collections.emptyList();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        WebProperties.CryptoProperties cryptoProps = webProperties.getCrypto();
        if (!cryptoProps.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        HandlerMethod handlerMethod = resolveHandlerMethod(request);
        if (handlerMethod == null) {
            filterChain.doFilter(request, response);
            return;
        }

        CryptoEndpointSpec spec = resolveEndpointSpec(handlerMethod, request, cryptoProps);
        if (!spec.activeDecrypt() && !spec.activeEncrypt()) {
            filterChain.doFilter(request, response);
            return;
        }

        int maxBodyBytes = resolveMaxBodyBytes(cryptoProps);
        CryptoResponseKeyMode responseKeyMode = spec.activeEncrypt()
                ? resolveResponseKeyMode(spec.encrypt(), cryptoProps)
                : CryptoResponseKeyMode.CLIENT_PUBLIC_KEY;
        boolean useRequestSessionKey = spec.activeEncrypt()
                && responseKeyMode == CryptoResponseKeyMode.REQUEST_SESSION_KEY;

        HttpServletRequest requestForChain = request;
        RequestSessionKeyState sessionKeyState = null;

        try {
            // 0. REQUEST_SESSION_KEY 模式下先校验入站与出站显式算法是否冲突，Fail-Fast 拦截 500
            if (useRequestSessionKey && spec.activeDecrypt()) {
                CryptoAlgorithm decAnnAlg = spec.decrypt() != null ? spec.decrypt().algorithm() : CryptoAlgorithm.DEFAULT;
                CryptoAlgorithm encAnnAlg = spec.encrypt() != null ? spec.encrypt().algorithm() : CryptoAlgorithm.DEFAULT;
                if (decAnnAlg != null && decAnnAlg != CryptoAlgorithm.DEFAULT
                        && encAnnAlg != null && encAnnAlg != CryptoAlgorithm.DEFAULT
                        && decAnnAlg != encAnnAlg) {
                    log.error("API 加解密注解配置冲突: uri={}, decryptAlg={}, encryptAlg={}",
                            request.getRequestURI(), decAnnAlg, encAnnAlg);
                    writeErrorResponse(response, cryptoProps, CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                            String.format("REQUEST_SESSION_KEY 模式下入站解密算法 (%s) 与出站加密算法 (%s) 不一致", decAnnAlg, encAnnAlg),
                            true);
                    return;
                }
            }

            // 1. 入站方向：优先 @ApiSign 密文验签 + Nonce 防重放，随后 @ApiDecrypt 解密换流（或 REQUEST_SESSION_KEY 无体握手解封）
            if (spec.activeDecrypt()) {
                try {
                    InboundDecryptionOutcome outcome = processInboundDecryption(
                            request, spec, cryptoProps, maxBodyBytes, useRequestSessionKey);
                    requestForChain = outcome.request();
                    sessionKeyState = outcome.sessionKeyState();
                } catch (BusinessException e) {
                    if (CommonResultCode.INTERNAL_SERVER_ERROR.getCode().equals(e.getCode())) {
                        log.error("API 入站加解密系统异常: uri={}, code={}, msg={}", request.getRequestURI(), e.getCode(), e.getMsg(), e);
                    } else {
                        log.warn("API 入站加解密/验签拦截: uri={}, code={}, msg={}", request.getRequestURI(), e.getCode(), e.getMsg());
                    }
                    writeErrorResponse(response, cryptoProps, e.getCode(), e.getMsg(), isCryptoError(e));
                    return;
                }
            } else if (useRequestSessionKey && !isResponseBodylessMethod(request.getMethod())) {
                try {
                    InboundDecryptionOutcome outcome = prepareRequestSessionKeyForHandshake(
                            request, spec, cryptoProps, maxBodyBytes);
                    requestForChain = outcome.request();
                    sessionKeyState = outcome.sessionKeyState();
                } catch (BusinessException e) {
                    if (CommonResultCode.INTERNAL_SERVER_ERROR.getCode().equals(e.getCode())) {
                        log.error("API 密钥握手系统异常: uri={}, code={}, msg={}", request.getRequestURI(), e.getCode(), e.getMsg(), e);
                    } else {
                        log.warn("API 密钥握手/验签拦截: uri={}, code={}, msg={}", request.getRequestURI(), e.getCode(), e.getMsg());
                    }
                    writeErrorResponse(response, cryptoProps, e.getCode(), e.getMsg(), isCryptoError(e));
                    return;
                }
            }

            if (!spec.activeEncrypt()) {
                filterChain.doFilter(requestForChain, response);
                return;
            }

            // 2. 出站方向：标记挂起 @ApiSign 响应加签并缓冲响应体，保证 Encrypt-then-Sign
            requestForChain.setAttribute(ApiSignAspect.OUTBOUND_ENCRYPT_ACTIVE_ATTRIBUTE, Boolean.TRUE);
            ApiCryptoResponseWrapper responseWrapper = new ApiCryptoResponseWrapper(response, maxBodyBytes);
            try {
                filterChain.doFilter(requestForChain, responseWrapper);
            } catch (BusinessException e) {
                if (responseWrapper.isPayloadTooLarge()) {
                    clearCryptoAndSignHeaders(response, cryptoProps);
                    writeErrorResponse(response, cryptoProps, CommonResultCode.PAYLOAD_TOO_LARGE.getCode(), e.getMsg(), true);
                    return;
                }
                throw e;
            } finally {
                requestForChain.removeAttribute(ApiSignAspect.OUTBOUND_ENCRYPT_ACTIVE_ATTRIBUTE);
            }

            if (responseWrapper.isPayloadTooLarge()) {
                clearCryptoAndSignHeaders(response, cryptoProps);
                writeErrorResponse(response, cryptoProps, CommonResultCode.PAYLOAD_TOO_LARGE.getCode(),
                        "出站加密响应体大小超过允许的最大限制: " + maxBodyBytes + " 字节", true);
                return;
            }

            if (requestForChain.isAsyncStarted() || responseWrapper.isPassthrough()) {
                return;
            }

            processOutboundEncryption(requestForChain, response, responseWrapper, spec, cryptoProps,
                    useRequestSessionKey, sessionKeyState);
        } finally {
            if (sessionKeyState != null) {
                sessionKeyState.clear();
            }
        }
    }

    private InboundDecryptionOutcome processInboundDecryption(HttpServletRequest request,
                                                            CryptoEndpointSpec spec,
                                                            WebProperties.CryptoProperties cryptoProps,
                                                            int maxBodyBytes,
                                                            boolean useRequestSessionKey) throws IOException {
        if (request.getContentLengthLong() > maxBodyBytes) {
            throw new CryptoBusinessException(CommonResultCode.PAYLOAD_TOO_LARGE.getCode(),
                    "加密请求体大小超过允许的最大限制: " + maxBodyBytes + " 字节");
        }

        CachedBodyHttpServletRequest cachedRequest = WebUtils.getNativeRequest(request, CachedBodyHttpServletRequest.class);
        byte[] rawBody;
        HttpServletRequest verifyRequestTarget;
        if (cachedRequest != null) {
            rawBody = cachedRequest.getCachedBody();
            if (rawBody.length > maxBodyBytes) {
                throw new CryptoBusinessException(CommonResultCode.PAYLOAD_TOO_LARGE.getCode(),
                        "加密请求体大小超过允许的最大限制: " + maxBodyBytes + " 字节");
            }
            verifyRequestTarget = request;
        } else {
            try {
                cachedRequest = new CachedBodyHttpServletRequest(request, maxBodyBytes);
            } catch (BusinessException e) {
                throw new CryptoBusinessException(e.getCode(), e.getMsg(), e);
            }
            rawBody = cachedRequest.getCachedBody();
            verifyRequestTarget = cachedRequest;
        }

        // 先对原始密文请求执行 @ApiSign 验签与 Nonce 防重放（杜绝未认证请求消耗非对称私钥解密算力）
        verifyInboundSignatureIfNeeded(verifyRequestTarget, spec.apiSign());

        String encryptKeyHeader = resolveHeaderName(cryptoProps.getHeaderEncryptKey(), HEADER_ENCRYPT_KEY);
        String encryptIvHeader = resolveHeaderName(cryptoProps.getHeaderEncryptIv(), HEADER_ENCRYPT_IV);
        String keyIdHeader = resolveHeaderName(cryptoProps.getHeaderKeyId(), HEADER_KEY_ID);
        String encryptAlgHeader = resolveHeaderName(cryptoProps.getHeaderEncryptAlg(), HEADER_ENCRYPT_ALG);

        String encryptKey = request.getHeader(encryptKeyHeader);
        String encryptIv = request.getHeader(encryptIvHeader);
        boolean hasAnyEncryptHeader = StringUtils.hasText(encryptKey) || StringUtils.hasText(encryptIv);

        if (!hasAnyEncryptHeader && !spec.decrypt().required()) {
            // REQUEST_SESSION_KEY 模式下坚持 Fail-Closed，不允许无会话密钥的明文请求触发会话回包
            if (useRequestSessionKey) {
                throw new CryptoBusinessException(CommonResultCode.BAD_REQUEST.getCode(),
                        String.format("REQUEST_SESSION_KEY 模式下必须携带加密会话密钥请求头 (%s, %s)",
                                encryptKeyHeader, encryptIvHeader));
            }
            // 灰度兼容模式：未携带加密头时按明文请求体放行
            return new InboundDecryptionOutcome(verifyRequestTarget, null);
        }
        if (!StringUtils.hasText(encryptKey) || !StringUtils.hasText(encryptIv)) {
            throw new CryptoBusinessException(CommonResultCode.BAD_REQUEST.getCode(),
                    String.format("缺少加密请求头 (%s, %s)", encryptKeyHeader, encryptIvHeader));
        }
        if (rawBody.length == 0) {
            throw new CryptoBusinessException(CommonResultCode.BAD_REQUEST.getCode(), "加密请求体不能为空");
        }

        ApiEncryptedPayload envelope;
        try {
            envelope = objectMapper.readValue(rawBody, ApiEncryptedPayload.class);
        } catch (Exception e) {
            throw new CryptoBusinessException(CommonResultCode.BAD_REQUEST.getCode(),
                    "加密请求体格式非法，期望标准密文信封", e);
        }
        if (envelope == null || !StringUtils.hasText(envelope.getCipherText())) {
            throw new CryptoBusinessException(CommonResultCode.BAD_REQUEST.getCode(), "密文信封字段 cipherText 不能为空");
        }

        CryptoAlgorithm effectiveAlg;
        try {
            effectiveAlg = useRequestSessionKey
                    ? resolveSessionKeyPairAlgorithm(spec.decrypt(), spec.encrypt(), request.getHeader(encryptAlgHeader), cryptoProps)
                    : resolveInboundAlgorithm(spec.decrypt(), request.getHeader(encryptAlgHeader), cryptoProps);
        } catch (BusinessException e) {
            throw new CryptoBusinessException(e.getCode(), e.getMsg(), e);
        }
        request.setAttribute(REQUEST_RESOLVED_ALG_ATTR, effectiveAlg);

        String appId = resolveAppId(request, cryptoProps);
        String rawKeyId = request.getHeader(keyIdHeader);
        String effectiveKeyId = StringUtils.hasText(rawKeyId)
                ? rawKeyId.trim()
                : keyProvider.getActiveKeyId(appId, effectiveAlg);

        final String serverPrivateKey;
        try {
            serverPrivateKey = keyProvider.getServerPrivateKey(appId, effectiveKeyId, effectiveAlg);
        } catch (BusinessException e) {
            throw new CryptoBusinessException(e.getCode(), e.getMsg(), e);
        }

        byte[] aad = cryptoProps.isBindAad()
                ? ApiCryptoContext.buildStandardAad(appId, effectiveKeyId, effectiveAlg)
                : null;
        ApiCryptoContext cryptoContext = new ApiCryptoContext(effectiveAlg, appId, effectiveKeyId, serverPrivateKey, aad);
        ApiCipher cipher = findCipher(effectiveAlg);

        if (!useRequestSessionKey) {
            byte[] decryptedBytes;
            try {
                decryptedBytes = cipher.decrypt(
                        new ApiCipherInput(encryptKey, encryptIv, envelope.getCipherText()),
                        cryptoContext
                );
            } catch (BusinessException e) {
                throw new CryptoBusinessException(e.getCode(), e.getMsg(), e);
            }
            if (decryptedBytes.length > maxBodyBytes) {
                throw new CryptoBusinessException(CommonResultCode.PAYLOAD_TOO_LARGE.getCode(),
                        "解密后明文载荷大小超过允许的最大限制: " + maxBodyBytes + " 字节");
            }
            // 若原有 Request 为外部多层包装（如 XssHttpServletRequestWrapper），继续保留外层 Header/Query 能力仅替换流内容
            return new InboundDecryptionOutcome(new DecryptedBodyHttpServletRequest(verifyRequestTarget, decryptedBytes), null);
        }

        byte[] sessionKey;
        try {
            sessionKey = cipher.unwrapSessionKey(encryptKey, cryptoContext);
        } catch (BusinessException e) {
            throw new CryptoBusinessException(e.getCode(), e.getMsg(), e);
        }
        try {
            byte[] decryptedBytes = cipher.decryptWithSessionKey(sessionKey, encryptIv, envelope.getCipherText(), cryptoContext);
            if (decryptedBytes.length > maxBodyBytes) {
                throw new CryptoBusinessException(CommonResultCode.PAYLOAD_TOO_LARGE.getCode(),
                        "解密后明文载荷大小超过允许的最大限制: " + maxBodyBytes + " 字节");
            }
            RequestSessionKeyState state = new RequestSessionKeyState(effectiveAlg, appId, effectiveKeyId, sessionKey);
            return new InboundDecryptionOutcome(new DecryptedBodyHttpServletRequest(verifyRequestTarget, decryptedBytes), state);
        } catch (BusinessException e) {
            Arrays.fill(sessionKey, (byte) 0);
            throw new CryptoBusinessException(e.getCode(), e.getMsg(), e);
        } catch (Throwable ex) {
            Arrays.fill(sessionKey, (byte) 0);
            throw ex;
        }
    }

    private InboundDecryptionOutcome prepareRequestSessionKeyForHandshake(HttpServletRequest request,
                                                                          CryptoEndpointSpec spec,
                                                                          WebProperties.CryptoProperties cryptoProps,
                                                                          int maxBodyBytes) throws IOException {
        HttpServletRequest requestForChain = request;
        ApiSign effectiveSign = spec.apiSign();
        if (effectiveSign != null && effectiveSign.verifyRequest()) {
            HttpServletRequest verifyTarget = request;
            if (!isBodylessSignMethod(request.getMethod())
                    && WebUtils.getNativeRequest(request, CachedBodyHttpServletRequest.class) == null) {
                if (request.getContentLengthLong() > maxBodyBytes) {
                    throw new CryptoBusinessException(CommonResultCode.PAYLOAD_TOO_LARGE.getCode(),
                            "请求体大小超过允许的最大限制: " + maxBodyBytes + " 字节");
                }
                try {
                    CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request, maxBodyBytes);
                    verifyTarget = cachedRequest;
                    requestForChain = cachedRequest;
                } catch (BusinessException e) {
                    throw new CryptoBusinessException(e.getCode(), e.getMsg(), e);
                }
            }
            // 先执行 @ApiSign 验签与 Nonce 防重放，杜绝未认证请求消耗非对称私钥解封算力
            verifyInboundSignatureIfNeeded(verifyTarget, effectiveSign);
        }

        String encryptKeyHeader = resolveHeaderName(cryptoProps.getHeaderEncryptKey(), HEADER_ENCRYPT_KEY);
        String keyIdHeader = resolveHeaderName(cryptoProps.getHeaderKeyId(), HEADER_KEY_ID);
        String encryptAlgHeader = resolveHeaderName(cryptoProps.getHeaderEncryptAlg(), HEADER_ENCRYPT_ALG);

        String encryptKey = request.getHeader(encryptKeyHeader);
        if (!StringUtils.hasText(encryptKey)) {
            throw new CryptoBusinessException(CommonResultCode.BAD_REQUEST.getCode(),
                    String.format("REQUEST_SESSION_KEY 模式下缺少加密会话密钥请求头 (%s)", encryptKeyHeader));
        }

        CryptoAlgorithm effectiveAlg;
        try {
            effectiveAlg = resolveEncryptAlgorithmWithHeader(spec.encrypt(), request.getHeader(encryptAlgHeader), cryptoProps);
        } catch (BusinessException e) {
            throw new CryptoBusinessException(e.getCode(), e.getMsg(), e);
        }
        request.setAttribute(REQUEST_RESOLVED_ALG_ATTR, effectiveAlg);

        String appId = resolveAppId(request, cryptoProps);
        String rawKeyId = request.getHeader(keyIdHeader);
        String effectiveKeyId = StringUtils.hasText(rawKeyId)
                ? rawKeyId.trim()
                : keyProvider.getActiveKeyId(appId, effectiveAlg);

        final String serverPrivateKey;
        try {
            serverPrivateKey = keyProvider.getServerPrivateKey(appId, effectiveKeyId, effectiveAlg);
        } catch (BusinessException e) {
            throw new CryptoBusinessException(e.getCode(), e.getMsg(), e);
        }

        ApiCryptoContext cryptoContext = new ApiCryptoContext(effectiveAlg, appId, effectiveKeyId, serverPrivateKey, null);
        ApiCipher cipher = findCipher(effectiveAlg);
        byte[] sessionKey;
        try {
            sessionKey = cipher.unwrapSessionKey(encryptKey, cryptoContext);
        } catch (BusinessException e) {
            throw new CryptoBusinessException(e.getCode(), e.getMsg(), e);
        }
        return new InboundDecryptionOutcome(requestForChain,
                new RequestSessionKeyState(effectiveAlg, appId, effectiveKeyId, sessionKey));
    }

    private void verifyInboundSignatureIfNeeded(HttpServletRequest verifyRequestTarget, ApiSign effectiveSign) {
        if (effectiveSign != null && effectiveSign.verifyRequest()) {
            ApiSignAspect apiSignAspect = apiSignAspectProvider != null ? apiSignAspectProvider.getIfAvailable() : null;
            if (apiSignAspect != null) {
                try {
                    apiSignAspect.verifyInboundFromFilter(verifyRequestTarget, effectiveSign);
                } catch (BusinessException e) {
                    throw new SignBusinessException(e.getCode(), e.getMsg(), e);
                }
            }
        }
    }

    private void processOutboundEncryption(HttpServletRequest request,
                                           HttpServletResponse realResponse,
                                           ApiCryptoResponseWrapper responseWrapper,
                                           CryptoEndpointSpec spec,
                                           WebProperties.CryptoProperties cryptoProps,
                                           boolean useRequestSessionKey,
                                           RequestSessionKeyState sessionKeyState) throws IOException {
        byte[] plainBytes = responseWrapper.getContentAsByteArray();
        if (plainBytes.length == 0 || PayloadLoggingFilter.isBinaryOrMultipart(responseWrapper.getContentType())) {
            writeRawBytes(realResponse, plainBytes);
            return;
        }

        boolean isError = Boolean.TRUE.equals(request.getAttribute(GlobalExceptionHandler.ERROR_RESPONSE_ATTRIBUTE))
                || realResponse.getStatus() >= 400;
        if (isError && !spec.encrypt().encryptError()) {
            writeRawBytes(realResponse, plainBytes);
            return;
        }

        try {
            final CryptoAlgorithm effectiveAlg;
            final String effectiveKeyId;
            final ApiCipherResult cipherResult;
            if (useRequestSessionKey) {
                if (sessionKeyState == null || sessionKeyState.sessionKey() == null) {
                    throw new CryptoBusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                            "缺少请求会话密钥状态，无法执行响应加密");
                }
                effectiveAlg = sessionKeyState.algorithm();
                String appId = sessionKeyState.appId();
                effectiveKeyId = sessionKeyState.effectiveKeyId();
                byte[] aad = cryptoProps.isBindAad()
                        ? ApiCryptoContext.buildStandardAad(appId, effectiveKeyId, effectiveAlg)
                        : null;
                ApiCryptoContext cryptoContext = new ApiCryptoContext(effectiveAlg, appId, effectiveKeyId, null, aad);
                ApiCipher cipher = findCipher(effectiveAlg);
                cipherResult = cipher.encryptWithSessionKey(plainBytes, sessionKeyState.sessionKey(), cryptoContext);
            } else {
                effectiveAlg = resolveOutboundAlgorithm(spec.encrypt(), request, cryptoProps);
                String appId = resolveAppId(request, cryptoProps);
                effectiveKeyId = keyProvider.getActiveKeyId(appId, effectiveAlg);
                String clientPublicKey = keyProvider.getClientPublicKey(appId, effectiveKeyId, effectiveAlg);
                byte[] aad = cryptoProps.isBindAad()
                        ? ApiCryptoContext.buildStandardAad(appId, effectiveKeyId, effectiveAlg)
                        : null;

                ApiCryptoContext cryptoContext = new ApiCryptoContext(effectiveAlg, appId, effectiveKeyId, clientPublicKey, aad);
                ApiCipher cipher = findCipher(effectiveAlg);
                cipherResult = cipher.encrypt(plainBytes, cryptoContext);
            }

            byte[] envelopeBytes = objectMapper.writeValueAsBytes(new ApiEncryptedPayload(cipherResult.cipherText()));

            // 出站 Encrypt-then-Sign：先对最终密文信封字节流加签；加签成功后再原子写入全部加密响应头，防止部分失败污染响应头
            Object deferredSign = request.getAttribute(ApiSignAspect.DEFERRED_OUTBOUND_SIGN_ATTRIBUTE);
            if (deferredSign != null) {
                ApiSignAspect apiSignAspect = apiSignAspectProvider != null ? apiSignAspectProvider.getIfAvailable() : null;
                if (apiSignAspect != null) {
                    apiSignAspect.signDeferredResponse(request, realResponse, envelopeBytes, deferredSign);
                }
            }

            // REQUEST_SESSION_KEY 模式下 encryptedKey 为 null，省略 X-Encrypt-Key 响应头以便客户端直走内存会话密钥解密
            if (StringUtils.hasText(cipherResult.encryptedKey())) {
                realResponse.setHeader(resolveHeaderName(cryptoProps.getHeaderEncryptKey(), HEADER_ENCRYPT_KEY), cipherResult.encryptedKey());
            }
            realResponse.setHeader(resolveHeaderName(cryptoProps.getHeaderEncryptIv(), HEADER_ENCRYPT_IV), cipherResult.iv());
            realResponse.setHeader(resolveHeaderName(cryptoProps.getHeaderKeyId(), HEADER_KEY_ID), effectiveKeyId);
            realResponse.setHeader(resolveHeaderName(cryptoProps.getHeaderEncryptAlg(), HEADER_ENCRYPT_ALG), effectiveAlg.name());

            realResponse.setContentType("application/json;charset=UTF-8");
            realResponse.setCharacterEncoding(StandardCharsets.UTF_8.name());
            realResponse.setContentLength(envelopeBytes.length);
            ServletOutputStream out = realResponse.getOutputStream();
            out.write(envelopeBytes);
            out.flush();
        } catch (BusinessException e) {
            log.error("API 出站响应加密/回签失败: uri={}, code={}, msg={}", request.getRequestURI(), e.getCode(), e.getMsg(), e);
            clearCryptoAndSignHeaders(realResponse, cryptoProps);
            writeErrorResponse(realResponse, cryptoProps, e.getCode(), e.getMsg(), true);
        } catch (Exception e) {
            log.error("API 出站响应加密发生未知异常: uri={}", request.getRequestURI(), e);
            clearCryptoAndSignHeaders(realResponse, cryptoProps);
            writeErrorResponse(realResponse, cryptoProps,
                    CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    spec.encrypt().message(),
                    true);
        }
    }

    private void clearCryptoAndSignHeaders(HttpServletResponse response, WebProperties.CryptoProperties cryptoProps) {
        if (response.isCommitted()) {
            return;
        }
        String keyHeader = resolveHeaderName(cryptoProps.getHeaderEncryptKey(), HEADER_ENCRYPT_KEY);
        String ivHeader = resolveHeaderName(cryptoProps.getHeaderEncryptIv(), HEADER_ENCRYPT_IV);
        String keyIdHeader = resolveHeaderName(cryptoProps.getHeaderKeyId(), HEADER_KEY_ID);
        String algHeader = resolveHeaderName(cryptoProps.getHeaderEncryptAlg(), HEADER_ENCRYPT_ALG);
        if (response.containsHeader(keyHeader)) {
            response.setHeader(keyHeader, "");
        }
        if (response.containsHeader(ivHeader)) {
            response.setHeader(ivHeader, "");
        }
        if (response.containsHeader(keyIdHeader)) {
            response.setHeader(keyIdHeader, "");
        }
        if (response.containsHeader(algHeader)) {
            response.setHeader(algHeader, "");
        }
        WebProperties.SignProperties signProps = webProperties.getSign();
        String tsHeader = resolveHeaderName(signProps.getHeaderTimestamp(), ApiSignAspect.HEADER_TIMESTAMP);
        String nonceHeader = resolveHeaderName(signProps.getHeaderNonce(), ApiSignAspect.HEADER_NONCE);
        String sigHeader = resolveHeaderName(signProps.getHeaderSignature(), ApiSignAspect.HEADER_SIGNATURE);
        if (response.containsHeader(tsHeader)) {
            response.setHeader(tsHeader, "");
        }
        if (response.containsHeader(nonceHeader)) {
            response.setHeader(nonceHeader, "");
        }
        if (response.containsHeader(sigHeader)) {
            response.setHeader(sigHeader, "");
        }
    }

    private void writeRawBytes(HttpServletResponse response, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        if (!StringUtils.hasText(response.getContentType())) {
            response.setContentType("application/json;charset=UTF-8");
        }
        response.setContentLength(bytes.length);
        ServletOutputStream out = response.getOutputStream();
        out.write(bytes);
        out.flush();
    }

    private void writeErrorResponse(HttpServletResponse response,
                                    WebProperties.CryptoProperties cryptoProps,
                                    Integer code,
                                    String message,
                                    boolean cryptoFailure) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        String traceId = MDC.get(TraceFilter.MDC_TRACE_ID_KEY);
        if (StringUtils.hasText(traceId) && !response.containsHeader(TraceFilter.DEFAULT_HEADER)) {
            response.setHeader(TraceFilter.DEFAULT_HEADER, traceId);
        }
        int status = resolveHttpStatus(cryptoProps, code, cryptoFailure);
        ServletUtil.writeJson(response, status, Result.fail(code != null ? code : CommonResultCode.BAD_REQUEST.getCode(), message));
    }

    private int resolveHttpStatus(WebProperties.CryptoProperties cryptoProps, Integer code, boolean cryptoFailure) {
        if (!cryptoFailure || !cryptoProps.isUseHttpStatus() || code == null) {
            return HttpServletResponse.SC_OK;
        }
        if (code == CommonResultCode.PAYLOAD_TOO_LARGE.getCode()) {
            return HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE;
        }
        if (code == CommonResultCode.INTERNAL_SERVER_ERROR.getCode()) {
            return HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        }
        if (code == CommonResultCode.BAD_REQUEST.getCode()) {
            return HttpServletResponse.SC_BAD_REQUEST;
        }
        return HttpServletResponse.SC_OK;
    }

    private ApiCipher findCipher(CryptoAlgorithm algorithm) {
        for (ApiCipher cipher : ciphers) {
            if (cipher.supports(algorithm)) {
                return cipher;
            }
        }
        throw new CryptoBusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                "未装配支持指定算法的 ApiCipher 引擎: " + algorithm);
    }

    private CryptoAlgorithm resolveInboundAlgorithm(ApiDecrypt decryptAnn,
                                                    String headerAlgValue,
                                                    WebProperties.CryptoProperties cryptoProps) {
        CryptoAlgorithm annAlg = (decryptAnn != null && decryptAnn.algorithm() != null)
                ? decryptAnn.algorithm()
                : CryptoAlgorithm.DEFAULT;
        CryptoAlgorithm headerAlg = CryptoAlgorithm.fromHeader(headerAlgValue);
        if (annAlg != CryptoAlgorithm.DEFAULT) {
            if (headerAlg != null && headerAlg != annAlg) {
                throw new BusinessException(CommonResultCode.BAD_REQUEST,
                        "请求加密算法与接口声明不匹配，期望: " + annAlg + "，实际: " + headerAlg);
            }
            return annAlg;
        }
        if (headerAlg != null) {
            return headerAlg;
        }
        return resolveGlobalAlgorithm(cryptoProps);
    }

    private CryptoAlgorithm resolveSessionKeyPairAlgorithm(ApiDecrypt decryptAnn,
                                                            ApiEncrypt encryptAnn,
                                                            String headerAlgValue,
                                                            WebProperties.CryptoProperties cryptoProps) {
        CryptoAlgorithm decAlg = (decryptAnn != null && decryptAnn.algorithm() != null)
                ? decryptAnn.algorithm()
                : CryptoAlgorithm.DEFAULT;
        CryptoAlgorithm encAlg = (encryptAnn != null && encryptAnn.algorithm() != null)
                ? encryptAnn.algorithm()
                : CryptoAlgorithm.DEFAULT;
        CryptoAlgorithm annAlg = decAlg != CryptoAlgorithm.DEFAULT ? decAlg : encAlg;
        CryptoAlgorithm headerAlg = CryptoAlgorithm.fromHeader(headerAlgValue);
        if (annAlg != CryptoAlgorithm.DEFAULT) {
            if (headerAlg != null && headerAlg != annAlg) {
                throw new BusinessException(CommonResultCode.BAD_REQUEST,
                        "请求加密算法与接口声明不匹配，期望: " + annAlg + "，实际: " + headerAlg);
            }
            return annAlg;
        }
        if (headerAlg != null) {
            return headerAlg;
        }
        return resolveGlobalAlgorithm(cryptoProps);
    }

    private CryptoAlgorithm resolveEncryptAlgorithmWithHeader(ApiEncrypt encryptAnn,
                                                              String headerAlgValue,
                                                              WebProperties.CryptoProperties cryptoProps) {
        CryptoAlgorithm annAlg = (encryptAnn != null && encryptAnn.algorithm() != null)
                ? encryptAnn.algorithm()
                : CryptoAlgorithm.DEFAULT;
        CryptoAlgorithm headerAlg = CryptoAlgorithm.fromHeader(headerAlgValue);
        if (annAlg != CryptoAlgorithm.DEFAULT) {
            if (headerAlg != null && headerAlg != annAlg) {
                throw new BusinessException(CommonResultCode.BAD_REQUEST,
                        "请求加密算法与接口声明不匹配，期望: " + annAlg + "，实际: " + headerAlg);
            }
            return annAlg;
        }
        if (headerAlg != null) {
            return headerAlg;
        }
        return resolveGlobalAlgorithm(cryptoProps);
    }

    private static CryptoResponseKeyMode resolveResponseKeyMode(ApiEncrypt encryptAnn,
                                                                WebProperties.CryptoProperties cryptoProps) {
        if (encryptAnn != null && encryptAnn.keyMode() != null && encryptAnn.keyMode() != CryptoResponseKeyMode.DEFAULT) {
            return encryptAnn.keyMode();
        }
        CryptoResponseKeyMode global = cryptoProps.getResponseKeyMode();
        return (global != null && global != CryptoResponseKeyMode.DEFAULT)
                ? global
                : CryptoResponseKeyMode.CLIENT_PUBLIC_KEY;
    }

    private static boolean isResponseBodylessMethod(String method) {
        return "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method)
                || "TRACE".equalsIgnoreCase(method);
    }

    private static boolean isBodylessSignMethod(String method) {
        return "GET".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method)
                || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method);
    }

    private CryptoAlgorithm resolveOutboundAlgorithm(ApiEncrypt encryptAnn,
                                                     HttpServletRequest request,
                                                     WebProperties.CryptoProperties cryptoProps) {
        if (encryptAnn != null && encryptAnn.algorithm() != null && encryptAnn.algorithm() != CryptoAlgorithm.DEFAULT) {
            return encryptAnn.algorithm();
        }
        Object resolvedFromInbound = request.getAttribute(REQUEST_RESOLVED_ALG_ATTR);
        if (resolvedFromInbound instanceof CryptoAlgorithm alg && alg != CryptoAlgorithm.DEFAULT) {
            return alg;
        }
        CryptoAlgorithm headerAlg = CryptoAlgorithm.fromHeader(
                request.getHeader(resolveHeaderName(cryptoProps.getHeaderEncryptAlg(), HEADER_ENCRYPT_ALG))
        );
        if (headerAlg != null) {
            return headerAlg;
        }
        return resolveGlobalAlgorithm(cryptoProps);
    }

    private static CryptoAlgorithm resolveGlobalAlgorithm(WebProperties.CryptoProperties cryptoProps) {
        CryptoAlgorithm global = cryptoProps.getAlgorithm();
        return (global != null && global != CryptoAlgorithm.DEFAULT) ? global : CryptoAlgorithm.RSA_AES_GCM;
    }

    private String resolveAppId(HttpServletRequest request, WebProperties.CryptoProperties cryptoProps) {
        String headerName = resolveHeaderName(cryptoProps.getHeaderAppId(), HEADER_APP_ID);
        String fromHeader = request.getHeader(headerName);
        if (StringUtils.hasText(fromHeader)) {
            return fromHeader.trim();
        }
        return StringUtils.hasText(cryptoProps.getAppId()) ? cryptoProps.getAppId().trim() : "default";
    }

    private static String resolveHeaderName(String configured, String defaultName) {
        return StringUtils.hasText(configured) ? configured.trim() : defaultName;
    }

    private static int resolveMaxBodyBytes(WebProperties.CryptoProperties cryptoProps) {
        DataSize size = cryptoProps.getMaxBodySize();
        long bytes = size != null ? size.toBytes() : CachedBodyHttpServletRequest.DEFAULT_MAX_BODY_SIZE;
        if (bytes <= 0L) {
            return CachedBodyHttpServletRequest.DEFAULT_MAX_BODY_SIZE;
        }
        return (int) Math.min(bytes, Integer.MAX_VALUE);
    }

    private HandlerMethod resolveHandlerMethod(HttpServletRequest request) {
        List<HandlerMapping> mappings = getHandlerMappings();
        if (mappings.isEmpty()) {
            return null;
        }
        for (HandlerMapping mapping : mappings) {
            try {
                HandlerExecutionChain chain = mapping.getHandler(request);
                if (chain != null && chain.getHandler() instanceof HandlerMethod handlerMethod) {
                    return handlerMethod;
                }
            } catch (Exception ignored) {
                // 405/415 等映射异常交给 DispatcherServlet 与 GlobalExceptionHandler 正常处理
            }
        }
        return null;
    }

    private List<HandlerMapping> getHandlerMappings() {
        if (explicitHandlerMappings != null) {
            return explicitHandlerMappings;
        }
        List<HandlerMapping> current = cachedHandlerMappings;
        if (current == null) {
            current = handlerMappingProvider.orderedStream().toList();
            cachedHandlerMappings = current;
        }
        return current;
    }

    private CryptoEndpointSpec resolveEndpointSpec(HandlerMethod handlerMethod,
                                                   HttpServletRequest request,
                                                   WebProperties.CryptoProperties cryptoProps) {
        ApiDecrypt methodDecrypt = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), ApiDecrypt.class);
        ApiDecrypt classDecrypt = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), ApiDecrypt.class);
        ApiDecrypt effectiveDecrypt = methodDecrypt != null ? methodDecrypt : classDecrypt;
        boolean decryptFromClass = (methodDecrypt == null && classDecrypt != null);

        boolean activeDecrypt = effectiveDecrypt != null && effectiveDecrypt.enabled()
                && shouldDecryptRequest(request, decryptFromClass, cryptoProps);

        ApiEncrypt methodEncrypt = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), ApiEncrypt.class);
        ApiEncrypt classEncrypt = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), ApiEncrypt.class);
        ApiEncrypt effectiveEncrypt = methodEncrypt != null ? methodEncrypt : classEncrypt;
        boolean activeEncrypt = effectiveEncrypt != null && effectiveEncrypt.enabled();

        ApiSign methodSign = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), ApiSign.class);
        ApiSign classSign = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), ApiSign.class);
        ApiSign effectiveSign = methodSign != null ? methodSign : classSign;

        return new CryptoEndpointSpec(activeDecrypt, effectiveDecrypt, activeEncrypt, effectiveEncrypt, effectiveSign);
    }

    private static boolean shouldDecryptRequest(HttpServletRequest request,
                                                boolean decryptFromClass,
                                                WebProperties.CryptoProperties cryptoProps) {
        if (PayloadLoggingFilter.isBinaryOrMultipart(request.getContentType())) {
            return false;
        }
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method) || "TRACE".equalsIgnoreCase(method)) {
            return false;
        }
        if ("DELETE".equalsIgnoreCase(method) && decryptFromClass) {
            String keyHeader = request.getHeader(resolveHeaderName(cryptoProps.getHeaderEncryptKey(), HEADER_ENCRYPT_KEY));
            String ivHeader = request.getHeader(resolveHeaderName(cryptoProps.getHeaderEncryptIv(), HEADER_ENCRYPT_IV));
            return StringUtils.hasText(keyHeader) || StringUtils.hasText(ivHeader) || request.getContentLengthLong() > 0;
        }
        return true;
    }

    private record InboundDecryptionOutcome(HttpServletRequest request, RequestSessionKeyState sessionKeyState) {
    }

    private static final class RequestSessionKeyState {
        private final CryptoAlgorithm algorithm;
        private final String appId;
        private final String effectiveKeyId;
        private byte[] sessionKey;

        private RequestSessionKeyState(CryptoAlgorithm algorithm,
                                       String appId,
                                       String effectiveKeyId,
                                       byte[] sessionKey) {
            this.algorithm = algorithm;
            this.appId = appId;
            this.effectiveKeyId = effectiveKeyId;
            this.sessionKey = sessionKey;
        }

        private CryptoAlgorithm algorithm() {
            return algorithm;
        }

        private String appId() {
            return appId;
        }

        private String effectiveKeyId() {
            return effectiveKeyId;
        }

        private byte[] sessionKey() {
            return sessionKey;
        }

        private void clear() {
            if (this.sessionKey != null) {
                Arrays.fill(this.sessionKey, (byte) 0);
                this.sessionKey = null;
            }
        }
    }

    private record CryptoEndpointSpec(boolean activeDecrypt,
                                      ApiDecrypt decrypt,
                                      boolean activeEncrypt,
                                      ApiEncrypt encrypt,
                                      ApiSign apiSign) {
    }

    private static final class CryptoBusinessException extends BusinessException {
        private CryptoBusinessException(Integer code, String msg) {
            super(code, msg);
        }

        private CryptoBusinessException(Integer code, String msg, Throwable cause) {
            super(code, msg, cause);
        }
    }

    private static final class SignBusinessException extends BusinessException {
        private SignBusinessException(Integer code, String msg, Throwable cause) {
            super(code, msg, cause);
        }
    }

    private static boolean isCryptoError(BusinessException ex) {
        return ex instanceof CryptoBusinessException;
    }
}

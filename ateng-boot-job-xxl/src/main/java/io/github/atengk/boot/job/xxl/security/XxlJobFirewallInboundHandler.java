package io.github.atengk.boot.job.xxl.security;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * XXL-Job Netty 入站安全防火墙处理器
 *
 * <p>在 Netty ChannelPipeline 最前端对调度中心的入站 HTTP 交互执行安全防卫：
 * <ol>
 *     <li><b>停机排空防卫</b>：执行器处于 Graceful Draining 阶段时拒收新调度任务；</li>
 *     <li><b>CIDR 白名单防卫</b>：拦截非授权网段来源 IP；</li>
 *     <li><b>AccessToken 快速失败</b>：在进入业务线程池前完成令牌合法性校验，抵御非法嗅探与探测。</li>
 * </ol>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@ChannelHandler.Sharable
public class XxlJobFirewallInboundHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(XxlJobFirewallInboundHandler.class);

    private static final String ACCESS_TOKEN_HEADER = "XXL-JOB-ACCESS-TOKEN";

    private final CidrMatcher cidrMatcher;
    private final String expectedAccessToken;
    private final BooleanSupplier isDrainingSupplier;

    public XxlJobFirewallInboundHandler(
            List<String> cidrWhitelist,
            String expectedAccessToken,
            BooleanSupplier isDrainingSupplier) {
        this.cidrMatcher = new CidrMatcher(cidrWhitelist);
        this.expectedAccessToken = StringUtils.hasText(expectedAccessToken) ? expectedAccessToken.trim() : null;
        this.isDrainingSupplier = isDrainingSupplier != null ? isDrainingSupplier : () -> false;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof FullHttpRequest req)) {
            super.channelRead(ctx, msg);
            return;
        }

        String clientIp = extractClientIp(ctx);
        String uri = req.uri();

        // 1. 停机排空期拒绝接收触发新任务
        if (isDrainingSupplier.getAsBoolean() && uri.contains("/run")) {
            log.warn("XXL-Job 执行器正在平滑停机排空中，已拒收来自 [{}] 的新任务触发请求: uri={}", clientIp, uri);
            sendResponse(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE,
                    "{\"code\":500,\"msg\":\"Executor is in draining phase, new job trigger rejected.\"}");
            return;
        }

        // 2. CIDR 入站白名单防卫
        if (!cidrMatcher.isAllowed(clientIp)) {
            log.warn("XXL-Job CIDR 防火墙拦截未授权来源 IP: clientIp={}, uri={}", clientIp, uri);
            sendResponse(ctx, HttpResponseStatus.FORBIDDEN,
                    "{\"code\":403,\"msg\":\"Forbidden: Client IP not allowed by CIDR whitelist\"}");
            return;
        }

        // 3. AccessToken 快速失败防卫
        if (expectedAccessToken != null) {
            String token = req.headers().get(ACCESS_TOKEN_HEADER);
            if (!StringUtils.hasText(token) || !expectedAccessToken.equals(token.trim())) {
                log.warn("XXL-Job AccessToken 校验未通过，拦截非法入站请求: clientIp={}, uri={}", clientIp, uri);
                sendResponse(ctx, HttpResponseStatus.FORBIDDEN,
                        "{\"code\":403,\"msg\":\"Forbidden: Invalid AccessToken\"}");
                return;
            }
        }

        // 放行至下游业务 Handler
        super.channelRead(ctx, msg);
    }

    private String extractClientIp(ChannelHandlerContext ctx) {
        SocketAddress socketAddress = ctx.channel().remoteAddress();
        if (socketAddress instanceof InetSocketAddress inetSocketAddress) {
            if (inetSocketAddress.getAddress() != null) {
                return inetSocketAddress.getAddress().getHostAddress();
            }
        }
        return "unknown";
    }

    private void sendResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String jsonContent) {
        byte[] bytes = jsonContent.getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.wrappedBuffer(bytes)
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

}

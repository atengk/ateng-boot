package io.github.atengk.boot.mqtt.test;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.mqtt.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 Netty 原生 MQTT 编解码器的超轻量纯内存 MQTT Broker 测试桩
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class EmbeddedMqttBroker {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedMqttBroker.class);

    private final int requestedPort;
    private int boundPort;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    private final Map<Channel, Set<String>> channelSubscriptions = new ConcurrentHashMap<>();
    private final Map<Channel, String> clientIds = new ConcurrentHashMap<>();
    private final AtomicInteger messageIdCounter = new AtomicInteger(1);
    private final List<ReceivedMessage> receivedMessages = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static class ReceivedMessage {
        private final String clientId;
        private final String topic;
        private final byte[] payload;
        private final MqttQoS qos;
        private final boolean retained;
        private final io.netty.handler.codec.mqtt.MqttProperties properties;

        public ReceivedMessage(String clientId, String topic, byte[] payload, MqttQoS qos, boolean retained, io.netty.handler.codec.mqtt.MqttProperties properties) {
            this.clientId = clientId;
            this.topic = topic;
            this.payload = payload;
            this.qos = qos;
            this.retained = retained;
            this.properties = properties;
        }

        public String getClientId() {
            return clientId;
        }

        public String getTopic() {
            return topic;
        }

        public byte[] getPayload() {
            return payload;
        }

        public MqttQoS getQos() {
            return qos;
        }

        public boolean isRetained() {
            return retained;
        }

        public io.netty.handler.codec.mqtt.MqttProperties getProperties() {
            return properties;
        }
    }

    public List<ReceivedMessage> getReceivedMessages() {
        return Collections.unmodifiableList(receivedMessages);
    }

    public void clearReceivedMessages() {
        receivedMessages.clear();
    }

    public EmbeddedMqttBroker() {
        this(0);
    }

    public EmbeddedMqttBroker(int requestedPort) {
        this.requestedPort = requestedPort;
    }

    public synchronized void start() {
        if (serverChannel != null && serverChannel.isActive()) {
            return;
        }
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            p.addLast("decoder", new MqttDecoder());
                            p.addLast("encoder", MqttEncoder.INSTANCE);
                            p.addLast("handler", new MqttBrokerHandler());
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);

            ChannelFuture f = b.bind(new InetSocketAddress("127.0.0.1", requestedPort)).sync();
            this.serverChannel = f.channel();
            InetSocketAddress localAddress = (InetSocketAddress) serverChannel.localAddress();
            this.boundPort = localAddress.getPort();
            log.info("[EmbeddedMqttBroker] Started In-Memory MQTT Broker on port [{}]", boundPort);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to start EmbeddedMqttBroker", e);
        }
    }

    public synchronized void stop() {
        log.info("[EmbeddedMqttBroker] Stopping In-Memory MQTT Broker on port [{}]", boundPort);
        if (serverChannel != null) {
            try {
                serverChannel.close().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        channelSubscriptions.clear();
        clientIds.clear();
        receivedMessages.clear();
        log.info("[EmbeddedMqttBroker] Stopped In-Memory MQTT Broker successfully");
    }

    public int getPort() {
        return boundPort;
    }

    public String getServerUri() {
        return "tcp://127.0.0.1:" + boundPort;
    }

    private class MqttBrokerHandler extends SimpleChannelInboundHandler<MqttMessage> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, MqttMessage msg) {
            if (msg.decoderResult().isFailure()) {
                log.warn("[EmbeddedMqttBroker] Decode failed: {}", msg.decoderResult().cause().getMessage());
                ctx.close();
                return;
            }

            MqttMessageType messageType = msg.fixedHeader().messageType();
            switch (messageType) {
                case CONNECT -> handleConnect(ctx, (MqttConnectMessage) msg);
                case PINGREQ -> ctx.writeAndFlush(MqttMessage.PINGRESP);
                case DISCONNECT -> handleDisconnect(ctx);
                case SUBSCRIBE -> handleSubscribe(ctx, (MqttSubscribeMessage) msg);
                case UNSUBSCRIBE -> handleUnsubscribe(ctx, (MqttUnsubscribeMessage) msg);
                case PUBLISH -> handlePublish(ctx, (MqttPublishMessage) msg);
                case PUBREL -> handlePubRel(ctx, msg);
                case PUBACK, PUBREC, PUBCOMP -> {
                    // 接收到 ACK 报文
                }
                default -> log.debug("[EmbeddedMqttBroker] Unhandled message type: {}", messageType);
            }
        }

        private void handleConnect(ChannelHandlerContext ctx, MqttConnectMessage msg) {
            String clientId = msg.payload().clientIdentifier();
            clientIds.put(ctx.channel(), clientId);
            channelSubscriptions.computeIfAbsent(ctx.channel(), k -> new CopyOnWriteArraySet<>());

            MqttConnAckMessage connAck = MqttMessageBuilders.connAck()
                    .returnCode(MqttConnectReturnCode.CONNECTION_ACCEPTED)
                    .sessionPresent(false)
                    .build();
            ctx.writeAndFlush(connAck);
            log.debug("[EmbeddedMqttBroker] Client [{}] connected from {}", clientId, ctx.channel().remoteAddress());
        }

        private void handleDisconnect(ChannelHandlerContext ctx) {
            cleanChannel(ctx.channel());
            ctx.close();
        }

        private void handleSubscribe(ChannelHandlerContext ctx, MqttSubscribeMessage msg) {
            int packetId = msg.variableHeader().messageId();
            List<MqttTopicSubscription> topicSubscriptions = msg.payload().topicSubscriptions();
            List<MqttQoS> grantedQosList = new ArrayList<>();

            Set<String> subs = channelSubscriptions.computeIfAbsent(ctx.channel(), k -> new CopyOnWriteArraySet<>());
            for (MqttTopicSubscription sub : topicSubscriptions) {
                subs.add(sub.topicName());
                grantedQosList.add(sub.qualityOfService());
                log.debug("[EmbeddedMqttBroker] Client [{}] subscribed to [{}]", clientIds.get(ctx.channel()), sub.topicName());
            }

            MqttSubAckMessage subAck = MqttMessageBuilders.subAck()
                    .packetId(packetId)
                    .addGrantedQoses(grantedQosList.toArray(new MqttQoS[0]))
                    .build();
            ctx.writeAndFlush(subAck);
        }

        private void handleUnsubscribe(ChannelHandlerContext ctx, MqttUnsubscribeMessage msg) {
            int packetId = msg.variableHeader().messageId();
            List<String> topics = msg.payload().topics();
            Set<String> subs = channelSubscriptions.get(ctx.channel());
            if (subs != null) {
                subs.removeAll(topics);
            }

            MqttUnsubAckMessage unsubAck = MqttMessageBuilders.unsubAck()
                    .packetId(packetId)
                    .build();
            ctx.writeAndFlush(unsubAck);
        }

        private void handlePublish(ChannelHandlerContext ctx, MqttPublishMessage msg) {
            String topic = msg.variableHeader().topicName();
            byte[] payloadBytes = new byte[msg.payload().readableBytes()];
            msg.payload().readBytes(payloadBytes);

            MqttQoS qos = msg.fixedHeader().qosLevel();
            int packetId = msg.variableHeader().packetId();
            boolean retained = msg.fixedHeader().isRetain();
            io.netty.handler.codec.mqtt.MqttProperties props = msg.variableHeader().properties();
            String clientId = clientIds.get(ctx.channel());
            receivedMessages.add(new ReceivedMessage(clientId, topic, payloadBytes, qos, retained, props));

            if (qos == MqttQoS.AT_LEAST_ONCE) {
                MqttMessage pubAck = MqttMessageBuilders.pubAck().packetId(packetId).build();
                ctx.writeAndFlush(pubAck);
            } else if (qos == MqttQoS.EXACTLY_ONCE) {
                MqttFixedHeader recHeader = new MqttFixedHeader(MqttMessageType.PUBREC, false, MqttQoS.AT_MOST_ONCE, false, 0);
                MqttMessageIdVariableHeader recVar = MqttMessageIdVariableHeader.from(packetId);
                ctx.writeAndFlush(new MqttMessage(recHeader, recVar));
            }

            // 转发消息给匹配的订阅客户端
            broadcastMessage(topic, payloadBytes, qos);
        }

        private void handlePubRel(ChannelHandlerContext ctx, MqttMessage msg) {
            int packetId = 0;
            if (msg.variableHeader() instanceof MqttMessageIdVariableHeader idHeader) {
                packetId = idHeader.messageId();
            }
            MqttFixedHeader compHeader = new MqttFixedHeader(MqttMessageType.PUBCOMP, false, MqttQoS.AT_MOST_ONCE, false, 0);
            MqttMessageIdVariableHeader compVar = MqttMessageIdVariableHeader.from(packetId);
            ctx.writeAndFlush(new MqttMessage(compHeader, compVar));
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            cleanChannel(ctx.channel());
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("[EmbeddedMqttBroker] Channel exception: {}", cause.getMessage());
            cleanChannel(ctx.channel());
            ctx.close();
        }

        private void cleanChannel(Channel ch) {
            channelSubscriptions.remove(ch);
            clientIds.remove(ch);
        }
    }

    private void broadcastMessage(String topic, byte[] payloadBytes, MqttQoS qos) {
        for (Map.Entry<Channel, Set<String>> entry : channelSubscriptions.entrySet()) {
            Channel ch = entry.getKey();
            if (!ch.isActive()) {
                continue;
            }
            Set<String> filters = entry.getValue();
            for (String filter : filters) {
                String effectiveFilter = filter;
                if (filter.startsWith("$share/")) {
                    int thirdSlash = filter.indexOf('/', 7);
                    if (thirdSlash > 0) {
                        effectiveFilter = filter.substring(thirdSlash + 1);
                    }
                }
                if (isTopicMatch(effectiveFilter, topic)) {
                    int nextId = messageIdCounter.getAndIncrement();
                    if (nextId > 65535) {
                        messageIdCounter.set(1);
                        nextId = 1;
                    }
                    MqttPublishMessage forward = MqttMessageBuilders.publish()
                            .topicName(topic)
                            .qos(qos)
                            .retained(false)
                            .messageId(nextId)
                            .payload(Unpooled.copiedBuffer(payloadBytes))
                            .build();
                    ch.writeAndFlush(forward);
                    break;
                }
            }
        }
    }

    public static boolean isTopicMatch(String filter, String topic) {
        if (filter.equals(topic)) {
            return true;
        }
        String[] fLevels = filter.split("/");
        String[] tLevels = topic.split("/");
        int fLen = fLevels.length;
        int tLen = tLevels.length;

        for (int i = 0; i < fLen; i++) {
            String fLevel = fLevels[i];
            if ("#".equals(fLevel)) {
                return true;
            }
            if (i >= tLen) {
                return false;
            }
            String tLevel = tLevels[i];
            if (!"+".equals(fLevel) && !fLevel.equals(tLevel)) {
                return false;
            }
        }
        return fLen == tLen;
    }
}

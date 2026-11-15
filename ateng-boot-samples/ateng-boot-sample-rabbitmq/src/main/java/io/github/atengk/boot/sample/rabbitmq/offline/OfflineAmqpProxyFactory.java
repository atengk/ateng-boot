package io.github.atengk.boot.sample.rabbitmq.offline;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionListener;
import org.springframework.amqp.rabbit.connection.PublisherCallbackChannel;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于纯 JDK 动态代理的无外部依赖 AMQP 离线仿真工厂，完全避免主代码引入测试 Mock 框架
 *
 * @author Ateng
 * @since 2026-11-15
 */
public final class OfflineAmqpProxyFactory {

    private static final Object UNHANDLED = new Object();
    private static final Map<String, Object> OFFLINE_SERVER_PROPERTIES = Map.of(
            "product", "RabbitMQ-Offline-Sim",
            "version", "3.13-offline"
    );

    private OfflineAmqpProxyFactory() {
    }

    /**
     * 创建纯 JDK 离线 ConnectionFactory 代理
     *
     * @param broker 内存 Broker 状态中心
     * @return ConnectionFactory 代理实例
     */
    public static ConnectionFactory createConnectionFactory(OfflineAmqpBroker broker) {
        Objects.requireNonNull(broker, "broker 不能为空");
        Connection sharedConnection = createConnection(broker);
        List<ConnectionListener> listeners = new CopyOnWriteArrayList<>();
        AtomicBoolean initialized = new AtomicBoolean(false);
        return (ConnectionFactory) Proxy.newProxyInstance(
                ConnectionFactory.class.getClassLoader(),
                new Class<?>[]{ConnectionFactory.class},
                (proxy, method, args) -> {
                    Object objectResult = handleObjectMethod(proxy, method, args, "OfflineConnectionFactory");
                    if (objectResult != UNHANDLED) {
                        return objectResult;
                    }
                    return switch (method.getName()) {
                        case "createConnection" -> {
                            if (initialized.compareAndSet(false, true)) {
                                for (ConnectionListener listener : listeners) {
                                    listener.onCreate(sharedConnection);
                                }
                            }
                            yield sharedConnection;
                        }
                        case "addConnectionListener" -> {
                            if (args != null && args.length > 0 && args[0] instanceof ConnectionListener l) {
                                listeners.add(l);
                            }
                            yield null;
                        }
                        case "removeConnectionListener" -> {
                            if (args != null && args.length > 0 && args[0] instanceof ConnectionListener l) {
                                yield listeners.remove(l);
                            }
                            yield false;
                        }
                        case "clearConnectionListeners" -> {
                            listeners.clear();
                            yield null;
                        }
                        case "getHost" -> "offline-mock";
                        case "getPort" -> 5672;
                        case "getVirtualHost" -> "/";
                        case "getUsername" -> "guest";
                        case "isPublisherConfirms", "isPublisherReturns", "isSimplePublisherConfirms" -> true;
                        default -> defaultReturnValue(method.getReturnType());
                    };
                }
        );
    }

    /**
     * 创建纯 JDK 离线 Spring Connection 代理
     *
     * @param broker 内存 Broker 状态中心
     * @return Connection 代理实例
     */
    public static Connection createConnection(OfflineAmqpBroker broker) {
        Objects.requireNonNull(broker, "broker 不能为空");
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    Object objectResult = handleObjectMethod(proxy, method, args, "OfflineConnection");
                    if (objectResult != UNHANDLED) {
                        return objectResult;
                    }
                    return switch (method.getName()) {
                        case "createChannel" -> createChannel(broker);
                        case "isOpen" -> true;
                        case "getLocalPort" -> 5672;
                        default -> defaultReturnValue(method.getReturnType());
                    };
                }
        );
    }

    /**
     * 创建纯 JDK 离线 Channel 代理（同时实现 PublisherCallbackChannel，支撑 RabbitAdmin 与 RabbitHealthIndicator）
     *
     * @param broker 内存 Broker 状态中心
     * @return Channel 代理实例
     */
    public static Channel createChannel(OfflineAmqpBroker broker) {
        Objects.requireNonNull(broker, "broker 不能为空");
        com.rabbitmq.client.Connection amqpConnection = createAmqpClientConnection();
        return (Channel) Proxy.newProxyInstance(
                Channel.class.getClassLoader(),
                new Class<?>[]{Channel.class, PublisherCallbackChannel.class},
                (proxy, method, args) -> {
                    Object objectResult = handleObjectMethod(proxy, method, args, "OfflineChannel");
                    if (objectResult != UNHANDLED) {
                        return objectResult;
                    }
                    return handleChannelMethod(broker, (Channel) proxy, amqpConnection, method, args);
                }
        );
    }

    /**
     * 创建用于切面回归或直接拦截触发的 MethodInvocation 代理
     *
     * @param arguments 被拦截方法的真实参数数组
     * @param action    目标执行体
     * @return MethodInvocation 实例
     */
    public static MethodInvocation createMethodInvocation(Object[] arguments, ProceedAction action) {
        Objects.requireNonNull(action, "action 不能为空");
        Object[] safeArgs = arguments != null ? arguments : new Object[0];
        return new MethodInvocation() {
            @Override
            public Method getMethod() {
                return null;
            }

            @Override
            public Object[] getArguments() {
                return safeArgs;
            }

            @Override
            public Object proceed() throws Throwable {
                return action.proceed();
            }

            @Override
            public Object getThis() {
                return null;
            }

            @Override
            public java.lang.reflect.AccessibleObject getStaticPart() {
                return null;
            }
        };
    }

    private static com.rabbitmq.client.Connection createAmqpClientConnection() {
        return (com.rabbitmq.client.Connection) Proxy.newProxyInstance(
                com.rabbitmq.client.Connection.class.getClassLoader(),
                new Class<?>[]{com.rabbitmq.client.Connection.class},
                (proxy, method, args) -> {
                    Object objectResult = handleObjectMethod(proxy, method, args, "OfflineAmqpConnection");
                    if (objectResult != UNHANDLED) {
                        return objectResult;
                    }
                    return switch (method.getName()) {
                        case "getServerProperties" -> OFFLINE_SERVER_PROPERTIES;
                        case "isOpen" -> true;
                        case "getPort" -> 5672;
                        case "getId" -> "offline-amqp-conn";
                        default -> defaultReturnValue(method.getReturnType());
                    };
                }
        );
    }

    private static Object handleChannelMethod(
            OfflineAmqpBroker broker,
            Channel selfProxy,
            com.rabbitmq.client.Connection amqpConnection,
            Method method,
            Object[] args) throws IOException {
        return switch (method.getName()) {
            case "isOpen" -> true;
            case "getConnection" -> amqpConnection;
            case "getDelegate" -> selfProxy;
            case "expire" -> Collections.emptyList();
            case "getPendingConfirmsCount" -> 0;
            case "addListener", "addPendingConfirm", "setAfterAckCallback" -> null;
            case "exchangeDeclare", "exchangeDeclareNoWait", "exchangeDeclarePassive" -> {
                broker.declareExchange(firstStringArg(args));
                yield createSimpleAmqpMethodProxy(AMQP.Exchange.DeclareOk.class, "Exchange.DeclareOk", 0);
            }
            case "queueDeclare" -> {
                String queue = firstStringArg(args);
                if (queue == null) {
                    queue = "amq.gen-" + UUID.randomUUID();
                }
                int count = broker.declareQueue(queue);
                yield createQueueDeclareOk(queue, count, broker.getConsumerCount(queue));
            }
            case "queueDeclarePassive" -> {
                String queue = firstStringArg(args);
                if (!broker.queueExists(queue)) {
                    throw new IOException("NOT_FOUND - no queue '" + queue + "' in vhost '/'");
                }
                yield createQueueDeclareOk(queue, broker.getQueueMessageCount(queue), broker.getConsumerCount(queue));
            }
            case "queueBind" -> createSimpleAmqpMethodProxy(AMQP.Queue.BindOk.class, "Queue.BindOk", 0);
            case "queuePurge" -> createSimpleAmqpMethodProxy(
                    AMQP.Queue.PurgeOk.class, "Queue.PurgeOk", broker.queuePurge(firstStringArg(args)));
            case "queueDelete" -> createSimpleAmqpMethodProxy(
                    AMQP.Queue.DeleteOk.class, "Queue.DeleteOk", broker.deleteQueue(firstStringArg(args)));
            case "messageCount" -> (long) broker.getQueueMessageCount(firstStringArg(args));
            case "consumerCount" -> (long) broker.getConsumerCount(firstStringArg(args));
            case "basicGet" -> {
                String queue = firstStringArg(args);
                boolean autoAck = args != null && args.length > 1 && args[1] instanceof Boolean b && b;
                GetResponse response = broker.basicGet(queue);
                if (autoAck && response != null) {
                    broker.basicAck(response.getEnvelope().getDeliveryTag(), false);
                }
                yield response;
            }
            case "basicAck" -> {
                if (args != null && args.length >= 2 && args[0] instanceof Long tag && args[1] instanceof Boolean multiple) {
                    broker.basicAck(tag, multiple);
                }
                yield null;
            }
            case "basicNack" -> {
                if (args != null && args.length >= 3
                        && args[0] instanceof Long tag
                        && args[1] instanceof Boolean multiple
                        && args[2] instanceof Boolean requeue) {
                    broker.basicNack(tag, multiple, requeue);
                }
                yield null;
            }
            case "basicReject" -> {
                if (args != null && args.length >= 2 && args[0] instanceof Long tag && args[1] instanceof Boolean requeue) {
                    broker.basicNack(tag, false, requeue);
                }
                yield null;
            }
            case "basicConsume" -> {
                String queue = firstStringArg(args);
                String tag = "offline-consumer-" + UUID.randomUUID();
                broker.registerConsumer(queue, tag);
                yield tag;
            }
            case "basicCancel" -> {
                broker.cancelConsumer(firstStringArg(args));
                yield null;
            }
            default -> defaultReturnValue(method.getReturnType());
        };
    }

    private static <T> T createSimpleAmqpMethodProxy(Class<T> type, String label, int messageCount) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> "getMessageCount".equals(method.getName())
                        ? messageCount
                        : handleAmqpMethod(proxy, method, args, label)
        ));
    }

    private static AMQP.Queue.DeclareOk createQueueDeclareOk(String queue, int messageCount, int consumerCount) {
        return (AMQP.Queue.DeclareOk) Proxy.newProxyInstance(
                AMQP.Queue.DeclareOk.class.getClassLoader(),
                new Class<?>[]{AMQP.Queue.DeclareOk.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getQueue" -> queue;
                    case "getMessageCount" -> messageCount;
                    case "getConsumerCount" -> consumerCount;
                    default -> handleAmqpMethod(proxy, method, args, "Queue.DeclareOk");
                }
        );
    }

    private static Object handleAmqpMethod(Object proxy, Method method, Object[] args, String label) {
        Object objectResult = handleObjectMethod(proxy, method, args, label);
        if (objectResult != UNHANDLED) {
            return objectResult;
        }
        return switch (method.getName()) {
            case "protocolClassId", "protocolMethodId" -> 0;
            case "protocolMethodName" -> label;
            default -> defaultReturnValue(method.getReturnType());
        };
    }

    private static Object handleObjectMethod(Object proxy, Method method, Object[] args, String toStringValue) {
        if (method.getDeclaringClass() != Object.class) {
            return UNHANDLED;
        }
        return switch (method.getName()) {
            case "equals" -> args != null && args.length == 1 && proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> toStringValue;
            default -> null;
        };
    }

    private static String firstStringArg(Object[] args) {
        return args != null && args.length > 0 && args[0] instanceof String s ? s : null;
    }

    private static Object defaultReturnValue(Class<?> returnType) {
        if (!returnType.isPrimitive() || returnType == void.class) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == double.class) {
            return 0.0d;
        }
        if (returnType == float.class) {
            return 0.0f;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }

    /**
     * 方法推进函数接口
     */
    @FunctionalInterface
    public interface ProceedAction {

        /**
         * 执行目标消费者方法体
         *
         * @return 返回结果
         * @throws Throwable 业务异常
         */
        Object proceed() throws Throwable;
    }
}

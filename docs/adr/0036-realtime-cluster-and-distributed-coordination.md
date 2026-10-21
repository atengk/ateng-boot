# 36. 实时通信多节点集群协同、防反射广播与优雅停机排空架构决策 (Realtime Cluster Coordination, Reflection-Free Broadcast & Graceful Shutdown Architecture)

## 状态
`Accepted`

---

## 背景与痛点 (Context & Problem Statement)

在分布式与微服务架构下，实时通信底座从单机演进至多节点高可用集群时，面临着长连接物理拓扑与分布式状态协同的固有矛盾，传统开源方案与简单实现普遍存在以下严重痛点：

1. **长连接“节点孤岛”与跨节点消息投递盲区 (Node Isolation & Routing Blindspots)**：
   WebSocket 与 SSE 是面向特定物理节点的有状态长连接。当用户 A 连接在节点 1，而用户 B 连接在节点 2 时，单机门面无法将 A 发送的点对点或组播消息投递至节点 2 的 B；传统做法要么引入重型集中式代理（如专门的代理网关或自建 Socket.IO 集群），架构臃肿且难以与 Spring 体系融通。
2. **集群广播“回环风暴”与重复消费 (Echo Loop & Self-Reflection Storm)**：
   采用 Redis Pub/Sub 实现跨节点广播时，如果发布节点同样作为订阅者监听同一 Topic，本节点发出的消息会再次回传给本节点。若无严格的防反射机制，将导致本地长连接收到重复消息，甚至在多端同步或事件触发逻辑中引发死循环风暴。
3. **强依赖耦合与不可控膨胀 (Heavyweight Hard Coupling)**：
   许多实时通信框架强制绑定 Redisson、Kafka 或 RabbitMQ 作为必选中间件。对于单体轻量化部署或非集群场景，引入庞大的分布式依赖造成严重的依赖臃肿与运维负担，违背了框架“底座收敛、按需激活”的原则。
4. **离线消息黑洞与断线重连消息丢失 (Offline Message Black Hole)**：
   在移动端或弱网环境下，用户临时断线、后台休眠或尚未登录上线时，下发的关键通知和私聊消息会直接丢失。缺乏标准化、轻量可拔插的离线暂存契约，业务层不得不自行侵入编写消息落库与上线重发逻辑。
5. **发布上线“野蛮断开”与客户端惊群风暴 (Brutal Termination & Thundering Herd)**：
   在容器滚动更新或应用优雅停机时，底层长连接直接被进程 kill 强制截断，客户端感知为非正常 TCP 重置（Connection Reset）。若无序断开，成千上万的客户端将在同一瞬间向存活节点发起重连鉴权与握手，引发毁灭性的“惊群风暴”。
6. **安全治理割裂与越权长连接滞留 (Security Disconnection & Orphan Sessions)**：
   当用户在业务端被踢下线、修改密码或被管理员封禁账号时，HTTP REST 接口能立即通过 Sa-Token 鉴权拦截，但既有建立的底层长连接由于协议解耦依然保持存活，成为逃脱权限治理的“法外之徒”。

---

## 决策内容与架构拓扑 (Decision & Architecture)

基于纯原生、轻量级、防御性与零臃肿原则，我们在 `ateng-boot-realtime` Starter 中构建了 **去中心化多节点集群协同与分布式防线体系**：

```mermaid
flowchart TD
    subgraph ClientNodes["多端客户端接入层"]
        ClientA["客户端 A (User 1001)"]
        ClientB["客户端 B (User 1002)"]
    end

    subgraph ClusterNode1["实时通信集群节点 1 (NodeId: 8a3f...)"]
        LocalSession1["RealtimeSessionManager<br>(持有 User 1001 本地连接)"]
        Template1["RealtimeTemplate 统一门面"]
        Shutdown1["RealtimeShutdownHook<br>(SmartLifecycle Order: MIN+100)"]
    end

    subgraph ClusterNode2["实时通信集群节点 2 (NodeId: 9c2e...)"]
        LocalSession2["RealtimeSessionManager<br>(持有 User 1002 本地连接)"]
        Template2["RealtimeTemplate 统一门面"]
        Shutdown2["RealtimeShutdownHook"]
    end

    subgraph DistributedInfra["分布式协调与存储底座"]
        RedisPubSub["Redis Pub/Sub 广播总线<br>(Topic: ateng:realtime:cluster:broadcast)"]
        RedisMetrics["Redis 集群连接度量 Hash / Keys<br>(ateng:realtime:cluster:sessions)"]
        OfflineSPI["RealtimeOfflineMessageProvider SPI<br>(Redis / 数据库离线暂存)"]
    end

    subgraph SecurityDomain["权限与安全治理域"]
        SaTokenListener["RealtimeSaTokenListener<br>(监听 doKickout / doDisable 封禁)"]
    end

    ClientA -->|WebSocket / SSE| LocalSession1
    ClientB -->|WebSocket / SSE| LocalSession2

    Template1 -->|sendToUser('1002')| RedisPubSub
    RedisPubSub -->|广播 ClusterMessageWrapper| Template2
    Template2 -->|识别 sourceNodeId != 本地<br>本地路由投递| LocalSession2
    LocalSession2 --> ClientB

    SaTokenListener -.->|监听到封禁事件| Template1
    Template1 -.->|广播踢人帧 (4002 KICKOUT)| RedisPubSub

    Shutdown1 -->|1. 广播停机声明<br>2. 握手拦截拒绝<br>3. 下发 1012 重启帧<br>4. 优雅等待并强制断开| LocalSession1
```

---

## 核心设计与防线展开 (Core Design & Safeguards)

### 1. 运行时无侵入依赖探测与双模降级 (Runtime Redisson Probing & Dual-Mode Fallback)
- **零强绑定条件装配**：`ateng-boot-realtime` 将 Redisson 声明为 `optional` 依赖。通过 `@ConditionalOnClass(RedissonClient.class)` 与 `@ConditionalOnProperty(prefix = "ateng.boot.realtime.cluster.redis", name = "enabled", havingValue = "true")` 双重守卫；
- **单机内存平滑回退**：当应用未引入 Redisson 或未显式开启集群配置时，自动装配并注入 `LocalClusterBroadcastManager`。本地调用直接在内存中闭环执行，不产生任何网络或反射开销，实现“一套代码、单机集群无缝切换”。

### 2. 多节点 Pub/Sub 广播与 `sourceNodeId` 防反射风暴 (Reflection-Free Pub/Sub)
- **唯一节点身份生成**：每个集群节点启动时在内存中生成唯一且不可变的 `sourceNodeId`（基于无锁高效 UUID）；
- **信封协议封装**：所有跨节点广播行为（单播、组播、全员广播、租户广播、踢人指令）统一包装为 `ClusterMessageWrapper` 信封对象，携带 `sourceNodeId`、`clusterMessageType`、目标标识及消息载荷；
- **端点防反射丢弃防线**：各节点监听 Redis Pub/Sub Topic 时，在反序列化消息后的第一道前置卫语句中执行：
  ```java
  if (currentNodeId.equals(wrapper.getSourceNodeId())) {
      // 绝对丢弃自身发出的广播，杜绝死循环回环风暴与重复推送
      return;
  }
  ```

### 3. 去中心化路由与租户/分组组播 (Decentralized Multicast & Tenant Isolation)
- **无主架构 (Peer-to-Peer)**：摒弃中心化路由网关，各节点平等对等。单播或组播时，发送节点无需探知目标连接究竟驻留在哪一台物理机器上，直接通过 Redis Topic 扇出；
- **本地感知投递**：集群中各节点在收到订阅事件后，以极高吞吐查询本地 `RealtimeSessionManager` 多维索引：
  - `USER` 类型：若本地持有该用户的活跃会话，立即推送到该会话物理通道；
  - `GROUP` / `TENANT` / `BROADCAST` 类型：本地遍历对应分组或租户内的会话索引执行局部扇出；
  - 若本地无匹配连接，则快速失败返回，零资源浪费。

### 4. 离线消息 SPI 与确定性生命周期治理 (Pluggable Offline Message Storage SPI)
- **契约与持久化解耦**：定义 `RealtimeOfflineMessageProvider` 标准 SPI，提供 `saveOfflineMessage(userId, message, expireSeconds)`、`pullAndClearOfflineMessages(userId)`、`getOfflineMessageCount(userId)` 完备对称 API；
- **全集群离线探测与静默暂存**：当调用 `sendToUser` 时，若目标用户在全集群均不在线（或单机模式下本地不在线），底层自动感知并触发 SPI 暂存机制；
- **安全防爆仓治理**：通过配置 `ateng.boot.realtime.offline.max-messages-per-user`（默认 100 条）与 `expire-time`（默认 7 天），严格防御恶意刷量导致的存储无界膨胀与内存溢出。

### 5. Sa-Token 权限体系无缝桥接与实时踢退 (Sa-Token Event-Driven Kickout Bridge)
- **权限与长连接实时同步**：实现 Sa-Token 的 `SaTokenListener` 监听器，挂载至 Spring 容器；
- **全集群 4002 阻断**：当业务侧调用 `StpUtil.kickout(userId)`、`StpUtil.disable(userId, ...)` 或管理员封禁用户时，监听器自动拦截并向集群发起踢人广播：
  - 全集群各节点检索并强制切断该用户的所有物理长连接；
  - 发送标准 RFC 6455 状态码 `4002 (USER_KICKED_OUT)`，前端可据此精准展示“账号已被踢出或封禁”并阻断自动重连。

### 6. Spring SmartLifecycle 优雅停机四阶排空时序 (Four-Stage Graceful Shutdown)
为了彻底解决微服务发布上线时的惊群风暴与句柄泄露，实现 `RealtimeShutdownHook` 并挂载至 `SmartLifecycle`（Order 为 `Integer.MIN_VALUE + 100`，确保优先于 Web 容器与其他业务 Bean 销毁前排空）：
- **阶段一：停止握手接纳 (Handshake Rejection)**：内部状态标志置为 `shuttingDown = true`，握手拦截器 `AtengHandshakeInterceptor` 与 SSE 注册端点立即对所有新建立的长连接请求返回 503 Service Unavailable 或拒绝握手；
- **阶段二：集群度量注销 (Metrics Deregistration)**：从 Redis 集群活跃节点列表与度量统计中移除当前节点；
- **阶段三：定向发送 1012 重启指令 (Broadcast 1012 Restart Frame)**：遍历本地持有的所有活跃 WebSocket 与 SSE 连接，主动下发包含 `RealtimeCloseStatus.SERVICE_RESTART (1012)` 的关闭帧或事件，客户端收到 1012 状态码后启动带抖动的指数退避（Jittered Backoff）重新连接其他可用节点；
- **阶段四：确定性超时排空 (Deterministic Timeout Drain)**：配置 `ateng.boot.realtime.shutdown.grace-period`（默认 10s），在独立受管调度线程池中等待活跃连接自行关闭；若超过宽限期仍有未关闭的死链，执行最终强制物理切断并清理底层会话池。

---

## 收益与不变量保证 (Consequences & Invariants)

1. **零臃肿与环境韧性保证**：无论下游项目是否引入 Redis / Redisson，Starter 均可平滑启动；单机与集群模式对外暴露的 API 完全对称同构；
2. **高吞吐防反射风暴保证**：基于 `sourceNodeId` 的毫秒级本地丢弃，杜绝 Redis Pub/Sub 回环震荡，保障每秒数万级消息推送下的 CPU 与内存平稳；
3. **确定性优雅停机保证**：四阶优雅排空机制消除了滚动发布带来的长连接震荡与惊群效应，满足金融与工业级 7x24 小时高可用平滑升级标准；
4. **全链路安全一致性保证**：长连接彻底纳入应用统一的权限控制生命周期，解决离线消息丢失与封禁滞留两大核心安全隐患。

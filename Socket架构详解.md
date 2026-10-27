# secs4java8 底层 Socket 架构详解

## 1. 总体架构概览

secs4java8 的底层 Socket 通信架构采用**分层设计** + **接口隔离** + **观察者模式**，基于 Java NIO.2 的 `AsynchronousSocketChannel`（异步 I/O）实现高性能的 SECS/HSMS 半导体设备通信。

### 1.1 架构分层图

```
┌─────────────────────────────────────────────────────────────┐
│                    应用层 (Application)                       │
│          MoljmHost / 自定义 Host 或 Equipment                 │
├─────────────────────────────────────────────────────────────┤
│                    GEM 层 (SEMI-E30)                         │
│       Gem 接口 — S1F13, S1F17, S9F1 等标准消息处理             │
├─────────────────────────────────────────────────────────────┤
│                 通信器层 (Communicator)                       │
│   SecsCommunicator → HsmsSsCommunicator / HsmsGsCommunicator│
│                    → Secs1OnTcpIpCommunicator                │
├─────────────────────────────────────────────────────────────┤
│                  会话层 (Session)                             │
│     AbstractHsmsSession → AbstractHsmsSsSession              │
│                         → AbstractHsmsGsSession              │
├─────────────────────────────────────────────────────────────┤
│              Socket 通道门面层 (Channel Facade)                │
│   AbstractHsmsAsynchronousSocketChannelFacade                │
│   ├── taskSendMessage()   (发送线程)                          │
│   ├── taskReceiveMessage() (接收线程)                          │
│   └── taskLinktest()        (心跳线程)                         │
├─────────────────────────────────────────────────────────────┤
│              Java NIO.2 异步 I/O 层                           │
│   AsynchronousSocketChannel (TCP 连接)                        │
│   AsynchronousServerSocketChannel (TCP 监听, Passive 模式)    │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 支持的通信协议

| 协议 | 包名 | 标准 | Socket 类型 |
|------|------|------|------------|
| HSMS-SS (单会话) | `hsmsss` | SEMI-E37.1 | TCP/IP |
| HSMS-GS (多会话) | `hsmsgs` | SEMI-E37.2 | TCP/IP |
| SECS-I on TCP/IP | `secs1ontcpip` | SEMI-E4 over TCP | TCP/IP |
| SECS-I (串口) | `secs1` | SEMI-E4 | Serial (RS-232) |

---

## 2. 核心接口体系

### 2.1 接口继承关系

```
OpenAndCloseable (open/close 生命周期)
    │
    ├── SecsCommunicator (顶层通信接口)
    │       ├── extends SecsMessageReceiveObservable   (消息接收监听)
    │       ├── extends SecsMessagePassThroughObservable (消息透传监听)
    │       ├── extends SecsCommunicateStateDetectable  (通信状态检测)
    │       ├── extends SecsLogObservable               (日志监听)
    │       └── extends SecsGemAccessor                 (GEM 接口访问)
    │
    ├── HsmsSsCommunicator (HSMS-SS 通信器)
    │       ├── extends HsmsMessageReceiveObservable
    │       ├── extends HsmsCommunicateStateDetectable
    │       ├── extends HsmsLogObservable
    │       └── extends HsmsMessagePassThroughObservable
    │
    └── HsmsGsCommunicator (HSMS-GS 多会话通信器)
```

### 2.2 关键接口说明

**`OpenAndCloseable`** — 最基础的生命周期接口：

```java
public interface OpenAndCloseable extends Closeable {
    void open() throws IOException;     // 打开通信
    boolean isOpen();                    // 是否已打开
    boolean isClosed();                  // 是否已关闭
}
```

**`SecsCommunicateStateDetectable`** — 通信状态检测与等待：

```java
public interface SecsCommunicateStateDetectable {
    boolean isCommunicatable();                                    // 当前是否可通信
    void waitUntilCommunicatable() throws InterruptedException;     // 阻塞等待可通信
    void waitUntilNotCommunicatable() throws InterruptedException;  // 阻塞等待不可通信
    boolean addSecsCommunicatableStateChangeListener(...);          // 注册状态变化监听
}
```

**`SecsMessageSendable`** — 消息发送能力：

```java
public interface SecsMessageSendable {
    // 发送 Primary 消息并等待回复
    Optional<SecsMessage> send(int strm, int func, boolean wbit, Secs2 secs2) ...;
    // 发送 Reply 消息
    Optional<SecsMessage> send(SecsMessage primaryMsg, int strm, int func, boolean wbit, Secs2 secs2) ...;
    // 通过 SML 发送
    Optional<SecsMessage> send(SmlMessage sml) ...;
}
```

---

## 3. 实现类继承体系

### 3.1 基础实现类

```
AbstractBaseCommunicator
│  - ExecutorService (缓存线程池, 守护线程)
│  - open/close 状态管理 (BooleanProperty + BooleanCompution)
│  - executeInvokeAny() 并发任务执行
│
└── AbstractSecsCommunicator
    │  - AbstractSecsCommunicatorConfig (配置)
    │  - Gem (GEM 接口实现)
    │  - offerThrowableToLog() 日志
    │
    ├── AbstractHsmsSsCommunicator (HSMS-SS 基类)
    │   │  - AbstractHsmsSsSession (会话管理)
    │   │  - AbstractHsmsLogObserverFacade (日志观察者)
    │   │  - AbstractHsmsMessagePassThroughObserverFacade (消息透传观察者)
    │   │
    │   ├── AbstractHsmsSsActiveCommunicator  (Active 主动连接模式)
    │   └── AbstractHsmsSsPassiveCommunicator (Passive 被动监听模式)
    │
    ├── AbstractHsmsGsCommunicator (HSMS-GS 多会话基类)
    │   ├── AbstractHsmsGsActiveCommunicator
    │   └── AbstractHsmsGsPassiveCommunicator
    │
    └── AbstractSecs1Communicator (SECS-I 基类)
        └── AbstractSecs1OnTcpIpCommunicator (SECS-I over TCP/IP)
```

### 3.2 `AbstractBaseCommunicator` — 通信器基石

这是所有通信器的最底层实现，提供：

- **线程池管理**：使用 `Executors.newCachedThreadPool()` 创建守护线程池
- **生命周期状态机**：通过响应式属性 `BooleanProperty` + `BooleanCompution` 实现 `isOpen` / `isClosed` 状态
- **并发任务执行**：`executeInvokeAny()` 方法支持执行多个 `Callable` 并返回最先完成的结果

```java
public abstract class AbstractBaseCommunicator implements OpenAndCloseable {
    
    private final ExecutorService execServ = Executors.newCachedThreadPool(r -> {
        Thread th = new Thread(r);
        th.setDaemon(true);  // 守护线程，JVM退出时自动终止
        return th;
    });
    
    private final BooleanProperty opened = BooleanProperty.newInstance(false);
    private final BooleanProperty closed = BooleanProperty.newInstance(false);
    private final BooleanCompution isOpen = this.opened.and(this.closed.not());
    
    @Override
    public void open() throws IOException {
        synchronized (this.syncOpenClose) {
            if (this.closed.booleanValue()) throw new AlreadyClosedException();
            if (this.opened.booleanValue()) throw new AlreadyOpenedException();
            this.opened.setTrue();
        }
    }
    
    @Override
    public void close() throws IOException {
        // 1. 设置关闭标志
        // 2. 优雅关闭线程池: shutdown → awaitTermination → shutdownNow
    }
}
```

---

## 4. Socket 连接层详解

### 4.1 两种连接模式

**`HsmsConnectionMode`** 枚举定义了两种角色：

| 模式 | 角色 | Socket 行为 | 典型场景 |
|------|------|------------|---------|
| `ACTIVE` | 主动方 (Host) | `AsynchronousSocketChannel.connect()` 主动发起连接 | Host 端 |
| `PASSIVE` | 被动方 (Equipment) | `AsynchronousServerSocketChannel.bind()` + `accept()` 监听等待 | 设备端 |

### 4.2 Active 模式连接流程

`AbstractHsmsSsActiveCommunicator` 的连接逻辑：

```java
@Override
public void open() throws IOException {
    super.open();
    // 在后台线程中反复尝试连接
    this.executorService().execute(() -> {
        try {
            this.openActive();  // 第一次尝试
            while (!this.isClosed()) {
                this.config.timeout().t5().sleep();  // 等待 T5 时间
                this.openActive();                    // 重连
            }
        } catch (InterruptedException ignore) {}
    });
}

private void openActive() throws InterruptedException {
    try (AsynchronousSocketChannel channel = AsynchronousSocketChannel.open()) {
        
        SocketAddress socketAddr = config.socketAddress().optional()
            .orElseThrow(UnsetSocketAddressException::new);
        
        // 异步连接，使用 CompletionHandler 回调
        channel.connect(socketAddr, null, new CompletionHandler<Void, Void>() {
            @Override
            public void completed(Void none, Void attachment) {
                // 连接成功 → 执行 completionAction
                // 包含: 创建 ChannelFacade → SELECT.REQ → 消息循环
            }
            @Override
            public void failed(Throwable t, Void attachment) {
                // 连接失败 → 记录日志，通知等待线程
            }
        });
        
        // 等待连接完成或失败
        synchronized (channel) { channel.wait(); }
    }
}
```

**Active 模式时序**：
```
Host (Active)                              Equipment (Passive)
    │                                           │
    │──── TCP connect ──────────────────────────>│
    │<─── TCP accept ───────────────────────────│
    │                                           │
    │──── SELECT.REQ (S=0,P=1) ────────────────>│
    │<─── SELECT.RSP (S=0,P=2, SUCCESS) ───────│
    │                                           │
    │     [SELECTED 状态, 可通信]                  │
    │                                           │
    │──── DATA: S1F13 W ───────────────────────>│
    │<─── DATA: S1F14 ─────────────────────────│
    │                                           │
    │──── SEPARATE.REQ ────────────────────────>│
    │     [连接断开]                              │
    │──── 等待 T5 ────> 重新 connect             │
```

### 4.3 Passive 模式连接流程

`AbstractHsmsSsPassiveCommunicator` 使用 `AsynchronousServerSocketChannel` 监听：

```java
private void openPassive() throws InterruptedException {
    try (AsynchronousServerSocketChannel server = AsynchronousServerSocketChannel.open()) {
        passiveAccepting(server);   // 绑定地址并开始接受连接
        synchronized (server) { server.wait(); }  // 等待直到服务器关闭
    }
}

private void passiveAccepting(AsynchronousServerSocketChannel server) {
    SocketAddress addr = config.socketAddress().optional()
        .orElseThrow(UnsetSocketAddressException::new);
    
    server.bind(addr);  // 绑定端口
    
    // 异步接受连接
    server.accept(null, new CompletionHandler<AsynchronousSocketChannel, Void>() {
        @Override
        public void completed(AsynchronousSocketChannel channel, Void attachment) {
            server.accept(attachment, this);  // 继续接受下一个连接
            
            // 处理新连接: completionAction → mainTask
            // mainTask: 等待 SELECT.REQ → 回复 SELECT.RSP → 进入消息循环
        }
        @Override
        public void failed(Throwable t, Void attachment) {
            // 服务器关闭时触发
        }
    });
}
```

**Passive 模式主任务**：
```java
private void mainTask(HsmsSsAsynchronousSocketChannelFacade asyncChannel) {
    // 1. 等待 SELECT.REQ (超时 T7)
    AbstractHsmsMessage initiateMsg = asyncChannel.pollPrimaryHsmsMessage(config.timeout().t7());
    
    // 2. 验证消息类型
    switch (initiateMsg.messageType()) {
    case SELECT_REQ:
        // 3. 设置通道并回复 SELECT.RSP SUCCESS
        session.setChannel(asyncChannel);
        asyncChannel.send(buildSelectResponse(initiateMsg, HsmsMessageSelectStatus.SUCCESS));
        
        // 4. 进入消息接收循环
        for (;;) {
            AbstractHsmsMessage primaryMsg = asyncChannel.takePrimaryHsmsMessage();
            switch (primaryMsg.messageType()) {
            case DATA:          // 数据消息 → 分发给消息接收观察者
            case LINKTEST_REQ:  // 心跳请求 → 回复 LINKTEST_RSP
            case SEPARATE_REQ:  // 分离请求 → 退出循环
            case SELECT_REQ:    // 重复选择 → 回复 ACTIVED
            ...
            }
        }
    }
}
```

---

## 5. Socket 通道门面 (Channel Facade)

### 5.1 核心类: `AbstractHsmsAsynchronousSocketChannelFacade`

这是整个 Socket 通信的**核心引擎**，封装了 `AsynchronousSocketChannel`，内部运行三个并发任务：

```java
public abstract class AbstractHsmsAsynchronousSocketChannelFacade implements Closeable {
    
    private final ExecutorService executorService = Executors.newCachedThreadPool(...);
    private final AsynchronousSocketChannel channel;
    
    public AbstractHsmsAsynchronousSocketChannelFacade(config, channel) {
        // 构造时立即启动三个后台任务
        executorService.execute(() -> this.taskSendMessage());    // 发送任务
        executorService.execute(() -> this.taskReceiveMessage()); // 接收任务
        executorService.execute(() -> this.taskLinktest());       // 心跳任务
    }
}
```

### 5.2 三线程模型

```
┌─────────────────────────────────────────────────────┐
│     AbstractHsmsAsynchronousSocketChannelFacade      │
│                                                      │
│  ┌──────────────┐  ┌──────────────┐  ┌────────────┐ │
│  │ 发送线程      │  │ 接收线程      │  │ 心跳线程    │ │
│  │ taskSend     │  │ taskReceive  │  │ taskLink   │ │
│  │ Message()    │  │ Message()    │  │ test()     │ │
│  └──────┬───────┘  └──────┬───────┘  └─────┬──────┘ │
│         │                 │                 │        │
│         ▼                 ▼                 ▼        │
│  ┌──────────────────────────────────────────────┐   │
│  │        AsynchronousSocketChannel              │   │
│  │            (Java NIO.2 TCP)                   │   │
│  └──────────────────────────────────────────────┘   │
│                                                      │
│  ┌──────────────────────────────────────────────┐   │
│  │  sendMsgQueue (BlockingQueue)                 │   │
│  │  transactionMap (HashMap<Integer, Pack>)      │   │
│  │  primaryMsgQueue (BlockingQueue)              │   │
│  └──────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────┘
```

### 5.3 发送流程 (`taskSendMessage`)

```java
private void taskSendMessage() throws InterruptedException {
    for (;;) {
        // 1. 从发送队列取出消息包
        SendAndReceiveMsgPack pack = this.sendMsgQueue.take();
        
        // 2. 检查是否已关闭
        if (this.shutdownProp.booleanValue()) {
            this.notifySendResultFailed(pack, new HsmChannelAlreadyShutdownException(msg));
            return;
        }
        
        // 3. 通知"正在发送"事件 (用于透传日志)
        this.notifyTrySendHsmsMessagePassThrough(pack.sendMsg);
        
        // 4. 构建 HSMS 帧: [4字节长度][10字节Header][Body]
        //    如果消息 > sendBufferSize(64KB), 分块发送
        long len = bodyLength + 10;  // header + body
        long msglen = len + 4;       // 总长度(含长度字段本身)
        
        if (msglen > prototypeDefaultSendBufferSize()) {
            // 分块发送: 先发 header, 再逐块发 body
            sendByteBuffer(headerBuffer);
            for (byte[] bs : bodyBytesList) { sendByteBuffer(bodyBuffer); }
        } else {
            // 一次发送完整消息
            sendByteBuffer(completeBuffer);
        }
        
        // 5. 通知发送成功
        this.notifySendResultSuccess(pack);
        
        // 6. 通知"已发送"事件
        this.notifySendedHsmsMessagePassThrough(pack.sendMsg);
    }
}
```

**HSMS 帧格式**：
```
┌──────────┬────────────────────────┬─────────────┐
│ 4 bytes  │     10 bytes Header    │  N bytes    │
│ 消息长度  │  (见下方 Header 结构)    │  SECS-II    │
│ (big-end)│                        │   Body      │
└──────────┴────────────────────────┴─────────────┘

10 字节 Header 结构:
┌──────────┬──────────┬──────┬──────┬──────────────┐
│ 2 bytes  │ 2 bytes  │ 1B   │ 1B   │   4 bytes    │
│ Session  │ Stream/  │ Wbit │ Ptype│  System      │
│ ID       │ Function │      │ Stype│   Bytes      │
└──────────┴──────────┴──────┴──────┴──────────────┘
```

### 5.4 接收流程 (`taskReceiveMessage`)

```java
private void taskReceiveMessage() throws InterruptedException {
    for (;;) {
        // 1. 读取 4 字节消息长度
        ByteBuffer lengthBuffer = ByteBuffer.allocate(8); // 前4字节填0
        readingBufferFirst(lengthBuffer);  // 首次读取(无超时)
        while (lengthBuffer.hasRemaining()) { readingBuffer(lengthBuffer); }
        long msgLength = lengthBuffer.getLong();
        
        // 2. 读取 10 字节 Header
        ByteBuffer headerBuffer = ByteBuffer.allocate(10);
        while (headerBuffer.hasRemaining()) { readingBuffer(headerBuffer); }
        
        // 3. 读取 Body (分块读取, 每块最大 1024 字节)
        List<ByteBuffer> bodyBuffers = new ArrayList<>();
        for (long rem = msgLength - 10L; rem > 0L;) {
            int size = (int)Math.min(rem, 1024L);
            ByteBuffer bf = ByteBuffer.allocate(size);
            while (bf.hasRemaining()) { rem -= readingBuffer(bf); }
            bodyBuffers.add(bf);
        }
        
        // 4. 构建消息对象
        AbstractHsmsMessage msg = AbstractHsmsMessageBuilder.buildFromBytes(header, body);
        
        // 5. 事务匹配: 检查是否是某个等待请求的回复
        AbstractHsmsMessage r = this.putMessageToTransaction(msg);
        if (r != null) {
            // 不是回复消息 → 作为 Primary 消息放入队列
            this.primaryMsgQueue.put(r);
        }
        
        // 6. 通知透传监听
        this.notifyReceiveHsmsMessagePassThrough(msg);
    }
}
```

### 5.5 事务匹配机制 (Transaction Map)

HSMS 使用 **System Bytes**（Header 的后 4 字节）实现请求-回复匹配：

```java
// 发送端: 注册事务
private final Map<Integer, SendAndReceiveMsgPack> transactionMap = new HashMap<>();

public Optional<HsmsMessage> send(HsmsMessage msg) {
    Integer key = getSystemBytesKey(msg);  // 从 Header[6..9] 提取 key
    
    TimeoutGettable timeout = getTimeout(msg);  // DATA+W→T3, 控制消息→T6
    SendAndReceiveMsgPack pack = new SendAndReceiveMsgPack(msg);
    
    if (timeout != null) {
        transactionMap.put(key, pack);  // 注册等待回复的事务
    }
    sendMsgQueue.put(pack);             // 放入发送队列
    waitUntilSended(pack);              // 等待发送完成
    
    if (timeout == null) {
        return Optional.empty();        // 不需要回复的消息
    } else {
        return Optional.of(waitUntilReceived(pack, timeout));  // 等待回复
    }
}

// 接收端: 匹配事务
private AbstractHsmsMessage putMessageToTransaction(AbstractHsmsMessage msg) {
    Integer key = getSystemBytesKey(msg);
    SendAndReceiveMsgPack pack = transactionMap.get(key);
    if (pack == null) {
        return msg;  // 无匹配事务 → 作为 Primary 消息
    } else {
        pack.recvMsg = msg;             // 匹配成功 → 设置回复消息
        transactionMap.notifyAll();      // 唤醒等待线程
        return null;                     // 不需要上层处理
    }
}
```

### 5.6 心跳机制 (`taskLinktest`)

```java
private void taskLinktest() throws InterruptedException {
    for (;;) {
        synchronized (linktestResetFlag) {
            linktestResetFlag.setFalse();
            if (config.doLinktest().booleanValue()) {
                linktestResetFlag.wait();  // 等待超时或重置
            } else {
                config.linktestTime().wait(linktestResetFlag);
            }
            if (linktestResetFlag.booleanValue()) continue;  // 有收发活动, 重置
            if (!config.doLinktest().booleanValue()) continue;
        }
        // 超时未收发 → 发送 LINKTEST 请求
        if (!this.linktest()) {
            this.shutdown();  // 心跳失败 → 关闭通道
        }
    }
}

public boolean linktest() throws InterruptedException {
    boolean result = this.send(buildLinktestHsmsMessage())  // LINKTEST.REQ
        .filter(m -> m.messageType() == HsmsMessageType.LINKTEST_RSP)
        .isPresent();
    if (!result) this.shutdown();
    return result;
}
```

**心跳重置**：每次 `sendByteBuffer()` 和 `readingBuffer()` 时都会调用 `linktestReset()`，表示链路活跃。

### 5.7 半包 / 粘包处理

TCP 是**面向字节流**的协议，不保留消息边界。一次 `read()` 可能只读到半条消息（**半包**），也可能读到多条消息粘在一起（**粘包**）。secs4java8 通过 HSMS 协议的 **4 字节长度前缀帧格式** 彻底解决了这个问题。

#### 5.7.1 帧格式设计

每条 HSMS 消息在 TCP 线路上的格式是固定的：

```
┌────────────────┬──────────────────┬──────────────────┐
│  4 bytes       │  10 bytes        │  N bytes         │
│  消息总长度 L   │  Header          │  SECS-II Body    │
│  (big-endian)  │  (固定10字节)     │  (L - 10 字节)   │
└────────────────┴──────────────────┴──────────────────┘
                   ←─────── L 字节 ─────────→
```

- 前 4 字节是 **大端序 (big-endian) 的无符号整数**，表示后续数据的总字节数（Header 10 字节 + Body N 字节）
- 这个长度字段就是天然的**消息分隔符**，接收端据此可以精确知道一条完整消息的边界

#### 5.7.2 发送端：确保完整写入

发送端通过 `sendByteBuffer()` 的 **while 循环** 保证 ByteBuffer 中的所有字节都被写入 Socket，不会因为单次 `write()` 未写完而丢失数据：

```java
// 确保 buffer 中所有字节都写入 TCP
private void sendByteBuffer(ByteBuffer buffer) throws ... {
    while (buffer.hasRemaining()) {          // ← 循环直到全部写完
        final Future<Integer> future = this.channel.write(buffer);
        int w = future.get().intValue();
        if (w <= 0) {
            throw new HsmsDetectTerminateException();  // 连接已断
        }
    }
    this.linktestReset();  // 写完后重置心跳计时器
}
```

发送时根据消息大小分两种策略：

```java
// 策略一：小消息 (≤ 64KB) — 拼装成一个完整的 ByteBuffer 一次发送
final ByteBuffer buffer = ByteBuffer.allocate((int)msglen);  // 4 + 10 + N
buffer.put((byte)(len >> 24));   // 长度第1字节
buffer.put((byte)(len >> 16));   // 长度第2字节
buffer.put((byte)(len >>  8));   // 长度第3字节
buffer.put((byte)(len      ));   // 长度第4字节
buffer.put(header10Bytes);       // 10字节 Header
for (byte[] bs : bodyBytesList) {
    buffer.put(bs);              // Body
}
buffer.flip();
sendByteBuffer(buffer);          // 循环写直到全部发出

// 策略二：大消息 (> 64KB) — 先发 Header，再逐块发 Body
// 先发 14 字节 (4字节长度 + 10字节Header)
ByteBuffer headerBuf = ByteBuffer.allocate(14);
headerBuf.put(length4Bytes);
headerBuf.put(header10Bytes);
headerBuf.flip();
sendByteBuffer(headerBuf);

// 再逐块发 Body (每块最大 1024 字节)
for (byte[] bs : bodyBytesList) {
    ByteBuffer bodyBuf = ByteBuffer.allocate(bs.length);
    bodyBuf.put(bs).flip();
    sendByteBuffer(bodyBuf);     // 每块都循环写直到全部发出
}
```

**关键点**：无论哪种策略，每次 `sendByteBuffer()` 都会通过 `while(hasRemaining())` 循环确保 buffer 中的**每一个字节**都被写入 TCP 通道，不会少写。

#### 5.7.3 接收端：按长度精确读取，解决半包

接收端是处理半包/粘包的**核心**。`taskReceiveMessage()` 采用**分阶段、定长度读取**的策略：

```
阶段一: 读 8 字节长度缓冲区 (前4字节填0, 后4字节是真正的消息长度)
阶段二: 读 10 字节 Header
阶段三: 读 Body (按 msgLength - 10 分块读取, 每块最大 1024 字节)
```

每个阶段都用 `while (buffer.hasRemaining())` 循环确保读满整个 ByteBuffer：

```java
private void taskReceiveMessage() {
    // 复用缓冲区 (每次循环 clear 重用)
    final ByteBuffer lengthBuffer = ByteBuffer.allocate(8);  // 8字节: 4字节0 + 4字节长度
    final ByteBuffer headerBuffer = ByteBuffer.allocate(10); // 10字节 Header
    final List<ByteBuffer> bodyBuffers = new ArrayList<>();  // Body 分块列表
    
    for (;;) {
        // ═══════════════════════════════════════════════
        // 阶段一: 读取消息长度 (8字节)
        // ═══════════════════════════════════════════════
        lengthBuffer.clear();
        lengthBuffer.put(length4Zero);  // 前4字节填0 (凑成8字节long)
        
        // 首次读取 — 无超时 (两条消息之间可能间隔很久)
        readingBufferFirst(lengthBuffer);  // channel.read(buffer).get()
        this.linktestReset();
        
        // 如果首次只读到了部分字节 (半包), 继续读到满
        while (lengthBuffer.hasRemaining()) {
            readingBuffer(lengthBuffer);   // channel.read(buffer).get(T8)
        }
        // 此时 lengthBuffer 已读满 8 字节
        
        lengthBuffer.flip();
        long msgLength = lengthBuffer.getLong();  // 解析出消息总长度
        if (msgLength < 10L) {
            throw new HsmsMessageLengthBytesLowerThanTenException(msgLength);
        }
        
        // ═══════════════════════════════════════════════
        // 阶段二: 读取 10 字节 Header
        // ═══════════════════════════════════════════════
        headerBuffer.clear();
        while (headerBuffer.hasRemaining()) {
            readingBuffer(headerBuffer);   // 每次 read 可能只读到部分, 循环直到读满
        }
        // 此时 headerBuffer 已读满 10 字节
        
        // ═══════════════════════════════════════════════
        // 阶段三: 读取 Body (按 msgLength - 10 精确读取)
        // ═══════════════════════════════════════════════
        bodyBuffers.clear();
        for (long rem = msgLength - 10L; rem > 0L;) {
            long bodySize = 1024L;  // 每块最大 1024 字节
            int size = (int)(rem > bodySize ? bodySize : rem);
            ByteBuffer bf = ByteBuffer.allocate(size);
            
            while (bf.hasRemaining()) {
                rem -= (long)(readingBuffer(bf));  // 循环直到这块读满
            }
            bodyBuffers.add(bf);
            this.linktestReset();  // 每读完一块, 重置心跳
        }
        // 此时 Body 全部读完, 总字节数 = msgLength - 10
        
        // ═══════════════════════════════════════════════
        // 阶段四: 拼装完整消息
        // ═══════════════════════════════════════════════
        headerBuffer.flip();
        byte[] header10Bytes = new byte[10];
        headerBuffer.get(header10Bytes);
        
        List<byte[]> bodyBytesList = bodyBuffers.stream()
            .map(bf -> { bf.flip(); byte[] bs = new byte[bf.remaining()]; bf.get(bs); return bs; })
            .collect(Collectors.toList());
        
        AbstractHsmsMessage msg = AbstractHsmsMessageBuilder.buildFromBytes(header10Bytes, bodyBytesList);
        // ... 事务匹配 / 放入 primaryMsgQueue ...
    }
}
```

#### 5.7.4 半包场景图解

下面用具体例子说明接收端如何处理 TCP 的各种拆包情况：

**场景一：一条消息被拆成多次 TCP 到达（半包）**

假设发送端发了一条 24 字节的消息 (长度字段=20, 即 4+10+6)：

```
发送端写入 TCP 的完整数据 (24字节):
[00][00][00][14] [Header 10字节...] [Body 6字节...]

TCP 可能拆成多次到达:

第1次 read() → 读到 3 字节:  [00][00][00]
  → lengthBuffer.hasRemaining() = true (还差5字节)
  → 第2次 readingBuffer() → 读到 2 字节: [00][14]
  → lengthBuffer 读满 8 字节 → 解析出 msgLength = 20
  
第2次 read() → 读到 6 字节:  [Header前6字节]
  → headerBuffer.hasRemaining() = true (还差4字节)
  → 第2次 readingBuffer() → 读到 4 字节: [Header后4字节]
  → headerBuffer 读满 10 字节 → Header 完整
  
第3次 read() → 读到 6 字节:  [Body全部]
  → bodyBuffer 读满 → Body 完整
  
→ 拼装完整消息 ✓
```

**场景二：多条消息连续到达（粘包）**

```
TCP 一次 read() 读到了两条消息的数据:

假设消息A: 长度=14 (4+10+0, 控制消息无Body)
假设消息B: 长度=30 (4+10+16)

发送端写入: [消息A 18字节][消息B 34字节] = 52字节

接收端处理:
① 读 lengthBuffer (8字节) → 得到消息A的长度 18
② 读 headerBuffer (10字节) → 从已收到的数据中读
③ Body = 18 - 10 = 8 字节 → 读完消息A
④ 回到 for(;;) 循环顶部
⑤ 读 lengthBuffer (8字节) → 得到消息B的长度 30
⑥ 读 headerBuffer (10字节)
⑦ Body 分块读: 30 - 10 = 20 字节 → 读完消息B

→ 两条消息都被正确解析 ✓
```

**关键点**：由于每条消息都有 **4 字节长度前缀**，接收端总是先知道「接下来要读多少字节」，因此：
- 读少了 → `while(hasRemaining())` 继续读（处理半包）
- 读多了 → 多余的字节留在 TCP 缓冲区，下一轮循环处理（处理粘包）
- `AsynchronousSocketChannel.read(ByteBuffer)` 只会把 buffer 填满，不会多读

#### 5.7.5 核心机制总结

```
┌────────────────────────────────────────────────────────────────┐
│                    半包 / 粘包 解决方案                          │
│                                                                │
│  发送端:                                                        │
│  ┌──────────────────────────────────────────────────────┐      │
│  │  while (buffer.hasRemaining()) {                     │      │
│  │      channel.write(buffer);  // 循环写直到全部写完     │      │
│  │  }                                                   │      │
│  └──────────────────────────────────────────────────────┘      │
│                                                                │
│  接收端:                                                        │
│  ┌──────────────────────────────────────────────────────┐      │
│  │  1. 读 4字节长度前缀 → 知道后续有多少字节              │      │
│  │  2. while(hasRemaining()) 循环读满 Header (10字节)    │      │
│  │  3. for(rem > 0) 循环分块读满 Body (L-10 字节)       │      │
│  │  4. 拼装完整消息 → 回到步骤1处理下一条                  │      │
│  └──────────────────────────────────────────────────────┘      │
│                                                                │
│  设计原理:                                                      │
│  • 4字节大端长度前缀 = 天然的消息边界分隔符                       │
│  • ByteBuffer.hasRemaining() + while 循环 = 半包拼接           │
│  • 固定长度读取 + 外层 for(;;) 循环 = 粘包拆分                  │
│  • T8 超时保护 = 半包卡住时不会无限等待                           │
│  • readingBufferFirst 无超时 = 消息间隔不受限                    │
└────────────────────────────────────────────────────────────────┘
```

| 问题 | 解决方案 | 关键代码 |
|------|---------|----------|
| **半包** (一次 read 只读到部分数据) | `while(buffer.hasRemaining())` 循环读取，直到 ByteBuffer 填满 | `readingBuffer()` + T8 超时保护 |
| **粘包** (一次 read 读到多条消息) | 按长度字段精确读取一条完整消息后，外层 `for(;;)` 自动处理下一条 | `taskReceiveMessage()` 的 `for(;;)` 循环 |
| **发送不完整** (一次 write 未写完) | `while(buffer.hasRemaining())` 循环写入，直到 ByteBuffer 清空 | `sendByteBuffer()` |
| **消息间隔** (两条消息之间长时间无数据) | 首次读取 `readingBufferFirst()` 不设超时 | `future.get()` 无超时参数 |
| **传输中断** (读到一半对方断开) | `readingBuffer()` 中 `read() < 0` 检测连接终止 | `HsmsDetectTerminateException` |

---

## 6. 会话管理层 (Session)

### 6.1 `AbstractHsmsSession` 核心职责

```java
public abstract class AbstractHsmsSession implements HsmsSession {
    
    // 通道属性 (响应式)
    private final ObjectProperty<AbstractHsmsAsynchronousSocketChannelFacade> channelProp;
    
    // 通道变化 → 自动更新 HSMS 通信状态
    channelProp.addChangeListener(channel -> {
        if (channel == null)
            state = HsmsCommunicateState.NOT_CONNECTED;
        else
            state = HsmsCommunicateState.NOT_SELECTED;
    });
    
    // 消息接收观察者
    private final AbstractHsmsMessageReceiveObserverFacade hsmsMsgRecvObserver;
    // 通信状态观察者
    private final AbstractHsmsCommunicateStateObserverFacade hsmsCommStateObserver;
    
    // 设置通道 (线程安全, CAS 语义)
    public boolean setChannel(AbstractHsmsAsynchronousSocketChannelFacade channel) {
        synchronized (channelProp) {
            if (channelProp.isNull()) { channelProp.set(channel); return true; }
            else return false;
        }
    }
    
    // 发送消息 (委托给 Channel Facade)
    public Optional<HsmsMessage> send(HsmsMessage msg) {
        AbstractHsmsAsynchronousSocketChannelFacade asyncChannel = getChannel();
        if (asyncChannel == null) throw new HsmsNotConnectedException();
        return asyncChannel.send(msg);
    }
}
```

### 6.2 HSMS 通信状态机

```
                    setChannel(null)
                 ┌──────────────────────┐
                 │                      ▼
         ┌───────────────┐    ┌─────────────────┐
         │ NOT_CONNECTED  │    │  NOT_SELECTED    │
         └───────────────┘    └─────────────────┘
                 ▲                      │
                 │ setChannel(null)     │ select() 成功
                 │                      ▼
                 │              ┌─────────────────┐
                 └──────────────│    SELECTED      │
                   separate()   │   (可通信状态)     │
                   或连接断开     └─────────────────┘
```

`HsmsCommunicateState` 枚举：

| 状态 | 含义 | `communicatable()` |
|------|------|-------------------|
| `NOT_CONNECTED` | TCP 未连接 | `false` |
| `NOT_SELECTED` | TCP 已连接但未完成 SELECT 握手 | `false` |
| `SELECTED` | 握手完成, 可正常收发数据消息 | `true` |

### 6.3 HSMS-SS Session 控制消息

```java
// SELECT.REQ — Active 端发起
public boolean select() throws InterruptedException {
    HsmsMessage msg = msgBuilder().buildSelectRequest(this);
    return this.send(msg)
        .filter(m -> m.messageType() == HsmsMessageType.SELECT_RSP)
        .map(HsmsMessageSelectStatus::get)
        .filter(s -> s == SUCCESS || s == ACTIVED)
        .isPresent();
}

// SEPARATE.REQ — 主动断开
public boolean separate() throws InterruptedException {
    HsmsMessage msg = msgBuilder().buildSeparateRequest(this);
    this.send(msg);
    return true;
}
```

---

## 7. 配置体系

### 7.1 配置类继承关系

```
AbstractSecsCommunicatorConfig
│  - SecsTimeout (T1~T8)
│  - BooleanProperty isEquip
│  - GemConfig (mdln, softrev 等)
│  - StringProperty name, logSubjectHeader
│
└── AbstractHsmsCommunicatorConfig
    │  - ObjectProperty<HsmsConnectionMode> connectionMode
    │  - BooleanProperty doLinktest + TimeoutProperty linktestTime
    │  - BooleanProperty doRebindIfPassive + TimeoutProperty rebindIfPassiveTime
    │
    ├── HsmsSsCommunicatorConfig
    │   - IntegerProperty sessionId
    │   - ObjectProperty<SocketAddress> socketAddr
    │
    └── HsmsGsCommunicatorConfig
        - List<Integer> sessionIds (多会话)
        - ObjectProperty<SocketAddress> socketAddr
```

### 7.2 超时机制详解 (T3 / T5 / T6 / T7 / T8)

HSMS 协议定义了 8 个超时定时器（T1~T8），其中 **T3、T5、T6、T7、T8** 直接参与 Socket 层的通信控制。下面逐一剖析每个超时的**触发场景、代码路径、超时后的具体行为**。

默认值定义在 `SecsTimeoutImpl` 中：

```java
private final TimeoutProperty t3 = TimeoutProperty.newInstance(45.0F);  // 45秒
private final TimeoutProperty t5 = TimeoutProperty.newInstance(10.0F);  // 10秒
private final TimeoutProperty t6 = TimeoutProperty.newInstance( 5.0F);  // 5秒
private final TimeoutProperty t7 = TimeoutProperty.newInstance(10.0F);  // 10秒
private final TimeoutProperty t8 = TimeoutProperty.newInstance( 6.0F);  // 6秒
```

---

#### 7.2.1 T3 — 等待数据消息回复超时 (Transaction Timeout)

**适用场景**：发送 **DATA 类型消息且 W-bit=1** 时，等待对方回复对应的 Reply 消息。

**触发条件**：
- 发送方通过 `send(strm, func, true/*wbit*/, secs2)` 发送了一条带 W-bit 的数据消息
- 在 T3 时间内未收到 System Bytes 匹配的回复消息

**代码路径**：

```java
// 1. send() 方法根据消息类型选择超时
private TimeoutGettable getTimeout(HsmsMessage msg) {
    switch (msg.messageType()) {
    case DATA:
        if (msg.wbit()) {
            return this.config.timeout().t3();  // ← DATA + W=1 使用 T3
        }
        break;
    // ...
    }
    return null;
}

// 2. 发送后进入等待回复
public Optional<HsmsMessage> send(HsmsMessage msg) {
    // 将事务注册到 transactionMap
    transactionMap.put(key, pack);
    sendMsgQueue.put(pack);           // 放入发送队列
    waitUntilSended(pack);            // 等待发送完成
    return Optional.of(
        waitUntilReceived(pack, timeout)  // ← 用 T3 等待回复
    );
}

// 3. waitUntilReceived 内部用 Future.get(T3) 等待
private AbstractHsmsMessage waitUntilReceived(pack, timeout) {
    Future<AbstractHsmsMessage> future = executorService.submit(() -> {
        synchronized (transactionMap) {
            for (;;) {
                if (pack.recvMsg != null) return pack.recvMsg;  // 收到回复
                transactionMap.wait();  // 等待接收线程唤醒
            }
        }
    });
    return timeout.futureGet(future);  // ← T3 超时点
}
```

**超时后行为**：

```
T3 超时
  │
  ├── 1. 取消等待 Future:  future.cancel(true)
  ├── 2. 从事务 map 移除:  transactionMap.remove(key)  (在 finally 块中)
  ├── 3. 抛出异常:         throw new HsmsTimeoutT3Exception(pack.sendMsg)
  │       └── 继承链: HsmsTimeoutT3Exception
  │                   → HsmsWaitReplyMessageException
  │                   → SecsWaitReplyMessageException
  │                   → SecsException
  ├── 4. 异常传播到调用方:  comm.send(1, 13, true, secs2) 抛出 SecsWaitReplyMessageException
  │
  └── ⚠️ 注意: T3 超时 **不会** 导致连接断开！
              仅仅是本次发送-等待回复失败，连接仍然保持
```

**典型场景**：Host 发送 S1F13（W=1）给 Equipment，Equipment 应在 45 秒内回复 S1F14。如果超时：
- Host 端 `comm.send(1, 13, true, secs2)` 抛出 `HsmsTimeoutT3Exception`
- 应用层可以 catch 并做重试或报警处理
- TCP 连接不受影响，仍然处于 SELECTED 状态

---

#### 7.2.2 T5 — 连接重试间隔 (Connect/Bind Interval)

**适用场景**：
- **Active 模式**：TCP 连接断开或连接失败后，等待 T5 时间再重新发起 `connect()`
- **Passive 模式**：当 `doRebindIfPassive=true` 时，bind 失败或连接断开后，等待 T5 时间再重新 `bind()`

**触发条件**：每次连接循环结束时（连接断开、被拒绝、select 失败等）

**代码路径 — Active 模式**：

```java
// AbstractHsmsSsActiveCommunicator.open()
this.executorService().execute(() -> {
    try {
        this.openActive();           // 第一次尝试连接
        while (!this.isClosed()) {
            this.config.timeout().t5().sleep();   // ← T5 等待
            this.openActive();                     // ← 重新连接
        }
    } catch (InterruptedException ignore) {}
});
```

**代码路径 — Passive 模式**：

```java
// AbstractHsmsSsPassiveCommunicator.open()
this.executorService().execute(() -> {
    try {
        this.openPassive();          // 第一次 bind + accept
        while (config.doRebindIfPassive().booleanValue() && !this.isClosed()) {
            this.config.timeout().t5().sleep();   // ← T5 等待
            this.openPassive();                    // ← 重新 bind
        }
    } catch (InterruptedException ignore) {}
});
```

**超时后行为**：

```
T5 超时（实际是 sleep 结束）
  │
  ├── Active:  重新执行 openActive() → AsynchronousSocketChannel.connect()
  │            如果连接成功 → 进入 completionAction → SELECT 握手
  │            如果连接失败 → 记录日志 → 再次 sleep T5 → 循环重试
  │
  └── Passive: 重新执行 openPassive() → AsynchronousServerSocketChannel.bind()
               如果 bind 成功 → accept() 等待新连接
               如果 bind 失败 → 记录日志 → 再次 sleep T5 → 循环重试
  
  ⚠️ T5 不是真正的"超时"，而是"重试间隔"
     它控制的是重连/重绑定的频率，防止过于频繁地尝试
```

**完整 Active 重连时序**：

```
open() → openActive() → connect(127.0.0.1:5000)
                           │
                    连接失败/断开
                           │
                    sleep(T5 = 10秒)
                           │
                    openActive() → connect(...)
                           │
                    连接成功 → SELECT.REQ → SELECTED
                           │
                    ... 通信中 ...
                           │
                    连接断开 (SEPARATE/网络故障)
                           │
                    sleep(T5 = 10秒)
                           │
                    openActive() → connect(...) → 重连循环
```

---

#### 7.2.3 T6 — 等待控制消息回复超时 (Control Transaction Timeout)

**适用场景**：发送以下 **控制消息** 后，等待对方回复的控制消息超时：
- `SELECT.REQ` → 等待 `SELECT.RSP`
- `DESELECT.REQ` → 等待 `DESELECT.RSP`
- `LINKTEST.REQ` → 等待 `LINKTEST.RSP`

**触发条件**：控制消息发出后，在 T6 时间内未收到对应的回复

**代码路径**：

```java
// 1. getTimeout() 为控制消息选择 T6
private TimeoutGettable getTimeout(HsmsMessage msg) {
    switch (msg.messageType()) {
    case SELECT_REQ:
    case DESELECT_REQ:
    case LINKTEST_REQ:
        return this.config.timeout().t6();  // ← 控制消息使用 T6
    // ...
    }
}

// 2. 同样的 waitUntilReceived() 用 T6 等待
//    超时后:
try {
    return timeout.futureGet(future);  // T6 超时
} catch (TimeoutException e) {
    future.cancel(true);
    if (pack.sendMsg.isDataMessage()) {
        throw new HsmsTimeoutT3Exception(...);   // 不会走到这里
    } else {
        throw new HsmsTimeoutT6Exception(...);   // ← T6 超时异常
    }
}
```

**超时后行为**：

```
T6 超时
  │
  ├── 抛出异常:  HsmsTimeoutT6Exception (继承自 HsmsWaitReplyMessageException)
  │
  ├── 对于 LINKTEST.REQ:
  │     └── linktest() 方法 catch 住异常 → result = false
  │         → this.shutdown()  ← ⚠️ 心跳失败会关闭通道！
  │         → 接收线程和发送线程检测到 shutdown → 退出
  │         → 连接断开 → 触发 T5 重连循环
  │
  ├── 对于 SELECT.REQ (Active 端):
  │     └── select() 方法 catch 住异常 → return false
  │         → mainTask() 中: if (!session.select()) return;
  │         → completionAction() 的 try-with-resources 关闭 asyncChannel
  │         → session.unsetChannel() → 状态变为 NOT_CONNECTED
  │         → 回到 T5 重连循环
  │
  └── ⚠️ T6 超时对控制消息的影响比 T3 更严重:
        - T3 超时只影响当次数据消息的调用方
        - T6 超时（特别是 LINKTEST）可能导致整个连接断开重建
```

**LINKTEST 超时完整链路**：

```
taskLinktest() 心跳线程
    │
    ├── 等待 linktestTime (默认120秒) 无收发活动
    │
    ├── 超时 → 调用 linktest()
    │     │
    │     ├── send(LINKTEST.REQ) → 注册事务到 transactionMap
    │     │     │
    │     │     ├── 发送线程: 写入 TCP → notifySendResultSuccess()
    │     │     │
    │     │     └── 等待回复: waitUntilReceived(pack, T6)
    │     │           │
    │     │           ├── 正常: 收到 LINKTEST.RSP → 匹配事务 → return true
    │     │           │
    │     │           └── T6超时: TimeoutException
    │     │                 → throw HsmsTimeoutT6Exception
    │     │                 → catch → result = false
    │     │                 → this.shutdown()  ← 关闭通道
    │     │                 → return false
    │     │
    │     └── linktest() return false
    │           │
    │           └── this.shutdown(); return;  ← 心跳退出
    │
    └── shutdown → 三个线程全部退出 → 连接断开 → T5 后重连
```

---

#### 7.2.4 T7 — Passive 端等待 SELECT.REQ 超时

**适用场景**：**仅 Passive 端**。TCP 连接被 accept 后，Passive 端等待 Active 端发来第一条消息（必须是 `SELECT.REQ`）。

**触发条件**：TCP 连接建立后，在 T7 时间内没有收到 `SELECT.REQ`，或者根本没收到任何消息。

**代码路径**：

```java
// AbstractHsmsSsPassiveCommunicator.mainTask()
private final void mainTask(HsmsSsAsynchronousSocketChannelFacade asyncChannel)
        throws InterruptedException {
    
    // ← 用 T7 等待第一条消息
    final AbstractHsmsMessage initiateMsg =
        asyncChannel.pollPrimaryHsmsMessage(this.config.timeout().t7());
    
    if (initiateMsg == null) {
        // ← T7 超时！没有收到任何消息
        this.offerThrowableToLog(new HsmsTimeoutT7Exception());
        return;  // 退出 mainTask → 连接关闭
    }
    
    // 收到消息，检查类型
    switch (initiateMsg.messageType()) {
    case SELECT_REQ:
        // 正常流程: 回复 SELECT.RSP → 进入消息循环
        break;
    default:
        // 第一条消息不是 SELECT.REQ → 协议违规
        this.offerThrowableToLog(
            new HsmsSsPassiveReceiveNotSelectRequestException());
        return;  // 退出
    }
}
```

其中 `pollPrimaryHsmsMessage` 的实现：

```java
// AbstractHsmsAsynchronousSocketChannelFacade
public AbstractHsmsMessage pollPrimaryHsmsMessage(TimeoutGettable timeout)
        throws InterruptedException {
    return timeout.blockingQueuePoll(this.primaryMsgQueue);
    // → primaryMsgQueue.poll(T7秒, TimeUnit)
    // 如果 T7 秒内队列中没有消息 → 返回 null
}
```

**超时后行为**：

```
T7 超时
  │
  ├── 1. pollPrimaryHsmsMessage() 返回 null
  ├── 2. 记录日志: offerThrowableToLog(new HsmsTimeoutT7Exception())
  ├── 3. mainTask() return → 退出主任务
  ├── 4. completionAction() 的 try-with-resources 关闭 asyncChannel
  ├── 5. Passive 端的 accept 循环继续 → 等待下一个 TCP 连接
  │
  └── ⚠️ 仅断开当前这一路连接，不影响 Passive 端继续监听
        如果 doRebindIfPassive=true，服务器本身也会自动重绑
```

**完整 Passive 端 T7 时序**：

```
Passive 端                                  Active 端
    │                                          │
    │←── TCP connect ──────────────────────────│
    │                                          │
    │  [开始 T7 计时]                            │
    │  pollPrimaryHsmsMessage(T7=10秒)          │
    │                                          │
    │  场景A: Active 及时发来 SELECT.REQ          │
    │←── SELECT.REQ ──────────────────────────│
    │──→ SELECT.RSP (SUCCESS) ────────────────→│
    │  [进入正常消息循环]                         │
    │                                          │
    │  场景B: Active 迟迟不发 SELECT.REQ         │
    │  ... 10秒过去 ...                          │
    │  T7 超时 → 记录日志 → 关闭连接              │
    │  继续 accept() 等待下一个连接               │
```

---

#### 7.2.5 T8 — TCP 字节间读取超时 (Inter-byte Timeout)

**适用场景**：接收消息时，**首包之后的后续字节**之间的超时。注意：收到第一个字节后，后续每个字节（或字节块）都必须在 T8 时间内到达。

**触发条件**：在接收一条 HSMS 消息的过程中，两个连续的 `channel.read()` 调用之间间隔超过 T8。

**代码路径**：

```java
// taskReceiveMessage() 接收线程
private void taskReceiveMessage() {
    for (;;) {
        // ① 读取前4字节(消息长度) — 首包，无 T8 限制
        readingBufferFirst(lengthBuffer);  // ← future.get() 无超时，永久等待
        
        // ② 读完剩余的长度字节 — 有 T8 限制
        while (lengthBuffer.hasRemaining()) {
            readingBuffer(lengthBuffer);   // ← T8 超时检测
        }
        
        // ③ 读取10字节 Header — 有 T8 限制
        while (headerBuffer.hasRemaining()) {
            readingBuffer(headerBuffer);   // ← T8 超时检测
        }
        
        // ④ 读取 Body (分块, 每块最大1024字节) — 有 T8 限制
        for (long rem = msgLength - 10L; rem > 0L;) {
            ByteBuffer bf = ByteBuffer.allocate(size);
            while (bf.hasRemaining()) {
                rem -= readingBuffer(bf);  // ← T8 超时检测
            }
            linktestReset();  // 每读完一块重置心跳计时器
        }
    }
}

// 首包读取 — 无超时（因为可能很久才收到下一条消息）
private int readingBufferFirst(ByteBuffer buffer) {
    Future<Integer> future = this.channel.read(buffer);
    int r = future.get();  // ← 无超时，永久阻塞等待
    if (r < 0) throw new HsmsDetectTerminateException();
    return r;
}

// 后续字节读取 — T8 超时保护
private int readingBuffer(ByteBuffer buffer) {
    Future<Integer> future = this.channel.read(buffer);
    int r = this.config.timeout().t8().futureGet(future);  // ← T8 超时
    // future.get(T8秒, TimeUnit)
    if (r < 0) throw new HsmsDetectTerminateException();
    return r;
    // 如果超时:
    // catch (TimeoutException e) {
    //     future.cancel(true);
    //     throw new HsmsTimeoutT8Exception(e);  ← T8 超时异常
    // }
}
```

**超时后行为**：

```
T8 超时
  │
  ├── 1. readingBuffer() 抛出 HsmsTimeoutT8Exception
  ├── 2. 异常传播到 taskReceiveMessage() 的 catch 块
  │       catch (HsmsException e) {    // HsmsTimeoutT8Exception 继承自 HsmsException
  │           this.shutdown();          // ← ⚠️ 关闭通道！
  │           this.offerThrowableToLog(e);
  │       }
  ├── 3. shutdown() 设置 shutdownProp = true
  ├── 4. 发送线程检测到 shutdown → 退出
  ├── 5. 心跳线程检测到 shutdown → 退出
  ├── 6. mainTask 中的 executeInvokeAny() 返回
  ├── 7. asyncChannel close → session.unsetChannel()
  ├── 8. 状态变为 NOT_CONNECTED
  │
  └── ⚠️ T8 超时 **会导致连接断开**！
        因为 T8 超时意味着 TCP 传输层出了问题（数据传了一半不传了），
        这通常表示网络异常，所以直接断开连接并触发 T5 重连
```

**T8 与 readingBufferFirst 的区别**：

```
一条 HSMS 消息的接收过程:

  ┌──────────────────────────────────────────────────────────────┐
  │  [4字节长度] [10字节Header] [Body字节流...]                   │
  │                                                              │
  │  ↑ 首包              ↑ 后续                                  │
  │  readingBufferFirst   readingBuffer                          │
  │  (无超时,永久等待)     (T8超时保护)                             │
  │                                                              │
  │  因为: 两条消息之间可能间隔很久(几分钟甚至几小时)                  │
  │        所以首包不能设超时                                      │
  │        但一旦开始接收一条消息, 后续字节应该连续到达                 │
  │        如果中间断了(T8秒没收到), 说明网络出了问题                  │
  └──────────────────────────────────────────────────────────────┘
```

---

#### 7.2.6 超时总结对照表

| 超时 | 默认值 | 触发场景 | 超时后果 | 是否断开连接 | 代码位置 |
|------|--------|---------|---------|-------------|----------|
| **T3** | 45s | 发送 DATA+W=1 等回复 | 抛 `HsmsTimeoutT3Exception` 给调用方 | **否** | `waitUntilReceived()` |
| **T5** | 10s | 连接断开后重连间隔 | sleep 结束, 发起新一轮 `connect()`/`bind()` | N/A (非超时) | `open()` 循环 |
| **T6** | 5s | 发送控制消息(SELECT/LINKTEST)等回复 | 抛 `HsmsTimeoutT6Exception`; LINKTEST失败→shutdown | **LINKTEST时是** | `waitUntilReceived()` + `linktest()` |
| **T7** | 10s | Passive 端等待首条 SELECT.REQ | 记录日志, 关闭当前连接, 继续 accept | **当前连接断开** | `pollPrimaryHsmsMessage()` |
| **T8** | 6s | 接收消息时后续字节间超时 | shutdown 整个通道, 触发重连 | **是** | `readingBuffer()` |

#### 7.2.7 超时异常传播链路

```
HsmsException (extends SecsException)
├── HsmsTimeoutT7Exception          → 记录日志, 退出当前连接
├── HsmsTimeoutT8Exception          → shutdown() → 连接断开
└── HsmsWaitReplyMessageException   → 传播给 send() 调用方
    ├── HsmsTimeoutT3Exception      → 仅当次调用失败, 连接保持
    └── HsmsTimeoutT6Exception      → LINKTEST 时触发 shutdown

SecsSendMessageException            → 传播给 send() 调用方
└── HsmsSendMessageException        → 发送失败(如通道已关闭)
```

#### 7.2.8 超时联动时序图 — 完整连接生命周期

```
                    T5          T7              T3/T6           T8
                     │           │                │              │
Active 端:           │           │                │              │
  open()             │           │                │              │
  ├── connect() ──失败│           │                │              │
  ├── sleep(T5) ─────┤           │                │              │
  ├── connect() ──成功 │           │                │              │
  ├── SELECT.REQ ────┼───────────┼──→             │              │
  ├── 等待RSP ───────┼───────────┼── T6超时? ─────┤              │
  │   成功           │           │   失败→重连     │              │
  ├── SELECTED       │           │                │              │
  ├── send DATA W=1 ─┼───────────┼──→             │              │
  ├── 等待Reply ─────┼───────────┼── T3超时? ─────┤              │
  │   成功           │           │   抛异常(不断)  │              │
  ├── ...            │           │                │              │
  ├── LINKTEST ──────┼───────────┼── T6超时? ─────┤              │
  │   失败           │           │   → shutdown   │              │
  ├── 连接断开        │           │                │              │
  ├── sleep(T5) ─────┤           │                │              │
  └── 重连循环 ...    │           │                │              │
                     │           │                │              │
Passive 端:          │           │                │              │
  open()             │           │                │              │
  ├── bind() + accept│           │                │              │
  ├── 新连接到来 ─────┼───────────┤                │              │
  ├── 等待SELECT.REQ │           │                │              │
  │   T7超时? ───────┼───────────┘                │              │
  │   超时→关闭连接   │                            │              │
  │   成功→回复RSP   │                            │              │
  ├── SELECTED       │                            │              │
  ├── 接收消息 ──────┼────────────────────────────┼── T8超时?    │
  │   首字节:无超时   │                            │  字节间断→   │
  │   后续:T8保护    │                            │  shutdown    │
  └── ...            │                            │              │
```

### 7.3 响应式属性 (Reactive Property)

配置值使用 `local.property` 包中的响应式属性，支持运行时动态修改并自动通知监听者：

```java
// BooleanProperty — 布尔属性
BooleanProperty isEquip = BooleanProperty.newInstance(false);
config.isEquip(true);                        // 设置值
config.isEquip().addChangeListener(v -> {});  // 监听变化

// TimeoutProperty — 超时属性
TimeoutProperty t3 = TimeoutProperty.newInstance(45.0F);
config.timeout().t3(45.0F);   // 设置 45 秒
config.timeout().t3().sleep(); // 阻塞等待 T3 时间

// ObjectProperty — 泛型对象属性
ObjectProperty<SocketAddress> socketAddr = ObjectProperty.newInstance(null);
config.socketAddress(new InetSocketAddress("127.0.0.1", 5000));
```

---

## 8. SECS-I on TCP/IP Socket 实现

### 8.1 与 HSMS 的区别

SECS-I on TCP/IP 将 SECS-I（原本基于串口 RS-232）的帧协议跑在 TCP/IP 上，因此：

- **没有 HSMS 的 SELECT/DESELECT 握手流程**
- **没有 HSMS 的 10 字节 Header**，使用 SECS-I 的 10 字节帧头 (Header: Length[2] + Stream[1] + Function[1] + Wbit + SystemBytes[2] + Checksum[2])
- 直接以字节流方式收发 SECS-I 帧

### 8.2 Socket 连接实现

```java
public abstract class AbstractSecs1OnTcpIpCommunicator extends AbstractSecs1Communicator {
    
    private final ListProperty<AsynchronousSocketChannel> channels = ListProperty.newInstance();
    
    @Override
    public void open() throws IOException {
        super.open();
        // 后台线程: 反复连接, 断开后等待 reconnectSeconds 再重连
        executorService().execute(() -> {
            while (!isClosed()) {
                connect();
                secs1OnTcpIpConfig.reconnectSeconds().sleep();
            }
        });
    }
    
    private void connect() {
        AsynchronousSocketChannel channel = AsynchronousSocketChannel.open();
        channel.connect(socketAddr, null, new CompletionHandler<>() {
            @Override
            public void completed(...) {
                addChannel(channel);
                reading(channel);  // 启动读取任务
            }
        });
    }
    
    // 读取: 循环读取字节流 → 交给 SECS-I 帧解析器
    private void reading(AsynchronousSocketChannel channel) {
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        for (;;) {
            buffer.clear();
            int r = channel.read(buffer).get();
            if (r < 0) break;
            buffer.flip();
            byte[] bs = new byte[buffer.remaining()];
            buffer.get(bs);
            putBytes(bs);  // 交给 SECS-I 层解析帧
        }
    }
    
    // 发送: 将 SECS-I 帧字节写入 TCP
    @Override
    public void sendBytes(byte[] bs) {
        ByteBuffer buffer = ByteBuffer.allocate(bs.length);
        buffer.put(bs).flip();
        while (buffer.hasRemaining()) {
            channel.write(buffer).get();
        }
    }
}
```

---

## 9. 观察者模式与事件分发

### 9.1 消息接收观察者

```
AbstractHsmsMessageReceiveObserverFacade
│  内部维护 Listener 列表
│  使用 ExecutorService 异步分发
│
├── putHsmsMessage(msg)     → 通知 HSMS 层监听者
└── putSecsMessage(msg)     → 通知 SECS 层监听者

监听者接口:
├── HsmsMessageReceiveListener      (HSMS 消息)
├── HsmsMessageReceiveBiListener    (HSMS 消息 + Session)
├── SecsMessageReceiveListener      (SECS 消息)
└── SecsMessageReceiveBiListener    (SECS 消息 + Session)
```

### 9.2 通信状态观察者

```
AbstractHsmsCommunicateStateObserverFacade
│  维护 HsmsCommunicateState 当前值
│  状态变化时通知所有监听者
│
├── setHsmsCommunicateState(state)   → 设置新状态
├── waitUntilHsmsCommunicateState()  → 等待特定状态
└── addHsmsCommunicateStateChangeListener() → 注册监听
```

### 9.3 消息透传观察者

```
AbstractHsmsMessagePassThroughObserverFacade
│  记录消息的完整生命周期
│
├── putToTrySendHsmsMessage(msg)   → 即将发送
├── putToSendedHsmsMessage(msg)    → 已发送完成
└── putToReceiveHsmsMessage(msg)   → 接收到消息
```

---

## 10. 实例代码

### 10.1 HSMS-SS Passive (设备端) 完整示例

```java
import java.net.InetSocketAddress;
import com.shimizukenta.secs.*;
import com.shimizukenta.secs.hsms.*;
import com.shimizukenta.secs.hsmsss.*;
import com.shimizukenta.secs.secs2.*;

public class EquipmentExample {
    public static void main(String[] args) throws Exception {
        
        // 1. 创建配置
        HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
        config.socketAddress(new InetSocketAddress("127.0.0.1", 5000));
        config.connectionMode(HsmsConnectionMode.PASSIVE);  // 设备端 = Passive
        config.sessionId(10);
        config.isEquip(true);
        config.notLinktest();              // 不做心跳检测
        config.timeout().t3(45.0F);        // T3: 等待回复超时 45s
        config.timeout().t6(5.0F);         // T6: 控制消息回复超时 5s
        config.timeout().t7(10.0F);        // T7: 等待SELECT超时 10s
        config.timeout().t8(6.0F);         // T8: 字节读取超时 6s
        config.gem().mdln("MODEL-A");      // 设备型号
        config.gem().softrev("000001");    // 软件版本
        
        // 2. 创建通信器 (try-with-resources 自动关闭)
        try (SecsCommunicator comm = HsmsSsCommunicator.newInstance(config)) {
            
            // 3. 注册日志监听
            comm.addSecsLogListener(System.out::println);
            
            // 4. 注册消息接收监听 — 处理来自 Host 的消息
            comm.addSecsMessageReceiveListener(msg -> {
                try {
                    int strm = msg.getStream();
                    int func = msg.getFunction();
                    boolean wbit = msg.wbit();
                    
                    if (strm == 1 && func == 13 && wbit) {
                        // S1F13: Establish Online Communication Request
                        // 回复 S1F14
                        comm.gem().s1f14(msg, COMMACK.OK);
                    }
                    else if (strm == 1 && func == 17 && wbit) {
                        // S1F17: Online Request
                        comm.gem().s1f18(msg, ONLACK.OK);
                    }
                    else if (wbit) {
                        // 不支持的功能 → 回复 S0F0 (函数错误)
                        comm.send(msg, strm, 0, false);
                        comm.gem().s9f5(msg);  // S9F5: Illegal Data
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            
            // 5. 注册通信状态变化监听
            comm.addSecsCommunicatableStateChangeListener(state -> {
                System.out.println("通信状态变化: " + (state ? "SELECTED" : "NOT SELECTED"));
            });
            
            // 6. 打开通信 (非阻塞, 后台等待连接)
            comm.open();
            
            // 7. 主线程等待
            synchronized (EquipmentExample.class) {
                EquipmentExample.class.wait();
            }
        }
    }
}
```

### 10.2 HSMS-SS Active (Host 端) 完整示例

```java
import java.net.InetSocketAddress;
import java.util.Optional;
import com.shimizukenta.secs.*;
import com.shimizukenta.secs.hsms.*;
import com.shimizukenta.secs.hsmsss.*;
import com.shimizukenta.secs.secs2.*;

public class HostExample {
    public static void main(String[] args) throws Exception {
        
        // 1. 创建配置
        HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
        config.socketAddress(new InetSocketAddress("127.0.0.1", 5000));
        config.connectionMode(HsmsConnectionMode.ACTIVE);  // Host 端 = Active
        config.sessionId(10);
        config.isEquip(false);
        config.linktest(60.0F);            // 每 60 秒做一次心跳检测
        config.timeout().t3(45.0F);
        config.timeout().t5(10.0F);        // T5: 重连间隔 10s
        config.timeout().t6(5.0F);
        config.timeout().t8(6.0F);
        
        // 2. 创建通信器
        try (HsmsSsCommunicator comm = HsmsSsCommunicator.newInstance(config)) {
            
            comm.addSecsLogListener(System.out::println);
            
            // 3. 注册通信状态监听 — 连接建立后自动发送消息
            comm.addSecsCommunicatableStateChangeListener(selected -> {
                try {
                    if (selected) {
                        System.out.println("=== 已连接 (SELECTED) ===");
                        
                        // 发送 S1F13 (Online Communication Request)
                        Optional<SecsMessage> reply = comm.send(1, 13, true, Secs2.list());
                        System.out.println("S1F14 回复: " + reply);
                        
                        // 发送 S1F17 (Online Request) 并获取回复
                        reply = comm.send(1, 17, true);
                        ONLACK onlack = ONLACK.get(reply.get().secs2());
                        System.out.println("ONLACK: " + onlack);
                        
                        // 发送自定义消息: S5F1 (Alarm Report)
                        Secs2 alarmBody = Secs2.list(
                            Secs2.list(
                                Secs2.ascii("ALARM-001"),
                                Secs2.list(Secs2.ascii("Temperature too high"))
                            )
                        );
                        comm.send(5, 1, false, alarmBody);  // wbit=false, 不需要回复
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            
            // 4. 注册消息接收监听 — 处理设备主动上报的消息
            comm.addSecsMessageReceiveListener(msg -> {
                try {
                    int strm = msg.getStream();
                    int func = msg.getFunction();
                    System.out.printf("收到消息: S%dF%d, wbit=%b%n", strm, func, msg.wbit());
                    
                    if (strm == 10 && func == 3 && msg.wbit()) {
                        // S10F3: Terminal Display Request
                        String text = msg.secs2().getAscii(1);
                        System.out.println("终端显示: " + text);
                        comm.gem().s10f4(msg, ACKC10.AcceptedForDisplay);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            
            // 5. 打开并等待连接建立
            comm.open();
            
            // 或者直接: 打开并阻塞等待可通信
            // comm.openAndWaitUntilCommunicatable();
            
            synchronized (HostExample.class) {
                HostExample.class.wait();
            }
        }
    }
}
```

### 10.3 HSMS-GS 多会话示例 (Host 端)

```java
import java.net.InetSocketAddress;
import com.shimizukenta.secs.hsms.*;
import com.shimizukenta.secs.hsmsgs.*;

public class MultiSessionHostExample {
    public static void main(String[] args) throws Exception {
        
        HsmsGsCommunicatorConfig config = new HsmsGsCommunicatorConfig();
        config.socketAddress(new InetSocketAddress("127.0.0.1", 5000));
        config.connectionMode(HsmsConnectionMode.ACTIVE);
        config.addSessionId(100);   // Session A
        config.addSessionId(200);   // Session B
        config.addSessionId(300);   // Session C
        config.isEquip(false);
        config.linktest(60.0F);
        config.timeout().t3(45.0F);
        config.timeout().t5(10.0F);
        config.timeout().t6(5.0F);
        config.timeout().t8(5.0F);
        
        try (HsmsGsCommunicator comm = HsmsGsCommunicator.newInstance(config)) {
            
            comm.addSecsLogListener(System.out::println);
            
            // 为每个 Session 注册独立的消息处理器
            comm.getHsmsSession(100).addSecsMessageReceiveBiListener((msg, session) -> {
                System.out.println("Session-100 收到: S" + msg.getStream() + "F" + msg.getFunction());
                // 通过 session 对象回复
            });
            
            comm.getHsmsSession(200).addSecsMessageReceiveBiListener((msg, session) -> {
                System.out.println("Session-200 收到: S" + msg.getStream() + "F" + msg.getFunction());
            });
            
            comm.open();
            
            synchronized (MultiSessionHostExample.class) {
                MultiSessionHostExample.class.wait();
            }
        }
    }
}
```

### 10.4 SECS-I on TCP/IP 示例

```java
import java.net.InetSocketAddress;
import com.shimizukenta.secs.*;
import com.shimizukenta.secs.secs1ontcpip.*;

public class Secs1OnTcpIpExample {
    public static void main(String[] args) throws Exception {
        
        Secs1OnTcpIpCommunicatorConfig config = new Secs1OnTcpIpCommunicatorConfig();
        config.socketAddress(new InetSocketAddress("192.168.1.100", 9600));
        config.isEquip(false);
        config.timeout().t1(0.5F);    // 等待 ACK 超时
        config.timeout().t2(10.0F);   // 字节间超时
        config.timeout().t3(45.0F);   // 等待回复超时
        
        try (SecsCommunicator comm = Secs1OnTcpIpCommunicator.newInstance(config)) {
            
            comm.addSecsLogListener(System.out::println);
            
            comm.addSecsMessageReceiveListener(msg -> {
                System.out.println("收到 SECS-I 消息: S" + msg.getStream() + "F" + msg.getFunction());
            });
            
            comm.open();
            
            // 发送消息
            comm.send(1, 1, true, Secs2.list());
            
            synchronized (Secs1OnTcpIpExample.class) {
                Secs1OnTcpIpExample.class.wait();
            }
        }
    }
}
```

---

## 11. 设计模式总结

| 设计模式 | 应用位置 | 说明 |
|----------|---------|------|
| **接口隔离** | `SecsCommunicator` 组合多个小接口 | 每个接口只关注一种能力 |
| **模板方法** | `Abstract*` 系列抽象类 | 定义算法骨架, 子类实现细节 |
| **工厂方法** | `HsmsSsCommunicator.newInstance(config)` | 根据 connectionMode 创建 Active/Passive 实例 |
| **观察者模式** | `*Observable` + `*Listener` 接口 | 消息接收、状态变化、日志、透传 |
| **门面模式** | `AbstractHsmsAsynchronousSocketChannelFacade` | 封装复杂的 Socket 读写、事务管理、心跳 |
| **响应式属性** | `local.property.*Property` | 配置值变化自动通知相关组件 |
| **生产者-消费者** | `BlockingQueue<SendAndReceiveMsgPack>` | 发送线程与业务线程解耦 |
| **事务匹配** | `transactionMap` + System Bytes | 请求-回复消息的异步匹配 |
| **状态机** | `HsmsCommunicateState` | NOT_CONNECTED → NOT_SELECTED → SELECTED |

---

## 12. 关键类速查表

| 类名 | 包路径 | 职责 |
|------|--------|------|
| `SecsCommunicator` | `com.shimizukenta.secs` | 顶层通信接口 |
| `AbstractBaseCommunicator` | `.impl` | 线程池 + 生命周期管理 |
| `AbstractSecsCommunicator` | `.impl` | Config + Gem + 日志 |
| `HsmsSsCommunicator` | `.hsmsss` | HSMS-SS 接口 + 工厂方法 |
| `AbstractHsmsSsCommunicator` | `.hsmsss.impl` | HSMS-SS 基类 (委托 Session) |
| `AbstractHsmsSsActiveCommunicator` | `.hsmsss.impl` | Active 连接逻辑 |
| `AbstractHsmsSsPassiveCommunicator` | `.hsmsss.impl` | Passive 监听逻辑 |
| `AbstractHsmsSsSession` | `.hsmsss.impl` | HSMS-SS 会话管理 |
| `AbstractHsmsAsynchronousSocketChannelFacade` | `.hsms.impl` | **核心 Socket 引擎** (三线程模型) |
| `AbstractHsmsSession` | `.hsms.impl` | HSMS 会话基类 |
| `HsmsSsCommunicatorConfig` | `.hsmsss` | HSMS-SS 配置 |
| `AbstractHsmsCommunicatorConfig` | `.hsms` | HSMS 通用配置 |
| `AbstractSecs1OnTcpIpCommunicator` | `.secs1ontcpip.impl` | SECS-I over TCP 实现 |
| `SecsTimeout` | `com.shimizukenta.secs` | T1~T8 超时配置接口 |

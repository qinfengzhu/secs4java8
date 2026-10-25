# SecsMessage 接口详解

## 概述

`SecsMessage` 是 secs4java8 库中的核心接口，用于表示 SECS（SEMI Equipment Communications Standard）协议中的消息。它是所有 SECS 消息的基础抽象，无论是 SECS-I 还是 HSMS 协议的消息都实现了这个接口。

## 接口定义

```java
public interface SecsMessage {
    // 获取 Stream 号（流号）
    public int getStream();
    
    // 获取 Function 号（功能号）
    public int getFunction();
    
    // 获取 W-Bit（等待位）
    public boolean wbit();
    
    // 获取 SECS-II 消息体数据
    public Secs2 secs2();
    
    // 获取 Device-ID（设备ID）
    public int deviceId();
    
    // 获取 Session-ID（会话ID）
    public int sessionId();
    
    // 获取消息头 10 字节
    public byte[] header10Bytes();
    
    // 转换为 JSON 字符串
    public String toJson();
}
```

## 核心属性说明

### 1. Stream（流号）

- **方法**：`getStream()`
- **含义**：SECS-II 消息的流号，用于标识消息的功能类别
- **取值范围**：0-127（7 位）
- **特殊值**：-1 表示非数据消息（如控制消息）

常见 Stream 定义：

| Stream | 名称 | 用途 |
|--------|------|------|
| S0 | 控制消息 | HSMS 通信控制 |
| S1 | 设备控制 | 在线/离线、通信建立等 |
| S2 | 设备控制2 | 远程命令、时间设置等 |
| S5 | 报警管理 | 报警事件和处理 |
| S6 | 数据收集 | 事件报告、数据采集 |
| S7 | 过程管理 | 程序传输和控制 |
| S9 | 错误处理 | 错误消息 |
| S10 | 终端显示 | 终端消息显示 |

### 2. Function（功能号）

- **方法**：`getFunction()`
- **含义**：SECS-II 消息的功能号，与 Stream 配合标识具体消息类型
- **取值范围**：0-255（8 位）
- **特殊值**：-1 表示非数据消息

消息命名规则：`S{stream}F{function}`，如 S1F1、S6F11

### 3. W-Bit（等待位）

- **方法**：`wbit()`
- **含义**：指示发送方是否期望接收回复
- **取值**：
  - `true`：主消息，期望回复
  - `false`：回复消息，不期望进一步回复

### 4. SECS-II 数据体

- **方法**：`secs2()`
- **含义**：消息的实际数据内容
- **类型**：`Secs2` 接口，支持多种数据类型

### 5. Device-ID / Session-ID

- **方法**：`deviceId()` / `sessionId()`
- **含义**：
  - **SECS-I**：使用 Device-ID（设备标识）
  - **HSMS**：使用 Session-ID（会话标识）
- **取值范围**：0-32767（15 位）

## 继承体系

```
SecsMessage (基础接口)
    │
    ├── Secs1Message (SECS-I 消息)
    │       - rbit(): R-Bit（回复位，SECS-I 特有）
    │       - toBlocks(): 拆分为消息块
    │       - isValidBlocks(): 验证消息块完整性
    │
    └── HsmsMessage (HSMS 消息)
            - messageType(): 消息类型（数据/控制）
            - isDataMessage(): 是否为数据消息
            - pType(): 主类型字节
            - sType(): 次类型字节
```

## 使用实例

### 实例 1：接收并解析消息（HSMS Passive Equipment）

```java
// 配置 HSMS-SS Passive 模式
HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
config.socketAddress(new InetSocketAddress("127.0.0.1", 5000));
config.connectionMode(HsmsConnectionMode.PASSIVE);
config.sessionId(10);
config.isEquip(true);

try (SecsCommunicator comm = HsmsSsCommunicator.newInstance(config)) {
    
    // 添加消息接收监听器
    comm.addSecsMessageReceiveListener(msg -> {
        
        // 获取消息属性
        int strm = msg.getStream();
        int func = msg.getFunction();
        boolean wbit = msg.wbit();
        Secs2 secs2 = msg.secs2();
        
        // 根据 Stream/Function 处理消息
        switch (strm) {
        case 1:  // S1: 设备控制
            switch (func) {
            case 1:  // S1F1: Are You There?
                if (wbit) {
                    // 发送回复 S1F2
                    comm.gem().s1f2(msg);
                }
                break;
            case 13:  // S1F13: Establish Communications
                if (wbit) {
                    comm.gem().s1f14(msg, COMMACK.OK);
                }
                break;
            }
            break;
            
        case 6:  // S6: 数据收集
            if (func == 11) {  // S6F11: Event Report
                // S6F11 通常 wbit=false，不需要回复
                System.out.println("收到事件报告");
            }
            break;
        }
    });
    
    comm.open();
}
```

### 实例 2：发送主消息并等待回复（HSMS Active Host）

```java
HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
config.socketAddress(new InetSocketAddress("127.0.0.1", 5000));
config.connectionMode(HsmsConnectionMode.ACTIVE);
config.isEquip(false);

try (HsmsSsCommunicator comm = HsmsSsCommunicator.newInstance(config)) {
    
    comm.addSecsCommunicatableStateChangeListener(state -> {
        if (state /* SELECTED */) {
            
            // 发送 S1F13 (Establish Communications)，wbit=true 期望回复
            Secs2 body = Secs2.list();  // 空列表
            Optional<SecsMessage> reply = comm.send(1, 13, true, body);
            
            // 处理回复
            reply.ifPresent(msg -> {
                System.out.println("收到回复:");
                System.out.println("  Stream: " + msg.getStream());
                System.out.println("  Function: " + msg.getFunction());
                System.out.println("  W-Bit: " + msg.wbit());
                
                // 解析回复数据
                Secs2 data = msg.secs2();
                // ...
            });
        }
    });
    
    comm.open();
}
```

### 实例 3：构建和解析 SECS-II 数据

```java
// 构建复杂数据结构
Secs2 data = Secs2.list(
    Secs2.ascii("MDLN-A"),           // 字符串
    Secs2.int4(12345),                // 4字节整数
    Secs2.binary(new byte[]{0x01, 0x02}),  // 二进制
    Secs2.bool(true, false),          // 布尔数组
    Secs2.list(                       // 嵌套列表
        Secs2.ascii("item1"),
        Secs2.uint2(100, 200, 300)
    )
);

// 发送消息
SecsMessage msg = comm.send(2, 31, true, data).get();

// 解析接收到的数据
Secs2 received = msg.secs2();
String str = received.getAscii(0);           // 获取第1个字符串
int num = received.getInt(1);                // 获取第2个整数
byte[] bytes = received.getBytes(2);         // 获取第3个二进制
boolean b = received.getBoolean(3);          // 获取第4个布尔值
String nestedStr = received.getAscii(4, 0);  // 获取嵌套列表中的字符串
```

### 实例 4：SECS-I 消息处理

```java
// 配置 SECS-I on TCP/IP
Secs1OnTcpIpCommunicatorConfig config = new Secs1OnTcpIpCommunicatorConfig();
config.socketAddress(new InetSocketAddress("127.0.0.1", 5000));
config.deviceId(10);
config.isEquip(true);

try (Secs1Communicator comm = Secs1OnTcpIpCommunicator.newInstance(config)) {
    
    comm.addSecsMessageReceiveListener(msg -> {
        // Secs1Message 扩展了 SecsMessage
        Secs1Message secs1Msg = (Secs1Message) msg;
        
        // 获取 SECS-I 特有属性
        boolean rbit = secs1Msg.rbit();  // R-Bit（回复位）
        
        // 获取消息块（SECS-I 消息可能分为多个块传输）
        List<Secs1MessageBlock> blocks = secs1Msg.toBlocks();
        
        System.out.println("Stream: " + secs1Msg.getStream());
        System.out.println("Function: " + secs1Msg.getFunction());
        System.out.println("W-Bit: " + secs1Msg.wbit());
        System.out.println("R-Bit: " + secs1Msg.rbit());
        System.out.println("Device-ID: " + secs1Msg.deviceId());
        System.out.println("消息块数: " + blocks.size());
    });
    
    comm.open();
}
```

### 实例 5：消息日志和调试

```java
// 添加日志监听器
comm.addSecsLogListener(log -> {
    System.out.println(log);
});

// 添加消息透传监听器（查看原始消息）
comm.addSecsMessagePassThroughListener(log -> {
    System.out.println("消息透传: " + log.toJson());
});

// 手动构建消息并查看其 JSON 表示
SecsMessage msg = Secs1Message.of(
    new byte[] {
        (byte)0x00, (byte)0x0A,  // 消息长度
        (byte)0x81, (byte)0x01,  // Stream=1, W-Bit=true, Function=1
        (byte)0x00, (byte)0x00,  // 保留
        (byte)0x00, (byte)0x0A,  // Device-ID=10
        (byte)0x00, (byte)0x01   // 保留
    },
    Secs2.empty()
);

System.out.println("消息 JSON: " + msg.toJson());
// 输出示例:
// {
//   "stream": 1,
//   "function": 1,
//   "wbit": true,
//   "deviceId": 10,
//   "secs2": null
// }
```

## Secs2 数据类型速查

| 方法 | SECS-II 类型 | 说明 | Java 类型 |
|------|-------------|------|----------|
| `Secs2.list()` | L | 列表 | List |
| `Secs2.ascii()` | A | ASCII 字符串 | String |
| `Secs2.binary()` | B | 二进制数据 | byte[] |
| `Secs2.bool()` | BOOLEAN | 布尔值 | boolean |
| `Secs2.int1()` | I1 | 1字节有符号整数 | byte |
| `Secs2.int2()` | I2 | 2字节有符号整数 | short |
| `Secs2.int4()` | I4 | 4字节有符号整数 | int |
| `Secs2.int8()` | I8 | 8字节有符号整数 | long |
| `Secs2.uint1()` | U1 | 1字节无符号整数 | short |
| `Secs2.uint2()` | U2 | 2字节无符号整数 | int |
| `Secs2.uint4()` | U4 | 4字节无符号整数 | long |
| `Secs2.uint8()` | U8 | 8字节无符号整数 | BigInteger |
| `Secs2.float4()` | F4 | 4字节浮点数 | float |
| `Secs2.float8()` | F8 | 8字节浮点数 | double |

## 消息交互模式

### 主消息-回复消息模式

```
Host                              Equipment
  │                                     │
  │  ── S1F1 (wbit=true) ──────────▶   │  主消息，期望回复
  │                                     │
  │  ◀── S1F2 (wbit=false) ─────────   │  回复消息，不期望进一步回复
  │                                     │
```

### 单向消息模式

```
Host                              Equipment
  │                                     │
  │  ── S6F11 (wbit=false) ────────▶   │  事件报告，不期望回复
  │                                     │
```

## 注意事项

1. **不可变性**：`SecsMessage` 实例是不可变的，创建后不能修改其属性。

2. **W-Bit 规则**：
   - 主消息通常 `wbit=true`
   - 回复消息必须 `wbit=false`
   - 错误的 W-Bit 设置会导致通信异常

3. **Device-ID vs Session-ID**：
   - SECS-I 使用 Device-ID
   - HSMS 使用 Session-ID
   - 两者在数值上可能相同，但语义不同

4. **消息块处理**：
   - SECS-I 消息可能分为多个块（最大 244 字节/块）
   - 使用 `toBlocks()` 可以查看消息块
   - 使用 `isValidBlocks()` 验证消息完整性

5. **异常处理**：
   - 发送消息可能抛出 `SecsSendMessageException`
   - 等待回复可能抛出 `SecsWaitReplyMessageException`
   - 数据解析可能抛出 `Secs2Exception`

## 参考

- SEMI E5 - SECS-I 标准
- SEMI E37 - HSMS 标准
- SEMI E5-SECS-II 数据格式标准
- secs4java8 项目源码

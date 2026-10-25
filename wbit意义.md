# SECS 协议中的 W-Bit（Wait Bit）详解

## 概述

在 SECS（SEMI Equipment Communications Standard）协议中，**W-Bit（Wait Bit，等待位）** 是消息头中的一个标志位，用于指示发送方是否期望接收方返回回复消息。

## 基本含义

| W-Bit 值 | 含义 | 典型场景 |
|---------|------|---------|
| `true` | 发送方**等待回复** | 主消息（Primary Message） |
| `false` | 发送方**不期望回复** | 回复消息（Reply Message） |

## 在 SECS-I 消息头中的编码位置

SECS-I 消息使用 10 字节头（Header-10-Bytes），W-Bit 编码在 **第 3 字节（Byte[2]）的最高位（bit 7）**：

```
Byte[2] = Stream_Number | (W-Bit ? 0x80 : 0x00)
```

### 编码示例

| Stream | W-Bit | Byte[2] 值 | 二进制表示 |
|--------|-------|-----------|-----------|
| 1 | true | 0x81 | 1000 0001 |
| 1 | false | 0x01 | 0000 0001 |
| 2 | true | 0x82 | 1000 0010 |
| 2 | false | 0x02 | 0000 0010 |
| 55 | false | 0x37 | 0011 0111 |

### SECS-I 消息头结构（10 字节）

```
┌─────────────┬─────────────┬─────────────┬─────────────┐
│  Byte[0-1]  │  Byte[2]    │  Byte[3]    │  Byte[4]    │
│  消息长度    │ Stream+WBit │ Function    │ RBit+...    │
│  (2 bytes)  │ (7bit+1bit) │ (8 bits)    │ (1bit+7bit) │
└─────────────┴─────────────┴─────────────┴─────────────┘
```

- **Byte[2] 高 7 位**：Stream Number（流号，0-127）
- **Byte[2] 最低位**：W-Bit（等待位）
- **Byte[4] 最高位**：R-Bit（回复位，SECS-I 特有）

## 在 SECS-II / HSMS 中的对应

在 HSMS（High-Speed Message Service）协议中，W-Bit 的概念相同，但编码方式不同。HSMS 消息头中，Stream 和 W-Bit 分别用独立的字节表示。

## 消息交互模式

### 典型的主消息-回复消息交互

```
Host                          Equipment
  │                               │
  │  ── S1F1 (W-Bit=true) ──▶    │  Host 发送主消息，等待回复
  │                               │
  │  ◀── S1F2 (W-Bit=false) ──   │  Equipment 发送回复，不再等待
  │                               │
```

### 不需要回复的消息

```
Host                          Equipment
  │                               │
  │  ── S6F11 (W-Bit=false) ──▶  │  Host 发送事件报告，不等待回复
  │                               │
```

## 在 secs4java8 中的使用

### 构建主消息（需要回复）

```java
// 参数: communicator, stream, function, wbit
Secs1Message primaryMsg = builder.buildDataMessage(comm, 1, 1, true);
```

### 构建回复消息（不需要回复）

```java
// 参数: communicator, primaryMessage, stream, function, wbit
Secs1Message replyMsg = builder.buildDataMessage(comm, primaryMsg, 1, 2, false);
```

### 判断是否需要回复

在 GEM 实现中，所有回复处理函数都会先检查 W-Bit：

```java
@Override
public Optional<SecsMessage> s1f2(SecsMessage primaryMsg) {
    // 如果主消息的 W-Bit 为 false，则不发送回复
    if ( ! primaryMsg.wbit() ) {
        return Optional.empty();
    }
    
    // 构建并发送回复消息
    Secs2 replyData = Secs2.list(...);
    return comm.send(primaryMsg, 1, 2, false, replyData);
}
```

### 读取接收消息的 W-Bit

```java
// 监听消息接收
comm.addMessageReceiveListener(msg -> {
    boolean wbit = msg.wbit();
    int stream = msg.getStream();
    int function = msg.getFunction();
    
    if (wbit) {
        // 需要回复此消息
        sendReply(msg);
    }
});
```

## 常见 SECS 消息的 W-Bit 规则

| 消息类型 | W-Bit | 说明 |
|---------|-------|------|
| S1F1 (Are You There?) | true | 需要回复 S1F2 |
| S1F2 (Online Ack) | false | 回复消息，不再等待 |
| S1F3 (Selected Equipment Status) | true | 需要回复 S1F4 |
| S1F4 (Data Reply) | false | 回复消息 |
| S1F13 (Establish Communications) | true | 需要回复 S1F14 |
| S1F14 (Communications Ack) | false | 回复消息 |
| S1F15 (Go Offline) | true | 需要回复 S1F16 |
| S1F16 (Offline Ack) | false | 回复消息 |
| S1F17 (Request Online) | true | 需要回复 S1F18 |
| S1F18 (Online Ack) | false | 回复消息 |
| S6F11 (Event Report) | false | 不要求回复 |
| S6F12 (Event Report Ack) | true | 需要回复 S6F11 |

## 注意事项

1. **回复消息的 W-Bit 必须为 false**：否则会导致无限循环的消息交互。

2. **某些消息的 W-Bit 可以为 false**：如事件报告（S6F11），发送方不要求回复，接收方不需要响应。

3. **W-Bit 与 R-Bit 的区别**：
   - **W-Bit（Wait Bit）**：存在于所有 SECS 消息中，表示是否等待回复。
   - **R-Bit（Reply Bit）**：仅存在于 SECS-I 消息中，表示消息方向（Host→Equip 或 Equip→Host）。

4. **协议合规性**：正确设置 W-Bit 是 SECS/GEM 协议合规的关键要求之一，错误的 W-Bit 设置可能导致通信异常。

## 参考

- SEMI E5-SECS-I 标准
- SEMI E37-HSMS 标准
- secs4java8 项目源码

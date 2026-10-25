# secs4java8 架构文档

## 概述

secs4java8 是一个基于 Java 8 的 SEMI SECS/GEM 通信协议实现库，用于半导体制造设备与主机系统之间的消息通信。

### 支持的 SEMI 标准

| 标准 | 描述 |
|------|------|
| SECS-I (SEMI-E4) | 串行通信协议 |
| SECS-II (SEMI-E5) | 数据格式标准 |
| GEM (SEMI-E30, 部分) | 通用设备模型 |
| HSMS (SEMI-E37) | 高速消息服务 |
| HSMS-SS (SEMI-E37.1) | 单会话 HSMS |
| HSMS-GS (SEMI-E37.2) | 通用会话 HSMS（多会话） |
| SML (PEER Group) | SECS 消息语言 |

---

## 包结构

```
com.shimizukenta.secs
├── (根包)              核心接口与抽象：SecsCommunicator, SecsMessage, 配置, 监听器等
├── impl/               核心抽象实现层
├── secs2/              SECS-II 数据格式 (SEMI-E5)
│   └── impl/           SECS-II 内部实现（各数据类型）
├── hsms/               HSMS 公共层（消息、会话、超时等）
│   └── impl/           HSMS 公共抽象实现
├── hsmsss/             HSMS-SS 单会话 (SEMI-E37.1)
│   └── impl/           HSMS-SS Active/Passive 实现
├── hsmsgs/             HSMS-GS 通用会话 (SEMI-E37.2)
│   └── impl/           HSMS-GS Active/Passive 实现
├── secs1/              SECS-I 通信 (SEMI-E4)
│   └── impl/           SECS-I 内部实现
├── secs1ontcpip/       SECS-I over TCP/IP 适配层
│   └── impl/           SECS-I on TCP/IP 实现
├── gem/                GEM 模型 (SEMI-E30)
│   └── impl/           GEM 内部实现
├── sml/                SML 消息解析 (PEER Group)
│   └── impl/           SML 解析器实现
├── local/property/     响应式属性框架（Property/Observer 模式）
└── util/               工具层（Entity 封装）
```

---

## 核心架构

### 分层架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        应用层 (Application)                       │
│                    SecsCommunicatorEntity (util)                  │
├─────────────────────────────────────────────────────────────────┤
│                        通信层 (Communication)                     │
│                                                                  │
│   ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐  │
│   │  HSMS-SS     │  │  HSMS-GS     │  │  SECS-I on TCP/IP    │  │
│   │  (E37.1)     │  │  (E37.2)     │  │  (E4 over TCP/IP)    │  │
│   └──────┬───────┘  └──────┬───────┘  └──────────┬───────────┘  │
│          │                 │                      │              │
│          └────────┬────────┘                      │              │
│                   │                               │              │
│          ┌────────┴────────┐             ┌────────┴───────┐     │
│          │  hsms (公共层)   │             │  secs1 (E4)    │     │
│          │  消息/会话/超时  │             │  消息/块/电路   │     │
│          └────────┬────────┘             └────────┬───────┘     │
│                   │                               │              │
│                   └───────────┬───────────────────┘              │
│                               │                                  │
│                    ┌──────────┴──────────┐                       │
│                    │  SecsCommunicator   │  ← 核心统一接口        │
│                    │  (核心抽象层)        │                       │
│                    └──────────┬──────────┘                       │
├───────────────────────────────┼──────────────────────────────────┤
│          ┌────────────────────┼────────────────────┐             │
│          │                    │                    │              │
│   ┌──────┴──────┐   ┌────────┴───────┐   ┌───────┴──────┐      │
│   │  secs2      │   │  gem           │   │  sml         │      │
│   │  数据格式    │   │  设备模型       │   │  消息语言     │      │
│   │  (SEMI-E5)  │   │  (SEMI-E30)    │   │  (PEER)      │      │
│   └─────────────┘   └────────────────┘   └──────────────┘      │
├─────────────────────────────────────────────────────────────────┤
│                 local.property (响应式属性框架)                    │
└─────────────────────────────────────────────────────────────────┘
```

---

## 核心接口与类

### 1. 通信核心 (`com.shimizukenta.secs`)

| 接口/类 | 说明 |
|---------|------|
| `SecsCommunicator` | **核心统一接口**，组合了 OpenAndCloseable、SecsGemAccessor、消息收发/监听、状态检测、日志观察等能力 |
| `SecsMessage` | SECS 消息接口，包含 Stream、Function、WBit、SECS-II 数据、Header 10 字节 |
| `SecsMessageSendable` | 消息发送接口，支持发送 Primary/Reply 消息，支持 SECS-II 和 SML 格式 |
| `OpenAndCloseable` | 生命周期管理接口（open/close/isOpen/isClosed） |
| `AbstractSecsCommunicatorConfig` | 通信配置抽象类，包含超时、设备类型、GEM 配置、日志头等 |
| `SecsTimeout` | SECS 超时参数（T1~T8） |

### 2. 协议实现

#### HSMS-SS (`hsmsss`)

| 接口/类 | 说明 |
|---------|------|
| `HsmsSsCommunicator` | HSMS-SS 通信接口，继承 SecsCommunicator |
| `HsmsSsCommunicatorConfig` | HSMS-SS 配置（连接模式、Socket地址、Session ID 等） |
| `impl.AbstractHsmsSsActiveCommunicator` | Active（主动/Host）模式实现 |
| `impl.AbstractHsmsSsPassiveCommunicator` | Passive（被动/Equipment）模式实现 |

#### HSMS-GS (`hsmsgs`)

| 接口/类 | 说明 |
|---------|------|
| `HsmsGsCommunicator` | HSMS-GS 通信接口，支持多会话管理 |
| `HsmsGsCommunicatorConfig` | HSMS-GS 配置 |
| `HsmsSession` | HSMS 会话接口（select/deselect/separate） |
| `impl.AbstractHsmsGsActiveCommunicator` | Active 模式实现 |
| `impl.AbstractHsmsGsPassiveCommunicator` | Passive 模式实现 |

#### SECS-I (`secs1`, `secs1ontcpip`)

| 接口/类 | 说明 |
|---------|------|
| `Secs1Communicator` | SECS-I 通信接口 |
| `Secs1Message` | SECS-I 消息 |
| `Secs1MessageBlock` | SECS-I 消息块（10 字节头 + 最多 244 字节数据） |
| `Secs1OnTcpIpCommunicator` | SECS-I over TCP/IP 客户端通信器 |
| `Secs1OnTcpIpReceiverCommunicator` | SECS-I over TCP/IP 服务端通信器 |

### 3. SECS-II 数据层 (`secs2`)

| 接口/类 | 说明 |
|---------|------|
| `Secs2` | **核心数据接口**，不可变，支持所有 SECS-II 数据类型的构建与解析 |
| `Secs2Item` | 数据类型枚举：LIST, B, BOOLEAN, A, I1, I2, I4, I8, U1, U2, U4, U8, F4, F8 |
| `Secs2Builder` | Secs2 构建器接口 |
| `Secs2Exception` | SECS-II 解析异常 |

**支持的数据类型：**

```
Secs2
├── Secs2List          <L [n]
├── Secs2Binary        <B [n]
├── Secs2Boolean       <BOOLEAN [n]
├── Secs2Ascii         <A [n]
├── Secs2Int1/2/4/8    <I1/I2/I4/I8 [n]
├── Secs2Uint1/2/4/8   <U1/U2/U4/U8 [n]
├── Secs2Float4/8      <F4/F8 [n]
├── Secs2BigInteger    (数值大数表示)
├── Secs2Number        (数值抽象)
├── Secs2RawBytes      (原始字节)
├── Secs2Jis8          (JIS-8 编码)
└── Secs2Unicode       (Unicode 编码)
```

### 4. GEM 模型 (`gem`)

| 接口/类 | 说明 |
|---------|------|
| `Gem` | GEM 接口，提供 S1F1~S9Fy 等标准消息方法 |
| `GemConfig` | GEM 配置（MDLN、SOFTREV、ClockType 等） |
| `DynamicEventReportConfig` | 动态事件报告配置 |
| `DynamicReport` | 动态报告 |
| `DynamicCollectionEvent` | 动态采集事件 |
| `DynamicLink` | 动态链接 |
| `Clock` | 时钟（A[12]/A[16] 格式） |

**ACK 码枚举：**

| 枚举 | 用途 |
|------|------|
| `COMMACK` | S1F14 通信建立确认 |
| `ONLACK` | S1F18 在线确认 |
| `OFLACK` | S1F16 离线确认 |
| `ACKC3` | S3 确认码 |
| `ACKC5` | S5 确认码 |
| `ACKC6` | S6 确认码 |
| `ACKC7` | S7 确认码 |
| `DRACK` | S2F33 定义报告确认 |
| `LRACK` | S2F35 链接确认 |
| `ERACK` | S2F37 事件使能确认 |
| `HCACK` | S2F40 主机命令确认 |
| `CMDA` | S2F22/S2F28 命令确认 |
| `TIACK` | S2F31 时间设置确认 |
| `GRANT` / `GRANT6` | 多块传输许可 |
| `CEED` | 采集事件使能/禁止 |

### 5. SML 消息语言 (`sml`)

| 接口/类 | 说明 |
|---------|------|
| `SmlMessage` | SML 消息接口（Stream/Function/WBit/SECS-II） |
| `SmlMessageParser` | SML 消息解析器 |
| `SmlDataItemParser` | SML 数据项解析器 |

### 6. 响应式属性框架 (`local.property`)

提供类似 JavaFX Property 的响应式数据绑定能力，是整个项目状态管理的基础设施。

| 类别 | 类型 |
|------|------|
| **基础接口** | `Gettable`, `Settable`, `Observable`, `Property` |
| **布尔** | `BooleanProperty`, `BooleanGettable`, `BooleanSettable`, `BooleanObservable` |
| **整数** | `IntegerProperty`, `IntegerCompution` |
| **长整** | `LongProperty`, `LongCompution` |
| **浮点** | `FloatProperty`, `DoubleProperty` |
| **字符串** | `StringProperty`, `StringGettable`, `StringCompution` |
| **对象** | `ObjectProperty`, `ObjectGettable`, `ObjectObservable` |
| **集合** | `ListProperty`, `SetProperty`, `MapProperty`, `CollectionProperty` |
| **数值** | `NumberProperty`, `NumberGettable`, `NumberObservable` |
| **超时** | `TimeoutProperty`, `TimeoutGettable` |
| **计算** | `Compution`, `LogicalCompution`, `ComparativeCompution` |

### 7. 工具层 (`util`)

| 接口/类 | 说明 |
|---------|------|
| `SecsCommunicatorEntity` | 通信器实体封装，简化消息路由（按 SxFy 注册监听器） |
| `EntityMessageReceiveListener` | 实体消息接收监听器 |
| `EntityEventAdapter` | 实体事件适配器 |
| `EntityMessageSender` | 实体消息发送器 |

---

## 设计模式

### 1. 接口隔离 + 组合

`SecsCommunicator` 通过组合多个小接口实现职责分离：

```
SecsCommunicator
├── OpenAndCloseable              生命周期管理
├── SecsGemAccessor               GEM 接口访问
├── SecsMessageReceiveObservable  消息接收订阅
├── SecsMessagePassThroughObservable  消息透传订阅
├── SecsCommunicateStateDetectable    通信状态检测
└── SecsLogObservable             日志订阅
```

### 2. 观察者模式

项目广泛使用观察者模式进行事件通知：

- **消息接收**: `SecsMessageReceiveListener` / `SecsMessageReceiveBiListener`
- **通信状态变化**: `SecsCommunicatableStateChangeListener`
- **日志**: `SecsLogListener`
- **消息透传**: `SecsMessagePassThroughListener`
- **HSMS 状态**: `HsmsCommunicateStateChangeListener`

### 3. 工厂方法

各通信器通过静态工厂方法创建，根据配置自动选择 Active/Passive 实现：

```java
// HSMS-SS: 根据 connectionMode 创建 Active 或 Passive 实例
HsmsSsCommunicator.newInstance(config)

// HSMS-GS: 根据 connectionMode 创建 Active 或 Passive 实例
HsmsGsCommunicator.newInstance(config)

// SECS-I on TCP/IP
Secs1OnTcpIpCommunicator.newInstance(config)
```

### 4. 模板方法

`impl` 包中的抽象类定义了算法骨架，子类填充具体协议细节：

```
AbstractSecsCommunicator (核心通信模板)
├── AbstractHsmsSsCommunicator (HSMS-SS 公共逻辑)
│   ├── AbstractHsmsSsActiveCommunicator
│   └── AbstractHsmsSsPassiveCommunicator
├── AbstractHsmsGsCommunicator (HSMS-GS 公共逻辑)
│   ├── AbstractHsmsGsActiveCommunicator
│   └── AbstractHsmsGsPassiveCommunicator
└── AbstractSecs1Communicator (SECS-I 逻辑)
    └── AbstractSecs1OnTcpIpCommunicator
```

### 5. 响应式属性

`local.property` 包实现了完整的响应式属性框架，支持：

- 值的读写（Gettable/Settable）
- 变更监听（ChangeListener）
- 计算属性（Compution）
- 集合属性（List/Set/Map/Collection）

---

## 类继承关系

```
OpenAndCloseable (interface)
│
└── SecsCommunicator (interface)
    │
    ├── HsmsSsCommunicator (interface, HSMS-SS)
    │   └── impl.AbstractHsmsSsCommunicator
    │       ├── impl.AbstractHsmsSsActiveCommunicator
    │       └── impl.AbstractHsmsSsPassiveCommunicator
    │
    ├── Secs1Communicator (interface, SECS-I)
    │   └── impl.AbstractSecs1Communicator
    │       └── secs1ontcpip.impl.AbstractSecs1OnTcpIpCommunicator
    │
    └── impl.AbstractSecsCommunicator (核心抽象实现)
        ├── HSMS-SS Active/Passive (通过继承链)
        └── SECS-I on TCP/IP (通过继承链)

HsmsGsCommunicator (interface, 独立继承 OpenAndCloseable)
└── impl.AbstractHsmsGsCommunicator
    ├── impl.AbstractHsmsGsActiveCommunicator
    └── impl.AbstractHsmsGsPassiveCommunicator
```

---

## 数据流

### 消息发送流程

```
应用层
  │
  ├─ SecsCommunicator.send(strm, func, wbit, secs2)
  │   或 SecsCommunicator.send(SmlMessage)
  │
  ▼
SecsMessage 构建
  │
  ├─ HSMS-SS/GS: HsmsMessage (10字节头 + SECS-II body)
  │   └─ TCP Socket 发送
  │
  └─ SECS-I: Secs1Message → Secs1MessageBlock[] (分块)
      └─ TCP/IP 串口适配层发送
```

### 消息接收流程

```
网络/串口层
  │
  ├─ HSMS: 接收 → HsmsMessage 解析 → SecsMessage
  └─ SECS-I: 接收 → Secs1MessageBlock[] → 组装 → Secs1Message → SecsMessage
  │
  ▼
消息分发
  │
  ├─ SecsMessageReceiveListener (Primary 消息)
  ├─ SecsMessagePassThroughListener (所有消息透传)
  └─ SecsLogListener (日志)
```

---

## 配置体系

```
AbstractSecsCommunicatorConfig
├── SecsTimeout (T1~T8 超时)
├── GemConfig (MDLN, SOFTREV, ClockType)
├── BooleanProperty isEquip
├── StringProperty name
└── StringProperty logSubjectHeader

AbstractHsmsCommunicatorConfig (extends AbstractSecsCommunicatorConfig)
├── HsmsConnectionMode (ACTIVE / PASSIVE)
├── InetSocketAddress
├── Session ID
├── Linktest 间隔
└── Rebind 间隔 (Passive)

Secs1OnTcpIpCommunicatorConfig (extends AbstractSecs1CommunicatorConfig)
├── InetSocketAddress
├── Device ID
├── Master/Slave
└── Retry 次数
```

---

## 异常体系

```
SecsException (基础异常)
├── SecsSendMessageException         发送失败
├── SecsWaitReplyMessageException    等待回复失败（含 T3 超时）
├── secs2.Secs2Exception             SECS-II 数据解析异常
├── hsms.HsmsException               HSMS 通信异常
│   ├── HsmsNotConnectedException
│   ├── HsmsTimeoutT3/T6/T7/T8Exception
│   ├── HsmsRejectException
│   └── HsmsWaitReplyMessageException
├── secs1.Secs1Exception             SECS-I 通信异常
│   ├── Secs1TimeoutT1/T2/T3/T4Exception
│   ├── Secs1RetryOverException
│   ├── Secs1SumCheckMismatchException
│   └── Secs1SendMessageException
└── sml.SmlParseException             SML 解析异常
```

---

## 构建信息

| 项目 | 值 |
|------|-----|
| Java 版本 | 1.8 |
| 构建工具 | Maven |
| 源码文件数 | 417 |
| 测试文件数 | 22 |
| 测试框架 | JUnit Jupiter 5.9.3 |
| 外部依赖 | 无（纯 JDK 实现） |

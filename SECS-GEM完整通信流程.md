# SECS/GEM 完整通信交互流程

## 概述

本文档描述了一台 Host（主动请求端）与 Equipment（被动响应端）之间完整的 SECS/GEM 通信交互流程。流程按照实际生产场景中的典型顺序组织，涵盖从连接建立到最终离线的全部阶段。

## 通信角色说明

| 角色 | 模式 | 职责 |
|------|------|------|
| **Host** | Active（主动连接） | 发起连接、发送指令、收集数据 |
| **Equipment** | Passive（被动监听） | 接受连接、响应指令、上报事件 |

---

## 完整通信流程图

```
阶段1: 连接建立          阶段2: 通信建立          阶段3: 上线
┌──────────┐           ┌──────────┐           ┌──────────┐
│   Host   │           │   Host   │           │   Host   │
│ (Active) │           │ (Active) │           │ (Active) │
└────┬─────┘           └────┬─────┘           └────┬─────┘
     │                      │                      │
     │  HSMS SELECT         │  S1F13               │  S1F17
     ├─────────────────────▶│─────────────────────▶│──────────▶
     │                      │  S1F14               │  S1F18
     │  ◀───────────────────│◀─────────────────────│───────────
     │                      │                      │
┌────┴─────┐           ┌────┴─────┐           ┌────┴─────┐
│Equipment │           │Equipment │           │Equipment │
│(Passive) │           │(Passive) │           │(Passive) │
└──────────┘           └──────────┘           └──────────┘

阶段4: 时间同步          阶段5: 数据收集配置       阶段6: 生产运行
┌──────────┐           ┌──────────┐           ┌──────────┐
│   Host   │           │   Host   │           │   Host   │
└────┬─────┘           └────┬─────┘           └────┬─────┘
     │                      │                      │
     │  S2F17/S2F31         │  S2F33/F35/F37       │  S1F3/S2F23/S6F1
     ├─────────────────────▶│─────────────────────▶│──────────▶
     │                      │                      │  S5F1/S6F11
     │  ◀───────────────────│◀─────────────────────│◀──────────
     │                      │                      │
┌────┴─────┐           ┌────┴─────┐           ┌────┴─────┐
│Equipment │           │Equipment │           │Equipment │
└──────────┘           └──────────┘           └──────────┘

阶段7: 过程管理          阶段8: 报警管理         阶段9: 离线
┌──────────┐           ┌──────────┐           ┌──────────┐
│   Host   │           │   Host   │           │   Host   │
└────┬─────┘           └────┬─────┘           └────┬─────┘
     │                      │                      │
     │  S7F19/S7F25         │  S5F3/S5F5           │  S1F15
     ├─────────────────────▶│─────────────────────▶│──────────▶
     │                      │  S5F1                │  S1F16
     │  ◀───────────────────│◀─────────────────────│───────────
     │                      │                      │
┌────┴─────┐           ┌────┴─────┐           ┌────┴─────┐
│Equipment │           │Equipment │           │Equipment │
└──────────┘           └──────────┘           └──────────┘
```

---

## 阶段1：连接建立

### 1.1 HSMS 连接（SEMI-E37）

在发送任何 SECS 消息之前，Host 需要先与 Equipment 建立 HSMS 连接。

```
Host                                    Equipment
  │                                         │
  │  TCP Connect                              │
  ├─────────────────────────────────────────▶│
  │                                         │
  │  Linktest (S0F0)                         │
  ├─────────────────────────────────────────▶│
  │                                         │
  │  SELECT Request                          │
  ├─────────────────────────────────────────▶│
  │                                         │
  │  SELECT Response                         │
  │  ◀──────────────────────────────────────│
  │                                         │
  │  ═══ HSMS SELECTED 状态 ═══              │
  │                                         │
```

**代码示例**：
```java
HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
config.socketAddress(new InetSocketAddress("192.168.1.100", 5000));
config.connectionMode(HsmsConnectionMode.ACTIVE);
config.sessionId(0);
config.isEquip(false);
config.timeout().t3(45.0F);
config.timeout().t5(10.0F);
config.timeout().t6(5.0F);
config.timeout().t7(10.0F);
config.timeout().t8(6.0F);

try (HsmsSsCommunicator comm = HsmsSsCommunicator.newInstance(config)) {
    
    // 等待连接建立并进入 SELECTED 状态
    comm.openAndWaitUntilCommunicatable();
    
    // 继续后续操作...
}
```

---

## 阶段2：通信建立与在线检查

### 2.1 在线确认 — S1F1/S1F2

Host 首先确认 Equipment 是否在线可用。

```
Host                                    Equipment
  │                                         │
  │  S1F1 W (Are You There?)                │
  │  ─────────────────────────────────────▶  │
  │                                         │
  │  S1F2 (On Line Data)                    │
  │  ◀─────────────────────────────────────  │
  │  <L[2] <A[MDLN]> <A[SOFTREV]>>         │
  │                                         │
```

**代码示例**：
```java
// Host 发送在线查询
Optional<SecsMessage> reply = comm.gem().s1f1();

reply.ifPresent(msg -> {
    System.out.println("Equipment 在线");
    String mdln = msg.secs2().getAscii(0);     // 设备型号
    String softrev = msg.secs2().getAscii(1);  // 软件版本
    System.out.println("型号: " + mdln + ", 软件版本: " + softrev);
});
```

### 2.2 建立通信 — S1F13/S1F14

Host 正式请求与 Equipment 建立 SECS 通信。

```
Host                                    Equipment
  │                                         │
  │  S1F13 W (Establish Communications)     │
  │  <L[0]>                                 │
  │  ─────────────────────────────────────▶  │
  │                                         │
  │  S1F14 (Establish Comm Ack)             │
  │  ◀─────────────────────────────────────  │
  │  <L[n+1] <B[1] COMMACK> ...>            │
  │                                         │
```

**COMMACK 确认码**：

| 值 | 名称 | 含义 |
|----|------|------|
| 0 | OK | 通信建立成功 |
| 1 | Busy | 设备忙，无法接受 |
| 2 | Selected | 已经建立过通信 |
| 3 | NotReady | 设备未准备好 |

**代码示例**：
```java
COMMACK commack = comm.gem().s1f13();

if (commack != COMMACK.OK) {
    System.err.println("通信建立失败: " + commack);
    return;
}
System.out.println("通信建立成功");
```

### 2.3 请求上线 — S1F17/S1F18

Host 请求 Equipment 切换到在线状态，准备接受生产指令。

```
Host                                    Equipment
  │                                         │
  │  S1F17 W (Go Online Request)            │
  │  ─────────────────────────────────────▶  │
  │                                         │
  │  S1F18 (Go Online Ack)                  │
  │  ◀─────────────────────────────────────  │
  │  <B[1] ONLACK>                          │
  │                                         │
```

**ONLACK 确认码**：

| 值 | 名称 | 含义 |
|----|------|------|
| 0 | OK | 成功上线 |
| 1 | Refused | 拒绝上线 |
| 2 | AlreadyOnline | 设备已经在线 |

**代码示例**：
```java
ONLACK onlack = comm.gem().s1f17();

switch (onlack) {
case OK:
    System.out.println("设备上线成功");
    break;
case AlreadyOnline:
    System.out.println("设备已经在线，继续操作");
    break;
case Refused:
    System.err.println("设备拒绝上线请求");
    return;
default:
    System.err.println("未知状态: " + onlack);
    return;
}
```

---

## 阶段3：时间同步

### 3.1 获取设备时间 — S2F17/S2F18

Host 读取 Equipment 的当前时间，用于判断是否需要校准。

```
Host                                    Equipment
  │                                         │
  │  S2F17 W (Date and Time Request)        │
  │  ─────────────────────────────────────▶  │
  │                                         │
  │  S2F18 (Date and Time Response)         │
  │  ◀─────────────────────────────────────  │
  │  <A[16] "2026010112000000">             │
  │                                         │
```

### 3.2 设置设备时间 — S2F31/S2F32

如果设备时间与 Host 不一致，Host 校准设备时间。

```
Host                                    Equipment
  │                                         │
  │  S2F31 W (Date and Time Set)            │
  │  <A[16] "2026010112000000">             │
  │  ─────────────────────────────────────▶  │
  │                                         │
  │  S2F32 (Date and Time Set Ack)          │
  │  ◀─────────────────────────────────────  │
  │  <B[1] TIACK>                           │
  │                                         │
```

**代码示例**：
```java
// 获取设备时间
Clock equipClock = comm.gem().s2f17();
System.out.println("设备当前时间: " + equipClock);

// 同步为当前时间
TIACK tiack = comm.gem().s2f31Now();
if (tiack == TIACK.OK) {
    System.out.println("时间同步成功");
}
```

---

## 阶段4：数据收集配置

### 4.1 查询状态变量名称 — S1F11/S1F12

Host 获取 Equipment 支持的所有状态变量及其名称。

```
Host                                    Equipment
  │                                         │
  │  S1F11 W (Status Variable Namelist)     │
  │  <L[0]>  (空列表=请求所有)               │
  │  ─────────────────────────────────────▶  │
  │                                         │
  │  S1F12 (Status Variable Namelist Resp)  │
  │  ◀─────────────────────────────────────  │
  │  <L[n]                                  │
  │    <L[2] <U4[SVVID]> <A[SVNM]> >        │
  │    <L[2] <U4[SVVID]> <A[SVNM]> >        │
  │    ...                                  │
  │  >                                      │
  │                                         │
```

**代码示例**：
```java
// 请求所有状态变量名称
Optional<SecsMessage> reply = comm.send(1, 11, true, Secs2.list());

reply.ifPresent(msg -> {
    Secs2 list = msg.secs2();
    for (Secs2 item : list) {
        long svid = item.getLong(0);
        String name = item.getAscii(1);
        System.out.println("SVVID=" + svid + " 名称=" + name);
    }
});
```

### 4.2 定义报告 — S2F33/S2F34

Host 定义要收集的数据报告，指定每个报告包含哪些采样变量。

```
Host                                    Equipment
  │                                         │
  │  S2F33 W (Define Report)                │
  │  <L[2]                                  │
  │    <U4[DATAID]>                         │
  │    <L[n]                                │
  │      <L[3]                              │
  │        <U4[RPTID]>                      │
  │        <BOOLEAN[CEID]>                  │
  │        <L[m] <U4[SPID]> ...>            │
  │      >                                  │
  │    >                                    │
  │  >                                      │
  │  ─────────────────────────────────────▶  │
  │                                         │
  │  S2F34 (Define Report Ack)              │
  │  ◀─────────────────────────────────────  │
  │  <B[1] DRACK>                           │
  │                                         │
```

**DRACK 确认码**：

| 值 | 名称 | 含义 |
|----|------|------|
| 0 | OK | 定义成功 |
| 1 | SampleOverflow | 采样数量溢出 |
| 2 | SampleDoesNotExist | 采样变量不存在 |
| 3 | DefinitionError | 定义错误 |

**代码示例**：
```java
DynamicEventReportConfig config = comm.gem().newDynamicEventReportConfig();

// 定义报告: 报告ID=1001, 包含采样变量 1, 2, 3
DynamicReport report = config.addDefineReport("myReport",
    Arrays.asList(Long.valueOf(1001)));

// 发送定义请求
DRACK drack = config.s2f33Define();
if (drack != DRACK.OK) {
    System.err.println("报告定义失败: " + drack);
}
```

### 4.3 链接事件报告 — S2F35/S2F36

Host 将报告链接到集合事件，当事件触发时自动上报数据。

```
Host                                    Equipment
  │                                         │
  │  S2F35 W (Link Event Report)            │
  │  <L[2]                                  │
  │    <U4[DATAID]>                         │
  │    <L[n]                                │
  │      <L[2]                              │
  │        <U4[CEID]>                       │
  │        <L[m] <U4[RPTID]> ...>           │
  │      >                                  │
  │    >                                    │
  │  >                                      │
  │  ─────────────────────────────────────▶  │
  │                                         │
  │  S2F36 (Link Event Report Ack)          │
  │  ◀─────────────────────────────────────  │
  │  <B[1] LRACK>                           │
  │                                         │
```

**代码示例**：
```java
// 将报告链接到集合事件 CEID=101
DynamicCollectionEvent ce = config.addEnableCollectionEvent("myEvent", 101);
config.addLinkByReport(ce, Arrays.asList(report));

LRACK lrack = config.s2f35();
if (lrack != LRACK.OK) {
    System.err.println("事件链接失败: " + lrack);
}
```

### 4.4 启用/禁用事件报告 — S2F37/S2F38

Host 启用已配置的事件报告。

```
Host                                    Equipment
  │                                         │
  │  S2F37 W (Enable/Disable Event Report)  │
  │  <L[2]                                  │
  │    <U1[CEDTYPE]>  (1=启用, 0=禁用)      │
  │    <L[n] <U4[CEID]> ...>                │
  │  >                                      │
  │  ─────────────────────────────────────▶  │
  │                                         │
  │  S2F38 (Enable/Disable Event Ack)       │
  │  ◀─────────────────────────────────────  │
  │  <B[1] ERACK>                           │
  │                                         │
```

**代码示例**：
```java
// 启用事件报告
ERACK erack = config.s2f37Enable();
if (erack != ERACK.OK) {
    System.err.println("启用事件报告失败: " + erack);
}

// 或者禁用所有事件报告
// ERACK erack = comm.gem().s2f37DisableAll();
```

---

## 阶段5：生产运行 — 正常数据交互

### 5.1 查询设备状态 — S1F3/S1F4

Host 在生产过程中定期查询 Equipment 的状态变量。

```
Host                                    Equipment
  │                                         │
  │  S1F3 W (Selected Equipment Status)     │
  │  <L[1] <U4[SVVID]>>                     │
  │  ─────────────────────────────────────▶  │
  │                                         │
  │  S1F4 (Selected Equipment Status Data)  │
  │  ◀─────────────────────────────────────  │
  │  <L[2] <U4[SVVID]> <value>>             │
  │                                         │
```

**代码示例**：
```java
// 查询状态变量 SVVID=1 (设备运行状态)
Secs2 request = Secs2.list(Secs2.uint4(1));
Optional<SecsMessage> reply = comm.send(1, 3, true, request);

reply.ifPresent(msg -> {
    long svid = msg.secs2().getLong(0);
    Secs2 value = msg.secs2().get(1);
    System.out.println("SVVID=" + svid + " 值=" + value.toJson());
});
```

### 5.2 启动远程命令 — S2F23/S2F28

Host 向 Equipment 发送远程命令，启动加工过程。

```
Host                                    Equipment
  │                                         │
  │  S2F23 W (Initiate Processing)          │
  │  <L[2] <U1[CMDTYPE]> <A[CMD]>>          │
  │  ─────────────────────────────────────▶  │
  │                                         │
  │  S2F28 (Initiate Processing Ack)        │
  │  ◀─────────────────────────────────────  │
  │  <B[1] CMDA>                            │
  │                                         │
```

**CMDA 确认码**：

| 值 | 名称 | 含义 |
|----|------|------|
| 1 | CommandWillExecute | 命令将执行 |
| 2 | CommandWillNotExecute | 命令不会执行 |
| 3 | CommandDoesNotExist | 命令不存在 |
| 4 | CommandFormatError | 命令格式错误 |

**代码示例**：
```java
// 发送远程命令 "START"
Secs2 cmdData = Secs2.list(
    Secs2.uint1(1),           // CMDTYPE = 1 (立即执行)
    Secs2.ascii("START")      // 命令字符串
);
Optional<SecsMessage> reply = comm.send(2, 23, true, cmdData);
```

### 5.3 发送带参数的命令 — S2F41/S2F42

Host 向 Equipment 发送带参数的复杂命令。

```
Host                                    Equipment
  │                                         │
  │  S2F41 W (Host Command Send)            │
  │  <L[3]                                  │
  │    <U4[DATAID]>                         │
  │    <U1[DSID]>                           │
  │    <L[n]                                │
  │      <L[2] <U4[PPID]> <value>>          │
  │    >                                    │
  │  >                                      │
  │  ─────────────────────────────────────▶  │
  │                                         │
  │  S2F42 (Host Command Send Ack)          │
  │  ◀─────────────────────────────────────  │
  │  <B[1] HCACK>                           │
  │                                         │
```

### 5.4 请求跟踪数据 — S6F1/S6F2

Host 请求 Equipment 发送历史跟踪数据。

```
Host                                    Equipment
  │                                         │
  │  S6F1 W (Trace Data Request)            │
  │  <L[3]                                  │
  │    <U4[DATAID]>                         │
  │    <U1[TRID]>                           │
  │    <L[n] <U4[SPID]> ...>                │
  │  >                                      │
  │  ─────────────────────────────────────▶  │
  │                                         │
  │  S6F2 (Trace Data Response)             │
  │  ◀─────────────────────────────────────  │
  │  <L[3] <U4[DATAID]> <U1[TRID]> <data>>  │
  │                                         │
```

### 5.5 设备主动上报事件 — S6F11/S6F12

Equipment 在集合事件触发时，主动向 Host 上报数据。

```
Host                                    Equipment
  │                                         │
  │                                         │  (事件触发)
  │  S6F11 (Event Report Send)              │
  │  <L[5] <DATAID> <CEID> <DT>             │
  │        <L[report]> <ACKC6>>             │
  │  ◀─────────────────────────────────────  │
  │                                         │
  │  S6F12 (Event Report Ack)               │
  │  ─────────────────────────────────────▶  │
  │  <B[1] ACKC6>                           │
  │                                         │
```

**代码示例（Equipment 端发送）**：
```java
// Equipment 端: 事件触发时发送报告
Secs2 eventData = Secs2.list(
    Secs2.uint4(1),                        // DATAID
    Secs2.uint4(101),                      // CEID (集合事件ID)
    Secs2.ascii(Clock.now().toAscii16()),  // 时间戳
    Secs2.list(                            // 报告数据
        Secs2.list(
            Secs2.uint4(1),
            Secs2.list(Secs2.int4(currentValue))
        )
    ),
    Secs2.bool(true)                       // ACKC6
);
comm.send(6, 11, false, eventData);
```

### 5.6 主动请求事件报告 — S6F15/S6F16

Host 主动请求 Equipment 发送特定事件的数据。

```
Host                                    Equipment
  │                                         │
  │  S6F15 W (Event Report Request)         │
  │  ─────────────────────────────────────▶  │
  │                                         │
  │  S6F16 (Event Report Data)              │
  │  ◀─────────────────────────────────────  │
  │                                         │
  │  S6F16 Ack                              │
  │  ─────────────────────────────────────▶  │
  │                                         │
```

**代码示例**：
```java
// Host 请求特定事件的数据
DynamicCollectionEvent ce = config.getCollectionEvent("myEvent");
Optional<SecsMessage> reply = comm.gem().s6f15(ce);
```

---

## 阶段6：过程管理

### 6.1 查询设备常量 — S7F19/S7F20

Host 获取 Equipment 的设备常量配置。

```
Host                                    Equipment
  │                                         │
  │  S7F19 W (Equipment Constant Request)   │
  │  <L[0]>  (空列表=请求所有)               │
  │  ─────────────────────────────────────▶  │
  │                                         │
  │  S7F20 (Equipment Constant Data)        │
  │  ◀─────────────────────────────────────  │
  │  <L[n]                                  │
  │    <L[2] <U4[ECID]> <value>>            │
  │  >                                      │
  │                                         │
```

**代码示例**：
```java
// 请求所有设备常量
Optional<SecsMessage> reply = comm.send(7, 19, true, Secs2.list());

reply.ifPresent(msg -> {
    Secs2 list = msg.secs2();
    for (Secs2 item : list) {
        long ecid = item.getLong(0);
        Secs2 value = item.get(1);
        System.out.println("ECID=" + ecid + " 值=" + value.toJson());
    }
});
```

### 6.2 查询当前加工程序 — S7F25/S7F26

Host 获取 Equipment 当前正在执行的加工程序。

```
Host                                    Equipment
  │                                         │
  │  S7F25 W (Current PP Request)           │
  │  ─────────────────────────────────────▶  │
  │                                         │
  │  S7F26 (Current PP Response)            │
  │  ◀─────────────────────────────────────  │
  │  <L[2] <A[PPID]> <U1[LOTSIZE]>>         │
  │                                         │
```

**代码示例**：
```java
Optional<SecsMessage> reply = comm.send(7, 25, true);

reply.ifPresent(msg -> {
    String ppName = msg.secs2().getAscii(0);
    int lotSize = msg.secs2().getInt(1);
    System.out.println("当前加工程序: " + ppName + ", Lot大小: " + lotSize);
});
```

---

## 阶段7：报警管理

### 7.1 查询报警名称列表 — S5F5/S5F6

Host 获取 Equipment 支持的所有报警及其名称。

```
Host                                    Equipment
  │                                         │
  │  S5F5 W (Alarm Namelist Request)        │
  │  <L[0]>  (空列表=请求所有)               │
  │  ─────────────────────────────────────▶  │
  │                                         │
  │  S5F6 (Alarm Namelist Response)         │
  │  ◀─────────────────────────────────────  │
  │  <L[n]                                  │
  │    <L[2] <U4[ALARID]> <A[ALARMNAME]>>   │
  │  >                                      │
  │                                         │
```

**代码示例**：
```java
Optional<SecsMessage> reply = comm.send(5, 5, true, Secs2.list());

reply.ifPresent(msg -> {
    Secs2 list = msg.secs2();
    for (Secs2 item : list) {
        long alarmId = item.getLong(0);
        String name = item.getAscii(1);
        System.out.println("ALARID=" + alarmId + " 名称=" + name);
    }
});
```

### 7.2 启用/禁用报警 — S5F3/S5F4

Host 启用或禁用 Equipment 的特定报警。

```
Host                                    Equipment
  │                                         │
  │  S5F3 W (Enable/Disable Alarm)          │
  │  <L[2]                                  │
  │    <U1[ALEDTYPE]>  (1=启用, 0=禁用)     │
  │    <L[n] <U4[ALARID]> ...>              │
  │  >                                      │
  │  ─────────────────────────────────────▶  │
  │                                         │
  │  S5F4 (Enable/Disable Alarm Ack)        │
  │  ◀─────────────────────────────────────  │
  │  <B[1] ACKC5>                           │
  │                                         │
```

### 7.3 接收报警报告 — S5F1/S5F2

Equipment 发生异常时，主动向 Host 上报报警。

```
Host                                    Equipment
  │                                         │
  │                                         │  (报警触发)
  │  S5F1 (Alarm Report Send)               │
  │  <L[4] <U4[ALARID]> <A[ALRDTY]>         │
  │        <A[ALRMESG]> <ACKC5>>            │
  │  ◀─────────────────────────────────────  │
  │                                         │
  │  S5F2 (Alarm Report Ack)                │
  │  ─────────────────────────────────────▶  │
  │  <B[1] ACKC5>                           │
  │                                         │
```

**代码示例（Host 端监听报警）**：
```java
comm.addSecsMessageReceiveListener(msg -> {
    if (msg.getStream() == 5 && msg.getFunction() == 1) {
        try {
            long alarmId = msg.secs2().getLong(0);
            String alarmDate = msg.secs2().getAscii(1);
            String alarmMsg = msg.secs2().getAscii(2);
            
            System.out.println("=== 报警 ===");
            System.out.println("报警ID: " + alarmId);
            System.out.println("时间: " + alarmDate);
            System.out.println("内容: " + alarmMsg);
            
            // 回复确认
            comm.gem().s5f2(msg, ACKC5.OK);
            
        } catch (Secs2Exception e) {
            e.printStackTrace();
        }
    }
});
```

---

## 阶段8：离线与断开

### 8.1 请求离线 — S1F15/S1F16

生产任务完成后，Host 请求 Equipment 切换到离线状态。

```
Host                                    Equipment
  │                                         │
  │  S1F15 W (Go Offline Request)           │
  │  ─────────────────────────────────────▶  │
  │                                         │
  │  S1F16 (Go Offline Ack)                 │
  │  ◀─────────────────────────────────────  │
  │  <B[1] OFLACK>                          │
  │                                         │
```

**代码示例**：
```java
OFLACK oflack = comm.gem().s1f15();
if (oflack == OFLACK.OK) {
    System.out.println("设备已离线");
}
```

### 8.2 断开 HSMS 连接

```java
// 关闭通信器，自动发送 DESELECT 并断开 TCP 连接
comm.close();
```

---

## 完整代码示例

```java
import java.net.InetSocketAddress;
import java.util.*;
import com.shimizukenta.secs.*;
import com.shimizukenta.secs.gem.*;
import com.shimizukenta.secs.hsms.*;
import com.shimizukenta.secs.hsmsss.*;
import com.shimizukenta.secs.secs2.*;

public class HostFullWorkflow {
    
    public static void main(String[] args) {
        
        // ═══════════════════════════════════════
        // 阶段1: 连接建立
        // ═══════════════════════════════════════
        
        HsmsSsCommunicatorConfig config = new HsmsSsCommunicatorConfig();
        config.socketAddress(new InetSocketAddress("192.168.1.100", 5000));
        config.connectionMode(HsmsConnectionMode.ACTIVE);
        config.sessionId(0);
        config.isEquip(false);
        config.timeout().t3(45.0F);
        config.timeout().t5(10.0F);
        config.timeout().t6(5.0F);
        config.timeout().t7(10.0F);
        config.timeout().t8(6.0F);
        
        try (HsmsSsCommunicator comm = HsmsSsCommunicator.newInstance(config)) {
            
            // 添加日志监听
            comm.addSecsLogListener(System.out::println);
            
            // 添加报警监听
            comm.addSecsMessageReceiveListener(msg -> {
                if (msg.getStream() == 5 && msg.getFunction() == 1) {
                    handleAlarm(comm, msg);
                }
            });
            
            // 等待连接建立
            comm.openAndWaitUntilCommunicatable();
            System.out.println("=== HSMS 连接已建立 ===");
            
            // ═══════════════════════════════════════
            // 阶段2: 通信建立
            // ═══════════════════════════════════════
            
            // S1F1: 在线确认
            Optional<SecsMessage> s1f2 = comm.gem().s1f1();
            System.out.println("=== Equipment 在线 ===");
            
            // S1F13: 建立通信
            COMMACK commack = comm.gem().s1f13();
            if (commack != COMMACK.OK) {
                System.err.println("通信建立失败: " + commack);
                return;
            }
            System.out.println("=== 通信已建立 ===");
            
            // S1F17: 请求上线
            ONLACK onlack = comm.gem().s1f17();
            if (onlack != ONLACK.OK && onlack != ONLACK.AlreadyOnline) {
                System.err.println("上线失败: " + onlack);
                return;
            }
            System.out.println("=== 设备已上线 ===");
            
            // ═══════════════════════════════════════
            // 阶段3: 时间同步
            // ═══════════════════════════════════════
            
            // S2F17: 获取设备时间
            Clock equipClock = comm.gem().s2f17();
            System.out.println("设备时间: " + equipClock);
            
            // S2F31: 同步时间
            TIACK tiack = comm.gem().s2f31Now();
            System.out.println("时间同步: " + tiack);
            
            // ═══════════════════════════════════════
            // 阶段4: 数据收集配置
            // ═══════════════════════════════════════
            
            // S1F11: 查询状态变量名称
            Optional<SecsMessage> svNames = comm.send(1, 11, true, Secs2.list());
            System.out.println("=== 状态变量列表已获取 ===");
            
            // S2F33 + S2F35 + S2F37: 配置数据收集
            DynamicEventReportConfig evRptConf = comm.gem().newDynamicEventReportConfig();
            
            // 禁用所有旧配置
            evRptConf.s2f37DisableAll();
            evRptConf.s2f33DeleteAll();
            
            // 定义报告
            DynamicReport rptNow = evRptConf.addDefineReport("now",
                Arrays.asList(Long.valueOf(1001)));
            DynamicReport rptTemp = evRptConf.addDefineReport("temp",
                Arrays.asList(Long.valueOf(2001)));
            
            // 链接事件
            DynamicCollectionEvent ceStart = evRptConf.addEnableCollectionEvent(
                "start", 101);
            evRptConf.addLinkByReport(ceStart, Arrays.asList(rptNow, rptTemp));
            
            DRACK drack = evRptConf.s2f33Define();
            LRACK lrack = evRptConf.s2f35();
            ERACK erack = evRptConf.s2f37Enable();
            System.out.println("数据收集配置完成");
            
            // ═══════════════════════════════════════
            // 阶段5: 生产运行
            // ═══════════════════════════════════════
            
            // S1F3: 查询设备状态
            Secs2 statusReq = Secs2.list(Secs2.uint4(1));
            comm.send(1, 3, true, statusReq);
            
            // S7F25: 查询当前加工程序
            Optional<SecsMessage> ppReply = comm.send(7, 25, true);
            
            // S7F19: 查询设备常量
            Optional<SecsMessage> ecReply = comm.send(7, 19, true, Secs2.list());
            
            // S5F5: 查询报警列表
            Optional<SecsMessage> alarmList = comm.send(5, 5, true, Secs2.list());
            
            // S2F23: 启动加工
            Secs2 cmdData = Secs2.list(
                Secs2.uint1(1),
                Secs2.ascii("START")
            );
            comm.send(2, 23, true, cmdData);
            
            // S6F15: 请求事件报告
            evRptConf.s6f15("start");
            
            // S6F19: 请求单个报告
            evRptConf.s6f19("now");
            
            // 等待生产完成...
            Thread.sleep(10000);
            
            // ═══════════════════════════════════════
            // 阶段6: 离线
            // ═══════════════════════════════════════
            
            // S1F15: 请求离线
            OFLACK oflack = comm.gem().s1f15();
            System.out.println("离线结果: " + oflack);
            
            System.out.println("=== 通信流程结束 ===");
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private static void handleAlarm(SecsCommunicator comm, SecsMessage msg) {
        try {
            long alarmId = msg.secs2().getLong(0);
            String alarmDate = msg.secs2().getAscii(1);
            String alarmMsg = msg.secs2().getAscii(2);
            
            System.out.println("=== 报警 ===");
            System.out.println("ID: " + alarmId);
            System.out.println("时间: " + alarmDate);
            System.out.println("内容: " + alarmMsg);
            
            // 回复报警确认
            comm.gem().s5f2(msg, ACKC5.OK);
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```

---

## 消息交互总览表

| 序号 | 阶段 | 消息 | 方向 | W-Bit | 用途 |
|------|------|------|------|-------|------|
| 1 | 在线检查 | S1F1/S1F2 | H→E | true | 确认设备在线 |
| 2 | 通信建立 | S1F13/S1F14 | H→E | true | 建立SECS通信 |
| 3 | 请求上线 | S1F17/S1F18 | H→E | true | 设备切换到在线 |
| 4 | 时间查询 | S2F17/S2F18 | H→E | true | 获取设备时间 |
| 5 | 时间设置 | S2F31/S2F32 | H→E | true | 校准设备时间 |
| 6 | 变量查询 | S1F11/S1F12 | H→E | true | 获取状态变量名称 |
| 7 | 定义报告 | S2F33/S2F34 | H→E | true | 定义数据收集报告 |
| 8 | 链接事件 | S2F35/S2F36 | H→E | true | 链接报告到事件 |
| 9 | 启用事件 | S2F37/S2F38 | H→E | true | 启用事件报告 |
| 10 | 状态查询 | S1F3/S1F4 | H→E | true | 查询设备状态 |
| 11 | 启动处理 | S2F23/S2F28 | H→E | true | 启动远程命令 |
| 12 | 命令发送 | S2F41/S2F42 | H→E | true | 发送带参命令 |
| 13 | 跟踪数据 | S6F1/S6F2 | H→E | true | 请求跟踪数据 |
| 14 | 事件上报 | S6F11/S6F12 | E→H | false | 设备主动上报事件 |
| 15 | 事件请求 | S6F15/S6F16 | H→E | true | 主动请求事件数据 |
| 16 | 常量查询 | S7F19/S7F20 | H→E | true | 查询设备常量 |
| 17 | 程序查询 | S7F25/S7F26 | H→E | true | 查询当前加工程序 |
| 18 | 报警查询 | S5F5/S5F6 | H→E | true | 查询报警名称 |
| 19 | 报警控制 | S5F3/S5F4 | H→E | true | 启用/禁用报警 |
| 20 | 报警上报 | S5F1/S5F2 | E→H | false | 设备主动上报报警 |
| 21 | 请求离线 | S1F15/S1F16 | H→E | true | 设备切换到离线 |

---

## 参考

- SEMI E5 — SECS-I 标准
- SEMI E37 — HSMS 标准
- SEMI E5-SECS-II — 数据格式标准
- SEMI E30 — GEM 标准
- secs4java8 项目源码

# SECS/GEM 命令详解

## 概述

本文档详细解释了 secs4java8 库中支持的 SECS/GEM 协议命令。每个命令按照 `S{Stream}F{Function}` 的格式命名，例如 S1F1 表示 Stream 1 Function 1。

---

## Stream 1 - 设备控制 (Equipment Control)

### S1F1 - Are You There? (在线查询)

| 属性 | 值 |
|------|-----|
| **方向** | Host → Equipment |
| **W-Bit** | true |
| **数据体** | 空 |
| **回复** | S1F2 |

**含义**：Host 查询 Equipment 是否在线可用。这是最基本的连接测试消息。

**代码示例**：
```java
// Host 发送查询
Optional<SecsMessage> reply = comm.gem().s1f1();
```

---

### S1F3 - Selected Equipment Status Request (设备状态请求)

| 属性 | 值 |
|------|-----|
| **方向** | Host → Equipment |
| **W-Bit** | true |
| **数据体** | `<L[1] <U4[svid]>>` (状态变量ID列表) |
| **回复** | S1F4 |

**含义**：Host 请求获取 Equipment 的指定状态变量值。

**数据结构**：
```
S1F3 W
<L[1]
  <U4[svid]>  // 状态变量ID
>
```

**代码示例**：
```java
// 请求状态变量 ID=1 的值
Secs2 request = Secs2.list(Secs2.uint4(1));
Optional<SecsMessage> reply = comm.send(1, 3, true, request);
```

---

### S1F11 - Status Variable Namelist Request (状态变量名称请求)

| 属性 | 值 |
|------|-----|
| **方向** | Host → Equipment |
| **W-Bit** | true |
| **数据体** | `<L[0]>` 或 `<L[n] <U4[svid]...>>` |
| **回复** | S1F12 |

**含义**：Host 请求获取状态变量的名称列表。如果发送空列表，则请求所有状态变量。

**数据结构**：
```
S1F11 W
<L[0]>  // 空列表表示请求所有
或
<L[n]
  <U4[svid-1]>
  <U4[svid-2]>
  ...
>
```

---

### S1F13 - Establish Communications Request (建立通信请求)

| 属性 | 值 |
|------|-----|
| **方向** | Host → Equipment |
| **W-Bit** | true |
| **数据体** | `<L[2] <A[mdln]> <A[softrev]>>` (Equipment) 或 `<L[0]>` (Host) |
| **回复** | S1F14 |
| **返回类型** | COMMACK |

**含义**：Host 请求与 Equipment 建立 SECS 通信连接。这是通信建立的第一步。

**COMMACK 确认码**：

| 值 | 名称 | 含义 |
|----|------|------|
| 0 | OK | 通信接受 |
| 1 | Busy | 设备忙 |
| 2 | Selected | 已选择 |
| 3 | NotReady | 设备未准备好 |

**代码示例**：
```java
// Host 发起通信建立
COMMACK commack = host.gem().s1f13();
if (commack == COMMACK.OK) {
    System.out.println("通信建立成功");
}
```

---

### S1F15 - Go Offline Request (离线请求)

| 属性 | 值 |
|------|-----|
| **方向** | Host → Equipment |
| **W-Bit** | true |
| **数据体** | 空 |
| **回复** | S1F16 |
| **返回类型** | OFLACK |

**含义**：Host 请求 Equipment 切换到离线状态。

**OFLACK 确认码**：

| 值 | 名称 | 含义 |
|----|------|------|
| 0 | OK | 接受离线请求 |

**代码示例**：
```java
OFLACK oflack = host.gem().s1f15();
if (oflack == OFLACK.OK) {
    System.out.println("设备已离线");
}
```

---

### S1F17 - Go Online Request (在线请求)

| 属性 | 值 |
|------|-----|
| **方向** | Host → Equipment |
| **W-Bit** | true |
| **数据体** | 空 |
| **回复** | S1F18 |
| **返回类型** | ONLACK |

**含义**：Host 请求 Equipment 切换到在线状态。

**ONLACK 确认码**：

| 值 | 名称 | 含义 |
|----|------|------|
| 0 | OK | 成功上线 |
| 1 | Refused | 拒绝上线 |
| 2 | AlreadyOnline | 已经在线 |

**代码示例**：
```java
ONLACK onlack = host.gem().s1f17();
switch (onlack) {
case OK:
    System.out.println("上线成功");
    break;
case Refused:
    System.out.println("上线被拒绝");
    break;
case AlreadyOnline:
    System.out.println("设备已在线");
    break;
}
```

---

## Stream 2 - 设备控制2 (Equipment Control 2)

### S2F17 - Date and Time Request (日期时间请求)

| 属性 | 值 |
|------|-----|
| **方向** | Host → Equipment |
| **W-Bit** | true |
| **数据体** | 空 |
| **回复** | S2F18 |
| **返回类型** | Clock |

**含义**：Host 请求获取 Equipment 的当前日期时间。

**代码示例**：
```java
Clock clock = host.gem().s2f17();
System.out.println("设备时间: " + clock);
```

---

### S2F23 - Initiate Processing Request (启动处理请求)

| 属性 | 值 |
|------|-----|
| **方向** | Host → Equipment |
| **W-Bit** | true |
| **数据体** | `<L[2] <U1[cmdtype]> <A[cmd]>>` |
| **回复** | S2F28 |
| **返回类型** | CMDA |

**含义**：Host 请求 Equipment 启动某个处理过程（如开始加工）。

**数据结构**：
```
S2F23 W
<L[2]
  <U1[cmdtype]>  // 命令类型
  <A[cmd]>       // 命令字符串
>
```

**CMDA 确认码**：

| 值 | 名称 | 含义 |
|----|------|------|
| 1 | CommandWillExecute | 命令将执行 |
| 2 | CommandWillNotExecute | 命令不会执行 |
| 3 | CommandDoesNotExist | 命令不存在 |
| 4 | CommandFormatError | 命令格式错误 |

---

### S2F33 - Define Report (定义报告)

| 属性 | 值 |
|------|-----|
| **方向** | Host → Equipment |
| **W-Bit** | true |
| **数据体** | `<L[2] <DATAID> <L[n] <REPORT>...>>` |
| **回复** | S2F34 |
| **返回类型** | DRACK |

**含义**：Host 定义数据收集报告，指定要收集哪些采样变量。

**数据结构**：
```
S2F33 W
<L[2]
  <DATAID>           // 数据ID
  <L[n]              // 报告列表
    <L[3]
      <U4[reportid]> // 报告ID
      <U1[ceid]>     // 集合事件ID (可选)
      <L[m]          // 变量列表
        <U4[svID]>   // 采样变量ID
      >
    >
  >
>
```

**DRACK 确认码**：

| 值 | 名称 | 含义 |
|----|------|------|
| 0 | OK | 定义成功 |
| 1 | SampleOverflow | 采样溢出 |
| 2 | SampleDoesNotExist | 采样不存在 |
| 3 | DefinitionError | 定义错误 |

**代码示例**：
```java
DynamicEventReportConfig config = host.gem().newDynamicEventReportConfig();
config.addDefineReport("report1", Arrays.asList(Long.valueOf(1001)));
DRACK drack = config.s2f33Define();
```

---

### S2F35 - Link Event Report (链接事件报告)

| 属性 | 值 |
|------|-----|
| **方向** | Host → Equipment |
| **W-Bit** | true |
| **数据体** | `<L[2] <DATAID> <L[n] <LINK>...>>` |
| **回复** | S2F36 |
| **返回类型** | LRACK |

**含义**：Host 将报告链接到集合事件，当集合事件触发时，自动发送关联的报告。

**数据结构**：
```
S2F35 W
<L[2]
  <DATAID>           // 数据ID
  <L[n]              // 链接列表
    <L[2]
      <U4[ceid]>     // 集合事件ID
      <L[m]          // 报告ID列表
        <U4[reportid]>
      >
    >
  >
>
```

**LRACK 确认码**：

| 值 | 名称 | 含义 |
|----|------|------|
| 0 | OK | 链接成功 |
| 1 | CEIDOverflow | 事件ID溢出 |
| 2 | UnknownCEID | 未知事件ID |
| 3 | ReportIDNotInDefinedList | 报告ID不在定义列表中 |
| 4 | CEIDAlreadyExists | 事件ID已存在 |

**代码示例**：
```java
DynamicEventReportConfig config = host.gem().newDynamicEventReportConfig();
config.addLinkByReport(report, Arrays.asList(ceid));
LRACK lrack = config.s2f35();
```

---

### S2F37 - Enable/Disable Event Report (启用/禁用事件报告)

| 属性 | 值 |
|------|-----|
| **方向** | Host → Equipment |
| **W-Bit** | true |
| **数据体** | `<L[2] <U1[cedtype]> <L[n] <CEID>...>>` |
| **回复** | S2F38 |
| **返回类型** | ERACK |

**含义**：Host 启用或禁用集合事件报告。

**数据结构**：
```
S2F37 W
<L[2]
  <U1[cedtype]>  // 0=禁用, 1=启用
  <L[n]          // 事件ID列表
    <U4[ceid]>
  >
>
```

**ERACK 确认码**：

| 值 | 名称 | 含义 |
|----|------|------|
| 0 | OK | 操作成功 |
| 1 | CEIDOverflow | 事件ID溢出 |
| 2 | UnknownCEID | 未知事件ID |
| 3 | CEIDAlreadyEnabled | 事件已启用 |
| 4 | CEIDAlreadyDisabled | 事件已禁用 |
| 5 | EventReportNotDefined | 事件报告未定义 |

**代码示例**：
```java
// 禁用所有事件报告
ERACK erack = host.gem().s2f37DisableAll();

// 启用特定事件报告
DynamicEventReportConfig config = host.gem().newDynamicEventReportConfig();
ERACK erack = config.s2f37Enable();
```

---

### S2F41 - Host Command Send (主机命令发送)

| 属性 | 值 |
|------|-----|
| **方向** | Host → Equipment |
| **W-Bit** | true |
| **数据体** | `<L[3] <DATAID> <DSID> <L[n] <PARAM>...>>` |
| **回复** | S2F42 |
| **返回类型** | HCACK |

**含义**：Host 向 Equipment 发送远程命令，可以带参数。

**数据结构**：
```
S2F41 W
<L[3]
  <DATAID>           // 数据ID
  <DSID>             // 数据集ID
  <L[n]              // 参数列表
    <L[2]
      <U4[ppid]>     // 参数ID
      <value>        // 参数值
    >
  >
>
```

---

## Stream 5 - 报警管理 (Alarm Management)

### S5F1 - Alarm Report Send (报警报告发送)

| 属性 | 值 |
|------|-----|
| **方向** | Equipment → Host |
| **W-Bit** | false |
| **数据体** | `<L[4] <U4[alarid]> <A[alarmdate]> <A[alarmmsg]> <ACKC5>>` |
| **回复** | S5F2 |

**含义**：Equipment 向 Host 报告发生的报警事件。

**数据结构**：
```
S5F1
<L[4]
  <U4[alarid]>      // 报警ID
  <A[alarmdate]>    // 报警时间 (ASCII)
  <A[alarmmsg]>     // 报警消息 (ASCII)
  <ACKC5>           // 报警码
>
```

**代码示例**：
```java
// Equipment 发送报警
Secs2 alarmData = Secs2.list(
    Secs2.uint4(100),                    // 报警ID
    Secs2.ascii("20240101120000"),       // 报警时间
    Secs2.ascii("Temperature too high"), // 报警消息
    Secs2.bool(true)                     // 报警状态
);
comm.send(5, 1, false, alarmData);
```

---

### S5F3 - Enable/Disable Alarm Send (启用/禁用报警发送)

| 属性 | 值 |
|------|-----|
| **方向** | Host → Equipment |
| **W-Bit** | true |
| **数据体** | `<L[2] <U1[aledtype]> <L[n] <U4[alarid]...>>` |
| **回复** | S5F4 |

**含义**：Host 启用或禁用 Equipment 的特定报警。

**数据结构**：
```
S5F3 W
<L[2]
  <U1[aledtype]>  // 0=禁用, 1=启用
  <L[n]           // 报警ID列表
    <U4[alarid]>
  >
>
```

---

### S5F5 - Alarm Namelist Request (报警名称列表请求)

| 属性 | 值 |
|------|-----|
| **方向** | Host → Equipment |
| **W-Bit** | true |
| **数据体** | `<L[0]>` 或 `<L[n] <U4[alarid]...>>` |
| **回复** | S5F6 |

**含义**：Host 请求获取报警的名称列表。

---

### S5F7 - Alarm Namelist Response (报警名称列表响应)

| 属性 | 值 |
|------|-----|
| **方向** | Equipment → Host |
| **W-Bit** | false |
| **数据体** | `<L[n] <L[2] <U4[alarid]> <A[alarmname]>...>>` |
| **回复** | 无 |

**含义**：Equipment 响应 S5F5 请求，返回报警名称列表。

---

## Stream 6 - 数据收集 (Data Collection)

### S6F1 - Trace Data Request (跟踪数据请求)

| 属性 | 值 |
|------|-----|
| **方向** | Host → Equipment |
| **W-Bit** | true |
| **数据体** | `<L[3] <U4[dataid]> <U1[trid]> <L[n] <U4[spid]...>>` |
| **回复** | S6F2 |

**含义**：Host 请求 Equipment 发送跟踪数据（采样数据）。

**数据结构**：
```
S6F1 W
<L[3]
  <U4[dataid]>  // 数据ID
  <U1[trid]>    // 跟踪请求ID
  <L[n]         // 采样点ID列表
    <U4[spid]>
  >
>
```

---

### S6F11 - Event Report Send (事件报告发送)

| 属性 | 值 |
|------|-----|
| **方向** | Equipment → Host |
| **W-Bit** | false |
| **数据体** | `<L[5] <U4[dataid]> <U4[ceid]> <A[dt]> <L[n] <RV>...> <ACKC6>>` |
| **回复** | S6F12 |

**含义**：Equipment 向 Host 发送集合事件报告数据。这是数据收集的核心消息。

**数据结构**：
```
S6F11
<L[5]
  <U4[dataid]>  // 数据ID
  <U4[ceid]>    // 集合事件ID
  <A[dt]>       // 时间戳
  <L[n]         // 报告数据列表
    <L[2]
      <U4[rtyp]> // 报告类型
      <L[m]      // 报告值
        <value>
      >
    >
  >
  <ACKC6>       // 确认码
>
```

**代码示例**：
```java
// Equipment 发送事件报告
Secs2 reportData = Secs2.list(
    Secs2.uint4(1),                    // DATAID
    Secs2.uint4(101),                  // CEID
    Secs2.ascii("20240101120000000"),  // 时间戳
    Secs2.list(                        // 报告数据
        Secs2.list(
            Secs2.uint4(1),            // 报告类型
            Secs2.list(Secs2.int4(100)) // 报告值
        )
    ),
    Secs2.bool(true)                   // ACKC6
);
comm.send(6, 11, false, reportData);
```

---

## Stream 7 - 过程管理 (Process Management)

### S7F19 - Current Equipment Constant Request (当前设备常量请求)

| 属性 | 值 |
|------|-----|
| **方向** | Host → Equipment |
| **W-Bit** | true |
| **数据体** | `<L[0]>` 或 `<L[n] <U4[ecid]...>>` |
| **回复** | S7F20 |

**含义**：Host 请求获取 Equipment 的当前设备常量值。

**数据结构**：
```
S7F19 W
<L[0]>  // 空列表表示请求所有
或
<L[n]
  <U4[ecid]>  // 设备常量ID
>
```

---

### S7F25 - Current Process Program Request (当前加工程序请求)

| 属性 | 值 |
|------|-----|
|------|-----|
| **方向** | Host → Equipment |
| **W-Bit** | true |
| **数据体** | 空 |
| **回复** | S7F26 |

**含义**：Host 请求获取 Equipment 当前正在使用的加工程序名称。

**代码示例**：
```java
// 请求当前加工程序
Optional<SecsMessage> reply = comm.send(7, 25, true);
reply.ifPresent(msg -> {
    String ppName = msg.secs2().getAscii();
    System.out.println("当前加工程序: " + ppName);
});
```

---

## 命令对照表

| 命令 | 名称 | 方向 | W-Bit | 回复 | 主要用途 |
|------|------|------|-------|------|----------|
| S1F1 | Are You There? | H→E | true | S1F2 | 在线状态查询 |
| S1F3 | Selected Equipment Status | H→E | true | S1F4 | 获取设备状态 |
| S1F11 | Status Variable Namelist | H→E | true | S1F12 | 获取状态变量名称 |
| S1F13 | Establish Communications | H→E | true | S1F14 | 建立通信连接 |
| S1F15 | Go Offline | H→E | true | S1F16 | 请求离线 |
| S1F17 | Go Online | H→E | true | S1F18 | 请求在线 |
| S2F17 | Date and Time Request | H→E | true | S2F18 | 获取设备时间 |
| S2F23 | Initiate Processing | H→E | true | S2F28 | 启动处理 |
| S2F33 | Define Report | H→E | true | S2F34 | 定义数据报告 |
| S2F35 | Link Event Report | H→E | true | S2F36 | 链接事件报告 |
| S2F37 | Enable/Disable Event | H→E | true | S2F38 | 启用/禁用事件 |
| S2F41 | Host Command Send | H→E | true | S2F42 | 发送远程命令 |
| S5F1 | Alarm Report | E→H | false | S5F2 | 报警报告 |
| S5F3 | Enable/Disable Alarm | H→E | true | S5F4 | 启用/禁用报警 |
| S5F5 | Alarm Namelist Request | H→E | true | S5F6 | 获取报警名称 |
| S5F7 | Alarm Namelist Response | E→H | false | - | 报警名称响应 |
| S6F1 | Trace Data Request | H→E | true | S6F2 | 请求跟踪数据 |
| S6F11 | Event Report Send | E→H | false | S6F12 | 发送事件报告 |
| S7F19 | Equipment Constant Request | H→E | true | S7F20 | 获取设备常量 |
| S7F25 | Process Program Request | H→E | true | S7F26 | 获取加工程序 |

**图例**：
- H→E: Host 到 Equipment
- E→H: Equipment 到 Host

---

## 典型初始化流程

```java
// 1. 建立通信
COMMACK commack = host.gem().s1f13();

// 2. 请求在线
ONLACK onlack = host.gem().s1f17();

// 3. 设置时间
TIACK tiack = host.gem().s2f31Now();

// 4. 配置事件报告
DynamicEventReportConfig config = host.gem().newDynamicEventReportConfig();
DRACK drack = config.s2f33Define();    // 定义报告
LRACK lrack = config.s2f35();         // 链接事件
ERACK erack = config.s2f37Enable();   // 启用事件

// 5. 执行生产操作...

// 6. 离线
OFLACK oflack = host.gem().s1f15();
```

---

## 参考

- SEMI E5 - SECS-I 标准
- SEMI E37 - HSMS 标准
- SEMI E5-SECS-II 数据格式标准
- SEMI E30 - GEM 标准
- secs4java8 项目源码

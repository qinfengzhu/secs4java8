package com.secsgem.moljm240d2;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import com.shimizukenta.secs.SecsCommunicator;
import com.shimizukenta.secs.SecsException;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.gem.ACKC5;
import com.shimizukenta.secs.gem.ACKC6;
import com.shimizukenta.secs.gem.COMMACK;
import com.shimizukenta.secs.gem.DRACK;
import com.shimizukenta.secs.gem.DynamicEventReportConfig;
import com.shimizukenta.secs.gem.ERACK;
import com.shimizukenta.secs.gem.HCACK;
import com.shimizukenta.secs.gem.LRACK;
import com.shimizukenta.secs.gem.OFLACK;
import com.shimizukenta.secs.gem.ONLACK;
import com.shimizukenta.secs.hsms.HsmsConnectionMode;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicator;
import com.shimizukenta.secs.hsmsss.HsmsSsCommunicatorConfig;
import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secs.secs2.Secs2Exception;

/**
 * MOLJM-240D2 设备 Host 端通信管理类。
 * <p>
 * 基于 secs4java8 库实现 HSMS-SS Active 连接，封装所有 GEM 通信流程。<br />
 * 支持：通讯建立/在线切换、状态变量查询、事件报告配置、报警管理、
 * 远程命令、数据追踪、工艺程序查询等。
 * </p>
 */
public class MoljmHost {

    private final HsmsSsCommunicatorConfig config;
    private SecsCommunicator communicator;
    private DynamicEventReportConfig eventReportConfig;
    private volatile boolean online = false;

    private final List<MoljmHostListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 构造 Host 实例。
     *
     * @param ip        设备 IP 地址
     * @param port      设备端口号
     * @param sessionId HSMS Session ID
     */
    public MoljmHost(String ip, int port, int sessionId) {
        this.config = new HsmsSsCommunicatorConfig();
        this.config.socketAddress(new InetSocketAddress(ip, port));
        this.config.connectionMode(HsmsConnectionMode.ACTIVE);
        this.config.sessionId(sessionId);
        this.config.isEquip(false);
        this.config.linktest(60.0F);
        this.config.timeout().t3(45.0F);
        this.config.timeout().t5(10.0F);
        this.config.timeout().t6(5.0F);
        this.config.timeout().t7(10.0F);
        this.config.timeout().t8(6.0F);
    }

    // ======================== 监听器管理 ========================

    public void addListener(MoljmHostListener listener) {
        listeners.add(listener);
    }

    public void removeListener(MoljmHostListener listener) {
        listeners.remove(listener);
    }

    // ======================== 连接管理 ========================

    /**
     * 打开连接并等待通讯建立。
     *
     * @throws IOException          如果连接失败
     * @throws InterruptedException 如果被中断
     */
    public void open() throws IOException, InterruptedException {
        communicator = HsmsSsCommunicator.newInstance(config);

        // 日志监听
        communicator.addSecsLogListener(log -> {
            System.out.println("[SECS-LOG] " + log);
        });

        // 消息接收监听 — 处理设备主动上报的消息
        communicator.addSecsMessageReceiveListener(this::handleEquipMessage);

        // 通讯状态变化监听
        communicator.addSecsCommunicatableStateChangeListener(selected -> {
            System.out.println("[STATE] Communicate state changed: " + (selected ? "SELECTED" : "NOT SELECTED"));
            for (MoljmHostListener l : listeners) {
                l.onCommunicateStateChanged(selected);
            }
        });

        communicator.openAndWaitUntilCommunicatable();
        System.out.println("[STATE] HSMS connection established (SELECTED)");
    }

    /**
     * 关闭连接。
     */
    public void close() {
        if (communicator != null) {
            try {
                communicator.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 获取底层 SecsCommunicator 实例（高级用法）。
     */
    public SecsCommunicator getCommunicator() {
        return communicator;
    }

    /**
     * 是否在线。
     */
    public boolean isOnline() {
        return online;
    }

    // ======================== 通讯建立流程 ========================

    /**
     * 执行完整的通讯建立和上线流程。
     * <p>
     * S1F13 (建立通讯) → S1F17 (请求上线) → S2F33/S2F35/S2F37 (配置事件报告)
     * </p>
     *
     * @return true 如果成功上线
     */
    public boolean establishCommunicationAndOnline() {
        try {
            // 1. S1F13 — 建立通讯
            COMMACK commack = communicator.gem().s1f13();
            System.out.println("[GEM] S1F13 COMMACK: " + commack);
            if (commack != COMMACK.OK) {
                System.err.println("[GEM] S1F13 通讯建立被拒绝: " + commack);
                return false;
            }

            // 2. S1F17 — 请求上线
            ONLACK onlack = communicator.gem().s1f17();
            System.out.println("[GEM] S1F17 ONLACK: " + onlack);
            if (onlack != ONLACK.OK && onlack != ONLACK.AlreadyOnline) {
                System.err.println("[GEM] S1F17 上线请求被拒绝: " + onlack);
                return false;
            }
            online = true;
            for (MoljmHostListener l : listeners) {
                l.onOnlineStateChanged(true);
            }

            // 3. 配置事件报告
            setupEventReportConfig();

            return true;
        } catch (SecsException | Secs2Exception | InterruptedException e) {
            System.err.println("[GEM] 通讯建立/上线失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 执行离线和通讯断开流程。
     *
     * @return true 如果成功离线
     */
    public boolean requestOffline() {
        try {
            OFLACK oflack = communicator.gem().s1f15();
            System.out.println("[GEM] S1F15 OFLACK: " + oflack);
            online = false;
            for (MoljmHostListener l : listeners) {
                l.onOnlineStateChanged(false);
            }
            return oflack == OFLACK.OK;
        } catch (SecsException | Secs2Exception | InterruptedException e) {
            System.err.println("[GEM] 离线请求失败: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // ======================== 事件报告配置 ========================

    /**
     * 配置动态事件报告 (S2F33 Define Report → S2F35 Link Event → S2F37 Enable)。
     */
    private void setupEventReportConfig() {
        try {
            eventReportConfig = communicator.gem().newDynamicEventReportConfig();

            // 定义所有 Report (RPTID → SVID 映射)
            defineAllReports();

            // 删除已有定义，重新定义
            DRACK drack = eventReportConfig.s2f33DeleteAll();
            System.out.println("[GEM] S2F33 DeleteAll DRACK: " + drack);

            drack = eventReportConfig.s2f33Define();
            System.out.println("[GEM] S2F33 Define DRACK: " + drack);
            if (drack != DRACK.OK) {
                System.err.println("[GEM] S2F33 Define 失败: " + drack);
                return;
            }

            // 链接事件报告 (CEID → RPTID 映射)
            LRACK lrack = eventReportConfig.s2f35();
            System.out.println("[GEM] S2F35 Link LRACK: " + lrack);
            if (lrack != LRACK.OK) {
                System.err.println("[GEM] S2F35 Link 失败: " + lrack);
                return;
            }

            // 启用所有事件报告
            ERACK erack = eventReportConfig.s2f37Enable();
            System.out.println("[GEM] S2F37 Enable ERACK: " + erack);
            if (erack != ERACK.OK) {
                System.err.println("[GEM] S2F37 Enable 失败: " + erack);
            }
        } catch (SecsException | Secs2Exception | InterruptedException e) {
            System.err.println("[GEM] 事件报告配置失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 定义所有 Report (RPTID 101-131 → SVID)。
     */
    private void defineAllReports() {
        // 普通报告: RPTID 101-131 对应 SVID 101-131（单个SVID）
        for (int i = DeviceConfig.CEID_START; i <= DeviceConfig.CEID_END; i++) {
            int rptid = i;
            int svid = i;

            // 特殊报告: RPTID 124/126/127/128 携带多个 SVID
            if (DeviceConfig.SPECIAL_RPTIDS.contains(rptid)) {
                List<Integer> svids = new ArrayList<>();
                svids.add(svid);
                // 添加 OPID + LOT1~8
                for (int s = DeviceConfig.SVID_LOT_START; s <= DeviceConfig.SVID_LOT_END; s++) {
                    svids.add(s);
                }
                eventReportConfig.addDefineReport(rptid, svids);
            } else {
                eventReportConfig.addDefineReport(rptid, Arrays.asList(svid));
            }
        }
    }

    // ======================== S1F1 — Are You There ========================

    /**
     * 发送 S1F1 心跳检测。
     *
     * @return 设备回复消息
     */
    public Optional<SecsMessage> s1f1AreYouThere()
            throws SecsException, InterruptedException {
        return communicator.gem().s1f1();
    }

    // ======================== S1F3 — 查询状态变量值 ========================

    /**
     * 发送 S1F3 查询指定 SVID 的当前值。
     *
     * @param svids 要查询的 SVID 列表
     * @return SVID 值列表 (Secs2 格式)
     */
    public Optional<SecsMessage> s1f3QueryStatus(List<Integer> svids)
            throws SecsException, InterruptedException {
        Secs2 data = Secs2.list(
                svids.stream().map(id -> Secs2.uint2(id)).toArray(Secs2[]::new)
        );
        return communicator.send(1, 3, true, data);
    }

    /**
     * 发送 S1F3 查询所有 SVID 的当前值。
     */
    public Optional<SecsMessage> s1f3QueryAllStatus()
            throws SecsException, InterruptedException {
        return s1f3QueryStatus(DeviceConfig.ALL_SVIDS);
    }

    // ======================== S1F11 — 获取 SVID 名称列表 ========================

    /**
     * 发送 S1F11 获取设备支持的 SVID 清单。
     */
    public Optional<SecsMessage> s1f11StatusVariableNameList()
            throws SecsException, InterruptedException {
        return communicator.send(1, 11, true);
    }

    // ======================== S2F17 — 日期时间请求 ========================

    /**
     * 发送 S2F17 请求设备当前日期时间。
     *
     * @return 设备返回的时间字符串 (YYYY-MM-DD-HH-MM-SS)
     */
    public String s2f17DateTimeRequest()
            throws SecsException, Secs2Exception, InterruptedException {
        com.shimizukenta.secs.gem.Clock clock = communicator.gem().s2f17();
        return clock.toString();
    }

    // ======================== S2F23 — 追踪初始化 ========================

    /**
     * 发送 S2F23 初始化数据追踪。
     *
     * @param trid       追踪报告 ID
     * @param period     采集周期 (秒), 格式 "000002"
     * @param totalSamples 总采样数
     * @param groupSize  每组报告大小
     * @param traceSvids 追踪的 SVID 列表
     */
    public void s2f23TraceInit(int trid, String period, int totalSamples,
                               int groupSize, List<Integer> traceSvids)
            throws SecsException, InterruptedException {
        Secs2 data = Secs2.list(
                Secs2.uint2(trid),
                Secs2.ascii(period),
                Secs2.uint2(totalSamples),
                Secs2.uint2(groupSize),
                Secs2.list(
                        traceSvids.stream().map(id -> Secs2.uint2(id)).toArray(Secs2[]::new)
                )
        );
        communicator.send(2, 23, true, data);
    }

    /**
     * 使用默认配置初始化追踪 (Temperature1-6, 周期2秒, 采样5次)。
     */
    public void s2f23TraceInitDefault()
            throws SecsException, InterruptedException {
        s2f23TraceInit(
                DeviceConfig.DEFAULT_TRID,
                "000002",
                5,
                1,
                DeviceConfig.TRACE_SVIDS
        );
    }

    // ======================== S2F41 — 远程命令 ========================

    /**
     * 发送 S2F41 远程命令（无参数）。
     *
     * @param rcmd 命令名称 (RUN / LOCAL / REMOTE)
     * @return HCACK 响应码
     */
    public HCACK s2f41SendCommand(String rcmd)
            throws SecsException, Secs2Exception, InterruptedException {
        Secs2 data = Secs2.list(
                Secs2.ascii(rcmd),
                Secs2.list()  // CPCOUNT=0, 无参数
        );
        Optional<SecsMessage> reply = communicator.send(2, 41, true, data);
        if (reply.isPresent()) {
            return HCACK.get(reply.get().secs2().get(0));
        }
        return HCACK.UNDEFINED;
    }

    /**
     * 发送 S2F41 远程命令（带参数）。
     *
     * @param rcmd    命令名称 (PP-SELECT)
     * @param cpName  参数名称
     * @param cpValue 参数值
     * @return HCACK 响应码
     */
    public HCACK s2f41SendCommand(String rcmd, String cpName, String cpValue)
            throws SecsException, Secs2Exception, InterruptedException {
        Secs2 data = Secs2.list(
                Secs2.ascii(rcmd),
                Secs2.list(
                        Secs2.list(
                                Secs2.ascii(cpName),
                                Secs2.ascii(cpValue)
                        )
                )
        );
        Optional<SecsMessage> reply = communicator.send(2, 41, true, data);
        if (reply.isPresent()) {
            return HCACK.get(reply.get().secs2().get(0));
        }
        return HCACK.UNDEFINED;
    }

    /**
     * 发送 RUN 命令。
     */
    public HCACK sendRunCommand()
            throws SecsException, Secs2Exception, InterruptedException {
        return s2f41SendCommand(DeviceConfig.RCMD_RUN);
    }

    /**
     * 发送 LOCAL 命令。
     */
    public HCACK sendLocalCommand()
            throws SecsException, Secs2Exception, InterruptedException {
        return s2f41SendCommand(DeviceConfig.RCMD_LOCAL);
    }

    /**
     * 发送 REMOTE 命令。
     */
    public HCACK sendRemoteCommand()
            throws SecsException, Secs2Exception, InterruptedException {
        return s2f41SendCommand(DeviceConfig.RCMD_REMOTE);
    }

    /**
     * 发送 PP-SELECT 命令选择工艺程序。
     *
     * @param ppName 工艺名
     */
    public HCACK sendPpSelectCommand(String ppName)
            throws SecsException, Secs2Exception, InterruptedException {
        return s2f41SendCommand(DeviceConfig.RCMD_PP_SELECT, "PPNAME", ppName);
    }

    // ======================== S5F3 — 启用/禁用报警 ========================

    /**
     * 发送 S5F3 启用或禁用指定报警。
     *
     * @param enable true=启用, false=禁用
     * @param alids  报警 ID 列表, 空=操作所有报警
     */
    public void s5f3EnableDisableAlarm(boolean enable, List<Integer> alids)
            throws SecsException, InterruptedException {
        byte aled = enable ? (byte) 0x80 : (byte) 0x00;
        Secs2 alidList;
        if (alids == null || alids.isEmpty()) {
            alidList = Secs2.list();
        } else {
            alidList = Secs2.list(
                    alids.stream().map(id -> Secs2.uint2(id)).toArray(Secs2[]::new)
            );
        }
        Secs2 data = Secs2.list(
                Secs2.binary(aled),
                alidList
        );
        communicator.send(5, 3, true, data);
    }

    /**
     * 启用所有报警。
     */
    public void s5f3EnableAllAlarms()
            throws SecsException, InterruptedException {
        s5f3EnableDisableAlarm(true, null);
    }

    /**
     * 禁用所有报警。
     */
    public void s5f3DisableAllAlarms()
            throws SecsException, InterruptedException {
        s5f3EnableDisableAlarm(false, null);
    }

    // ======================== S5F5 — 报警列表请求 ========================

    /**
     * 发送 S5F5 请求设备所有报警列表。
     */
    public Optional<SecsMessage> s5f5AlarmListRequest()
            throws SecsException, InterruptedException {
        return communicator.send(5, 5, true);
    }

    // ======================== S5F7 — 获取已启用报警列表 ========================

    /**
     * 发送 S5F7 获取已启用的报警列表。
     */
    public Optional<SecsMessage> s5f7GetEnabledAlarms()
            throws SecsException, InterruptedException {
        return communicator.send(5, 7, true);
    }

    // ======================== S7F19 — 查询所有工艺名 ========================

    /**
     * 发送 S7F19 查询设备所有工艺名 (EPPD)。
     *
     * @return 工艺名列表
     */
    public List<String> s7f19GetCurrentEppd()
            throws SecsException, Secs2Exception, InterruptedException {
        Optional<SecsMessage> reply = communicator.send(7, 19, true);
        List<String> ppNames = new ArrayList<>();
        if (reply.isPresent()) {
            Secs2 secs2 = reply.get().secs2();
            for (Secs2 item : secs2) {
                String name = item.getAscii();
                if (name != null && !name.isEmpty()) {
                    ppNames.add(name);
                }
            }
        }
        return ppNames;
    }

    // ======================== S7F25 — 查询工艺参数 ========================

    /**
     * 发送 S7F25 查询指定工艺的完整参数。
     *
     * @param ppId 工艺名
     * @return 工艺参数消息
     */
    public Optional<SecsMessage> s7f25RequestProcessProgram(String ppId)
            throws SecsException, InterruptedException {
        Secs2 data = Secs2.ascii(ppId);
        return communicator.send(7, 25, true, data);
    }

    // ======================== 设备消息处理 ========================

    /**
     * 处理设备主动上报的消息 (S5F1, S6F1, S6F11 等)。
     */
    private void handleEquipMessage(SecsMessage msg) {
        try {
            int stream = msg.getStream();
            int function = msg.getFunction();

            switch (stream) {
            case 5:
                handleStream5(function, msg);
                break;
            case 6:
                handleStream6(function, msg);
                break;
            default:
                System.out.println("[RECV] Unhandled S" + stream + "F" + function);
                break;
            }
        } catch (Secs2Exception e) {
            System.err.println("[RECV] SECS-II 解析异常: S" + msg.getStream() + "F" + msg.getFunction() + " - " + e.getMessage());
        } catch (Exception e) {
            System.err.println("[RECV] 处理消息异常: S" + msg.getStream() + "F" + msg.getFunction() + " - " + e.getMessage());
        }
    }

    /**
     * 处理 Stream 5 消息 (报警相关)。
     */
    private void handleStream5(int function, SecsMessage msg) throws SecsException, Secs2Exception, InterruptedException {
        switch (function) {
        case 1:
            // S5F1 — 报警事件上报
            handleS5F1(msg);
            break;
        default:
            System.out.println("[RECV] Unhandled S5F" + function);
            break;
        }
    }

    /**
     * 处理 S5F1 报警事件。
     */
    private void handleS5F1(SecsMessage msg) throws SecsException, Secs2Exception, InterruptedException {
        Secs2 secs2 = msg.secs2();
        int alcd = secs2.getByte(0) & 0xFF;
        int alid = secs2.getInt(1);
        String altx = secs2.getAscii(2);

        System.out.println("[ALARM] ALCD=" + alcd + " ALID=" + alid + " ALTX=" + altx);

        // 回复 S5F2 ACK
        ACKC5 ackc5 = ACKC5.OK;
        communicator.gem().s5f2(msg, ackc5);

        // 通知监听器
        for (MoljmHostListener l : listeners) {
            l.onAlarmEvent(alcd, alid, altx);
        }
    }

    /**
     * 处理 Stream 6 消息 (事件报告/追踪数据)。
     */
    private void handleStream6(int function, SecsMessage msg) throws SecsException, Secs2Exception, InterruptedException {
        switch (function) {
        case 1:
            // S6F1 — 追踪数据上报
            handleS6F1(msg);
            break;
        case 11:
            // S6F11 — 事件报告上报
            handleS6F11(msg);
            break;
        default:
            System.out.println("[RECV] Unhandled S6F" + function);
            break;
        }
    }

    /**
     * 处理 S6F1 追踪数据。
     */
    private void handleS6F1(SecsMessage msg) throws SecsException, Secs2Exception, InterruptedException {
        Secs2 secs2 = msg.secs2();
        int trid = secs2.getInt(0);
        int sampleNum = secs2.getInt(1);
        String timestamp = secs2.getAscii(2);

        Secs2 valueList = secs2.get(3);
        Object[] values = new Object[valueList.size()];
        int idx = 0;
        for (Secs2 v : valueList) {
            try {
                values[idx] = v.getAscii();
            } catch (Secs2Exception e) {
                try {
                    values[idx] = v.getInt();
                } catch (Secs2Exception e2) {
                    values[idx] = v.toJson();
                }
            }
            idx++;
        }

        System.out.println("[TRACE] TRID=" + trid + " Sample=" + sampleNum + " Time=" + timestamp);

        // 回复 S6F2 ACK
        communicator.gem().s6f2(msg, ACKC6.OK);

        // 通知监听器
        for (MoljmHostListener l : listeners) {
            l.onTraceData(trid, sampleNum, timestamp, values);
        }
    }

    /**
     * 处理 S6F11 事件报告。
     */
    private void handleS6F11(SecsMessage msg) throws SecsException, Secs2Exception, InterruptedException {
        Secs2 secs2 = msg.secs2();
        int dataId = secs2.getInt(0);
        int ceid = secs2.getInt(1);

        System.out.println("[EVENT] DATAID=" + dataId + " CEID=" + ceid);

        // 解析报告数据
        Map<Integer, Object> reportData = new LinkedHashMap<>();
        Secs2 reportList = secs2.get(2);
        for (Secs2 reportItem : reportList) {
            int rptid = reportItem.getInt(0);
            Secs2 valueData = reportItem.get(1);
            reportData.put(rptid, parseSecs2Value(valueData));
        }

        // 回复 S6F12 ACK
        communicator.gem().s6f12(msg, ACKC6.OK);

        // 通知监听器
        for (MoljmHostListener l : listeners) {
            l.onEventReport(ceid, reportData);
        }
    }

    /**
     * 解析 Secs2 值为 Java 对象。
     */
    private Object parseSecs2Value(Secs2 secs2) {
        if (secs2.size() > 1) {
            List<Object> list = new ArrayList<>();
            for (Secs2 item : secs2) {
                list.add(parseSecs2Value(item));
            }
            return list;
        }
        try {
            return secs2.getBoolean();
        } catch (Secs2Exception e1) {
            try {
                return secs2.getAscii();
            } catch (Secs2Exception e2) {
                try {
                    return secs2.getInt();
                } catch (Secs2Exception e3) {
                    try {
                        return secs2.getLong();
                    } catch (Secs2Exception e4) {
                        return secs2.toJson();
                    }
                }
            }
        }
    }

    // ======================== 便捷方法 ========================

    /**
     * 发送原始 SECS 消息（高级用法）。
     */
    public Optional<SecsMessage> sendRaw(int stream, int function, boolean wbit, Secs2 secs2)
            throws SecsException, InterruptedException {
        return communicator.send(stream, function, wbit, secs2);
    }

    /**
     * 发送原始 SECS 消息（Header-only）。
     */
    public Optional<SecsMessage> sendRaw(int stream, int function, boolean wbit)
            throws SecsException, InterruptedException {
        return communicator.send(stream, function, wbit);
    }

    /**
     * 回复原始 SECS 消息。
     */
    public Optional<SecsMessage> replyRaw(SecsMessage primary, int stream, int function, boolean wbit, Secs2 secs2)
            throws SecsException, InterruptedException {
        return communicator.send(primary, stream, function, wbit, secs2);
    }

    /**
     * 回复原始 SECS 消息（Header-only）。
     */
    public Optional<SecsMessage> replyRaw(SecsMessage primary, int stream, int function, boolean wbit)
            throws SecsException, InterruptedException {
        return communicator.send(primary, stream, function, wbit);
    }
}

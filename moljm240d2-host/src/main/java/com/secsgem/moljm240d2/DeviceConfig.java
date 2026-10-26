package com.secsgem.moljm240d2;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MOLJM-240D2 设备配置常量类。
 * <p>
 * 包含设备名称、SVID、CEID、RPTID、ALID 等所有静态配置信息。
 * 在 SECS/GEM 中，CEID（收集事件 ID）与 ReportId（报告 ID，常写作 RPTID）是“事件”与“数据模板”的绑定关系。
 * CEID 标识“发生了什么”，ReportId 定义“发生这件事时应该上报哪些数据”。
 * 一个 CEID 可以关联一个或多个 ReportId，而一个 ReportId 则包含一组具体的变量（VID/SVID）。
 * </p>
 */
public final class DeviceConfig {

    /** 设备名称 */
    public static final String EQUIPMENT_NAME = "MOLJM-240D2";

    /** 设备版本 */
    public static final String EQUIPMENT_VERSION = "1.0.0";

    // ======================== SVID 定义 ========================

    /** 布尔型状态变量 SVID 范围 (101-131) */
    public static final int SVID_BOOL_START = 101;
    public static final int SVID_BOOL_END = 131;

    /** 数值型温度 SVID 范围 (1001-1004) */
    public static final int SVID_TEMP_START = 1001;
    public static final int SVID_TEMP_END = 1004;

    /** 数值型其他 SVID (1005-1012) */
    public static final int SVID_OTHER_START = 1005;
    public static final int SVID_OTHER_END = 1012;

    /** 批次/操作员 SVID 范围 (2000-2008) */
    public static final int SVID_LOT_START = 2000;
    public static final int SVID_LOT_END = 2008;

    // 布尔型 SVID 名称映射 (101-131)
    public static final Map<Integer, String> BOOL_SVID_NAMES;
    static {
        Map<Integer, String> m = new LinkedHashMap<>();
        m.put(101, "HostStatus");
        m.put(102, "Offline");
        m.put(103, "OnlineLocal");
        m.put(104, "OnlineRemote");
        m.put(105, "OnlineStatus");
        m.put(106, "Mute");
        m.put(107, "DoorSealInPosition");
        m.put(108, "HighLiquidLevel");
        m.put(109, "LowLiquidLevel");
        m.put(110, "MagneticLockEngaged");
        m.put(111, "MagneticLockEngaged2");
        m.put(112, "IntakeDrainValve");
        m.put(113, "MagneticLock");
        m.put(114, "HotAirBlower");
        m.put(115, "Heating");
        m.put(116, "TowerLightBlue");
        m.put(117, "WaterInletValve");
        m.put(118, "TowerLightRed");
        m.put(119, "TowerLightYellow");
        m.put(120, "TowerLightGreen");
        m.put(121, "Buzzer");
        m.put(122, "N2MaintainValve");
        m.put(123, "ExhaustGasValve");
        m.put(124, "Run");
        m.put(125, "OxygenContentQualified");
        m.put(126, "ScanCodeConfirmed");
        m.put(127, "ProcessEnd");
        m.put(128, "ProcessAbnormalEnd");
        m.put(129, "HeatingUpStatus");
        m.put(130, "ConstantTempStatus");
        m.put(131, "CoolingDownStatus");
        BOOL_SVID_NAMES = Collections.unmodifiableMap(m);
    }

    // 数值型 SVID 名称映射 (1001-1012)
    public static final Map<Integer, String> NUMERIC_SVID_NAMES;
    static {
        Map<Integer, String> m = new LinkedHashMap<>();
        m.put(1001, "PV");
        m.put(1002, "SV");
        m.put(1003, "OverTemperaturePV");
        m.put(1004, "OverTemperatureSetpoint");
        m.put(1005, "HostPPname");
        m.put(1006, "OxygenContent");
        m.put(1007, "Temperature1");
        m.put(1008, "Temperature2");
        m.put(1009, "Temperature3");
        m.put(1010, "Temperature4");
        m.put(1011, "Temperature5");
        m.put(1012, "Temperature6");
        NUMERIC_SVID_NAMES = Collections.unmodifiableMap(m);
    }

    // 批次/操作员 SVID 名称映射 (2000-2008)
    public static final Map<Integer, String> LOT_SVID_NAMES;
    static {
        Map<Integer, String> m = new LinkedHashMap<>();
        m.put(2000, "OPID");
        m.put(2001, "LOT1");
        m.put(2002, "LOT2");
        m.put(2003, "LOT3");
        m.put(2004, "LOT4");
        m.put(2005, "LOT5");
        m.put(2006, "LOT6");
        m.put(2007, "LOT7");
        m.put(2008, "LOT8");
        LOT_SVID_NAMES = Collections.unmodifiableMap(m);
    }

    /** 所有 SVID 列表 */
    public static final List<Integer> ALL_SVIDS;
    static {
        List<Integer> svids = new java.util.ArrayList<>();
        for (int i = SVID_BOOL_START; i <= SVID_BOOL_END; i++) {
            svids.add(i);
        }
        for (int i = SVID_TEMP_START; i <= SVID_OTHER_END; i++) {
            svids.add(i);
        }
        for (int i = SVID_LOT_START; i <= SVID_LOT_END; i++) {
            svids.add(i);
        }
        ALL_SVIDS = Collections.unmodifiableList(svids);
    }

    // ======================== CEID / RPTID 定义 ========================

    /** CEID 范围 (101-131)，与 RPTID 一一对应 */
    public static final int CEID_START = 101;
    public static final int CEID_END = 131;

    /** 所有 CEID 列表 */
    public static final List<Integer> ALL_CEIDS;
    static {
        List<Integer> ceids = new java.util.ArrayList<>();
        for (int i = CEID_START; i <= CEID_END; i++) {
            ceids.add(i);
        }
        ALL_CEIDS = Collections.unmodifiableList(ceids);
    }

    /** 特殊 RPTID 列表 — 携带 OPID + LOT1~8 的报告 */
    public static final List<Integer> SPECIAL_RPTIDS = Collections.unmodifiableList(
            Arrays.asList(124, 126, 127, 128)
    );

    // ======================== ALID 定义 ========================

    /** 报警 ID 范围 (30000-30020) */
    public static final int ALID_START = 30000;
    public static final int ALID_END = 30020;

    /** 报警名称映射 */
    public static final Map<Integer, String> ALARM_NAMES;
    static {
        Map<Integer, String> m = new LinkedHashMap<>();
        m.put(30000, "N2LowPressureAlarm");
        m.put(30001, "LowWaterPressureAlarm");
        m.put(30002, "OxygenContentTooHigh");
        m.put(30003, "DeviationAlarm");
        m.put(30004, "OverTemperatureAlarm");
        m.put(30005, "PhaseSequenceAlarm");
        m.put(30006, "DoorCatchAlarm");
        m.put(30007, "OxygenReductionTimeoutAlarm");
        m.put(30008, "OxygenAnalyzerAbnormalAlarm");
        m.put(30009, "InverterAbnormalAlarm");
        m.put(30010, "DifferentialPressureAlarm");
        m.put(30011, "TankHighPressureAlarm");
        m.put(30012, "HotAirBlowerOverloadAlarm");
        m.put(30013, "LiquidLeakAlarm");
        m.put(30014, "MainTempControlCommAbnormal");
        m.put(30015, "OverTempMeterCommAbnormal");
        m.put(30016, "SixChannelCommAbnormal");
        m.put(30017, "MagneticLockEngageAbnormal1");
        m.put(30018, "MagneticLockEngageAbnormal2");
        m.put(30019, "CDALowPressureAlarm");
        m.put(30020, "ParameterWriteTimeoutAlarm");
        ALARM_NAMES = Collections.unmodifiableMap(m);
    }

    /** 所有 ALID 列表 */
    public static final List<Integer> ALL_ALIDS;
    static {
        List<Integer> alids = new java.util.ArrayList<>();
        for (int i = ALID_START; i <= ALID_END; i++) {
            alids.add(i);
        }
        ALL_ALIDS = Collections.unmodifiableList(alids);
    }

    // ======================== 追踪配置 ========================

    /** 默认追踪报告 ID (TRID) */
    public static final int DEFAULT_TRID = 100;

    /** 追踪用 SVID 列表 (Temperature1-6) */
    public static final List<Integer> TRACE_SVIDS = Collections.unmodifiableList(
            Arrays.asList(1007, 1008, 1009, 1010, 1011, 1012)
    );

    // ======================== 远程命令 ========================

    /** 远程命令: RUN */
    public static final String RCMD_RUN = "RUN";
    /** 远程命令: PP-SELECT */
    public static final String RCMD_PP_SELECT = "PP-SELECT";
    /** 远程命令: LOCAL */
    public static final String RCMD_LOCAL = "LOCAL";
    /** 远程命令: REMOTE */
    public static final String RCMD_REMOTE = "REMOTE";

    private DeviceConfig() {
        // 工具类，禁止实例化
    }
}

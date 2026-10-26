package com.secsgem.moljm240d2;

import java.util.Map;

/**
 * MOLJM-240D2 Host 事件监听接口。
 * <p>
 * 当设备上报事件（报警、事件报告、追踪数据等）时，通过此接口回调通知上层业务。
 * </p>
 */
public interface MoljmHostListener {

    /**
     * 收到报警事件 (S5F1)。
     *
     * @param alcd  报警代码: 128=触发, 0=消除
     * @param alid  报警 ID
     * @param altx  报警文本描述
     */
    default void onAlarmEvent(int alcd, int alid, String altx) {}

    /**
     * 收到设备事件报告 (S6F11)。
     *
     * @param ceid   集合事件 ID
     * @param report 报告数据, key=RPTID, value=对应 SVID 值列表
     */
    default void onEventReport(int ceid, Map<Integer, Object> report) {}

    /**
     * 收到追踪数据 (S6F1)。
     *
     * @param trid      追踪报告 ID
     * @param sampleNum 采样序号
     * @param timestamp 时间戳字符串
     * @param values    各 SVID 对应的值
     */
    default void onTraceData(int trid, int sampleNum, String timestamp, Object[] values) {}

    /**
     * 通讯状态变化。
     *
     * @param selected true=SELECTED, false=NOT_SELECTED
     */
    default void onCommunicateStateChanged(boolean selected) {}

    /**
     * 在线状态变化。
     *
     * @param online true=ONLINE, false=OFFLINE
     */
    default void onOnlineStateChanged(boolean online) {}
}

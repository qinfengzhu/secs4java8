package com.secsgem.moljm240d2;

import java.util.Map;
import java.util.Optional;

import com.shimizukenta.secs.SecsException;
import com.shimizukenta.secs.SecsMessage;
import com.shimizukenta.secs.gem.HCACK;
import com.shimizukenta.secs.secs2.Secs2;
import com.shimizukenta.secs.secs2.Secs2Exception;

/**
 * MOLJM-240D2 Host 端入口类。
 * <p>
 * 演示完整的通讯流程：连接 → 建立通讯 → 上线 → 配置事件报告 → 业务操作 → 离线。
 * </p>
 */
public class MoljmHostMain {

    // ======================== 设备连接配置 ========================
    /** 设备 IP 地址 */
    private static final String DEVICE_IP = "127.0.0.1";
    /** 设备端口号 */
    private static final int DEVICE_PORT = 5000;
    /** HSMS Session ID */
    private static final int SESSION_ID = 10;

    public static void main(String[] args) {

        MoljmHost host = new MoljmHost(DEVICE_IP, DEVICE_PORT, SESSION_ID);

        // 注册事件监听器
        host.addListener(new MoljmHostListener() {

            @Override
            public void onAlarmEvent(int alarmCode, int alarmId, String alarmText) {
                String action = (alarmCode == 128) ? "触发" : "消除";
                System.out.println("[ALARM-EVENT] 报警" + action + ": ALID=" + alarmId + " (" + alarmText + ")");
            }

            @Override
            public void onEventReport(int ceid, Map<Integer, Object> report) {
                System.out.println("[EVENT-REPORT] CEID=" + ceid);
                for (Map.Entry<Integer, Object> entry : report.entrySet()) {
                    System.out.println("  RPTID=" + entry.getKey() + " -> " + entry.getValue());
                }
            }

            @Override
            public void onTraceData(int trid, int sampleNum, String timestamp, Object[] values) {
                StringBuilder sb = new StringBuilder();
                sb.append("[TRACE-DATA] TRID=").append(trid)
                  .append(" Sample=").append(sampleNum)
                  .append(" Time=").append(timestamp)
                  .append(" Values=[");
                for (int i = 0; i < values.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(values[i]);
                }
                sb.append("]");
                System.out.println(sb.toString());
            }

            @Override
            public void onCommunicateStateChanged(boolean selected) {
                System.out.println("[STATE] HSMS " + (selected ? "SELECTED" : "NOT SELECTED"));
            }

            @Override
            public void onOnlineStateChanged(boolean online) {
                System.out.println("[STATE] Equipment " + (online ? "ONLINE" : "OFFLINE"));
            }
        });

        try {
            // ======================== 1. 建立连接 ========================
            System.out.println("========== 1. 建立 HSMS 连接 ==========");
            host.open();

            // ======================== 2. 通讯建立 + 上线 ========================
            System.out.println("========== 2. 通讯建立 + 上线 ==========");
            boolean success = host.establishCommunicationAndOnline();
            if (!success) {
                System.err.println("通讯建立或上线失败，退出");
                host.close();
                return;
            }
            System.out.println("设备已上线!");

            // ======================== 3. 心跳检测 ========================
            System.out.println("========== 3. 心跳检测 (S1F1) ==========");
            try {
                Optional<SecsMessage> s1f2Reply = host.s1f1AreYouThere();
                if (s1f2Reply.isPresent()) {
                    Secs2 data = s1f2Reply.get().secs2();
                    String mdln = data.getAscii(0);
                    String softrev = data.getAscii(1);
                    System.out.println("设备: " + mdln + " 版本: " + softrev);
                }
            } catch (SecsException | InterruptedException e) {
                System.err.println("心跳检测失败: " + e.getMessage());
            }

            // ======================== 4. 查询日期时间 ========================
            System.out.println("========== 4. 查询设备时间 (S2F17) ==========");
            try {
                String dateTime = host.s2f17DateTimeRequest();
                System.out.println("设备时间: " + dateTime);
            } catch (SecsException | Secs2Exception | InterruptedException e) {
                System.err.println("查询时间失败: " + e.getMessage());
            }

            // ======================== 5. 查询所有状态变量 ========================
            System.out.println("========== 5. 查询所有状态变量 (S1F3) ==========");
            try {
                Optional<SecsMessage> s1f4Reply = host.s1f3QueryAllStatus();
                if (s1f4Reply.isPresent()) {
                    System.out.println("S1F4 回复: " + s1f4Reply.get().secs2().toJson());
                }
            } catch (SecsException | InterruptedException e) {
                System.err.println("查询状态变量失败: " + e.getMessage());
            }

            // ======================== 6. 查询报警列表 ========================
            System.out.println("========== 6. 查询报警列表 (S5F5) ==========");
            try {
                Optional<SecsMessage> s5f6Reply = host.s5f5AlarmListRequest();
                if (s5f6Reply.isPresent()) {
                    System.out.println("S5F6 报警列表: " + s5f6Reply.get().secs2().toJson());
                }
            } catch (SecsException | InterruptedException e) {
                System.err.println("查询报警列表失败: " + e.getMessage());
            }

            // ======================== 7. 查询工艺列表 ========================
            System.out.println("========== 7. 查询工艺列表 (S7F19) ==========");
            try {
                java.util.List<String> ppNames = host.s7f19GetCurrentEppd();
                System.out.println("工艺列表: " + ppNames);
            } catch (SecsException | Secs2Exception | InterruptedException e) {
                System.err.println("查询工艺列表失败: " + e.getMessage());
            }

            // ======================== 8. 查询指定工艺参数 ========================
            System.out.println("========== 8. 查询工艺参数 (S7F25) ==========");
            try {
                Optional<SecsMessage> s7f26Reply = host.s7f25RequestProcessProgram("test");
                if (s7f26Reply.isPresent()) {
                    System.out.println("S7F26 工艺参数: " + s7f26Reply.get().secs2().toJson());
                }
            } catch (SecsException | InterruptedException e) {
                System.err.println("查询工艺参数失败: " + e.getMessage());
            }

            // ======================== 9. 初始化数据追踪 ========================
            System.out.println("========== 9. 初始化数据追踪 (S2F23) ==========");
            try {
                host.s2f23TraceInitDefault();
                System.out.println("数据追踪已初始化 (Temperature1-6, 周期2秒, 采样5次)");
            } catch (SecsException | InterruptedException e) {
                System.err.println("初始化追踪失败: " + e.getMessage());
            }

            // ======================== 10. 发送远程命令 ========================
            System.out.println("========== 10. 发送远程命令 ==========");
            try {
                // 选择工艺
                HCACK ppSelectAck = host.sendPpSelectCommand("test");
                System.out.println("PP-SELECT HCACK: " + ppSelectAck);

                // 启动运行
                HCACK runAck = host.sendRunCommand();
                System.out.println("RUN HCACK: " + runAck);
            } catch (SecsException | Secs2Exception | InterruptedException e) {
                System.err.println("发送远程命令失败: " + e.getMessage());
            }

            // ======================== 等待追踪数据/事件上报 ========================
            System.out.println("========== 等待设备上报数据 (30秒) ==========");
            Thread.sleep(30000);

            // ======================== 11. 离线 ========================
            System.out.println("========== 11. 请求离线 (S1F15) ==========");
            host.requestOffline();

        } catch (InterruptedException e) {
            System.out.println("程序被中断");
        } catch (Exception e) {
            System.err.println("运行异常: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // ======================== 关闭连接 ========================
            System.out.println("========== 关闭连接 ==========");
            host.close();
            System.out.println("程序结束");
        }
    }
}

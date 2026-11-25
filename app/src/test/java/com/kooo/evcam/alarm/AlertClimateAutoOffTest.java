/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to this code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.alarm.climate.ClimateOffGuard;
import com.kooo.evcam.alarm.climate.ClimateSnapshot;
import com.kooo.evcam.alarm.climate.ClimateSource;
import com.kooo.evcam.alarm.guard.GuardToken;
import com.kooo.evcam.alarm.ntfy.NtfyGate;
import com.kooo.evcam.alarm.ntfy.NtfyRisk;
import com.kooo.evcam.alarm.policy.ClimatePolicy;
import com.kooo.evcam.alarm.telegram.TelegramCommand;
import com.kooo.evcam.telemetry.CarAccess;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;
import com.kooo.evcam.telemetry.TestSnapshots;
import com.kooo.evcam.telemetry.VehicleState;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 自动关空调那一条路：排得上、等得到、说得出。还有回执里那句座椅、以及过期按钮的回话。
 *
 * <h3>为什么这几条非钉不可</h3>
 *
 * <p>回执上写了「几点自动关」。锁车十分钟之后车机睡了，{@code latest()} 和挡位都是 null ——
 * 4.15.0 之前这段代码把这当成「车在走」，直接跳过：空调开一整夜，回执上那句时间是假的。
 * 同一处还有两颗：只按读回排自动关（读回那时是空的）、以及出口不通就整段跳过（安全动作被出口绑住）。</p>
 *
 * <p>真车上看不出来 —— 十分钟以后才知道。所以这里把十分钟拨成一次调用，状态换成「读不到」。</p>
 *
 * <h3>关掉之后把车放回原样（{@code docs/climate-alerts/RESTORE.md} §6）</h3>
 *
 * <p>每一条「开」都先读一遍车上那九个字段（读不写东西），等自动关那一笔车写完之后，胶水照快照把动过的写回去 ——
 * 前提是那一笔关真的读回了 0、这一项开着、链上的号还对得上。下面这几条钉的就是这段胶水：读在写之前、写的是命令
 * 那一份而不是快照那一份、开着关着都照读、号对不上就一声不响、全不知道就不说话、被拒了说一句「没变」、
 * 开走了快照跟着没、连开两次回放的是第二次读到的那一份。</p>
 *
 * The receipt promises a clock time for the auto-off. Ten minutes after locking, the head unit is asleep
 * and both the snapshot and the gear are null; before this pass that was read as "the car is moving" and
 * skipped, so the AC ran all night and the promised time was a lie. None of it is visible from the car in
 * under ten minutes, so the clock is dialled and the state is made unreadable here.</p>
 *
 * <p>The put-back after the off: every ON reads the nine fields first (reads write nothing), and once the
 * auto-off's write is confirmed the glue replays that snapshot — but only when the off really read back 0,
 * the switch is on, and the chain's number still matches. These tests pin the glue itself, which had no
 * coverage: the read before the write, the write being the command's rather than the snapshot's, the read
 * that happens even with the switch off, silence on a dropped chain, silence when nothing was known, one
 * no-change sentence on a refusal, the snapshot dying with the park session, and two ONs replaying the
 * second capture.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, shadows = {ShadowNtfySender.class})
public class AlertClimateAutoOffTest {

    private static final String TOPIC =
            "zeekrsc_kymoy9m40cuQYXmHabcdefghijklmnopqrstu";

    // 车厢温度那几个功能号和区域（跟 ClimateSourceTest 一样是测试里编的号）：真车上由 CarNames 取，
    // 这里把常量表灌进去（seedCarNames），假车答的和胶水问的才是同一批号。
    // The climate ids and zones, made up here the same way ClimateSourceTest makes them up: on the car
    // CarNames resolves them, and seedCarNames puts them in so the fake answers what the glue asks.
    private static final int POWER = 0x821;
    private static final int TEMP = 0xA01;
    private static final int TEMP_UNIT = 0xA05;
    private static final int SEAT_HEAT = 0xB01;
    private static final int SEAT_VENT = 0xB02;
    private static final int CIRC = 0xB03;
    private static final int UNIT_C = 1;
    private static final int UNIT_F = 0x10;

    private static final int FRONT = 0x8;
    private static final int DRIVER = 0x1;
    private static final int PASSENGER = 0x4;
    private static final int REAR = 0x80;

    private Context context;
    private AppConfig config;
    private SentryAlerts alerts;
    /** {@code CarNames} 的常量表本来是什么样（这一条测试跑完就把它摆回去）。 */
    private Map<String, Integer> namesBefore;

    @Before
    public void setUp() throws Exception {
        forgetSingleton();
        namesBefore = new HashMap<>(carNamesTable());
        context = RuntimeEnvironment.getApplication();
        ShadowNtfySender.reset();
        config = new AppConfig(context);
        config.setAlertsEnabled(true);
        config.setAlertsTransport(NtfyGate.NTFY);
        config.setAlertsNtfyTopic(TOPIC);
        config.setAlertsNtfyRiskAck(NtfyRisk.ackFor(TOPIC));
        config.setAlertsNtfyPaid(true);
        config.setAlertsTelegramToken("");
        config.setAlertsTelegramChatId("");
        config.setAlertsClimateEnabled(true);
        config.setAlertsClimateMinutes(10);
        alerts = SentryAlerts.get(context);
        // 整个类共一个沙箱：上一条测试摆过的车（停着、假车、读数）不能留给下一条 ——
        // 不把它们摆回原样，后面那条「车没停稳」的测试看到的是上一条停着的车。
        // The whole class shares one sandbox: the car the previous test arranged (parked, the fake, its
        // readings) is put back to the default, or the next test sees a parked car it never arranged.
        setTelemetryState(VehicleState.empty());
        setTelemetryReadings(com.kooo.evcam.telemetry.Readings.empty());
        set("climateAccessForTest", null);
    }

    // ================================================================= 排上自动关

    /**
     * 开关那一笔车机受理了、读回却读到 0（车机已经在睡的路上）：自动关照样排上。
     *
     * <p>反过来说：只按读回排，这条就是空的 —— 空调没人关。</p>
     *
     * The power write was accepted but the readback says 0: the auto-off is still scheduled. Scheduling on
     * the readback alone leaves this null, which means nobody ever switches the AC off.
     */
    @Test
    public void anAcceptedPowerWriteSchedulesTheAutoOffEvenWhenTheReadbackSaysOff() throws Exception {
        onClimateReport(report(ClimateSource.Outcome.NO_CHANGE, 22f, false, true, false), 22, false, false, 0);

        assertNotNull("开关那一笔受理了：自动关必须排上", pending());
    }

    /** 对照：既没读到开、也没受理过，就不排（不要凭空排一个）。 */
    @Test
    public void withoutEitherEvidenceNoAutoOffIsScheduled() throws Exception {
        onClimateReport(report(ClimateSource.Outcome.NO_CHANGE, 22f, false, false, false), 22, false, false, 0);

        assertNull("既没读到开着、也没受理：不排", pending());
    }

    /**
     * 出口不通时自动关照样排上：安全动作不依赖聊天能不能发。
     *
     * <p>出口没配好（这里把话题清掉）时 {@code onClimateReport} 在发回执那一步就返回了 ——
     * 排自动关必须在那个返回之前。</p>
     *
     * The auto-off is scheduled even when the channel cannot send: a safety action must not depend on the
     * chat. With no topic the receipt path returns early, so scheduling has to come before that guard.
     */
    @Test
    public void aBlockedChannelStillGetsTheAutoOffScheduled() throws Exception {
        config.setAlertsNtfyTopic("");

        onClimateReport(report(ClimateSource.Outcome.DONE, 22f, true, true, false), 22, false, false, 0);

        assertNotNull("出口不通也得把空调关上", pending());
        assertEquals("出口不通就一句都没发出去", 0, ShadowNtfySender.texts);
    }

    // ================================================================= 等停着、然后放弃

    /**
     * 读不到车停着：不写、隔 30 秒再来一次（不是悄悄算了）。
     *
     * <p>这里把重试间隔换成够长的一段，只验「它确实重新排了自己一次」，30 秒这个数由
     * {@code ClimateOffGuardTest} 钉着。</p>
     *
     * Unreadable state: no write, and the task re-arms itself instead of quietly giving up. The interval is
     * stretched here so the assertion is about the re-arm; the 30 s number is pinned in ClimateOffGuardTest.
     */
    @Test
    public void anUnreadableStateRetriesInsteadOfSkipping() throws Exception {
        long[] now = {1_000L};
        setClock(now);
        setRetryMs(TimeUnit.HOURS.toMillis(1));

        alerts.attemptClimateOff(10, 2, now[0]);

        assertNotNull("读不到停车状态时它得把自己再排一次", pending());
        assertFalse("重新排的那一次还没到点", past(pending()));
        assertEquals("还没写就不该有回话", 0, ShadowNtfySender.texts);
    }

    /**
     * 等满 3 × minutes 还没见到「停着」：放弃，并且在聊天里说一句。回执上写了关的时刻，
     * 关不掉就不能闷着。
     *
     * After 3 × minutes without a parked reading: give up and say so in the chat.
     */
    @Test
    public void givingUpWithoutAParkedReadingSendsOneChatLine() throws Exception {
        long[] now = {1_000L};
        setClock(now);
        setRetryMs(TimeUnit.HOURS.toMillis(1));

        alerts.attemptClimateOff(10, 2, now[0]);
        assertNotNull("第一次读不到：又排了一次", pending());
        assertEquals("这时候还不能说话", 0, ShadowNtfySender.texts);

        now[0] = 1_000L + ClimateOffGuard.boundMs(10);
        alerts.attemptClimateOff(10, 2, 1_000L);

        assertTrue("关不掉要回一句话", awaitSend());
        assertEquals("只发这一句", 1, ShadowNtfySender.texts);
        assertEquals("说的是「车没报停着」那一句",
                context.getString(R.string.alert_climate_off_unconfirmed),
                ShadowNtfySender.sent.get(0));
        assertNull("放弃了就不再排", pending());
    }

    // ================================================================= 关不掉就别压着热

    /**
     * 关空调没做成（{@code NO_CHANGE}）：不再认为空调关着，热警报照报 —— 多报一条比闷着好。
     *
     * A failed OFF write must not keep suppressing hot alerts: this asserts the state flips back to "not
     * known to be on" and that the same policy then fires where "on" would have suppressed it.
     */
    @Test
    public void aFailedOffWriteStopsSuppressingHotAlerts() throws Exception {
        setClimateState(true, true);

        onClimateReport(report(ClimateSource.Outcome.NO_CHANGE, 0f, true, true, false), 0, false, false, 0);

        assertFalse("关不掉就不能再当它关着", believedOn());
        assertFalse("也不再声称知道它关着", known());

        // 同一个策略、同一个车厢：说「空调开着」就压住，说「空调没关成」就报。
        // 阀值、回差、计时全从跑着的那一份来（{@code newClimatePolicy()}），不在这条测试里另写一套数。
        //
        // Same policy, same cabin: "the AC is on" suppresses it, "the AC did not switch off" reports it.
        // Thresholds, band and timings all come from the live policy; none of them is typed in here.
        float cabin = config.getAlertsClimateHotC() + 1f;
        long after = ClimatePolicy.DEFAULT_HOLD_MS + ClimatePolicy.DEFAULT_STALE_MS / 3;
        assertEquals("空调开着时热警报被压住（这一条是反证）", ClimatePolicy.Kind.NONE,
                hot(after, true, cabin, newPolicy()));
        assertEquals("关不掉时同一条就要报", ClimatePolicy.Kind.HOT,
                hot(after, believedOn(), cabin, newPolicy()));
    }

    /**
     * 一条热读数序列：三个新鲜样本，最后一个时间点上得出的结论。
     *
     * A hot sample sequence: three fresh samples, the verdict at the last one.
     */
    private static ClimatePolicy.Kind hot(long lastAt, boolean hvacOn, float cabin,
                                          ClimatePolicy policy) {
        long step = ClimatePolicy.DEFAULT_STALE_MS / 3;
        policy.decide(cabin, null, null, true, hvacOn, false, false, false, 1_000L);
        policy.decide(cabin, null, null, true, hvacOn, false, false, false, 1_000L + step);
        return policy.decide(cabin, null, null, true, hvacOn, false, false, false, lastAt).kind;
    }

    // ================================================================= 车开走了：这一趟结束

    /**
     * 车在十分钟里开走了：自动关这一条链当场取消，而且一声不响。
     *
     * <p>没有这一道的时候：开走之后链还在重试，重试满了就回一句「关不掉空调」——
     * 而车里的空调是车主自己留着开的，那句话是假的。
     *
     * <p>钻不动十分钟，所以这里走的是哨兵那条路看到的那一下：一条「不在 P」的读数来的时候
     * {@code climateCheck} 调 {@code climateForgetSession}。</p>
     *
     * The car drives off inside the window: the chain is cancelled and says nothing. Without that guard the
     * chain kept retrying and then reported "could not switch the AC off" about an AC the owner deliberately
     * left running. The clock cannot be wound here, so this drives the sample the watcher really sees: a
     * non-P reading, which makes climateCheck end the park session.
     */
    @Test
    public void drivingAwayCancelsTheAutoOffWithoutSayingAnything() throws Exception {
        onClimateReport(report(ClimateSource.Outcome.DONE, 22f, true, true, false), 22, false, false, 0);
        ScheduledFuture<?> armed = pending();
        assertNotNull("先得排上", armed);
        // 回执那条先落地、再清掉计数：下面要比的是「取消之后又说了没有」。
        // Let the receipt land, then clear the counters: what is measured below is noise after the cancel.
        assertTrue(awaitSend());
        ShadowNtfySender.reset();

        climateCheck(VehicleState.empty().edit().gear("D").build(),
                TestSnapshots.of(Signal.TEMP_INSIDE, 30f));

        assertNull("开走了：这一条链要取消", pending());
        assertTrue("取消要真的落到那个 future 上", armed.isCancelled() || armed.isDone());
        assertEquals("取消是安静的：一句话都不说", 0, ShadowNtfySender.texts);
    }

    /**
     * 温度开关关着、自动关那条链已经排上，车开走了：链照样要取消，一句话都不说。
     *
     * <p>以后 parked 的判断排在开关后面：开关一关这段就提前返回，链留着，重试到「丢手」，在车主开着空调
     * 开车的时候发一句「没能确认为你关了」—— 假消息，而且没法消除。离开 P 就是这一趟结束，与设置无关。</p>
     *
     * The switch is off and an auto-off chain is pending; the car drives away. The chain must still be
     * cancelled and nothing sent. With the parked check behind the switch this returned early, the chain
     * retried to GIVE_UP, and the owner got "could not confirm the AC is off" while driving with the AC on.
     * Leaving P ends the session whatever the settings say.
     */
    @Test
    public void drivingAwayCancelsTheAutoOffEvenWithTheClimateSwitchOff() throws Exception {
        onClimateReport(report(ClimateSource.Outcome.DONE, 22f, true, true, false), 22, false, false, 0);
        ScheduledFuture<?> armed = pending();
        assertNotNull("先得排上", armed);
        // 回执那条先落地，再把开关关掉、清计数：下面要比的是「取消之后又说了没有」。
        // Let the receipt land, then turn the switch off and clear the counters: what is measured
        // below is noise after the cancel.
        assertTrue(awaitSend());
        config.setAlertsClimateEnabled(false);
        ShadowNtfySender.reset();

        climateCheck(VehicleState.empty().edit().gear("D").build(),
                TestSnapshots.of(Signal.TEMP_INSIDE, 30f));

        assertNull("开关关着，开走了也一样：这一条链要取消", pending());
        assertTrue("取消要真的落到那个 future 上", armed.isCancelled() || armed.isDone());
        assertEquals("取消是安静的：一句话都不说", 0, ShadowNtfySender.texts);
    }

    /**
     * 取消以后，老的那条链就算还是跑了一次（取消和它撞在一起），也必须在号那一关停手：不写车、不说话。
     *
     * <p>这就是号存在的理由：{@code scheduleClimateOff} 在主线程上取消再赋值，而重试在
     * {@code alert-climate} 线程上赋值 —— 两条链能同时活着。</p>
     *
     * An older chain that still runs once (a cancel that collides with it) stops at the number check: no write,
     * no chat line. That is why the number exists: the main thread cancels and re-arms while a retry assigns on
     * the alert-climate thread, so two chains can be alive at once.
     */
    @Test
    public void anOlderChainStopsAtTheNumberCheck() throws Exception {
        onClimateReport(report(ClimateSource.Outcome.DONE, 22f, true, true, false), 22, false, false, 0);
        int old = generation();
        assertTrue(awaitSend());
        ShadowNtfySender.reset();

        climateCheck(VehicleState.empty().edit().gear("D").build(),
                TestSnapshots.of(Signal.TEMP_INSIDE, 30f));
        assertEquals("取消要换一个号", old + 1, generation());
        assertNull(pending());

        // 老链带着老号跑起来：连「放弃」那句话都不许说。
        attemptClimateOff(10, 2, 1_000L, old);

        assertEquals("老链一句话都不许说", 0, ShadowNtfySender.texts);
        assertNull("也不许给自己再排一次", pending());
    }

    /**
     * 车主自己关成了：自动关那一条当场取消，出口不通也一样取消。
     *
     * <p>以前取消那一步排在出口判断后面：出口没配好时直接返回，链还活着，十分钟后又写一次车、
     * 又在聊天里说一句。</p>
     *
     * A successful manual off cancels the chain even with a blocked channel. The cancel used to sit behind the
     * channel guard, so a blocked exit returned early and left the chain to write the car again ten minutes
     * later.
     */
    @Test
    public void aManualOffCancelsTheAutoOffEvenWhenTheChannelIsBlocked() throws Exception {
        config.setAlertsNtfyTopic("");
        onClimateReport(report(ClimateSource.Outcome.DONE, 22f, true, true, false), 22, false, false, 0);
        assertNotNull("出口不通也得先把自动关排上", pending());

        onClimateReport(report(ClimateSource.Outcome.DONE, 0f, false, true, true), 0, false, false, 0);

        assertNull("车主自己关了：自动关必须取消，出口不通也要取消", pending());
        assertEquals("出口不通，一句都没发", 0, ShadowNtfySender.texts);
    }

    // ================================================================= 回执里的座椅

    /**
     * 座椅那一笔被车机拒了：回执里不许写座椅，别的一样照写（温度、几点关）。
     *
     * A refused seat write must not be claimed in the receipt; the rest of the receipt still reads right.
     */
    @Test
    public void aRefusedSeatWriteIsNotClaimedInTheReceipt() throws Exception {
        onClimateReport(report(ClimateSource.Outcome.DONE, 22f, true, true, false), 22, true, false, 0);

        assertTrue(awaitSend());
        String receipt = ShadowNtfySender.sent.get(0);
        assertFalse("座椅没写进去，回执就不许说它: " + receipt,
                receipt.contains(context.getString(R.string.alert_climate_seat_heat_left)));
        assertTrue("温度还要写: " + receipt, receipt.contains("22.0"));
    }

    /** 座椅真的写了：回执里就有它（否则上一条是空过）。 */
    @Test
    public void anAcceptedSeatWriteIsNamedInTheReceipt() throws Exception {
        onClimateReport(report(ClimateSource.Outcome.DONE, 22f, true, true, true), 22, true, false, 0);

        assertTrue(awaitSend());
        assertTrue("座椅写了就该写进回执: " + ShadowNtfySender.sent.get(0),
                ShadowNtfySender.sent.get(0)
                        .contains(context.getString(R.string.alert_climate_seat_heat_left)));
    }

    /**
     * 只写主驾时回执不许提副驾：多写一个没动过的座椅，车主会去找一个不存在的加热。
     *
     * With the driver seat alone accepted, the receipt must not mention the passenger side: naming a seat
     * nobody switched on sends the owner looking for heat that is not there.
     */
    @Test
    public void aDriverSeatWriteDoesNotClaimThePassengerSeat() throws Exception {
        onClimateReport(report(ClimateSource.Outcome.DONE, 22f, true, true, true), 22, true, false, 0);

        assertTrue(awaitSend());
        String receipt = ShadowNtfySender.sent.get(0);
        assertFalse("副驾没受理就不许说它: " + receipt,
                receipt.contains(context.getString(R.string.alert_climate_seat_heat_right)));
        assertFalse("也不许说两个都开了: " + receipt,
                receipt.contains(context.getString(R.string.alert_climate_seat_heat_both)));
    }

    /** 两个座椅都受理了（设置里是 both）：回执说的是两个一起。 */
    @Test
    public void bothSeatsAcceptedIsSaidAsBoth() throws Exception {
        onClimateReport(reportBothSeats(ClimateSource.Outcome.DONE, 22f, true, true),
                22, true, false, 0);

        assertTrue(awaitSend());
        assertTrue("两个都写了就说两个: " + ShadowNtfySender.sent.get(0),
                ShadowNtfySender.sent.get(0)
                        .contains(context.getString(R.string.alert_climate_seat_heat_both)));
    }

    // ================================================================= 那个 20（回执印的是命令的度数）

    /**
     * {@code ac 22}，而设置里的默认值是 20：回执必须是 22.0，不是 20.0。
     *
     * <p>回执印的是车上的设定值（{@code Report.targetC}），不是设置里那个默认值。这两个数一混，车主看到
     * 22 变成了 20，就会以为车自己改了温度 —— 他根本没要过 20。</p>
     *
     * The configured default is 20 and the owner sent {@code ac 22}: the receipt has to say 22.0. It prints
     * the setpoint the write carried, never the configured default — mixing them is what made the owner read
     * "target 20" and wonder who asked for it.
     */
    @Test
    public void theReceiptPrintsTheCommandedTargetNotTheConfiguredDefault() throws Exception {
        config.setAlertsClimateTargetC(20f);

        onClimateReport(report(ClimateSource.Outcome.DONE, 22f, true, true, false), 22, false, false, 0);

        assertTrue(awaitSend());
        String receipt = ShadowNtfySender.sent.get(0);
        assertTrue("回执要写命令要的度数: " + receipt, receipt.contains("22.0"));
        assertFalse("回执不许写设置里那个默认值: " + receipt, receipt.contains("20.0"));
    }

    // ================================================================= 过期的空调按钮

    /**
     * 号码已经清掉的空调按钮按下去：回一句话，而且什么都不写车。
     *
     * <p>回的那句是「那是上一条警报的按钮」；要是它继续往下走，就会按聊天命令那条路办事
     * （这里没停着车，会回「车没停稳」）—— 两种回话不一样，所以这一句就是「没有动作」的凭据。</p>
     *
     * A climate button whose token has since been cleared gets one line back and no action. The wording is
     * the evidence: falling through to the command path would answer "the car is not parked" instead.
     */
    @Test
    public void aStaleClimateButtonGetsAnAnswerAndDoesNothing() throws Exception {
        setClimateToken("");
        String payload = AlertButton.data(AlertButton.climateAction(22), GuardToken.mint(77L));

        alerts.onAlertButton(payload);

        assertTrue("按了就得有回话", awaitSend());
        assertEquals("只发一句", 1, ShadowNtfySender.texts);
        assertEquals("说的是「那是上一条警报的按钮」",
                context.getString(R.string.alert_climate_stale_button),
                ShadowNtfySender.sent.get(0));
        assertNull("过期按钮不排任何事", pending());
        assertFalse("也不许声称空调开着", believedOn());
    }

    /** 对照：号码对得上的那颗照样走命令那条路（这里车没停稳，所以回的是「车没停稳」）。 */
    @Test
    public void aFreshClimateButtonStillGoesThroughTheCommandPath() throws Exception {
        setClimateToken(GuardToken.mint(78L));
        String payload = AlertButton.data(AlertButton.climateAction(22), GuardToken.mint(78L));

        alerts.onAlertButton(payload);

        assertTrue(awaitSend());
        assertEquals(context.getString(R.string.alert_climate_moving), ShadowNtfySender.sent.get(0));
    }

    /**
     * 过期的「关」那颗：回的是「要关就说 ac off」，不是「要开就说 ac on 22」。
     *
     * <p>以前两种按下去都回同一句开空调的话 —— 一个按钮回了反着的动作。</p>
     *
     * A stale OFF button is answered with "send ac off", not "send ac on 22": the old wording told the owner to
     * do the opposite of what they pressed.
     */
    @Test
    public void aStaleOffButtonAnswersWithTheOffWording() throws Exception {
        setClimateToken("");
        String payload = AlertButton.data(AlertButton.ACTION_CLIMATE_OFF, GuardToken.mint(79L));

        alerts.onAlertButton(payload);

        assertTrue("按了就得有回话", awaitSend());
        assertEquals("只发一句", 1, ShadowNtfySender.texts);
        assertEquals("说的是「要关就说 ac off」",
                context.getString(R.string.alert_climate_stale_off_button),
                ShadowNtfySender.sent.get(0));
        assertNull("过期按钮不排任何事", pending());
    }

    // ================================================================= 回执后面那句座椅问句

    /**
     * Telegram：回执之后问一句，五颗按钮，号码就是这个 climateToken。
     *
     * <p>五颗：主驾加热 / 副驾加热 / 主驾通风 / 副驾通风 / 关座椅，每颗的 callback_data 都是
     * {@code a:<action>:<token>} 那个形状。</p>
     *
     * On Telegram the receipt is followed by one question with five buttons, all carrying this alert's
     * climateToken in the existing {@code a:<action>:<token>} shape.
     */
    @Test
    public void theSeatQuestionCarriesFiveButtonsOnTelegram() throws Exception {
        config.setAlertsTransport(NtfyGate.TELEGRAM);
        String token = GuardToken.mint(80L);
        setClimateToken(token);

        String[] ask = alerts.seatAsk();

        assertNotNull("Telegram 有按钮：这一句就要发", ask);
        assertEquals(context.getString(R.string.alert_climate_seat_ask), ask[0]);
        String markup = ask[1];
        for (String action : new String[] {AlertButton.ACTION_SEAT_HEAT_LEFT,
                AlertButton.ACTION_SEAT_HEAT_RIGHT, AlertButton.ACTION_SEAT_VENT_LEFT,
                AlertButton.ACTION_SEAT_VENT_RIGHT, AlertButton.ACTION_SEAT_OFF}) {
            assertTrue(markup, markup.contains("\"callback_data\":\""
                    + AlertButton.data(action, token) + "\""));
        }
        assertEquals("五颗按钮", 5, markup.split("callback_data", -1).length - 1);
        assertTrue("按钮上要有字: " + markup,
                markup.contains(context.getString(R.string.alert_climate_seat_btn_driver_heat)));
    }

    /**
     * ntfy 没有按钮：静默跳过 —— 不发一句「座椅要不要也开」然后什么也按不到，也不多发一条消息。
     *
     * ntfy cannot show buttons, so the question is skipped silently: nothing to press, and no extra message.
     */
    @Test
    public void theSeatQuestionIsSkippedSilentlyOnNtfy() throws Exception {
        assertNull("ntfy 没有按钮：不许发一句没法按的话", alerts.seatAsk());

        onClimateReport(report(ClimateSource.Outcome.DONE, 22f, true, true, false), 22, false, false, 0);

        assertTrue(awaitSend());
        assertEquals("只发回执那一条", 1, ShadowNtfySender.texts);
    }

    /**
     * 「警报里的按钮」关掉：发送那一头一个 inline keyboard 都不挂，只有按钮才成立的那一句也不发。
     *
     * <p>四颗挂法全开着时都该有东西（不这么验的话，把一处删了也算通过）；关掉之后都该是空串，
     * 而座椅那一问干脆返回 {@code null} —— 一句「座椅要不要也开」没有按钮就只是没人能回的一句话，
     * 少发一条消息比多发一条没法按的强。</p>
     *
     * With buttons switched off nothing carries an inline keyboard, and the question that only exists
     * because of its buttons is not sent at all. Each site is checked while they are on first, so deleting
     * one of the gates cannot pass this test.
     */
    @Test
    public void withButtonsOffNothingCarriesAKeyboard() throws Exception {
        config.setAlertsTransport(NtfyGate.TELEGRAM);
        set("alertToken", GuardToken.mint(90L));
        set("guardToken", GuardToken.mint(91L));
        setClimateToken(GuardToken.mint(92L));

        assertFalse("开着时照片下面有那排按钮（HD / 片段）", alerts.alertMarkup().isEmpty());
        assertFalse("开着时温度警报下面那排也在", alerts.climateMarkup().isEmpty());
        assertFalse("开着时回执下面那两颗也在", alerts.guardReceiptMarkup(true).isEmpty());
        assertFalse("开着时「要不要开哨兵」那一问有两颗", alerts.guardAskMarkup().isEmpty());
        assertNotNull("开着时座椅那一问才发", alerts.seatAsk());

        config.setAlertsButtons(false);

        assertEquals("照片下面不再挂键盘", "", alerts.alertMarkup());
        assertEquals("温度警报也不再挂", "", alerts.climateMarkup());
        assertEquals("回执下面不再挂", "", alerts.guardReceiptMarkup(true));
        assertEquals("没有按钮就不发那一问", "", alerts.guardAskMarkup());
        assertNull("座椅那一问只有按钮，所以整句都不发", alerts.seatAsk());

        // 收尾：这一项存在同一份设置里。
        config.setAlertsButtons(true);
    }

    /** 直接发 {@code ac on} 来的回执没有温度警报的号：自己造一个，这排按钮才有主人。 */    @Test
    public void theSeatRowMintsItsOwnTokenWhenTheReceiptCameFromAChatCommand() throws Exception {
        config.setAlertsTransport(NtfyGate.TELEGRAM);
        setClimateToken("");

        assertNotNull(alerts.seatAsk());
        assertFalse("空号发出去就是五颗不能按的按钮", ((String) field("climateToken")).isEmpty());
    }

    // ================================================================= 座椅按钮按下去

    /** 号码对得上的座椅按钮走命令那条路（这里车没停着，所以回的是「车没停稳」）。 */
    @Test
    public void aFreshSeatButtonGoesThroughTheCommandPath() throws Exception {
        setClimateToken(GuardToken.mint(81L));

        alerts.onAlertButton(AlertButton.data(AlertButton.ACTION_SEAT_HEAT_LEFT, GuardToken.mint(81L)));

        assertTrue("按了就得有回话", awaitSend());
        assertEquals(context.getString(R.string.alert_climate_moving), ShadowNtfySender.sent.get(0));
    }

    /** 陈的座椅按钮：回现成的「那是上一条警报的按钮」那一句，而且一笔不写。 */
    @Test
    public void aStaleSeatButtonAnswersAndWritesNothing() throws Exception {
        setClimateToken("");

        alerts.onAlertButton(AlertButton.data(AlertButton.ACTION_SEAT_VENT_RIGHT, GuardToken.mint(82L)));

        assertTrue(awaitSend());
        assertEquals("只发一句", 1, ShadowNtfySender.texts);
        assertEquals(context.getString(R.string.alert_climate_stale_button), ShadowNtfySender.sent.get(0));
        assertNull("过期按钮不排任何事", pending());
        assertFalse("也不许声称空调开着", believedOn());
    }

    /** 写完座椅那一句：说车受理了什么，几档。 */
    @Test
    public void theSeatReplyNamesWhatTheCarAccepted() throws Exception {
        alerts.onSeatReport(seatReport(ClimateSource.Outcome.DONE, true, false),
                AlertButton.ACTION_SEAT_HEAT_LEFT);

        assertTrue(awaitSend());
        assertEquals(context.getString(R.string.alert_climate_seat_done,
                        context.getString(R.string.alert_climate_seat_btn_driver_heat),
                        config.getAlertsClimateSeatLevel()),
                ShadowNtfySender.sent.get(0));
    }

    /** 车机不受理：就说这一句，不报温度也不报档位。 */
    @Test
    public void aRefusedSeatButtonSaysTheCarRefused() throws Exception {
        alerts.onSeatReport(seatReport(ClimateSource.Outcome.REFUSED, false, false),
                AlertButton.ACTION_SEAT_HEAT_RIGHT);

        assertTrue(awaitSend());
        assertEquals(context.getString(R.string.alert_climate_seat_refused),
                ShadowNtfySender.sent.get(0));
    }

    /** 关座椅：回的是「两个都关了」。 */
    @Test
    public void theSeatOffReplySaysBothSeatsAreOff() throws Exception {
        alerts.onSeatReport(seatReport(ClimateSource.Outcome.DONE, true, true),
                AlertButton.ACTION_SEAT_OFF);

        assertTrue(awaitSend());
        assertEquals(context.getString(R.string.alert_climate_seat_off_done),
                ShadowNtfySender.sent.get(0));
    }

    /**
     * 一颗座椅按钮要写什么：档位从设置来（不是按钮里写死的数），soff 是 0 档、前排两个都写。
     *
     * The level a seat button writes comes from the settings, not from a number baked into the button, and
     * "seats off" is level 0 on both front seats.
     */
    @Test
    public void theSeatPlanComesFromTheSettings() {
        config.setAlertsClimateSeatLevel(3);

        ClimateSource.Seats heat = alerts.seatPlan(AlertButton.ACTION_SEAT_HEAT_RIGHT);
        assertFalse(heat.left);
        assertTrue(heat.right);
        assertTrue(heat.heat);
        assertEquals(3, heat.level);

        ClimateSource.Seats vent = alerts.seatPlan(AlertButton.ACTION_SEAT_VENT_LEFT);
        assertTrue(vent.left);
        assertFalse(vent.right);
        assertFalse("通风不是加热", vent.heat);
        assertEquals(3, vent.level);

        ClimateSource.Seats off = alerts.seatPlan(AlertButton.ACTION_SEAT_OFF);
        assertTrue(off.left);
        assertTrue(off.right);
        assertEquals("0 档就是关", 0, off.level);

        assertNull(alerts.seatPlan(AlertButton.climateAction(22f)));
    }

    // ================================================================= 关掉之后把车放回原样

    /**
     * 一条「开」的命令：九个字段先读完，再写；第一笔写的是命令要的设定值和座椅，不是快照那一份。
     *
     * <p>读在写之前是这一段的全部意义 —— 写完再读，读到的就是自己刚写下去的东西，快照等于没有。
     * 写的是命令那一份：写回（放回原样）写的是 21.5 和 0 档，这里一笔都不许出现。</p>
     *
     * One ON command: the nine fields are read first, then the write; the first write carries the command's
     * setpoint and seat level, never the snapshot's. Reading before writing is the whole point — read after
     * the write and the snapshot is the app's own values. No put-back-shaped write (21.5, level 0) appears.
     */
    @Test
    public void anOnCommandReadsTheNineFieldsBeforeItWritesAndTheWriteIsTheCommands()
            throws Exception {
        FakeCar car = answeringCar();
        parked(car);
        bothSeats();

        onCommand(22f);

        assertTrue("回执要落地", awaitTexts(1));
        assertEquals("写车之前，快照那九笔读要先走完", nineReads(),
                new ArrayList<>(car.calls.subList(0, 9)));
        int firstWrite = firstWrite(car);
        assertTrue("九笔读完才轮到写: " + car.calls, firstWrite >= 9);
        assertEquals("第一笔写的是命令要的 22.0，不是快照里的 21.5",
                floatWrite(TEMP, DRIVER, 22f), car.calls.get(firstWrite));
        assertEquals(floatWrite(TEMP, PASSENGER, 22f), car.calls.get(firstWrite + 1));
        assertTrue("座椅也照命令写（2 档）: " + car.calls,
                car.calls.contains("write " + SEAT_VENT + "@" + DRIVER + "=" + (SEAT_VENT + 2)));
        assertTrue(car.calls.toString(),
                car.calls.contains("write " + SEAT_VENT + "@" + PASSENGER + "=" + (SEAT_VENT + 2)));
        assertFalse("快照那一份不许被写回去: " + car.calls,
                car.calls.toString().contains("=21.5"));
        assertFalse("放回原样写的是 0 档，这里一笔都不许有: " + car.calls,
                car.calls.toString().contains("write " + SEAT_VENT + "@" + DRIVER + "=0"));
    }

    /**
     * 开着「放回原样」的一次自动关：关读回 0、这一趟算做成，照快照把动过的那几格写回去 —— 一笔不多、一笔不少。
     *
     * <p>快照是「开」之前那一刻：两区 21.5、座椅全 0、内循环、后排关着。命令把两区推到 22.0，自动关把座椅写回 0，
     * 所以到放回这一刻，动的只有两区那两格 —— 整张单子列出来比，多的少的都红。</p>
     *
     * An auto-off with the switch on: the off reads back 0 (the pass is DONE) and the put-back writes exactly
     * the fields that drifted. The snapshot is the moment before the ON (21.5 both zones, seats at 0, inside
     * circulation, rear off); the command pushed both zones to 22.0, so at the put-back only those two
     * drifted. The whole call list is compared, so an extra write and a missing one both go red.
     */
    @Test
    public void anAutoOffWithTheSwitchOnPutsBackExactlyTheDriftedFields() throws Exception {
        FakeCar car = answeringCar();
        parked(car);
        bothSeats();
        onCommand(22f);
        assertTrue("回执要落地", awaitTexts(1));

        car.calls.clear();
        alerts.attemptClimateOff(10, 2, android.os.SystemClock.elapsedRealtime());

        List<String> expected = new ArrayList<>(offWrites());
        expected.addAll(nineReads());
        expected.add("read " + TEMP_UNIT + "@" + DRIVER);
        expected.add(floatWrite(TEMP, DRIVER, 21.5f));
        expected.add(floatWrite(TEMP, PASSENGER, 21.5f));
        assertEquals(expected, new ArrayList<>(car.calls));
        assertEquals("这一趟没有要说的（关做成、放回也做成）", 1, ShadowNtfySender.texts);
    }

    /**
     * 「放回原样」关着：读照读，写回一笔都没有。
     *
     * <p>读不写东西，所以每一笔「开」都读（§6.3）：关着就不读的话，开关在十分钟里被打开，回车的就是上一趟的旧快照。
     * 写回只在开着的时候发生 —— 关着的那一趟，单子上除了自动关自己那几笔，什么都不许多。</p>
     *
     * With the switch off the read still happens (reads write nothing, and a switch flipped mid-window must
     * not replay an older snapshot) and nothing is written back: the pass is exactly the off's own calls.
     */
    @Test
    public void withThePutBackSwitchOffTheCaptureStillRunsAndNothingIsWrittenBack()
            throws Exception {
        FakeCar car = answeringCar();
        parked(car);
        bothSeats();
        config.setAlertsClimateRestore(false);

        onCommand(22f);

        assertTrue("回执要落地", awaitTexts(1));
        assertEquals("开关关着，快照照读", nineReads(),
                new ArrayList<>(car.calls.subList(0, 9)));
        assertNotNull("快照真的存下来了", field("climateSnapshot"));

        car.calls.clear();
        alerts.attemptClimateOff(10, 2, android.os.SystemClock.elapsedRealtime());

        assertEquals("这一趟就是自动关自己那几笔", offWrites(), new ArrayList<>(car.calls));
        assertEquals("一句都不多说", 1, ShadowNtfySender.texts);
    }

    /**
     * 写车的时候这一条链被取消了（号变了）：照快照写回那一趟立刻停手，不写车，也不说话。
     *
     * <p>取消是真的会跟写撞上的：车开走了、车主自己关了、又排了一条新的 —— 都在写车那几百毫秒里可能发生。
     * 这里让假车在第一笔写之后把号 +1，正好撞在那一笔上。</p>
     *
     * The chain is cancelled while the off is being written (the number moves on): the put-back stops, writes
     * nothing and says nothing. A cancel really does collide with a write — the car drove away, the owner
     * switched it off, a new chain was armed. The fake bumps the number right after the first write.
     */
    @Test
    public void aDroppedChainWritesNothingBackEvenWhenItWroteTheOff() throws Exception {
        FakeCar car = answeringCar();
        parked(car);
        bothSeats();
        onCommand(22f);
        assertTrue("回执要落地", awaitTexts(1));

        car.calls.clear();
        final int generation = generation();
        car.afterFirstWrite = () -> {
            try {
                set("climateOffGeneration", generation + 1);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };

        attemptClimateOff(10, 2, android.os.SystemClock.elapsedRealtime(), generation);

        assertTrue("关那一笔还是要写的: " + car.calls,
                car.calls.contains("write " + POWER + "@" + FRONT + "=0"));
        assertFalse("号对不上：照快照写回一笔都不许: " + car.calls,
                car.calls.toString().contains("writeFloat"));
        assertFalse(car.calls.toString().contains("writePlain"));
        assertEquals("也不许说话", 1, ShadowNtfySender.texts);
    }

    /**
     * 快照只认得一个字段（前排开关，而它永远不写回）：照快照那一趟照跑，一笔不写，聊天里一句话都没有。
     *
     * <p>结论是 {@code REFUSED} —— 那一趟没弄成任何东西，也就没什么可报告的（§6.1：安静的是这一档）。
     * 「说了什么」和「没说什么」在这里是同一件事：车机睡着了，别为一次什么都没干的放回吵醒车主。</p>
     *
     * The snapshot knows one field only (front power, which is never written back): the put-back pass still
     * runs, writes nothing and says nothing in the chat — its outcome is REFUSED, and a pass that knew
     * nothing else has nothing to report (§6.1).
     */
    @Test
    public void aSnapshotThatKnewNothingWritesNothingAndSaysNothing() throws Exception {
        FakeCar car = new FakeCar();
        car.putInt(POWER, FRONT, 1);
        car.silent = true;                       // 除了自动关读回要的那一个号，什么都不回
        car.answering = POWER + "@" + FRONT;
        parked(car);
        bothSeats();

        onCommand(22f);

        assertTrue("回执要落地", awaitTexts(1));
        ClimateSnapshot.Snapshot snapshot = (ClimateSnapshot.Snapshot) field("climateSnapshot");
        assertNotNull(snapshot);
        assertEquals("只认得前排开关那一个字段", 1, snapshot.knownCount());

        car.calls.clear();
        alerts.attemptClimateOff(10, 2, android.os.SystemClock.elapsedRealtime());

        List<String> expected = new ArrayList<>(offWrites());
        expected.addAll(nineReads());
        expected.add("read " + TEMP_UNIT + "@" + DRIVER);
        assertEquals("照快照那一趟读完就完了，一笔都不写", expected, new ArrayList<>(car.calls));
        assertEquals("一句话都不说", 1, ShadowNtfySender.texts);
    }

    /**
     * 车机拒了照快照写回的一笔：这一趟算「没变」，并且用现成那句「没变」在聊天里说一次 —— 只一次。
     *
     * <p>拒了不停：主驾那一笔被拒，副驾那一笔照写。说出来的是车主需要知道的那一句，不是一串号码。</p>
     *
     * A refused put-back write ends the pass as NO_CHANGE and is said out loud once with the existing
     * no-change sentence — once. The pass itself does not stop: the passenger setpoint is still written.
     */
    @Test
    public void aRefusedPutBackEndsNoChangeAndSaysTheNoChangeSentenceOnce() throws Exception {
        FakeCar car = answeringCar();
        parked(car);
        bothSeats();
        onCommand(22f);
        assertTrue("回执要落地", awaitTexts(1));

        car.calls.clear();
        car.refused.add(TEMP + "@" + DRIVER);
        alerts.attemptClimateOff(10, 2, android.os.SystemClock.elapsedRealtime());

        assertTrue("被拒了也说完", awaitTexts(2));
        assertEquals("回执一条 + 「没变」一条", 2, ShadowNtfySender.texts);
        assertEquals("说的就是现成那句「没变」",
                context.getString(R.string.alert_climate_no_change_plain),
                ShadowNtfySender.sent.get(1));
        assertTrue("被拒不停：副驾那一笔照写: " + car.calls,
                car.calls.contains(floatWrite(TEMP, PASSENGER, 21.5f)));
    }

    /**
     * 车开走了：这一趟的快照跟着没；再停回来、手动重排一条链，假车照样答得出，也一笔都不写回去。
     *
     * <p>上一趟读到的东西不许写到这一趟的车上（§6.3）。没有这一道，上周五读到的那一份会在这个车位上车。</p>
     *
     * Driving away drops this session's snapshot: parking again and re-arming by hand writes nothing back,
     * even though the fake car answers every field. What the previous drive read must never reach this
     * car (§6.3).
     */
    @Test
    public void drivingAwayDropsTheSnapshotSoTheNextParkPutsNothingBack() throws Exception {
        FakeCar car = answeringCar();
        parked(car);
        bothSeats();
        onCommand(22f);
        assertTrue("回执要落地", awaitTexts(1));
        assertNotNull("先得有一份快照", field("climateSnapshot"));

        climateCheck(VehicleState.empty().edit().gear("D").build(),
                TestSnapshots.of(Signal.TEMP_INSIDE, 30f));
        assertNull("开走了：快照要跟着这一趟结束", field("climateSnapshot"));

        int said = ShadowNtfySender.texts;
        car.calls.clear();
        setTelemetryState(VehicleState.empty().edit().gear("P").speedKmh(0f).build());
        alerts.attemptClimateOff(10, 2, android.os.SystemClock.elapsedRealtime());

        assertTrue("自动关自己那几笔照写: " + car.calls,
                car.calls.contains("write " + POWER + "@" + FRONT + "=0"));
        assertFalse("手里没有快照：一笔都不许写回去: " + car.calls,
                car.calls.toString().contains("writeFloat"));
        assertEquals("也没多说什么", said, ShadowNtfySender.texts);
    }

    /**
     * 连开两次：第二次读到的就是第一次写下去的那一份，自动关回放的是第二次读到的那份（§6.4，认了的行为）。
     *
     * <p>第一趟把两区推到 22.0、座椅 2 档；第二趟读到的就是 22.0 和 2 档（快照存它），然后把两区推到 24.0。
     * 自动关之后回放的是 22.0 和 2 档 —— 不是车主最早那份 21.5，也不是空。这就是写下来的那个取舍。</p>
     *
     * Two ONs in a row: the second capture records what the first wrote (22.0 and seat level 2), the second
     * pushes the zones to 24.0, and the auto-off replays the second snapshot — 22.0 and level 2, not the
     * owner's original 21.5. That is the accepted §6.4 behaviour, pinned here.
     */
    @Test
    public void aSecondOnInARowCapturesWhatTheFirstWroteAndThatIsWhatTheAutoOffPutsBack()
            throws Exception {
        FakeCar car = answeringCar();
        parked(car);
        bothSeats();

        onCommand(22f);
        assertTrue("第一条回执要落地", awaitTexts(1));
        onCommand(24f);
        assertTrue("第二条回执要落地", awaitTexts(2));

        ClimateSnapshot.Snapshot snapshot = (ClimateSnapshot.Snapshot) field("climateSnapshot");
        assertNotNull(snapshot);
        assertEquals("第二次读到的是第一次写下去的 22.0", Float.valueOf(22f), snapshot.driverSetpointC);
        assertEquals(Float.valueOf(22f), snapshot.passengerSetpointC);
        assertEquals("座椅那一格也是第一次写下去的 2 档", Integer.valueOf(SEAT_VENT + 2),
                snapshot.seatVentLeft);

        car.calls.clear();
        alerts.attemptClimateOff(10, 2, android.os.SystemClock.elapsedRealtime());

        List<String> expected = new ArrayList<>(offWrites());
        expected.addAll(nineReads());
        expected.add("read " + TEMP_UNIT + "@" + DRIVER);
        expected.add(floatWrite(TEMP, DRIVER, 22f));
        expected.add(floatWrite(TEMP, PASSENGER, 22f));
        expected.add("write " + SEAT_VENT + "@" + DRIVER + "=" + (SEAT_VENT + 2));
        expected.add("write " + SEAT_VENT + "@" + PASSENGER + "=" + (SEAT_VENT + 2));
        assertEquals(expected, new ArrayList<>(car.calls));
        assertFalse("车主最早那份 21.5 已经不在手里了: " + car.calls,
                car.calls.toString().contains("=21.5"));
    }

    // ================================================================= 在家那一条门不碰聊天

    /**
     * 车在家、而且「在家也发通知」关着：车主在聊天里发的 {@code ac} 回执照发。
     *
     * <p>那一条门只管<b>警报</b>，不管车主自己问的话。把门写宽一点，车在家就叫不动空调、问一句
     * 也没人答 —— 那不是静音，那是关机。</p>
     *
     * At home with at-home notifications off, a chat receipt still goes out: the gate covers alerts, never the
     * answer to the owner's own command.
     */
    @Test
    public void theAtHomeGateDoesNotSilenceAChatReceipt() throws Exception {
        atHome();

        onClimateReport(report(ClimateSource.Outcome.DONE, 22f, true, true, false), 22, false, false, 0);

        assertTrue("车主自己问的那一句要有人答", awaitSend());
        assertEquals("回执照发一条", 1, ShadowNtfySender.texts);
    }

    /** 把车摆在「在家」那一格：家和定位都摆好，而且真的读出 HOME（不然这条测试是空过）。 */
    private void atHome() throws Exception {
        config.setAlertsGuardHome(51.500000, -0.120000);
        config.setAlertsNotifyAtHome(false);
        set("guardFixLat", 51.500000);
        set("guardFixLon", -0.120000);
        set("guardFixAtMs", android.os.SystemClock.elapsedRealtime());
        java.lang.reflect.Method m = SentryAlerts.class.getDeclaredMethod("guardWhere");
        m.setAccessible(true);
        assertEquals("车该读成在家", com.kooo.evcam.alarm.guard.GuardHome.Where.HOME,
                m.invoke(alerts));
    }

    /**
     * 常量表摆回原样：沙箱可能跟别的测试类共一个 JVM，{@link #seedCarNames()} 潘进去的东西不能漏到别人那里。
     * Put the name table back: the sandbox may be shared with other test classes, so what seedCarNames put in
     * must not leak out of this class.
     */
    @After
    public void restoreCarNames() throws Exception {
        Map<String, Integer> table = carNamesTable();
        table.clear();
        table.putAll(namesBefore);
    }

    // ================================================================= 小工具

    /**
     * 假车：值按「号@区域」存，每一次读写都记下来 —— 断言比的就是这张单子。
     *
     * <p>{@link #silent} 是「这台适配层对一个号也不答话」（自动关那条读回要的那一个除外），{@link #refused} 是
     * 车机不受理的那一笔；{@link #afterFirstWrite} 让「写车的时候这一条链被取消了」有地方发生。</p>
     *
     * The fake car: values keyed by id@zone, every read and write recorded — the recorded list is what the
     * assertions compare. Silent means this adapter answers nothing (except the one id the off's readback
     * needs), refused is a write the car rejects, and afterFirstWrite is where a cancel can collide.
     */
    private static final class FakeCar implements CarAccess {
        final Map<String, Integer> ints = new HashMap<>();
        final Map<String, Float> floats = new HashMap<>();
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final Set<String> refused = new HashSet<>();
        Runnable afterFirstWrite;
        boolean silent;
        /** 「只答这一个号」的写法（{@link #silent} 的时候）：自动关的读回走的就是它。 */
        String answering = "";

        void putInt(int id, int zone, int value) {
            ints.put(id + "@" + zone, value);
        }

        /** 不带区域那个通道（内循环走的就是它）。 The scope-less channel, which circulation uses. */
        void putInt(int id, int value) {
            ints.put(String.valueOf(id), value);
        }

        void putFloat(int id, int zone, float value) {
            floats.put(id + "@" + zone, value);
        }

        private void wrote() {
            Runnable once = afterFirstWrite;
            if (once != null) {
                afterFirstWrite = null;
                once.run();
            }
        }

        @Override
        public Integer read(int id) {
            calls.add("readPlain " + id);
            return silent ? null : ints.get(String.valueOf(id));
        }

        @Override
        public Integer read(int id, int zone) {
            calls.add("read " + id + "@" + zone);
            if (silent && !(id + "@" + zone).equals(answering)) {
                return null;
            }
            return ints.get(id + "@" + zone);
        }

        @Override
        public boolean write(int id, int value) {
            calls.add("writePlain " + id + "=" + value);
            if (refused.contains(String.valueOf(id))) {
                return false;
            }
            ints.put(String.valueOf(id), value);
            wrote();
            return true;
        }

        @Override
        public boolean write(int id, int zone, int value) {
            calls.add("write " + id + "@" + zone + "=" + value);
            if (refused.contains(id + "@" + zone)) {
                return false;
            }
            ints.put(id + "@" + zone, value);
            wrote();
            return true;
        }

        @Override
        public Float readFloat(int id, int zone) {
            calls.add("readFloat " + id + "@" + zone);
            if (silent && !(id + "@" + zone).equals(answering)) {
                return null;
            }
            return floats.get(id + "@" + zone);
        }

        @Override
        public boolean writeFloat(int id, int zone, float value) {
            calls.add(floatWrite(id, zone, value));
            if (refused.contains(id + "@" + zone)) {
                return false;
            }
            floats.put(id + "@" + zone, value);
            wrote();
            return true;
        }

        @Override
        public String status(int id, int zone) {
            return "active";
        }

        @Override
        public Integer config(int n) {
            return null;
        }

        @Override
        public Float sensor(int type) {
            return null;
        }
    }

    /** 浮点那一笔写进去时的记法（跟 {@code ClimateSource} 的 detail 一样一位小数）。 */
    private static String floatWrite(int id, int zone, float value) {
        return String.format(Locale.US, "writeFloat %d@%d=%.1f", id, zone, value);
    }

    /**
     * 一台答得出九个字段的车：前排关着、两区 21.5、四个座椅位全 0、内循环、后排关着、单位是摄氏。
     *
     * 21.5 跟命令要的 22.0 错开，放回那一笔才看得出来；座椅 0 档跟命令写的 2 档错开，同理。
     * A car that answers all nine fields: 21.5 both zones, seats at 0, inside circulation, rear off, Celsius.
     * 21.5 differs from the commanded 22.0 and the seats differ from level 2, so a put-back shows up.
     */
    private static FakeCar answeringCar() {
        FakeCar car = new FakeCar();
        car.putInt(POWER, FRONT, 0);
        car.putFloat(TEMP, DRIVER, 21.5f);
        car.putFloat(TEMP, PASSENGER, 21.5f);
        car.putInt(SEAT_HEAT, DRIVER, 0);
        car.putInt(SEAT_VENT, DRIVER, 0);
        car.putInt(SEAT_HEAT, PASSENGER, 0);
        car.putInt(SEAT_VENT, PASSENGER, 0);
        car.putInt(CIRC, CIRC + 1);
        car.putInt(POWER, REAR, 0);
        car.putInt(TEMP_UNIT, DRIVER, UNIT_C);
        return car;
    }

    /** 快照那一趟读的九个号，按它读的次序。 The nine fields the snapshot pass reads, in its order. */
    private static List<String> nineReads() {
        return List.of(
                "read " + POWER + "@" + FRONT,
                "readFloat " + TEMP + "@" + DRIVER,
                "readFloat " + TEMP + "@" + PASSENGER,
                "read " + SEAT_HEAT + "@" + DRIVER,
                "read " + SEAT_VENT + "@" + DRIVER,
                "read " + SEAT_HEAT + "@" + PASSENGER,
                "read " + SEAT_VENT + "@" + PASSENGER,
                "readPlain " + CIRC,
                "read " + POWER + "@" + REAR);
    }

    /**
     * 自动关自己那几笔：开关写 0、四个座椅位写 0（设置里是 both），再加一笔读回（读到 0 才算做成）。
     *
     * The off's own calls: power to 0, the four seat cells to 0 (both seats), and the readback that has to
     * read 0 for the pass to count as DONE.
     */
    private static List<String> offWrites() {
        return List.of(
                "write " + POWER + "@" + FRONT + "=0",
                "write " + SEAT_HEAT + "@" + DRIVER + "=0",
                "write " + SEAT_VENT + "@" + DRIVER + "=0",
                "write " + SEAT_HEAT + "@" + PASSENGER + "=0",
                "write " + SEAT_VENT + "@" + PASSENGER + "=0",
                "read " + POWER + "@" + FRONT);
    }

    /** 第一笔写是单子上的第几条（没有写就是 -1）。 Which call is the first write. */
    private static int firstWrite(FakeCar car) {
        for (int i = 0; i < car.calls.size(); i++) {
            if (car.calls.get(i).startsWith("write")) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 真车上的号从 {@code CarNames} 来，而 ecarx 那些接口类在 Robolectric 里没有：把常量表照实灌进它的缓存，
     * 假车答的和胶水问的才是同一批号。
     *
     * The real ids come from CarNames, whose ecarx interface classes do not exist under Robolectric. The
     * name table is seeded so the fake answers on the very ids the glue asks for.
     */
    private static void seedCarNames() throws Exception {
        Map<String, Integer> found = carNamesTable();
        found.put("IHvac.HVAC_FUNC_POWER", POWER);
        found.put("IHvac.HVAC_FUNC_TEMP", TEMP);
        found.put("IHvac.HVAC_FUNC_TEMP_UNIT", TEMP_UNIT);
        found.put("IHvac.HVAC_FUNC_SEAT_HEATING", SEAT_HEAT);
        found.put("IHvac.HVAC_FUNC_SEAT_VENTILATION", SEAT_VENT);
        found.put("IHvac.HVAC_FUNC_CIRCULATION", CIRC);
        found.put("IHvac.TEMPERATURE_UNIT_C", UNIT_C);
        found.put("IHvac.TEMPERATURE_UNIT_F", UNIT_F);
        found.put("VehicleZone.ROW_1", FRONT);
        found.put("VehicleZone.ROW_1_LEFT", DRIVER);
        found.put("VehicleZone.ROW_1_RIGHT", PASSENGER);
        found.put("VehicleZone.ROW_2", REAR);
    }

    /** {@code CarNames} 里「找过的号」那张表（常量名 → 号码）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Integer> carNamesTable() throws Exception {
        java.lang.reflect.Field field =
                com.kooo.evcam.telemetry.CarNames.class.getDeclaredField("FOUND");
        field.setAccessible(true);
        return (Map<String, Integer>) field.get(null);
    }

    /** 车停着、外面 25 度、车里 30 度（比 22 度的目标高出死区：座椅该通风），车辆接口换成这台假车。 */
    private void parked(FakeCar car) throws Exception {
        seedCarNames();
        set("climateAccessForTest", car);
        setTelemetryState(VehicleState.empty().edit().gear("P").speedKmh(0f).build());
        setTelemetryReadings(TestSnapshots.of(Signal.TEMP_INSIDE, 30f, Signal.TEMP_OUTSIDE, 25f));
    }

    /** 前排两个座椅都写（放回那一趟的座椅读数才两边都有）。 Both front seats, so both sides are written. */
    private void bothSeats() {
        config.setAlertsClimateSeats("both");
    }

    /** 车主的一条「开」：走命令那条路（轮询线程叫的就是它）。 An owner ON through the command path. */
    private void onCommand(float targetC) {
        alerts.onClimateCommand(
                new TelegramCommand.Climate(TelegramCommand.ClimateAction.ON, targetC));
    }

    /**
     * 等聊天里凑到 {@code count} 句。回执那条弦要过主线程再交发送线程，所以这里一边等一边把主线程的队列推完。
     *
     * Wait until the chat holds {@code count} lines. The receipt crosses the main thread before the send
     * executor, so the main queue is drained while waiting.
     */
    private static boolean awaitTexts(int count) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            if (ShadowNtfySender.texts >= count) {
                return true;
            }
            Thread.sleep(20);
        }
        return ShadowNtfySender.texts >= count;
    }

    private static void setTelemetryState(VehicleState state) throws Exception {
        java.lang.reflect.Field field = Telemetry.class.getDeclaredField("state");
        field.setAccessible(true);
        field.set(Telemetry.get(), state);
    }

    private static void setTelemetryReadings(com.kooo.evcam.telemetry.Readings readings)
            throws Exception {
        java.lang.reflect.Field field = Telemetry.class.getDeclaredField("readings");
        field.setAccessible(true);
        field.set(Telemetry.get(), readings);
    }

    /** 座椅那一笔的报告（没碰空调）。 */
    private static ClimateSource.Report seatReport(ClimateSource.Outcome outcome, boolean left,
                                                   boolean right) {
        return new ClimateSource.Report(outcome, 0f, false, false, false, left, right, "test");
    }

    private static ClimateSource.Report report(ClimateSource.Outcome outcome, float targetC,
                                               boolean powerOn, boolean powerWrote, boolean seatLeft) {
        return new ClimateSource.Report(outcome, targetC, powerOn, powerWrote, powerWrote,
                seatLeft, false, "test");
    }

    /** 两个前排座椅都受理了的报告（设置里是 {@code both} 时回执就是这么来的）。 */
    private static ClimateSource.Report reportBothSeats(ClimateSource.Outcome outcome, float targetC,
                                                         boolean powerOn, boolean powerWrote) {
        return new ClimateSource.Report(outcome, targetC, powerOn, powerWrote, powerWrote, true, true,
                "test");
    }

    private static boolean awaitSend() throws InterruptedException {
        CountDownLatch latch = ShadowNtfySender.latch;
        return latch.await(5, TimeUnit.SECONDS);
    }

    /** onClimateReport 是私有的：这条测试走的就是它（轮询线程/主线程都会叫到它）。 */
    private void onClimateReport(ClimateSource.Report report, int targetC, boolean heat, boolean vent,
                                 int session) throws Exception {
        java.lang.reflect.Method m = SentryAlerts.class.getDeclaredMethod("onClimateReport",
                ClimateSource.Report.class, float.class, boolean.class, boolean.class, int.class);
        m.setAccessible(true);
        m.invoke(alerts, report, targetC, heat, vent, session);
    }

    private ScheduledFuture<?> pending() throws Exception {
        return (ScheduledFuture<?>) field("climateOffPending");
    }

    private static boolean past(ScheduledFuture<?> future) {
        return future == null || future.isCancelled() || future.isDone();
    }

    private boolean believedOn() throws Exception {
        return (Boolean) field("climateBelievedOn");
    }

    private boolean known() throws Exception {
        return (Boolean) field("climateKnown");
    }

    private void setClimateState(boolean believedOn, boolean known) throws Exception {
        set("climateBelievedOn", believedOn);
        set("climateKnown", known);
    }

    /** 和设置里那一条同样参数的策略：直接问跑着的那一份（{@code newClimatePolicy()}）。 */
    private ClimatePolicy newPolicy() throws Exception {
        java.lang.reflect.Method m = SentryAlerts.class.getDeclaredMethod("newClimatePolicy");
        m.setAccessible(true);
        return (ClimatePolicy) m.invoke(alerts);
    }

    private int generation() throws Exception {
        return (Integer) field("climateOffGeneration");
    }

    /** 带号的那一次尝试：老链就是这么跑起来的。The attempt with a generation: how an old chain wakes up. */
    private void attemptClimateOff(int minutes, int level, long firstAt, int generation)
            throws Exception {
        java.lang.reflect.Method m = SentryAlerts.class.getDeclaredMethod("attemptClimateOff",
                int.class, int.class, long.class, int.class);
        m.setAccessible(true);
        m.invoke(alerts, minutes, level, firstAt, generation);
    }

    /** 哨兵那条路在读数来的时候叫的就是它（开走了就靠这一下）。 */
    private void climateCheck(VehicleState state, com.kooo.evcam.telemetry.Readings readings)
            throws Exception {
        java.lang.reflect.Method m = SentryAlerts.class.getDeclaredMethod("climateCheck",
                VehicleState.class, com.kooo.evcam.telemetry.Readings.class);
        m.setAccessible(true);
        m.invoke(alerts, state, readings);
    }

    private void setClock(long[] now) throws Exception {
        set("climateOffClock", (ClimateOffGuard.Clock) () -> now[0]);
    }

    private void setRetryMs(long ms) throws Exception {
        set("climateOffRetryMs", ms);
    }

    private void setClimateToken(String token) throws Exception {
        set("climateToken", token);
    }

    private Object field(String name) throws Exception {
        java.lang.reflect.Field f = SentryAlerts.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(alerts);
    }

    private void set(String name, Object value) throws Exception {
        java.lang.reflect.Field f = SentryAlerts.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(alerts, value);
    }

    /** 单例活在整个沙箱里：清掉它，下一次 {@code get()} 才用这一次的 Application。 */
    private static void forgetSingleton() throws Exception {
        java.lang.reflect.Field field = SentryAlerts.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
    }
}

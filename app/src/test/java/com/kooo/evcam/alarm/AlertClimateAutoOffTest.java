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
import com.kooo.evcam.alarm.climate.ClimateSource;
import com.kooo.evcam.alarm.guard.GuardToken;
import com.kooo.evcam.alarm.ntfy.NtfyGate;
import com.kooo.evcam.alarm.ntfy.NtfyRisk;
import com.kooo.evcam.alarm.policy.ClimatePolicy;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

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
 * The receipt promises a clock time for the auto-off. Ten minutes after locking, the head unit is asleep
 * and both the snapshot and the gear are null; before this pass that was read as "the car is moving" and
 * skipped, so the AC ran all night and the promised time was a lie. None of it is visible from the car in
 * under ten minutes, so the clock is dialled and the state is made unreadable here.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, shadows = {ShadowNtfySender.class})
public class AlertClimateAutoOffTest {

    private static final String TOPIC =
            "zeekrsc_kymoy9m40cuQYXmHabcdefghijklmnopqrstu";

    private Context context;
    private AppConfig config;
    private SentryAlerts alerts;

    @Before
    public void setUp() throws Exception {
        forgetSingleton();
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
        // 读数得一直在来（每两个之间不超 staleMs），否则计时起点作废 —— 这也是车里真实的样子。
        long t = 1_000L;
        ClimatePolicy suppressed = freshPolicy();
        suppressed.decide(33f, 20f, 60, true, true, false, false, false, t);
        assertEquals("空调开着时热警报被压住（这一条是反证）", ClimatePolicy.Kind.NONE,
                suppressed.decide(33f, 20f, 60, true, true, false, false, false, t + 600_000L).kind);
        ClimatePolicy fires = freshPolicy();
        fires.decide(33f, 20f, 60, true, believedOn(), false, false, false, t);
        fires.decide(33f, 20f, 60, true, believedOn(), false, false, false, t + 300_000L);
        assertEquals("关不掉时同一条就要报", ClimatePolicy.Kind.HOT,
                fires.decide(33f, 20f, 60, true, believedOn(), false, false, false, t + 600_000L).kind);
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
                receipt.contains(context.getString(R.string.alert_climate_seat_heat)));
        assertTrue("温度还要写: " + receipt, receipt.contains("22.0"));
    }

    /** 座椅真的写了：回执里就有它（否则上一条是空过）。 */
    @Test
    public void anAcceptedSeatWriteIsNamedInTheReceipt() throws Exception {
        onClimateReport(report(ClimateSource.Outcome.DONE, 22f, true, true, true), 22, true, false, 0);

        assertTrue(awaitSend());
        assertTrue("座椅写了就该写进回执: " + ShadowNtfySender.sent.get(0),
                ShadowNtfySender.sent.get(0)
                        .contains(context.getString(R.string.alert_climate_seat_heat)));
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

    // ================================================================= 小工具

    private static ClimateSource.Report report(ClimateSource.Outcome outcome, float targetC,
                                               boolean powerOn, boolean powerWrote, boolean seatWrote) {
        return new ClimateSource.Report(outcome, targetC, powerOn, powerWrote, seatWrote, "test");
    }

    private static boolean awaitSend() throws InterruptedException {
        CountDownLatch latch = ShadowNtfySender.latch;
        return latch.await(5, TimeUnit.SECONDS);
    }

    /** onClimateReport 是私有的：这条测试走的就是它（轮询线程/主线程都会叫到它）。 */
    private void onClimateReport(ClimateSource.Report report, int targetC, boolean heat, boolean vent,
                                 int session) throws Exception {
        java.lang.reflect.Method m = SentryAlerts.class.getDeclaredMethod("onClimateReport",
                ClimateSource.Report.class, int.class, boolean.class, boolean.class, int.class);
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

    /** 和设置里那一条同样参数的策略（10 分钟 hold、30 度热、15 分钟重复）。 */
    private static ClimatePolicy freshPolicy() {
        return new ClimatePolicy(30f, 5f, 40f, 35f, 3f, 20, 10 * 60_000L, 15 * 60_000L,
                10 * 60_000L, 3);
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

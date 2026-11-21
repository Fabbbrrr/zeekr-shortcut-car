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
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.TestSnapshots;
import com.kooo.evcam.telemetry.VehicleState;

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

    /** 直接发 {@code ac on} 来的回执没有温度警报的号：自己造一个，这排按钮才有主人。 */
    @Test
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

    // ================================================================= 小工具

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

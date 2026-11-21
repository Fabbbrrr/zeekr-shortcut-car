/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.alarm.guard.GuardHome;
import com.kooo.evcam.alarm.ntfy.NtfyGate;
import com.kooo.evcam.alarm.ntfy.NtfyRisk;
import com.kooo.evcam.alarm.policy.ClimatePolicy;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.telemetry.Readings;
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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 车在家的时候发不发消息：一条判断、两条出口、五条规矩。
 *
 * <h3>这一条要钉住的五件事</h3>
 *
 * <ol>
 *   <li>在家、开关关着：哨兵那条不发，热 / 冷也不发，两边各留一行黑匣子原因；</li>
 *   <li>在家、开关开着：两边照发；</li>
 *   <li>在外面：开关怎么设都发；</li>
 *   <li>不知道在哪儿：发（判断错成「在家」会把真警报吞掉）；</li>
 *   <li>危险那一档不看这一项：座椅上有人 35 °C / 40 °C，在家也发。</li>
 * </ol>
 *
 * <h3>为什么走的是真的那两层</h3>
 *
 * <p>温度那两条走 {@code SentryAlerts.climateCheck}（和车上同一个入口），哨兵那条走
 * {@code onReadingsChanged}（双闪亮起来那一下）。两条最后都落到 {@link ShadowNtfySender}，所以
 * 「发了没有」是数出来的，不是猜的。位置用的是真 {@link GuardHome}（一个家、200 米、定位超过十分钟
 * 算不知道），测试只把「车此刻的位置」摆成想要的那一种。</p>
 *
 * <h3>十分钟那个数怎么办</h3>
 *
 * <p>{@code climateCheck} 里的时钟是 {@code SystemClock.elapsedRealtime()}，热 / 冷的十分钟等待
 * 拨不动；所以这里换掉的是跑着的那一份 {@link ClimatePolicy}（{@code holdMs = 0}），
 * 而阀值、回差、安全那两档照抄产品里的数。换的不是产品代码，是这一趟停车的计时。</p>
 *
 * <p>Five rules, one gate: at home with the switch off nothing is sent by either path and each writes one
 * black-box line; at home with it on both send; away both send whatever the switch says; an unknown
 * position sends; the danger tier ignores the switch. Both paths run the real code — climateCheck for the
 * cabin, onReadingsChanged for the hazard-light trigger — and only the position and the ten-minute hold are
 * arranged by the test.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, shadows = {ShadowNtfySender.class})
public class NotifyAtHomeTest {

    private static final String TOPIC = "zeekrsc_kymoy9m40cuQYXmHabcdefghijklmnopqrstu";

    /** 家：一个编出来的点。Home: an invented point. */
    private static final double HOME_LAT = 51.500000;
    private static final double HOME_LON = -0.120000;
    /** 在外面：离上面那个点两公里出头。 Away: a couple of kilometres from it. */
    private static final double AWAY_LAT = 51.520000;
    private static final double AWAY_LON = -0.120000;

    /** 黑匣子里那句原因：开关关着、车在家。 The reason line for "at home, muted". */
    private static final String MUTED = "not sent (at home, notify at home off)";

    private Context context;
    private AppConfig config;
    private SentryAlerts alerts;

    @Before
    public void setUp() throws Exception {
        forgetSingleton();
        context = RuntimeEnvironment.getApplication();
        ShadowNtfySender.reset();
        // 黑匣子落到文件里，断言才读得到那行原因（没接上时它只攒在内存里，报告是空的）。
        // Attached, so the reason line reaches a file the test can read back.
        attachBlackBox();
        config = new AppConfig(context);
        config.setAlertsEnabled(true);
        config.setAlertsTransport(NtfyGate.NTFY);
        config.setAlertsNtfyTopic(TOPIC);
        config.setAlertsNtfyRiskAck(NtfyRisk.ackFor(TOPIC));
        config.setAlertsNtfyPaid(true);
        config.setAlertsTelegramToken("");
        config.setAlertsTelegramChatId("");
        config.setAlertsClimateEnabled(true);
        config.setAlertsClimateSafety(true);
        config.setAlertsClimateMinutes(10);
        config.setAlertsGuardHome(HOME_LAT, HOME_LON);
        config.setAlertsNotifyAtHome(false);
        // 只发文字：这条路不碰相机（哨兵那条也是一样）。
        // Text only, so the sentry path never reaches for a camera.
        config.setAlertsPictureEnabled(false);
        alerts = SentryAlerts.get(context);
        // 这一趟停车不等十分钟：heat / cold 的等待拨成 0，其余照抄产品里的数。
        // No ten-minute wait in this test: the hold is dialled to zero, everything else is the shipped value.
        setPolicy(newClimatePolicy(0L));
    }

    /**
     * 收尾：把借用过的那两个静态的东西放回去。
     *
     * <p>沙箱是按 {@code @Config} 复用的，同一个 {@code Telemetry} 单例和同一个 {@code BlackBox.appContext}
     * 会被后面的测试类看到 —— 留下一个「停着、布防、在家」的车或者一个已经死掉的 Application，
     * 就是在别的测试里动手脚。</p>
     *
     * Put back the two statics this test borrows: the sandbox is reused, so a parked armed car or a dead
     * Application left behind is interference in somebody else's test.
     */
    @After
    public void tearDown() throws Exception {
        setTelemetryState(VehicleState.empty());
        java.lang.reflect.Field field = BlackBox.class.getDeclaredField("appContext");
        field.setAccessible(true);
        field.set(null, null);
    }

    // ================================================================= 1. 在家、开关关着

    /**
     * 在家、开关关着：温度那条热警报一个字都不发，黑匣子里留下原因。
     *
     * <p>反过来说：把 {@code mayNotify} 那一句从 {@code climateCheck} 里删掉，这条就是红的。</p>
     *
     * At home with the switch off the hot alert does not leave the car, and the black box says why.
     */
    @Test
    public void atHomeWithTheSwitchOffAHotCabinSendsNothingAndSaysWhy() throws Exception {
        at(GuardHome.Where.HOME);

        climateCheck(parkedState(), hotCabin());

        assertEquals("在家、开关关着：一条都不发", 0, sentTexts());
        assertTrue("黑匣子里要能看出是「在家不发」: " + blackBox(),
                blackBox().contains("climate HOT " + MUTED));
    }

    /**
     * 在家、开关关着：双闪亮起来那一下也不发，原因同样进黑匣子。
     *
     * <p>这一条走的是 {@code onReadingsChanged} 里那条触发路（双闪从灭到亮），不是温度那一层。</p>
     *
     * At home with the switch off the hazard trigger sends nothing either, and the reason is recorded.
     */
    @Test
    public void atHomeWithTheSwitchOffAHazardTriggerSendsNothingAndSaysWhy() throws Exception {
        at(GuardHome.Where.HOME);

        hazardRises();

        assertEquals("在家、开关关着：双闪亮了也不发", 0, sentTexts());
        assertTrue("黑匣子里要能看出是「在家不发」: " + blackBox(),
                blackBox().contains(MUTED));
    }

    // ================================================================= 2. 在家、开关开着

    /** 在家、开关开着：热警报照发。 With the switch on, the hot alert goes out at home. */
    @Test
    public void atHomeWithTheSwitchOnAHotCabinSends() throws Exception {
        config.setAlertsNotifyAtHome(true);
        at(GuardHome.Where.HOME);

        climateCheck(parkedState(), hotCabin());

        assertTrue("开着就得发", awaitSend());
        assertEquals("就发一条", 1, sentTexts());
    }

    /** 在家、开关开着：双闪那一条也照发。 */
    @Test
    public void atHomeWithTheSwitchOnAHazardTriggerSends() throws Exception {
        config.setAlertsNotifyAtHome(true);
        at(GuardHome.Where.HOME);

        hazardRises();

        assertTrue("开着就得发", awaitSend());
        assertEquals("就发一条", 1, sentTexts());
    }

    // ================================================================= 3. 在外面

    /** 在外面：开关关着也发（这一项只管「在家」）。 Away: it sends with the switch off as well. */
    @Test
    public void awayBothPathsSendWithTheSwitchOff() throws Exception {
        at(GuardHome.Where.AWAY);

        climateCheck(parkedState(), hotCabin());
        assertTrue("在外面：温度那条要发", awaitSend());
        assertEquals("就发一条", 1, sentTexts());

        ShadowNtfySender.reset();
        hazardRises();
        assertTrue("在外面：哨兵那条也要发", awaitSend());
        assertEquals("就发一条", 1, sentTexts());
    }

    /** 在外面、开关开着：一样发（这一项开着不该改变在外面的行为）。 */
    @Test
    public void awayBothPathsSendWithTheSwitchOn() throws Exception {
        config.setAlertsNotifyAtHome(true);
        at(GuardHome.Where.AWAY);

        climateCheck(parkedState(), hotCabin());
        assertTrue("在外面：温度那条要发", awaitSend());

        ShadowNtfySender.reset();
        hazardRises();
        assertTrue("在外面：哨兵那条也要发", awaitSend());
    }

    // ================================================================= 4. 位置不知道

    /**
     * 不知道在哪儿：发。
     *
     * <p>没定位、没设家、定位太旧、坐标不像话 —— 全算不知道。判断错成「在家」会吞掉真警报；
     * 错成「在外面」只是多一条消息，所以只吞「确定在家」。</p>
     *
     * Unknown position sends: no fix, no home, a stale fix or a nonsense coordinate all count as unknown.
     */
    @Test
    public void anUnknownPositionSendsBothPaths() throws Exception {
        at(GuardHome.Where.UNKNOWN);

        climateCheck(parkedState(), hotCabin());
        assertTrue("位置不知道：温度那条要发", awaitSend());
        assertEquals("就发一条", 1, sentTexts());

        ShadowNtfySender.reset();
        hazardRises();
        assertTrue("位置不知道：哨兵那条也要发", awaitSend());
        assertEquals("就发一条", 1, sentTexts());
    }

    /** 没设家：没有那个点就没有「在家」，一律发。 No home point at all: nothing can be "at home". */
    @Test
    public void withoutAHomePointNothingIsMuted() throws Exception {
        config.clearAlertsGuardHome();
        // 定位是新的，但家没设过：没有那个点就没有「在家」。
        // A fresh fix, but no home point: nothing can be "at home".
        put("guardFixLat", HOME_LAT);
        put("guardFixLon", HOME_LON);
        put("guardFixAtMs", android.os.SystemClock.elapsedRealtime());
        assertEquals("没设家就是不知道在哪儿", GuardHome.Where.UNKNOWN, whereNow());

        climateCheck(parkedState(), hotCabin());

        assertTrue("没设家：照发", awaitSend());
    }

    /** 定位太旧（超过十分钟没更新）：算不知道，发。 A fix older than ten minutes is unknown, so it sends. */
    @Test
    public void aStaleFixCountsAsUnknownAndSends() throws Exception {
        // 家的坐标就是此刻的坐标，但这一下定位已经放了十一分钟 —— 车在地库里就是这样。
        // The fix is the home point, but it is eleven minutes old: exactly what a car in a basement looks like.
        put("guardFixLat", HOME_LAT);
        put("guardFixLon", HOME_LON);
        put("guardFixAtMs", android.os.SystemClock.elapsedRealtime()
                - GuardHome.FIX_MAX_AGE_MS - 60_000L);
        assertEquals("十一分钟前的定位算不知道", GuardHome.Where.UNKNOWN, whereNow());

        climateCheck(parkedState(), hotCabin());

        assertTrue("位置太旧：发", awaitSend());
    }

    /** 差一点点：半径是「忘了开哨兵」那一个（200 米），这里验它指的是同一个数。 */
    @Test
    public void theRadiusIsTheGuardsRadius() throws Exception {
        GuardHome.Point home = GuardHome.parse(GuardHome.format(HOME_LAT, HOME_LON));
        assertNotNull(home);
        // 往北 150 米：在家。往北 3 公里：在外面。纬度 1 度约 111.32 公里。
        // 150 m north reads as home, 3 km north reads as away; one degree of latitude is about 111.32 km.
        double near = HOME_LAT + 150.0 / 111_320.0;
        double far = HOME_LAT + 3_000.0 / 111_320.0;
        assertEquals("150 米算在家", GuardHome.Where.HOME,
                GuardHome.classify(home, near, HOME_LON, 0L));
        assertEquals("3 公里算在外面", GuardHome.Where.AWAY,
                GuardHome.classify(home, far, HOME_LON, 0L));
        assertEquals("半径就是同一个数", 200.0, GuardHome.RADIUS_M, 0.0);
    }

    // ================================================================= 5. 危险那一档

    /**
     * 危险那一档在家也发：开关关着、车在家、车厢 42 °C —— 照发。
     *
     * <p>车里可能坐着人，在家里也一样。这一条不走 {@code mayNotify}。</p>
     *
     * The danger tier fires at home with the switch off: somebody may be sitting in there, at home too.
     */
    @Test
    public void theSafetyTierFiresAtHomeWithTheSwitchOff() throws Exception {
        at(GuardHome.Where.HOME);

        climateCheck(parkedState(), TestSnapshots.of(Signal.TEMP_INSIDE, 42f));

        assertTrue("危险那一档不看这一项", awaitSend());
        assertEquals("就发一条", 1, sentTexts());
        assertTrue("发的是危险那一条: " + ShadowNtfySender.sent,
                ShadowNtfySender.sent.get(0).contains("42"));
        assertFalse("黑匣子里不该有「在家不发」: " + blackBox(), blackBox().contains("climate SAFETY " + MUTED));
    }

    // ================================================================= 那一条判断本身

    /**
     * 判断表：{@code mayNotify} 只看「在家」和「开关」，别的一律放行。
     *
     * <p>这张表就是门本身：任何一格被改歪，上面那些端到端的测试里至少有一条会跟着红。</p>
     *
     * The decision table on its own: only HOME with the switch off mutes; everything else sends.
     */
    @Test
    public void theDecisionTableIsOnlyHomeWithTheSwitchOff() throws Exception {
        for (boolean notifyAtHome : new boolean[] {false, true}) {
            config.setAlertsNotifyAtHome(notifyAtHome);
            for (GuardHome.Where where : GuardHome.Where.values()) {
                at(where);
                boolean muted = where == GuardHome.Where.HOME && !notifyAtHome;
                assertEquals("notifyAtHome=" + notifyAtHome + ", where=" + where,
                        !muted, alerts.mayNotify("table"));
            }
        }
    }

    /**
     * 两条发消息的路都要问这一句。
     *
     * <p>温度那条能在 Robolectric 里开到 {@code climateCheck}，哨兵那条开不动（它一进门就去读
     * {@code Telemetry.latest()}，而且还要布防、要挡位、要那一下边沿）—— 上面那几条已经在
     * {@code onReadingsChanged} 上真跑过了，这一条是第二道：把那一句从两条路上删掉，这里就红。</p>
     *
     * Both sending paths ask the gate. The climate one is driven end to end above; the sentry one is driven
     * through onReadingsChanged too, and this is the second net: removing a call site turns this red.
     */
    @Test
    public void bothSendingPathsAskTheGate() throws IOException {
        File source = new File(moduleRoot(),
                "src/main/java/com/kooo/evcam/alarm/SentryAlerts.java");
        assumeTrue("读不到 SentryAlerts.java，跳过", source.isFile());
        String text = new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);

        // 一处定义 + 三处调用：双闪那一组、画面移动、热 / 冷那两条。
        // One definition and three call sites: the trigger group, the movement path, and the comfort tiers.
        Matcher matcher = Pattern.compile("mayNotify\\(").matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        assertTrue("mayNotify() 只该在 SentryAlerts 里出现：一处定义、三处调用，现在是 " + count,
                count >= 4);
    }


    // ================================================================= 小工具：位置

    /** 把车子摆在 {@code where} 说的那一格。 Put the car where {@code where} says it is. */
    private void at(GuardHome.Where where) throws Exception {
        switch (where) {
            case HOME:
                put("guardFixLat", HOME_LAT);
                put("guardFixLon", HOME_LON);
                put("guardFixAtMs", android.os.SystemClock.elapsedRealtime());
                break;
            case AWAY:
                put("guardFixLat", AWAY_LAT);
                put("guardFixLon", AWAY_LON);
                put("guardFixAtMs", android.os.SystemClock.elapsedRealtime());
                break;
            default:
                // 不知道：从来没记过定位（这是「车在地库里、一帧都没有」那一种）。
                put("guardFixLat", null);
                put("guardFixLon", null);
                put("guardFixAtMs", 0L);
                break;
        }
        assertEquals("摆好的位置要读出这个结论", where, whereNow());
    }

    /** 车觉得它在哪儿（真 {@link GuardHome} 算的）。 Where the car thinks it is, from the real guard. */
    private GuardHome.Where whereNow() throws Exception {
        java.lang.reflect.Method m = SentryAlerts.class.getDeclaredMethod("guardWhere");
        m.setAccessible(true);
        return (GuardHome.Where) m.invoke(alerts);
    }

    // ================================================================= 小工具：两条路

    /** 停着的车（P 挡）。 A parked car. */
    private static VehicleState parkedState() {
        return VehicleState.empty().edit().gear("P").speedKmh(0f).build();
    }

    /** 车厢比「太热」那一档高五度。 The cabin, five degrees over the hot threshold. */
    private Readings hotCabin() {
        return TestSnapshots.of(Signal.TEMP_INSIDE, (float) config.getAlertsClimateHotC() + 5f);
    }

    /** 温度那条路：和车上一样，读数来的时候判一次。 The climate path, called the way the car calls it. */
    private void climateCheck(VehicleState state, Readings readings) throws Exception {
        java.lang.reflect.Method m = SentryAlerts.class.getDeclaredMethod("climateCheck",
                VehicleState.class, Readings.class);
        m.setAccessible(true);
        m.invoke(alerts, state, readings);
    }

    /**
     * 双闪那一下：车上给的真是一帧「转向指示 = 3」，走的是 {@code onReadingsChanged}。
     *
     * <p>这一段要先把「正在看着」、上一帧的双闪（灭）、哨兵那些状态摆好 —— 那些是车机给了信号才会有
     * 的东西，测试里只能自己摆。</p>
     *
     * The hazard moment: one real frame with the indicator at 3, through onReadingsChanged. Both the
     * "watching" flag and the previous frame are arranged here, because only a car on the road produces them.
     */
    private void hazardRises() throws Exception {
        put("watching", true);
        put("lastHazard", Boolean.FALSE);
        put("lastFlash", Boolean.FALSE);
        put("lastLamps", Boolean.FALSE);
        put("lastScreenOn", Boolean.FALSE);
        // 只看双闪那一项（默认那三项一起看，而车门、车灯那两帧在这一帧里都是空的）。
        // Only the hazard trigger, so this one frame carries everything the decision looks at.
        put("triggers", com.kooo.evcam.alarm.policy.TriggerSet.parse("hazard"));
        com.kooo.evcam.alarm.policy.AlertPolicy policy = policy();
        policy.setEnabled(true);
        policy.setConfigured(true);
        // 车：停着、哨兵布防、就在测试摆好的那个位置上。
        // The car: parked, sentry armed, at the position the test arranged.
        setTelemetryState(VehicleState.empty().edit().gear("P").speedKmh(0f).sentry(2)
                .position(currentLat(), currentLon()).build());

        alerts.onReadingsChanged(TestSnapshots.of(Signal.INDICATOR, 3));
    }

    private double currentLat() throws Exception {
        Object value = get("guardFixLat");
        return value == null ? 0d : (Double) value;
    }

    private double currentLon() throws Exception {
        Object value = get("guardFixLon");
        return value == null ? 0d : (Double) value;
    }

    private void setTelemetryState(VehicleState state) throws Exception {
        java.lang.reflect.Field field = Telemetry.class.getDeclaredField("state");
        field.setAccessible(true);
        field.set(Telemetry.get(), state);
    }

    // ================================================================= 小工具：设置与字段

    /** 跑着的那一份策略，只把「连续多久才算热」拨成 0（其余照抄产品里的数）。 */
    private ClimatePolicy newClimatePolicy(long holdMs) {
        return new ClimatePolicy(config.getAlertsClimateHotC(), config.getAlertsClimateColdC(),
                40f, 35f, 3f, config.getAlertsClimateSocFloor(), holdMs,
                15 * 60_000L, 10 * 60_000L, 3);
    }

    private void setPolicy(ClimatePolicy policy) throws Exception {
        put("climatePolicy", policy);
    }

    private com.kooo.evcam.alarm.policy.AlertPolicy policy() throws Exception {
        return (com.kooo.evcam.alarm.policy.AlertPolicy) get("policy");
    }

    private int sentTexts() {
        return ShadowNtfySender.texts;
    }

    private static boolean awaitSend() throws InterruptedException {
        return ShadowNtfySender.latch.await(5, TimeUnit.SECONDS);
    }

    /** 黑匣子里目前写下来的那些行。 The black box, as far as it has been written. */
    private String blackBox() {
        return BlackBox.export(context, 200_000);
    }

    private Object get(String name) throws Exception {
        java.lang.reflect.Field f = SentryAlerts.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(alerts);
    }

    private void put(String name, Object value) throws Exception {
        java.lang.reflect.Field f = SentryAlerts.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(alerts, value);
    }

    /**
     * 把黑匣子接到这一次的 Application 上。
     *
     * <p>{@code BlackBox.appContext} 是静态的，AndRoid 的每个测试方法换一个 Application ——
     * 不清掉它，这一条写进的是上一个方法那个目录，而 {@code export(context, …)} 读的是这一个目录，
     * 于是「什么都没记」看起来像「没有那句话」。</p>
     *
     * Attach the black box to this test's Application: the context is static and every test method gets a
     * fresh Application, so a stale one would write into the previous test's directory and the export below
     * would read nothing.
     */
    private void attachBlackBox() throws Exception {
        java.lang.reflect.Field field = BlackBox.class.getDeclaredField("appContext");
        field.setAccessible(true);
        field.set(null, null);
        BlackBox.attach(context, "NotifyAtHomeTest");
    }

    /** 单例活在整个沙箱里：清掉它，下一次 {@code get()} 才用这一次的 Application。 */
    private static void forgetSingleton() throws Exception {
        java.lang.reflect.Field field = SentryAlerts.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
    }

    /** 模块根目录（{@code app/}）：源码那条断言要读文件。 */
    private static File moduleRoot() {
        File dir = new File("").getAbsoluteFile();
        for (int i = 0; i < 4 && dir != null; i++) {
            File candidate = new File(dir, "app/src/main/java");
            if (candidate.isDirectory()) {
                return new File(dir, "app");
            }
            candidate = new File(dir, "src/main/java");
            if (candidate.isDirectory()) {
                return dir;
            }
            dir = dir.getParentFile();
        }
        return new File("app");
    }
}

/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to this code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SeekBarPreference;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.alarm.SentryAlerts;
import com.kooo.evcam.alarm.ntfy.NtfyGate;
import com.kooo.evcam.screens.ShotActivity;
import com.kooo.evcam.screens.Shots;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * 车厢温度那几行改完之后，跑着的那一套知道不知道。
 *
 * <h3>为什么非要测这一条</h3>
 *
 * <p>{@link SentryAlerts#refresh()} 是唯一重建判断阀值的地方，也是唯一会为了「只盯温度」而开始读车信号的地方。
 * 设置里改完不叫它，看起来就是「设置没存上」：重启才生效，而且只填了 ntfy 的车根本不会开始读温度。
 * 4.15.0 之前只有总开关那一行叫了它，车厢温度那一页的开关和四根拖动条都没叫。</p>
 *
 * <p>这里叫的是真的监听器（{@code callChangeListener}），看的是真的副作用（{@code climatePolicy} 被换成新的一份）——
 * 所以把那一行 {@code refresh()} 删掉，这些测试就红。</p>
 *
 * <p>Setting a climate row has to tell the running alerts: refresh() is the only place the policy is
 * rebuilt and the only place a climate-only watcher starts. Without it the change waits for a restart and
 * an ntfy-only car never starts reading the temperature. Only the master switch used to call it.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class ClimateSettingsWiringTest {

    private AppConfig config;
    private SettingsShellFragment shell;

    @Before
    public void setUp() throws Exception {
        // 单例活在整个沙箱里，每个方法换一个 Application：不清掉它，看的还是上一个方法那一份。
        forgetSingleton();
        config = new AppConfig(RuntimeEnvironment.getApplication());
        // 出口没配好：refresh() 建完策略就返回，不去连车辆接口（这条测试只看「有没有叫」）。
        config.setAlertsEnabled(true);
        config.setAlertsTransport(NtfyGate.TELEGRAM);
        config.setAlertsTelegramToken("");
        config.setAlertsTelegramChatId("");
        config.setAlertsClimateEnabled(true);
        config.setAlertsClimateHotC(30);

        ShotActivity activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(activity);
        shell = new SettingsShellFragment();
        activity.getSupportFragmentManager().beginTransaction()
                .replace(activity.container, shell).commitNow();
        Shots.settle();
        shell.showSection("screen_alerts");
        Shots.settle();
    }

    // ================================================================= 开关

    /**
     * 关掉这一组的开关：存进 AppConfig，并且当场告诉跑着的那一套。
     *
     * <p>「告诉」的证据是 {@code climatePolicy} 被换成了新的一份 —— {@code refresh()} 一进门就重建它，
     * 而它一开始是 null。</p>
     *
     * Switching the group off stores the value and tells the running alerts: the proof is a freshly built
     * policy, which refresh() does first thing and which starts out null.
     */
    @Test
    public void theClimateSwitchTellsTheRunningAlerts() throws Exception {
        Preference row = climatePage().findPreference("pref_alerts_climate");
        assertNotNull("少了车厢温度那个开关", row);
        assertNull("开始时没有策略（没人叫过 refresh）", policy());

        assertEquals(Boolean.TRUE, row.callChangeListener(false));

        assertFalse("开关的值存进了设置", config.isAlertsClimateEnabled());
        assertNotNull("开关改完必须叫 refresh()，否则改到的东西不算数", policy());
    }

    /** 一根拖动条：一样要叫 refresh()（阀值就在这一根上）。 */
    @Test
    public void aClimateSliderTellsTheRunningAlerts() throws Exception {
        SeekBarPreference hot = climatePage().findPreference("pref_alerts_climate_hot");
        assertNotNull("少了太热那根拖动条", hot);
        assertNull("开始时没有策略", policy());

        assertEquals(Boolean.TRUE, hot.callChangeListener(35));

        assertEquals("拖完就存下了", 35, config.getAlertsClimateHotC());
        assertNotNull("拖动条改完也要叫 refresh()", policy());
    }

    /**
     * 目标温度那根是半度一格的：拖到第 45 格存的是 22.5，行尾写一位小数，而且一样要叫 refresh()。
     *
     * <p>这一根和别的不一样（格子是度数的两倍），所以单独钉一条：把它接回整度、或者把 refresh() 删了，
     * 这里都红。</p>
     *
     * The target slider is half a degree per tick: tick 45 stores 22.5, the summary carries one decimal, and
     * it tells the running alerts just like the others. It is the one slider whose tick count is twice the
     * Celsius value, so it gets its own check.
     */
    @Test
    public void theTargetSliderStoresHalfDegreesAndTellsTheRunningAlerts() throws Exception {
        SeekBarPreference target = climatePage().findPreference("pref_alerts_climate_target");
        assertNotNull("少了目标温度那根拖动条", target);
        assertNull("开始时没有策略", policy());
        assertEquals("一格半度：最小值是 32 格", 32, target.getMin());
        assertEquals("最大值是 60 格", 60, target.getMax());

        assertEquals(Boolean.TRUE, target.callChangeListener(45));

        assertEquals("第 45 格就是 22.5 °C", 22.5f, config.getAlertsClimateTargetC(), 0f);
        assertEquals("行尾写一位小数", "22.5 °C", String.valueOf(target.getSummary()));
        assertNotNull("目标温度拖完也要叫 refresh()", policy());
    }

    /**
     * 只填了 ntfy 的车：这一组开着就该真的开始读车信号，不只是换一份策略。
     *
     * <p>{@code watching} 是「登记了车辆信号」那一个字段：换成 Telegram 没填好的出口时它是 false，
     * 只填了 ntfy 话题时就该是 true —— 否则「只盯温度」的车白白一座车开出去，什么都没读到。</p>
     *
     * <p>只有策略不算数：策略一直在换（refresh 一进门就换），换得再勤，没人读信号也看不到温度。</p>
     *
     * An ntfy-only car must actually start watching the signals, not just get a fresh policy: watching is the
     * field that says the vehicle signals are registered. A fresh policy alone proves nothing — refresh()
     * always builds one, and none of it matters if nobody is reading the temperature.
     */
    @Test
    public void anNtfyOnlyCarStartsWatchingTheSignals() throws Exception {
        config.setAlertsTransport(NtfyGate.NTFY);
        config.setAlertsNtfyTopic("zeekrsc_kymoy9m40cuQYXmHabcdefghijklmnopqrstu");
        config.setAlertsNtfyRiskAck(
                com.kooo.evcam.alarm.ntfy.NtfyRisk.ackFor(config.getAlertsNtfyTopic()));
        config.setAlertsNtfyPaid(true);
        assertFalse("开始时没在听", watching());

        Preference row = climatePage().findPreference("pref_alerts_climate");
        assertNotNull("少了车厢温度那个开关", row);
        assertEquals(Boolean.TRUE, row.callChangeListener(true));

        assertTrue("只填了 ntfy 的车也要开始读温度", watching());
        assertNotNull("策略照旧换一份", policy());
    }

    // ================================================================= 行尾

    /**
     * 行尾写的是刚拖到的那一格。
     *
     * <p>滑动条自己那个值由 androidx 在监听器返回之后才提交，所以监听器里读 {@code pref.getValue()} 拿到的是
     * 上一格：以前行尾就总慢一格（拖到 35 写着 30）。这里不先 setValue，直接叫监听器 —— 那就是提交之前的那一刻。</p>
     *
     * The summary shows the step that was just chosen. androidx commits the new value after the listener
     * returns, so reading pref.getValue() inside it wrote the previous step; the listener is called here
     * without setValue first, which is exactly that moment.
     */
    @Test
    public void theSliderSummaryShowsTheNewValueNotTheOldOne() throws Exception {
        SeekBarPreference hot = climatePage().findPreference("pref_alerts_climate_hot");
        hot.setValue(30);
        // 建页时写的是存着的那一格
        assertEquals("30 °C", String.valueOf(hot.getSummary()));

        hot.callChangeListener(35);

        assertEquals("行尾得是刚拖到的那一格，不是上一格（值本身还没提交）",
                "35 °C", String.valueOf(hot.getSummary()));
        assertEquals(35, config.getAlertsClimateHotC());
    }

    // ================================================================= 那一行实话

    /**
     * 这一组的开关关着时，那一行实话还在。
     *
     * <p>它说的是「空调只会自己关、不会自己开」，还有「要靠哨兵才叫得动车」—— 恰恰是开关关着的时候
     * 最该看到的两句，收起来等于对想开它的人关门。</p>
     *
     * The honest note stays while the switch is off: it is exactly what someone considering switching it on
     * needs to read.
     */
    @Test
    public void theHonestNoteStaysWhenTheClimateSwitchIsOff() throws Exception {
        config.setAlertsClimateEnabled(false);
        SettingsPreferenceFragment page = climatePage();

        assertFalse("开关关着：下面几行收起来", shown(page, "pref_alerts_climate_hot"));
        assertTrue("那一行实话不能被一起收起来", shown(page, "pref_alerts_climate_note"));
        assertEquals("而且它在最上面（三页一样）", "pref_alerts_climate_note",
                page.getPreferenceScreen().getPreference(0).getKey());
    }

    // ================================================================= 在家也发通知

    /**
     * 「在家也发通知」：存进 AppConfig，并且当场告诉跑着的那一套。
     *
     * <p>这一项和这一栏里每一个开关一样 —— 设置存了、跑着的那一套没听见，就是「重启才生效」。</p>
     *
     * The at-home switch stores the value and tells the running alerts, like every other switch in this
     * section: stored but unheard means it only works after a restart.
     */
    @Test
    public void theAtHomeSwitchTellsTheRunningAlerts() throws Exception {
        SettingsPreferenceFragment page = notificationsPage();
        Preference row = page.findPreference("pref_alerts_notify_at_home");
        assertNotNull("少了「在家也发通知」那个开关", row);
        assertNull("开始时没有策略（没人叫过 refresh）", policy());

        assertEquals(Boolean.TRUE, row.callChangeListener(true));

        assertTrue("开关的值存进了设置", config.isAlertsNotifyAtHome());
        assertNotNull("开关改完必须叫 refresh()，否则改到的东西不算数", policy());
        config.setAlertsNotifyAtHome(false);
    }

    // ================================================================= 左栏再点一次

    /**
     * 进了下一层以后在左栏再点「停车警报」：下层收掉，回到总开关那一页。
     *
     * <p>以前 {@code showSection} 不管返回栈：返回栈里还留着下层页、标题区的返回键也还亮着，
     * 再点同一个分区就叠出第二层。</p>
     *
     * Re-tapping the section in the left rail after opening a child page must pop the child: otherwise the
     * back arrow and the stack still belong to it, and the next tap stacks a second one.
     */
    @Test
    public void reTappingTheSectionPopsTheChildPage() throws Exception {
        SettingsPreferenceFragment hub = (SettingsPreferenceFragment) shell.getChildFragmentManager()
                .findFragmentById(R.id.settings_detail);
        assertNotNull(hub);
        Preference entry = hub.findPreference("pref_alerts_climate_page");
        assertNotNull("少了车厢温度那一行的入口", entry);
        entry.getOnPreferenceClickListener().onPreferenceClick(entry);
        Shots.settle();
        assertTrue("进了下一层就该在返回栈里",
                shell.getChildFragmentManager().getBackStackEntryCount() > 0);

        shell.showSection("screen_alerts");
        Shots.settle();

        assertEquals("再点同一个分区：下层要先收掉", 0,
                shell.getChildFragmentManager().getBackStackEntryCount());
        SettingsPreferenceFragment now = (SettingsPreferenceFragment) shell.getChildFragmentManager()
                .findFragmentById(R.id.settings_detail);
        assertNotNull(now);
        assertNotNull("该回到总开关那一页",
                now.findPreference("pref_alerts_climate_page"));
        assertNotNull("总开关那一页的行都接上了线",
                now.findPreference("pref_alerts_notifications"));
    }

    // ================================================================= 座椅：前排两个

    /**
     * 座椅那一行三个选项（不动 / 只主驾 / 两个前排），存的值就叫 {@code both}，改完要叫 refresh()。
     *
     * <p>第三个值以前被拿掉了：车主在车上发了一句 {@code ac 22}，主驾的座椅热了，副驾那边没动，
     * 他要的就是「两个一起」。存的值不能叫 {@code front} 也不能叫别的 —— 代码认的是 {@code both}，
     * 两边拼错一个字母，选完就当没选。</p>
     *
     * The seat row offers three values again, and the stored one is {@code both}. The third value had been
     * dropped: one {@code ac 22} warmed the driver seat and left the passenger side alone, which is exactly
     * what the owner asked to change.
     */
    @Test
    public void theSeatsRowOffersBothFrontSeatsAndStoresIt() throws Exception {
        ListPreference seats = climatePage().findPreference("pref_alerts_climate_seats");
        assertNotNull("少了动不动座椅那一行", seats);
        CharSequence[] values = seats.getEntryValues();
        assertEquals("三项：不动 / 只主驾 / 两个前排", 3, values.length);
        assertEquals("off", String.valueOf(values[0]));
        assertEquals("driver", String.valueOf(values[1]));
        assertEquals("both", String.valueOf(values[2]));
        assertFalse("存的值不叫 front", java.util.Arrays.asList(values).contains("front"));
        assertNull("开始时没有策略（没人叫过 refresh）", policy());

        assertEquals(Boolean.FALSE, seats.callChangeListener("both"));

        assertEquals("both 存进了设置", "both", config.getAlertsClimateSeats());
        assertTrue("副驾也要跟着开", config.isAlertsClimateSeatsBoth());
        assertNotNull("改完必须叫 refresh()，否则改到的东西不算数", policy());
        assertTrue("行尾要写刚选的那一项: " + seats.getSummary(),
                String.valueOf(seats.getSummary())
                        .contains(String.valueOf(seats.getEntries()[2])));
    }

    /** 存着一个认不得的值（旧的 {@code front}）：读出来是默认的主驾，副驾那一位是 false。 */
    @Test
    public void anUnknownStoredSeatValueReadsAsDriverOnly() throws Exception {
        // 直接写存储：{@code setAlertsClimateSeats} 自己就会拒掉不合法的值，那条路测不到「读到旧值」。
        // Written straight into the store: the setter refuses an invalid value, so the read-back of an old
        // stored string is only reachable from the storage side.
        RuntimeEnvironment.getApplication()
                .getSharedPreferences(AppConfig.PREF_NAME, android.content.Context.MODE_PRIVATE)
                .edit().putString("alerts_climate_seats", "front").commit();

        assertEquals("认不得的值读出来是默认的主驾", "driver", config.getAlertsClimateSeats());
        assertFalse("副驾没跟着开", config.isAlertsClimateSeatsBoth());
    }

    // ================================================================= 小工具

    /** 走车主的路进车厢温度那一页：点总开关那一页上那一行。 */
    private SettingsPreferenceFragment climatePage() {
        SettingsPreferenceFragment hub = (SettingsPreferenceFragment) shell.getChildFragmentManager()
                .findFragmentById(R.id.settings_detail);
        assertNotNull(hub);
        Preference entry = hub.findPreference("pref_alerts_climate_page");
        assertNotNull("少了车厢温度那一行的入口", entry);
        entry.getOnPreferenceClickListener().onPreferenceClick(entry);
        Shots.settle();
        SettingsPreferenceFragment page = (SettingsPreferenceFragment) shell.getChildFragmentManager()
                .findFragmentById(R.id.settings_detail);
        assertNotNull("车厢温度那一页没打开", page);
        assertNotNull("该换成下一层那一页", page.findPreference("pref_alerts_climate"));
        return page;
    }

    /** 走车主的路进通知那一页：点总开关那一页上那一行。 */
    private SettingsPreferenceFragment notificationsPage() {
        SettingsPreferenceFragment hub = (SettingsPreferenceFragment) shell.getChildFragmentManager()
                .findFragmentById(R.id.settings_detail);
        assertNotNull(hub);
        Preference entry = hub.findPreference("pref_alerts_notifications");
        assertNotNull("少了通知那一行的入口", entry);
        entry.getOnPreferenceClickListener().onPreferenceClick(entry);
        Shots.settle();
        SettingsPreferenceFragment page = (SettingsPreferenceFragment) shell.getChildFragmentManager()
                .findFragmentById(R.id.settings_detail);
        assertNotNull("通知那一页没打开", page);
        assertNotNull("该换成下一层那一页", page.findPreference("pref_alerts_notify_at_home"));
        return page;
    }

    private static boolean shown(SettingsPreferenceFragment page, String key) {
        Preference row = page.findPreference(key);
        assertNotNull("少了 " + key, row);
        return row.isVisible();
    }

    /** refresh() 一进门就把策略换成新的一份；这里看的就是那个字段。 */
    private static Object policy() throws Exception {
        java.lang.reflect.Field field = SentryAlerts.class.getDeclaredField("climatePolicy");
        field.setAccessible(true);
        return field.get(SentryAlerts.get(RuntimeEnvironment.getApplication()));
    }

    /** 登记了车辆信号没有（{@code SentryAlerts.watching}）。Whether the vehicle signals are registered. */
    private static boolean watching() throws Exception {
        java.lang.reflect.Field field = SentryAlerts.class.getDeclaredField("watching");
        field.setAccessible(true);
        return field.getBoolean(SentryAlerts.get(RuntimeEnvironment.getApplication()));
    }

    private static void forgetSingleton() throws Exception {
        java.lang.reflect.Field field = SentryAlerts.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
    }
}

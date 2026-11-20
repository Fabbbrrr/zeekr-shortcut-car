/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.preference.Preference;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.screens.ShotActivity;
import com.kooo.evcam.screens.Shots;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;

/** 设置的每一页画成图片：白天、夜间各一套，另加一套可选功能全开的（{@link Shots}）。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SettingsShotsTest {

    private static final String[] SECTIONS = {
            "screen_recording", "screen_alerts", "screen_storage", "screen_rearview",
            "screen_side_view", "screen_floating", "screen_general", "screen_update",
    };

    @Test
    public void everySectionByDay() {
        shoot(Shots.DAY, "day");
    }

    @Test
    public void everySectionByDayInEnglish() {
        shoot(Shots.DAY_EN, "day-en");
    }

    /**
     * 从右往左的语言：设置这类文字界面跟着镜像 —— 左栏到了右边（行尾的 › 翻过来看图）；
     * ① 标题区不镜像，菜单键还在左上、标题紧跟着（项目所有者 2026-11-19；ui-spec §十七）。
     */
    @Test
    public void everySectionRightToLeft() {
        rightToLeft(shoot(Shots.DAY_RTL, "day-rtl"));
    }

    /** 阿拉伯语：同上，文字是阿拉伯文、数字 0–9。 */
    @Test
    public void everySectionInArabic() {
        rightToLeft(shoot(Shots.DAY_AR, "day-ar"));
    }

    private static void rightToLeft(ShotActivity activity) {
        int[] headers = new int[2];
        int[] detail = new int[2];
        activity.findViewById(R.id.settings_headers).getLocationInWindow(headers);
        activity.findViewById(R.id.settings_detail).getLocationInWindow(detail);
        assertTrue("左栏到了右边：" + headers[0] + " / " + detail[0], headers[0] > detail[0]);
        int[] nav = new int[2];
        int[] title = new int[2];
        activity.findViewById(R.id.settings_nav).getLocationInWindow(nav);
        activity.findViewById(R.id.settings_title).getLocationInWindow(title);
        assertTrue("菜单键在左上：" + nav[0], nav[0] < headers[0] / 4);
        assertTrue("标题紧跟在菜单键右边：" + nav[0] + " / " + title[0], nav[0] < title[0]);
    }

    @Test
    public void everySectionByNight() {
        shoot(Shots.NIGHT, "night");
    }

    /** 可选的功能都打开：关着时只有一行的那几页，展开以后是什么样。 */
    @Test
    public void everySectionWithEverythingOn() {
        AppConfig config = new AppConfig(RuntimeEnvironment.getApplication());
        config.setRearViewEnabled(true);
        config.setSideViewEnabled(true);
        config.setDriveSoundEnabled(true);
        // 停车警报开着时下面四组才出现：这张图就是用来看那一屏的
        config.setAlertsEnabled(true);
        config.setAlertsTelegramToken("123456789:AAHexampleTOKENvalue0123456789");
        config.setAlertsTelegramChatId("123456789");
        config.setAlertsClipSeconds(5);
        shoot(Shots.DAY, "day-on");
    }

    @Test
    public void everySectionWithEverythingOnInEnglish() {
        AppConfig config = new AppConfig(RuntimeEnvironment.getApplication());
        config.setRearViewEnabled(true);
        config.setSideViewEnabled(true);
        config.setDriveSoundEnabled(true);
        config.setAlertsEnabled(true);
        config.setAlertsTelegramToken("123456789:AAHexampleTOKENvalue0123456789");
        config.setAlertsTelegramChatId("123456789");
        config.setAlertsOwnerId("123456789");
        config.setAlertsClipSeconds(10);
        config.setAlertsQuietEnabled(true);
        shoot(Shots.DAY_EN, "day-on-en");
    }

    /**
     * 「按键」页里绑定的灵动贴（项目所有者 2026-11-14「名称 / 位置」）：起了名字的显示名字、默认名（带短编号）挪到下面那一行；
     * 没起的照旧是默认名。存着的名字再怪（这里直接往存储里塞一段带标签、格式符、换行、方向控制符的长字，像是从改过的
     * 备份里来的），显示出来也只是最多 20 个字的一行字。地址是随便编的。
     */
    @Test
    public void namedSmartButtons() {
        namedSmartButtons(Shots.DAY, "");
    }

    @Test
    public void namedSmartButtonsInEnglish() {
        namedSmartButtons(Shots.DAY_EN, "-en");
    }

    @Test
    public void knobRows() {
        knobRows(Shots.DAY, "");
    }

    @Test
    public void knobRowsInEnglish() {
        knobRows(Shots.DAY_EN, "-en");
    }

    private void knobRows(String qualifiers, String suffix) {
        android.content.Context app = RuntimeEnvironment.getApplication();
        AppConfig config = new AppConfig(app);
        String button = "00:11:22:33:44:55";
        String knob = "66:77:88:99:AA:BB";
        config.addAccessory(com.kooo.evcam.button.Accessory.BUTTON, button);
        config.addAccessory(com.kooo.evcam.button.Accessory.KNOB, knob);

        RuntimeEnvironment.setQualifiers(qualifiers);
        ShotActivity activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(activity);
        SettingsShellFragment shell = new SettingsShellFragment();
        activity.getSupportFragmentManager().beginTransaction().replace(activity.container, shell).commitNow();
        Shots.settle();
        shell.showSection("screen_floating");
        Shots.settle();
        SettingsPreferenceFragment section = (SettingsPreferenceFragment)
                shell.getChildFragmentManager().findFragmentById(R.id.settings_detail);
        assertNotNull(section);

        assertNotNull(section.findPreference("cat_smart_buttons"));
        assertNotNull(section.findPreference("pref_smart_buttons"));
        assertNotNull(section.findPreference("pref_smart_button_bind"));
        org.junit.Assert.assertNull(section.findPreference("cat_x_knobs"));
        org.junit.Assert.assertNull(section.findPreference("pref_x_knobs"));
        Preference row = section.findPreference("x_knob." + knob);
        assertNotNull(row);
        assertEquals(activity.getString(R.string.x_knob_name, com.kooo.evcam.button.SmartButtonAd.shortId(knob)),
                String.valueOf(row.getTitle()));
        String summary = String.valueOf(row.getSummary());
        assertTrue(summary.contains(activity.getString(R.string.knob_fn_volume)));
        assertNotNull(section.findPreference("smart_button." + button));

        section.scrollToPreference("x_knob." + knob);
        File file = Shots.save(activity, "settings-floating-day-knob" + suffix);
        assertTrue(file.length() > 10_000);
        Preference status = section.findPreference("x_knob." + knob + "/status");
        assertNotNull(status);
        assertFalse(status.isVisible());

        Preference buttonRow = section.findPreference("smart_button." + button);
        buttonRow.getOnPreferenceClickListener().onPreferenceClick(buttonRow);
        Shots.settle();
        assertFalse(section.findPreference("smart_button." + button + "/status").isVisible());
        assertTrue(section.findPreference("smart_button." + button + "/name").isVisible());
        assertTrue(section.findPreference("smart_button." + button + "/2").isVisible());
        section.scrollToPreference("smart_button." + button + "/2");
        Shots.settle();
        File buttonOpen = Shots.save(activity, "settings-floating-day-button-open" + suffix);
        assertTrue(buttonOpen.length() > 10_000);
        buttonRow.getOnPreferenceClickListener().onPreferenceClick(buttonRow);
        Shots.settle();
        assertFalse(section.findPreference("smart_button." + button + "/name").isVisible());

        row.getOnPreferenceClickListener().onPreferenceClick(row);
        Shots.settle();
        assertTrue(status.isVisible());
        assertTrue(String.valueOf(status.getSummary()).contains(activity.getString(R.string.x_knob_mode_normal)));
        Preference turnRow = section.findPreference("x_knob." + knob + "/turn");
        Preference homeRow = section.findPreference("x_knob." + knob + "/home");
        assertTrue(turnRow.isVisible());
        assertFalse(homeRow.isVisible());
        section.scrollToPreference("x_knob." + knob + "/turn");
        Shots.settle();
        File open = Shots.save(activity, "settings-floating-day-knob-open" + suffix);
        assertTrue(open.length() > 10_000);

        turnRow.getOnPreferenceClickListener().onPreferenceClick(turnRow);
        Shots.settle();
        android.app.Dialog turn = org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull(turn);
        assertEquals(1 + com.kooo.evcam.button.KnobTurn.groups().indexOf(com.kooo.evcam.button.ButtonAction.Group.MEDIA),
                ((androidx.appcompat.app.AlertDialog) turn).getListView().getCheckedItemPosition());
        File turnFile = Shots.save(turn, "settings-floating-day-knob-turn" + suffix);
        turn.dismiss();
        assertTrue(turnFile.length() > 10_000);

        CardItemPreference exclusive = section.findPreference("x_knob." + knob + "/exclusive");
        assertNotNull(exclusive);
        assertFalse(exclusive.isChecked());
        exclusive.onClick();
        Shots.settle();
        assertTrue(config.isKnobExclusive(knob));
        assertFalse(turnRow.isVisible());
        assertTrue(homeRow.isVisible());
        section.scrollToPreference("x_knob." + knob + "/home");
        Shots.settle();
        File openExclusive = Shots.save(activity, "settings-floating-day-knob-open-exclusive" + suffix);
        assertTrue(openExclusive.length() > 10_000);

        homeRow.getOnPreferenceClickListener().onPreferenceClick(homeRow);
        Shots.settle();
        android.app.Dialog home = org.robolectric.shadows.ShadowDialog.getLatestDialog();
        org.junit.Assert.assertNotSame(turn, home);
        File homeFile = Shots.save(home, "settings-floating-day-knob-home" + suffix);
        android.util.SparseBooleanArray picks = ((androidx.appcompat.app.AlertDialog) home).getListView()
                .getCheckedItemPositions();
        java.util.List<com.kooo.evcam.button.KnobTurn> catalog = new java.util.ArrayList<>(
                java.util.Arrays.asList(com.kooo.evcam.button.KnobTurn.values()));
        catalog.remove(com.kooo.evcam.button.KnobTurn.NONE);
        for (int i = 0; i < 8; i++) {
            assertEquals(catalog.get(i).key, com.kooo.evcam.button.KnobTurn.home().contains(catalog.get(i)),
                    picks.get(i));
        }
        home.dismiss();
        assertTrue(homeFile.length() > 10_000);

        row.getOnPreferenceClickListener().onPreferenceClick(row);
        Shots.settle();
        assertFalse(status.isVisible());
        assertFalse(homeRow.isVisible());
        config.setKnobExclusive(knob, false);
    }

    private void namedSmartButtons(String qualifiers, String suffix) {
        android.content.Context app = RuntimeEnvironment.getApplication();
        AppConfig config = new AppConfig(app);
        String first = "00:11:22:33:44:55";
        String second = "66:77:88:99:AA:BB";
        String third = "CC:DD:EE:FF:00:11";
        config.addAccessory(com.kooo.evcam.button.Accessory.BUTTON, first);
        config.addAccessory(com.kooo.evcam.button.Accessory.BUTTON, second);
        config.addAccessory(com.kooo.evcam.button.Accessory.BUTTON, third);
        config.setAccessoryName(com.kooo.evcam.button.Accessory.BUTTON, first, "方向盘左");
        app.getSharedPreferences(AppConfig.PREF_NAME, android.content.Context.MODE_PRIVATE).edit()
                .putString("smart_button_name." + second, "<b>%s</b>\n\n\u202E" + "很长的名字".repeat(30)).commit();

        RuntimeEnvironment.setQualifiers(qualifiers);
        ShotActivity activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(activity);
        SettingsShellFragment shell = new SettingsShellFragment();
        activity.getSupportFragmentManager().beginTransaction().replace(activity.container, shell).commitNow();
        Shots.settle();
        shell.showSection("screen_floating");
        Shots.settle();
        SettingsPreferenceFragment section = (SettingsPreferenceFragment)
                shell.getChildFragmentManager().findFragmentById(R.id.settings_detail);
        assertNotNull(section);

        Preference named = section.findPreference("smart_button." + first);
        assertNotNull(named);
        assertEquals("起了名字的显示名字", "方向盘左", String.valueOf(named.getTitle()));
        String defaultName = activity.getString(R.string.smart_button_name,
                com.kooo.evcam.button.SmartButtonAd.shortId(first));
        assertTrue("默认名挪到下面那一行：" + named.getSummary(), String.valueOf(named.getSummary()).contains(defaultName));

        Preference odd = section.findPreference("smart_button." + second);
        assertNotNull(odd);
        String shown = String.valueOf(odd.getTitle());
        assertEquals("怪名字读出来是洗过的", com.kooo.evcam.button.DeviceLabel.clean(shown), shown);
        assertTrue(shown.codePointCount(0, shown.length()) <= com.kooo.evcam.button.DeviceLabel.MAX);
        assertFalse(shown.contains("\n"));
        assertTrue("尖括号、百分号只是字：" + shown, shown.startsWith("<b>%s</b> 很长的名字"));

        Preference plain = section.findPreference("smart_button." + third);
        assertNotNull(plain);
        assertEquals("没起名的照旧是默认名", activity.getString(R.string.smart_button_name,
                com.kooo.evcam.button.SmartButtonAd.shortId(third)), String.valueOf(plain.getTitle()));

        section.scrollToPreference("smart_button." + third);
        File file = Shots.save(activity, "settings-floating-day-names" + suffix);
        assertTrue(file + " 是空的", file.length() > 10_000);
    }

    /**
     * 「通用」的「模拟声浪」一组：听感、转速变化方式、变速箱所有人都有（4.0.0 收进过开发者选项，项目所有者 2026-11-13
     * 上车后要回来）；听感、变速箱跟着声音风格出现。这一组在第一屏下面，滚到它另出一张图。
     */
    @Test
    public void driveSoundRowsAreThereForEveryone() {
        driveSoundRows(Shots.DAY, "");
    }

    @Test
    public void driveSoundRowsInEnglish() {
        driveSoundRows(Shots.DAY_EN, "-en");
    }

    private void driveSoundRows(String qualifiers, String suffix) {
        assertFalse("这一条看的是开发者选项关着时", DeveloperMode.isUnlocked());
        new AppConfig(RuntimeEnvironment.getApplication()).setDriveSoundEnabled(true);
        RuntimeEnvironment.setQualifiers(qualifiers);
        ShotActivity activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(activity);
        SettingsShellFragment shell = new SettingsShellFragment();
        activity.getSupportFragmentManager().beginTransaction().replace(activity.container, shell).commitNow();
        Shots.settle();
        shell.showSection("screen_general");
        Shots.settle();
        SettingsPreferenceFragment section = (SettingsPreferenceFragment)
                shell.getChildFragmentManager().findFragmentById(R.id.settings_detail);
        assertNotNull(section);
        for (String key : new String[] {"pref_drive_sound_style", "pref_drive_sound_view", "pref_drive_sound_rpm",
                "pref_drive_sound_gearbox", "pref_drive_sound_standard", "pref_drive_sound_volume",
                "pref_drive_sound_output"}) {
            assertTrue("美式 V8 时该有 " + key, shown(section, key));
        }
        section.scrollToPreference("pref_drive_sound_output");
        File file = Shots.save(activity, "settings-general-day-sound" + suffix);
        assertTrue(file + " 是空的", file.length() > 10_000);

        // 超音速（旧版）：没有听感（只有美式 V8 分车内车外）、没有变速箱（固定 8 速）
        Preference style = section.findPreference("pref_drive_sound_style");
        assertNotNull(style);
        style.callChangeListener(AppConfig.DRIVE_SOUND_STYLE_JET_OLD);
        Shots.settle();
        assertFalse(shown(section, "pref_drive_sound_view"));
        assertFalse(shown(section, "pref_drive_sound_gearbox"));
        assertTrue(shown(section, "pref_drive_sound_rpm"));
    }

    /**
     * 开发者选项「文件维护」一组里的「录像回放：逐个读取片段时长」（项目所有者 2026-11-14：打开文件读时长作后备，
     * 放进开发者选项）：默认关。开发者选项在这里直接拨开（不走密码），看完拨回去。
     */
    @Test
    public void developerFileRows() throws Exception {
        java.lang.reflect.Field unlocked = DeveloperMode.class.getDeclaredField("unlocked");
        unlocked.setAccessible(true);
        unlocked.setBoolean(null, true);
        try {
            assertFalse("默认关", new AppConfig(RuntimeEnvironment.getApplication()).isPlaybackReadDurations());
            RuntimeEnvironment.setQualifiers(Shots.DAY);
            ShotActivity activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
            Shots.stillFrames(activity);
            SettingsShellFragment shell = new SettingsShellFragment();
            activity.getSupportFragmentManager().beginTransaction().replace(activity.container, shell).commitNow();
            Shots.settle();
            shell.showSection(SettingsShellFragment.DEVELOPER_SECTION);
            Shots.settle();
            SettingsPreferenceFragment section = (SettingsPreferenceFragment)
                    shell.getChildFragmentManager().findFragmentById(R.id.settings_detail);
            assertNotNull(section);
            androidx.preference.SwitchPreferenceCompat row = section.findPreference("pref_playback_read_durations");
            assertNotNull(row);
            assertTrue(row.isVisible());
            assertFalse(row.isChecked());
            section.scrollToPreference("pref_archive");
            File file = Shots.save(activity, "settings-developer-day-files");
            assertTrue(file + " 是空的", file.length() > 10_000);

            assertTrue(row.callChangeListener(true));
            assertTrue("拨开就存下了", new AppConfig(RuntimeEnvironment.getApplication()).isPlaybackReadDurations());
        } finally {
            unlocked.setBoolean(null, false);
        }
        assertFalse("开发者选项关着时按关算，存着的值不动",
                new AppConfig(RuntimeEnvironment.getApplication()).isPlaybackReadDurations());
    }

    /**
     * 「忘了开哨兵」那三行在哨兵那一页的「什么时候发」那一组里，排在安静时段之后；跟着警报总开关走，
     * 但走 ntfy 出口时收起来（按钮是 callback_query，ntfy 没有回程）。滚到它们另出一张图。
     *
     * <p>几种状态各开一屏新的页来验：总开关开着（在、位置对）、换成 ntfy（收起来）、
     * 总开关关着（三行一起收起来）。出口那一组在另一页上，所以这一页上根本没有它。</p>
     *
     * The three Forgot Sentry rows sit in the sentry page's "When it fires" group after quiet hours and
     * follow the master switch only; they fold away on the ntfy exit. The exit block lives on another
     * page, so it is not loaded here at all.
     */
    @Test
    public void forgotSentryRowsSitUnderWhenItFires() {
        SettingsPreferenceFragment on = alertsChildPage(Shots.DAY_EN, true,
                com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM, "pref_alerts_sentry",
                CHILD_SENTRY, R.string.set_alerts_page_sentry, config -> {
                });
        androidx.preference.PreferenceGroup when = (androidx.preference.PreferenceGroup)
                on.findPreference("cat_alerts_when");
        assertNotNull("少了「什么时候发」那一组", when);
        for (String key : GUARD_ROWS) {
            Preference row = on.findPreference(key);
            assertNotNull("少了 " + key, row);
            assertTrue(key + " 该在「什么时候发」那一组里", row.getParent() == when);
            assertTrue(key + " 该看得见", row.isVisible());
            assertTrue(key + " 该排在安静时段之后",
                    indexIn(when, key) > indexIn(when, "pref_alerts_quiet_end"));
        }
        on.scrollToPreference("pref_alerts_guard_everywhere");
        File file = Shots.save(shotActivity, "settings-alerts-day-guard");
        assertTrue(file + " 是空的", file.length() > 10_000);

        // 换成 ntfy：忘了开哨兵那三行收起来；出口那一组在通知那一页上，而通知那一页照旧出现 ntfy 块。
        // Switch to ntfy: the Forgot Sentry rows fold away, and the ntfy block shows on its own page.
        SettingsPreferenceFragment ntfy = alertsChildPage(Shots.DAY_EN, true,
                com.kooo.evcam.alarm.ntfy.NtfyGate.NTFY, "pref_alerts_sentry",
                CHILD_SENTRY, R.string.set_alerts_page_sentry, config -> {
                });
        for (String key : GUARD_ROWS) {
            assertFalse("ntfy 时 " + key + " 该收起来", ntfy.findPreference(key).isVisible());
        }
        assertNull("出口那一组在通知那一页，不在哨兵这一页", ntfy.findPreference("cat_alerts_ntfy"));

        SettingsPreferenceFragment ntfyNotifications = alertsChildPage(Shots.DAY_EN, true,
                com.kooo.evcam.alarm.ntfy.NtfyGate.NTFY, "pref_alerts_notifications",
                CHILD_NOTIFICATIONS, R.string.set_alerts_page_notifications, config -> {
                });
        assertTrue("ntfy 时通知那一页该摆 ntfy 那一组",
                ntfyNotifications.findPreference("cat_alerts_ntfy").isVisible());
        assertNull("通知那一页没有哨兵的「什么时候发」",
                ntfyNotifications.findPreference("cat_alerts_when"));

        // 总开关关着：三行一起收起来。Master off: all three go with it.
        SettingsPreferenceFragment off = alertsChildPage(Shots.DAY_EN, false,
                com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM, "pref_alerts_sentry",
                CHILD_SENTRY, R.string.set_alerts_page_sentry, config -> {
                });
        for (String key : GUARD_ROWS) {
            assertFalse("总开关关着时 " + key + " 该收起来", off.findPreference(key).isVisible());
        }
    }

    /**
     * 聊天里 {@code alerts off} 之后，设置里那一行必须在屏上，而且点一下就恢复。
     *
     * <p>一个看不见的静音是最糟的状态：总开关写着「开」，车主以为车在看着，其实一条都不会发。
     * 所以暂停必须是一行看得见的东西，而不是藏在聊天记录里的一个词。</p>
     *
     * The row after "alerts off" in the chat must be on screen, and one tap resumes. An invisible mute is
     * the worst state: the master switch reads "on" while nothing is sent. So a pause is a visible row,
     * not a word someone said in a chat once.
     */
    @Test
    public void thePauseRowAppearsAndResumesOnTap() {
        RuntimeEnvironment.setQualifiers(Shots.DAY_EN);
        SettingsPreferenceFragment pausedPage = alertsPage(true,
                com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM, config -> config.setAlertsPaused(true));
        assertTrue("暂停时那一行该出现", shown(pausedPage, "pref_alerts_paused"));
        androidx.preference.Preference row = pausedPage.findPreference("pref_alerts_paused");
        assertNotNull(row);
        assertNotNull("那一行该点得动", row.getOnPreferenceClickListener());
        row.getOnPreferenceClickListener().onPreferenceClick(row);
        assertFalse("点一下就该恢复",
                new AppConfig(RuntimeEnvironment.getApplication()).isAlertsPaused());
        assertFalse("恢复之后那一行收起来", shown(pausedPage, "pref_alerts_paused"));

        SettingsPreferenceFragment runningPage = alertsPage(true,
                com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM, config -> config.setAlertsPaused(false));
        assertFalse("没暂停时不该出现", shown(runningPage, "pref_alerts_paused"));
    }

    private static final String[] GUARD_ROWS =
            {"pref_alerts_guard", "pref_alerts_guard_home", "pref_alerts_guard_everywhere"};

    /** 停车警报下面那三页的 key（和 PreferencesFragment 里的常量一致）。 */
    private static final String CHILD_NOTIFICATIONS = "screen_alerts_notifications";
    private static final String CHILD_SENTRY = "screen_alerts_sentry";
    private static final String CHILD_CLIMATE = "screen_alerts_climate";

    /** 总开关那一页上的三行入口。 */
    private static final String[] HUB_ROWS =
            {"pref_alerts_notifications", "pref_alerts_sentry", "pref_alerts_climate_page"};

    /**
     * 总开关那一页：开关、出口、状态行，然后三行入口，别的什么都没有（4.6，ui-spec §十六）。
     *
     * <p>三块内容各在下一层的一页上：那三页本身在这一屏上是收着的，所以一屏里不会同时摆三套互不相干的东西。
     * 行尾写的是现在的情形，不是固定的一句话。</p>
     *
     * The hub: master switch, exit, status row, then three entry rows and nothing else. The three child
     * pages are collapsed here, and each summary says what is true right now.
     */
    @Test
    public void theHubListsThreePagesAndNothingElse() {
        RuntimeEnvironment.setQualifiers(Shots.DAY_EN);
        SettingsPreferenceFragment hub = alertsPage(true, com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM);
        androidx.preference.PreferenceGroup screen = hub.getPreferenceScreen();
        String[] rows = {"pref_alerts_notifications", "pref_alerts_sentry", "pref_alerts_climate_page"};
        int last = indexIn(screen, "pref_alerts_channel_status");
        assertTrue("出口和状态行该在这一页上", last >= 0);
        for (String key : rows) {
            Preference row = hub.findPreference(key);
            assertNotNull("少了入口那一行 " + key, row);
            assertTrue(key + " 该看得见", row.isVisible());
            assertNotNull(key + " 该点得动", row.getOnPreferenceClickListener());
            assertTrue(key + " 该排在出口和状态行之后", indexIn(screen, key) > last);
            last = indexIn(screen, key);
            assertFalse(key + " 的行尾该按现在的设置写",
                    String.valueOf(row.getSummary()).isEmpty());
        }

        // 行尾：通知写出口和填好了没有，车厢温度写现在那两个数。
        // Summaries: the exit and whether it is filled in; the two stored temperature numbers.
        String notifications = String.valueOf(hub.findPreference("pref_alerts_notifications").getSummary());
        assertTrue("通知那一行该写出口: " + notifications,
                notifications.contains(hub.getString(R.string.set_alerts_transport_telegram)));
        assertTrue("令牌和会话号都填了，就该写填好了: " + notifications,
                notifications.contains(hub.getString(R.string.set_alerts_hub_ready)));
        String climate = String.valueOf(hub.findPreference("pref_alerts_climate_page").getSummary());
        AppConfig now = new AppConfig(RuntimeEnvironment.getApplication());
        assertTrue("车厢温度那一行该写现在的太热那一档: " + climate,
                climate.contains(String.valueOf(now.getAlertsClimateHotC())));
        assertTrue("车厢温度那一行该写现在的目标温度: " + climate,
                climate.contains(String.valueOf(now.getAlertsClimateTargetC())));
        String sentry = String.valueOf(hub.findPreference("pref_alerts_sentry").getSummary());
        AppConfig sentryNow = new AppConfig(RuntimeEnvironment.getApplication());
        com.kooo.evcam.alarm.policy.TriggerSet watching =
                com.kooo.evcam.alarm.policy.TriggerSet.parse(sentryNow.getAlertsTriggers());
        int watched = 0;
        for (String name : com.kooo.evcam.alarm.policy.TriggerSet.ALL) {
            if (watching.has(name)) {
                watched++;
            }
        }
        assertTrue("哨兵那一行该写看着几项: " + sentry,
                sentry.contains(hub.getString(R.string.set_alerts_hub_watched, watched)));
        int armMinutes = sentryNow.getAlertsArmDelayMinutes();
        String armedPhrase = armMinutes <= 0
                ? hub.getString(R.string.set_alerts_hub_sentry_armed)
                : hub.getString(R.string.set_alerts_hub_sentry_delay,
                        hub.getString(armMinutes == 1 ? R.string.opt_alerts_arm_1
                                : R.string.opt_alerts_arm_3));
        assertTrue("哨兵那一行该写布防快不快（暗号不算）: " + sentry,
                sentry.contains(armedPhrase));

        // 三块内容各自在下一层：这一屏上那三页都是收着的（列成一行会点了没处接）
        for (String key : new String[]{CHILD_NOTIFICATIONS, CHILD_SENTRY, CHILD_CLIMATE}) {
            Preference page = hub.findPreference(key);
            assertNotNull("少了下一层那一页 " + key, page);
            assertFalse(key + " 不该在这一屏上列成一行", page.isVisible());
        }

        // 总开关关着：只剩开关、出口、状态行和暂停那一行，三行入口一起收起来。
        // Master off: only the first rows are left; the three entry rows fold away.
        SettingsPreferenceFragment off = alertsPage(false,
                com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM, config -> config.setAlertsPaused(true));
        for (String key : new String[]{"pref_alerts", "pref_alerts_transport",
                "pref_alerts_channel_status"}) {
            assertTrue("总开关关着时 " + key + " 还该在", shown(off, key));
        }
        assertFalse("总开关关着时不摆暂停那一行（老规矩）", shown(off, "pref_alerts_paused"));
        for (String key : rows) {
            assertFalse("总开关关着时 " + key + " 该收起来", shown(off, key));
        }
    }

    /**
     * 通知那一行的行尾问的是真的出口：话题填了但免费方案的提醒没接受，就不许说「已填好」。
     *
     * <p>以前只看话题那个框里有没有字 —— 换过话题（换话题会清掉「接受过」那一条）之后，
     * 行尾写着已填好，而每一条警报都被出口自己拒绝掉。</p>
     *
     * The notifications row asks the real exit: a topic with no accepted free-plan warning must not read
     * "filled in". The old summary looked only at whether the topic box had text, so after rotating the topic
     * (which clears the acceptance) it claimed everything was set while the exit refused every alert.
     */
    @Test
    public void theHubSaysTheNtfyWarningIsNotAccepted() {
        String topic = "zeekrsc_kymoy9m40cuQYXmHabcdefghijklmnopqrstu";

        // 话题有了、提醒没接受：行尾说的是差这一格。
        SettingsPreferenceFragment unacked = alertsPage(Shots.DAY_EN, true,
                com.kooo.evcam.alarm.ntfy.NtfyGate.NTFY,
                config -> config.setAlertsNtfyTopic(topic));
        String unackedSummary = String.valueOf(
                unacked.findPreference("pref_alerts_notifications").getSummary());
        assertFalse("提醒没接受就不许说已填好: " + unackedSummary,
                unackedSummary.contains(unacked.getString(R.string.set_alerts_hub_ready)));
        assertTrue("要说清楚差的是那一句提醒: " + unackedSummary,
                unackedSummary.contains(unacked.getString(R.string.set_alerts_hub_ack_missing)));

        // 接受了就是已填好（否则上一条是空过：永远不写 ready 也算通过）。
        SettingsPreferenceFragment acked = alertsPage(Shots.DAY_EN, true,
                com.kooo.evcam.alarm.ntfy.NtfyGate.NTFY, config -> {
                    config.setAlertsNtfyTopic(topic);
                    config.setAlertsNtfyRiskAck(com.kooo.evcam.alarm.ntfy.NtfyRisk.ackFor(topic));
                });
        String ackedSummary = String.valueOf(
                acked.findPreference("pref_alerts_notifications").getSummary());
        assertTrue("接受了提醒就是已填好: " + ackedSummary,
                ackedSummary.contains(acked.getString(R.string.set_alerts_hub_ready)));
    }

    /** 三页各一张图（总开关那一页的图由 {@link #everySectionByDay} 那一套出）。 */
    @Test
    public void alertsChildPagesByDay() {
        alertsChildPages(Shots.DAY, "day");
    }

    @Test
    public void alertsChildPagesInEnglish() {
        alertsChildPages(Shots.DAY_EN, "day-en");
    }

    /**
     * 三页各自摆什么，以及「检查」只在通知这一页上。
     *
     * <p>车厢温度那一页第一行是这一组自己的开关，四个数值是拖动条：范围、行尾都照 {@link AppConfig} 里那一份。
     * 拖一下写回 AppConfig，不是写进 Preference 自己。</p>
     *
     * What each page holds, and that the checks group lives on the notifications page only. On the climate
     * page the group's own switch is the first row and the four numbers are sliders.
     */
    private void alertsChildPages(String qualifiers, String tag) {
        // 这四样的默认值：太热 30、太冷 5、目标 22、电量下限 20（测试一开始设置是干净的）
        AppConfig fresh = new AppConfig(RuntimeEnvironment.getApplication());
        assertEquals("太热的默认值", 30, fresh.getAlertsClimateHotC());
        assertEquals("太冷的默认值", 5, fresh.getAlertsClimateColdC());
        assertEquals("目标温度的默认值", 22, fresh.getAlertsClimateTargetC());
        assertEquals("电量下限的默认值", 20, fresh.getAlertsClimateSocFloor());

        // 通知：两条出口的块、命令那几行、「检查」那一组，都在这一页
        SettingsPreferenceFragment notifications = alertsChildPage(qualifiers, true,
                com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM, "pref_alerts_notifications",
                CHILD_NOTIFICATIONS, R.string.set_alerts_page_notifications, config -> {
                });
        assertTrue(shown(notifications, "cat_alerts_telegram"));
        assertTrue(shown(notifications, "pref_alerts_token"));
        assertTrue("检查这一组只在通知这一页", shown(notifications, "cat_alerts_checks"));
        for (String key : new String[]{"pref_alerts_probe", "pref_alerts_events",
                "pref_alerts_diagnostics"}) {
            assertTrue(shown(notifications, key));
        }
        assertNull("通知这一页没有哨兵的「什么时候发」",
                notifications.findPreference("cat_alerts_when"));
        assertNull("通知这一页没有车厢温度那一组",
                notifications.findPreference("cat_alerts_climate"));
        File notificationShot = Shots.save(shotActivity, "settings-alerts-notifications-" + tag);
        assertTrue(notificationShot + " 是空的", notificationShot.length() > 10_000);

        // 哨兵：什么时候发、照片、短视频；检查不在这一页。
        SettingsPreferenceFragment sentry = alertsChildPage(qualifiers, true,
                com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM, "pref_alerts_sentry",
                CHILD_SENTRY, R.string.set_alerts_page_sentry,
                config -> config.setAlertsClipSeconds(5));
        for (String key : new String[]{"cat_alerts_when", "pref_alerts_triggers", "pref_alerts_arm_delay",
                "cat_alerts_picture", "pref_alerts_send_picture", "cat_alerts_clip", "pref_alerts_clip"}) {
            assertTrue("哨兵这一页该有 " + key, shown(sentry, key));
        }
        assertNull("检查不在哨兵这一页", sentry.findPreference("cat_alerts_checks"));
        assertNull("检查那几行也不在", sentry.findPreference("pref_alerts_probe"));
        File sentryShot = Shots.save(shotActivity, "settings-alerts-sentry-" + tag);
        assertTrue(sentryShot + " 是空的", sentryShot.length() > 10_000);

        // 车厢温度：开关第一行，四个数值是拖动条，其余几项还是下拉框；检查不在这一页。
        SettingsPreferenceFragment climate = alertsChildPage(qualifiers, true,
                com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM, "pref_alerts_climate_page",
                CHILD_CLIMATE, R.string.set_alerts_page_climate,
                config -> config.setAlertsClimateEnabled(true));
        androidx.preference.PreferenceGroup screen = climate.getPreferenceScreen();
        assertNull("页名已经说了这一页是什么，组名不再重复一遍",
                climate.findPreference("cat_alerts_climate"));
        assertEquals("第一行是这一组自己的开关", "pref_alerts_climate",
                screen.getPreference(0).getKey());
        sliderShows(climate, "pref_alerts_climate_hot", 25, 40, 30, "30 °C");
        sliderShows(climate, "pref_alerts_climate_cold", -10, 15, 5, "5 °C");
        sliderShows(climate, "pref_alerts_climate_target", 16, 30, 22, "22 °C");
        sliderShows(climate, "pref_alerts_climate_soc_floor", 20, 99, 20, "20%");
        for (String key : CLIMATE_ROWS) {
            assertTrue("开关开着时该有 " + key, shown(climate, key));
        }
        for (String key : new String[]{"pref_alerts_climate_seats", "pref_alerts_climate_seat_level",
                "pref_alerts_climate_minutes"}) {
            assertFalse(key + " 还是下拉框，不是拖动条",
                    climate.findPreference(key) instanceof androidx.preference.SeekBarPreference);
        }
        assertNull("检查不在车厢温度这一页", climate.findPreference("cat_alerts_checks"));
        File climateShot = Shots.save(shotActivity, "settings-alerts-climate-" + tag);
        assertTrue(climateShot + " 是空的", climateShot.length() > 10_000);

        // 拖一下：值是写进 AppConfig 的，行尾跟着变（拖动条自己不存值）
        androidx.preference.SeekBarPreference hot = climate.findPreference("pref_alerts_climate_hot");
        hot.setValue(34);
        assertEquals(Boolean.TRUE, hot.callChangeListener(34));
        assertEquals("拖完就存下了", 34,
                new AppConfig(RuntimeEnvironment.getApplication()).getAlertsClimateHotC());
        assertEquals("行尾跟着变", "34 °C", String.valueOf(hot.getSummary()));

        // 范围外的一律夹到最近的一头（不再是「不在单子上就回默认值」）
        AppConfig config = new AppConfig(RuntimeEnvironment.getApplication());
        config.setAlertsClimateHotC(99);
        assertEquals("太热夹到 40", 40, config.getAlertsClimateHotC());
        config.setAlertsClimateColdC(-40);
        assertEquals("太冷夹到 -10", -10, config.getAlertsClimateColdC());
        config.setAlertsClimateTargetC(5);
        assertEquals("目标温度夹到 16", 16, config.getAlertsClimateTargetC());
        config.setAlertsClimateSocFloor(5);
        assertEquals("电量下限夹到 20", 20, config.getAlertsClimateSocFloor());
        config.setAlertsClimateSocFloor(150);
        assertEquals("电量下限夹到 99", 99, config.getAlertsClimateSocFloor());

        // 收尾：这些都在同一份设置里，别把后面的测试带跑。
        config.setAlertsClimateEnabled(false);
        config.setAlertsClimateHotC(30);
        config.setAlertsClimateColdC(5);
        config.setAlertsClimateTargetC(22);
        config.setAlertsClimateSocFloor(20);
        config.setAlertsClipSeconds(0);
    }

    /** 一根拖动条：最小、最大、现在的值、行尾。 */
    private static void sliderShows(SettingsPreferenceFragment page, String key, int min, int max,
                                    int value, String summary) {
        Preference row = page.findPreference(key);
        assertNotNull("少了拖动条 " + key, row);
        assertTrue(key + " 该是拖动条", row instanceof androidx.preference.SeekBarPreference);
        androidx.preference.SeekBarPreference bar = (androidx.preference.SeekBarPreference) row;
        assertEquals(key + " 的最小值", min, bar.getMin());
        assertEquals(key + " 的最大值", max, bar.getMax());
        assertEquals(key + " 现在的值", value, bar.getValue());
        assertEquals(key + " 的行尾", summary, String.valueOf(bar.getSummary()));
    }

    /**
     * 暂停那一行在总开关那一页上：回到警报就是这一页，所以恢复的办法不用跟着进下一层。
     * 跟哪一类无关的老规矩没变，只是三页收起来了。
     *
     * The paused row lives on the hub, which is the page the owner lands on; the old rule that it is
     * family-independent still holds, and the child pages do not repeat it.
     */
    @Test
    public void thePausedRowStaysOnTheHub() {
        SettingsPreferenceFragment paused = alertsPage(true,
                com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM, config -> config.setAlertsPaused(true));
        assertTrue("暂停时这一行该出现", shown(paused, "pref_alerts_paused"));

        SettingsPreferenceFragment off = alertsPage(false,
                com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM, config -> config.setAlertsPaused(true));
        assertFalse("总开关关着时不摆暂停那一行", shown(off, "pref_alerts_paused"));

        SettingsPreferenceFragment notifications = alertsChildPage(Shots.DAY_EN, true,
                com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM, "pref_alerts_notifications",
                CHILD_NOTIFICATIONS, R.string.set_alerts_page_notifications,
                config -> config.setAlertsPaused(true));
        assertNull("下一层没有这一行：它在总开关那一页", notifications.findPreference("pref_alerts_paused"));

        new AppConfig(RuntimeEnvironment.getApplication()).setAlertsPaused(false);
    }

    /** 车厢温度那一组的八行（开关自己不在里面）：这一组自己的开关关着时一起收起来。那一行实话不在里面 —— 它开关关着也要看得到。 */
    private static final String[] CLIMATE_ROWS = {
            "pref_alerts_climate_hot", "pref_alerts_climate_cold", "pref_alerts_climate_safety",
            "pref_alerts_climate_target", "pref_alerts_climate_seats",
            "pref_alerts_climate_seat_level", "pref_alerts_climate_minutes",
            "pref_alerts_climate_soc_floor"};

    /**
     * 车厢温度那一页：开关一直在，开着才有下面九行；总开关关着时整页都收起来（那一页也就进不去）。
     *
     * <p>行尾写着的必须是存着的那一档，不是默认值。</p>
     *
     * The climate page: the switch always shows, the nine rows follow it, and with the master switch off
     * the page folds away. The summaries must show the stored step, not the default.
     */
    @Test
    public void climateRowsFollowTheirOwnSwitch() {
        RuntimeEnvironment.setQualifiers(Shots.DAY_EN);
        SettingsPreferenceFragment climate = alertsChildPage(Shots.DAY_EN, true,
                com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM, "pref_alerts_climate_page",
                CHILD_CLIMATE, R.string.set_alerts_page_climate, config -> {
                    config.setAlertsClimateEnabled(true);
                    config.setAlertsClimateHotC(30);
                    config.setAlertsClimateColdC(5);
                    config.setAlertsClimateMinutes(10);
                });
        assertTrue("开关本身要在", shown(climate, "pref_alerts_climate"));
        for (String key : CLIMATE_ROWS) {
            assertTrue("这一组开着时该有 " + key, shown(climate, key));
        }
        Preference minutes = climate.findPreference("pref_alerts_climate_minutes");
        assertEquals("10", ((androidx.preference.ListPreference) minutes).getValue());
        Preference note = climate.findPreference("pref_alerts_climate_note");
        assertFalse("那一行只是说明，点不动", note.isSelectable());
        assertTrue("说明里要写开着几分钟: " + note.getSummary(),
                String.valueOf(note.getSummary()).contains("10"));

        // 这一组自己的开关关着：八行一起收起来，开关自己留着，那一行实话也留着（它说的是实话，不是选项）。
        SettingsPreferenceFragment climateOff = alertsChildPage(Shots.DAY_EN, true,
                com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM, "pref_alerts_climate_page",
                CHILD_CLIMATE, R.string.set_alerts_page_climate,
                config -> config.setAlertsClimateEnabled(false));
        assertTrue("开关本身要留着", shown(climateOff, "pref_alerts_climate"));
        for (String key : CLIMATE_ROWS) {
            assertFalse("这一组的开关关着时 " + key + " 该收起来", shown(climateOff, key));
        }
        assertTrue("那一行实话开关关着也要看得到", shown(climateOff, "pref_alerts_climate_note"));

        // 收尾：这些都在同一份设置里，别把后面的测试带跑。
        AppConfig config = new AppConfig(RuntimeEnvironment.getApplication());
        config.setAlertsClimateEnabled(false);
        config.setAlertsClimateMinutes(10);
    }

    /**
     * 「警报附一张照片」关掉之后，下面三行跟着收起来。
     *
     * <p>排版（一张四宫格 / 每路一张）、那几路、掰直 —— 不拍照的时候三行都没有意义，留着只会让人以为
     * 自己在设置里能改到已经不發生的事。片段那两行不动：它是聊天里点出来的，跟这条警报带不带图无关。
     * 开关本身还在，而且跟着存着的值。这些行在哨兵那一页上。</p>
     *
     * With "send a picture with each alert" off, the three rows below it collapse. The clip rows stay —
     * those are asked for from the chat. These rows live on the sentry page.
     */
    @Test
    public void thePictureSwitchCollapsesTheRowsBelowIt() {
        RuntimeEnvironment.setQualifiers(Shots.DAY_EN);
        SettingsPreferenceFragment on = alertsChildPage(Shots.DAY_EN, true,
                com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM, "pref_alerts_sentry",
                CHILD_SENTRY, R.string.set_alerts_page_sentry, config -> {
                    config.setAlertsPictureEnabled(true);
                    config.setAlertsLayout(com.kooo.evcam.alarm.media.AlertPicture.layoutValue(
                            com.kooo.evcam.alarm.media.AlertPicture.Layout.SEPARATE));
                    config.setAlertsClipSeconds(5);
                });
        assertTrue("开着时该看得见", shown(on, "pref_alerts_send_picture"));
        assertTrue("排版该在", shown(on, "pref_alerts_picture"));
        assertTrue("每路一张时那几路该在", shown(on, "pref_alerts_cameras"));
        assertTrue("掰直该在", shown(on, "pref_alerts_straighten"));

        SettingsPreferenceFragment off = alertsChildPage(Shots.DAY_EN, true,
                com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM, "pref_alerts_sentry",
                CHILD_SENTRY, R.string.set_alerts_page_sentry, config -> {
                    config.setAlertsPictureEnabled(false);
                    config.setAlertsClipSeconds(5);
                });
        androidx.preference.Preference switchRow = off.findPreference("pref_alerts_send_picture");
        assertNotNull("少了附图那一行", switchRow);
        assertTrue("开关本身要留着", switchRow.isVisible());
        assertFalse("存着关，开关就该是关的",
                ((androidx.preference.SwitchPreferenceCompat) switchRow).isChecked());
        assertFalse("不拍照就不该有排版", shown(off, "pref_alerts_picture"));
        assertFalse("不拍照就不该有那几路", shown(off, "pref_alerts_cameras"));
        assertFalse("不拍照就不该有掰直", shown(off, "pref_alerts_straighten"));
        assertTrue("片段是聊天里点的，跟这条不图无关", shown(off, "pref_alerts_clip"));

        // 收尾：这几项是存在同一份设置里的，别把后面的测试带跑。
        AppConfig config = new AppConfig(RuntimeEnvironment.getApplication());
        config.setAlertsPictureEnabled(true);
        config.setAlertsClipSeconds(0);
        config.setAlertsLayout(com.kooo.evcam.alarm.media.AlertPicture.layoutValue(
                com.kooo.evcam.alarm.media.AlertPicture.Layout.GRID));
    }

    /**
     * ntfy 的两行（在通知那一页上）：换话题谁都看得见，命令那一行要看三道门。
     *
     * <p>纯函数（{@code NtfyCommandsTest}）只说「该不该开」；这一条钉的是设置页有没有照着它
     * 画 —— 行在不在、点不点得动、那句原因在不在。三种状态各开一屏新的设置页来验：
     * 免费档 + 开发者选项关着、免费档 + 开着、付费档 + 开着且开关打开。</p>
     *
     * <p>第四种是 Telegram：换话题和命令两行都不该在，因为那一组整个不出现。</p>
     *
     * The two ntfy rows on the notifications page: changing the topic is for everyone, the command row
     * answers to three gates. The pure function only says whether it may be on; this pins that the
     * settings page draws it that way.
     */
    @Test
    public void ntfyRowsFollowTheThreeGates() {
        android.content.Context context = RuntimeEnvironment.getApplication();
        try {
            // 1. 免费档 + 开发者选项关着：换话题在，命令行不出现（那一行整个不给），公开话题那句在。
            //    Free plan, developer options closed: rotate is there, the command row is not, the note is.
            DeveloperMode.lock(context);
            SettingsPreferenceFragment free = ntfyPage(config -> {
                config.setAlertsNtfyPaid(false);
                config.setAlertsNtfyCommands(true);
            });
            assertTrue("换话题该看得见", shown(free, "pref_alerts_ntfy_rotate"));
            assertFalse("开发者选项关着，命令那一行不该出现",
                    shown(free, "pref_alerts_ntfy_commands"));
            assertFalse("命令那一行不在，那句公开话题的说明也跟着收起来",
                    shown(free, "pref_alerts_ntfy_commands_note"));

            // 2. 免费档 + 开发者选项开着：命令行出现、灰的，理由接在自己的说明后面。
            //    Free plan with developer options open: the row appears, disabled, with the reason appended.
            DeveloperMode.unlock(context, "6651");
            SettingsPreferenceFragment freeOpen = ntfyPage(config -> {
                config.setAlertsNtfyPaid(false);
                config.setAlertsNtfyCommands(true);
            });
            Preference row = freeOpen.findPreference("pref_alerts_ntfy_commands");
            assertNotNull("少了命令那一行", row);
            assertTrue("开发者选项开着，这一行该出现", row.isVisible());
            assertFalse("免费档上点不动", row.isEnabled());
            assertTrue("理由要写在行上", String.valueOf(row.getSummary())
                    .contains(freeOpen.getString(R.string.set_alerts_ntfy_commands_needs_paid)));
            assertTrue("免费档那句还在", shown(freeOpen, "pref_alerts_ntfy_commands_note"));

            // 3. 付费档 + 开发者选项开着：命令行能点、开关跟着存的值；那句解释收起来。
            //    Paid with developer options open: the row works and follows the stored value; the note goes.
            SettingsPreferenceFragment paid = ntfyPage(config -> {
                config.setAlertsNtfyPaid(true);
                config.setAlertsNtfyCommands(true);
            });
            Preference paidRow = paid.findPreference("pref_alerts_ntfy_commands");
            assertTrue("付费档上该能点", paidRow.isEnabled());
            assertTrue("开关要跟着存着的值", ((androidx.preference.SwitchPreferenceCompat) paidRow).isChecked());
            assertFalse("付费档不再需要那句解释", shown(paid, "pref_alerts_ntfy_commands_note"));

            // 4. Telegram：那一组整个不出现，两行都不在。
            //    Telegram: the whole block is gone, so neither row shows.
            DeveloperMode.lock(context);
            SettingsPreferenceFragment telegram = alertsChildPage(Shots.DAY_EN, true,
                    com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM, "pref_alerts_notifications",
                    CHILD_NOTIFICATIONS, R.string.set_alerts_page_notifications, config -> {
                    });
            assertFalse("走 Telegram 时换话题不该出现", shown(telegram, "pref_alerts_ntfy_rotate"));
            assertFalse("走 Telegram 时命令行不该出现",
                    shown(telegram, "pref_alerts_ntfy_commands"));
        } finally {
            DeveloperMode.lock(context);
        }
    }

    /** 走 ntfy 出口的通知那一页。The notifications page with the ntfy exit. */
    private SettingsPreferenceFragment ntfyPage(java.util.function.Consumer<AppConfig> tweak) {
        return alertsChildPage(Shots.DAY_EN, true, com.kooo.evcam.alarm.ntfy.NtfyGate.NTFY,
                "pref_alerts_notifications", CHILD_NOTIFICATIONS,
                R.string.set_alerts_page_notifications, tweak);
    }

    private ShotActivity shotActivity;

    /** 照着存好的状态开一屏新的「停车警报」。一条按存的这个状态绑出来的设置页。 */
    private SettingsPreferenceFragment alertsPage(boolean alertsOn, String transport) {
        return alertsPage(Shots.DAY_EN, alertsOn, transport, config -> {
        });
    }

    /** 同上，但在建页之前改一点设置（付费档、命令开关）。The same page, with one tweak before it is built. */
    private SettingsPreferenceFragment alertsPage(boolean alertsOn, String transport,
                                                  java.util.function.Consumer<AppConfig> tweak) {
        return alertsPage(Shots.DAY_EN, alertsOn, transport, tweak);
    }

    /**
     * 同上，语言也由调用方定：左栏和右边是同一屏，语言要在建页之前定下来，不然左栏和右边会各说各的。
     * The same page with the qualifiers given up front: the left rail and the detail render together.
     */
    private SettingsPreferenceFragment alertsPage(String qualifiers, boolean alertsOn, String transport,
                                                  java.util.function.Consumer<AppConfig> tweak) {
        AppConfig config = new AppConfig(RuntimeEnvironment.getApplication());
        config.setAlertsEnabled(alertsOn);
        config.setAlertsTransport(transport);
        config.setAlertsTelegramToken("123456789:AAHexampleTOKENvalue0123456789");
        config.setAlertsTelegramChatId("123456789");
        config.setAlertsQuietEnabled(true);
        tweak.accept(config);
        RuntimeEnvironment.setQualifiers(qualifiers);
        shotActivity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(shotActivity);
        SettingsShellFragment shell = new SettingsShellFragment();
        shotActivity.getSupportFragmentManager().beginTransaction()
                .replace(shotActivity.container, shell).commitNow();
        Shots.settle();
        shell.showSection("screen_alerts");
        Shots.settle();
        SettingsPreferenceFragment section = (SettingsPreferenceFragment) shell.getChildFragmentManager()
                .findFragmentById(R.id.settings_detail);
        assertNotNull("「停车警报」那一屏", section);
        return section;
    }

    /**
     * 停车警报下面的一页：走车主的路 —— 点总开关那一页上那一行，外壳把这个下层页换到右栏。
     *
     * <p>总开关关着时那一行收着、点不到，可是这一页也得验（行全收起来、页面进不去）：那种情况下
     * 直接把这棵子树交给外壳，走的是同一个 {@code withRoot}。</p>
     *
     * One of the alerts child pages, opened the way the owner opens it. With the master switch off the
     * row cannot be tapped, so the same withRoot subtree is handed to the shell directly.
     */
    private SettingsPreferenceFragment alertsChildPage(String qualifiers, boolean alertsOn,
                                                       String transport, String rowKey, String rootKey,
                                                       int titleRes,
                                                       java.util.function.Consumer<AppConfig> tweak) {
        SettingsPreferenceFragment hub = alertsPage(qualifiers, alertsOn, transport, tweak);
        Preference row = hub.findPreference(rowKey);
        assertNotNull("少了入口那一行 " + rowKey, row);
        SettingsShellFragment shell = (SettingsShellFragment) hub.getParentFragment();
        assertNotNull("那一页该在外壳里", shell);
        if (row.isVisible()) {
            assertNotNull(rowKey + " 该点得动", row.getOnPreferenceClickListener());
            row.getOnPreferenceClickListener().onPreferenceClick(row);
        } else {
            shell.openDetail(SettingsPreferenceFragment.withRoot(rootKey, titleRes),
                    hub.getString(titleRes));
        }
        Shots.settle();
        SettingsPreferenceFragment child = (SettingsPreferenceFragment) shell.getChildFragmentManager()
                .findFragmentById(R.id.settings_detail);
        assertNotNull("「" + hub.getString(titleRes) + "」那一页", child);
        assertTrue("该换成下一层那一页", child != hub);
        return child;
    }

    /** 这一行在组里的第几行。Where a row sits inside its group. */
    private static int indexIn(androidx.preference.PreferenceGroup group, String key) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            if (key.equals(group.getPreference(i).getKey())) {
                return i;
            }
        }
        return -1;
    }

    private static boolean shown(SettingsPreferenceFragment section, String key) {
        Preference row = section.findPreference(key);
        assertNotNull("少了 " + key, row);
        return row.isVisible();
    }

    /** 状态能点（ui-spec §十三）：从主界面带着「去哪一行」打开设置，停在那一行所在的分区；已经开着就在里面换过去。 */
    @Test
    public void openingAtARowLandsOnItsSection() {
        RuntimeEnvironment.setQualifiers(Shots.DAY);
        ShotActivity activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(activity);
        SettingsShellFragment shell = SettingsShellFragment.at("screen_storage", "pref_storage_location", null);
        activity.getSupportFragmentManager().beginTransaction().replace(activity.container, shell).commitNow();
        Shots.settle();
        assertEquals("screen_storage", shell.currentSection());

        shell.showRow("screen_recording", "pref_record_quality", null);
        Shots.settle();
        assertEquals("screen_recording", shell.currentSection());
    }

    private ShotActivity shoot(String qualifiers, String tag) {
        RuntimeEnvironment.setQualifiers(qualifiers);
        ShotActivity activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(activity);
        SettingsShellFragment shell = new SettingsShellFragment();
        activity.getSupportFragmentManager().beginTransaction().replace(activity.container, shell).commitNow();
        Shots.settle();
        for (String section : SECTIONS) {
            shell.showSection(section);
            File file = Shots.save(activity, "settings-" + section.substring("screen_".length()) + "-" + tag);
            assertTrue(file + " 是空的", file.length() > 10_000);
        }
        return activity;
    }
}

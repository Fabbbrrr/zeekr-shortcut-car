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

import android.app.Dialog;

import androidx.preference.Preference;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.screens.ShotActivity;
import com.kooo.evcam.screens.Shots;

import org.junit.AfterClass;
import org.junit.BeforeClass;
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

    /**
     * 截图测试不联网：这一套要的是排布和文字，不是 GitHub 此刻发到哪一版。
     *
     * <p>「更新与关于」那一页打开就会在后台查两个仓库（{@code LatestVersions}）。真去问的话，
     * 没网时每个仓库各等一个 10 秒的连接超时，把整套测试拖成几分钟。关掉之后那一行只显示存着
     * 的数字（测试自己先存好），与「查不到」那一句。</p>
     */
    @BeforeClass
    public static void noNetwork() {
        com.kooo.evcam.update.LatestVersions.setNetworkAllowed(false);
    }

    @AfterClass
    public static void restoreNetwork() {
        com.kooo.evcam.update.LatestVersions.setNetworkAllowed(true);
    }

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
        ShotActivity activity = newShotActivity();
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
        ShotActivity activity = newShotActivity();
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
        ShotActivity activity = newShotActivity();
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
            ShotActivity activity = newShotActivity();
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
     * 「忘了开哨兵」那一对在总开关那一页上（4.17 从哨兵那一页提上来），排在总开关和那行要求之后、出口之前。
     *
     * <p>为什么值得钉：它问的是「这一栏到底有没有在看」，和总开关是同一个问题；埋在哨兵那一页时，车主开着
     * 总开关、以为车在看，其实哨兵关着 —— 一切都收不到。它还得真的把「哪一档」写出来，并且每一档旁边都写着
     * 「要有哨兵模式」，而不是只把下拉的选项原样当行尾。</p>
     *
     * <p>三种状态各开一屏新的页：总开关开着（在、位置对、行尾读档）、换成 ntfy（收起）、总开关关着（收起）。</p>
     *
     * The Forgot Sentry pair sits on the hub in 4.17, right under the master switch and the requirement line, above
     * the exit. It answers the master switch's own question, and both rows are gone from the Sentry page.
     */
    @Test
    public void forgotSentryRowsSitUnderTheMasterSwitch() {
        RuntimeEnvironment.setQualifiers(Shots.DAY_EN);
        SettingsPreferenceFragment hub = alertsPage(true, com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM);
        androidx.preference.PreferenceGroup screen = hub.getPreferenceScreen();

        // 总开关后面第一行是要求，然后才是忘了开哨兵那一对。
        // The requirement line comes first, then the pair.
        assertEquals("第一行还是总开关", "pref_alerts", screen.getPreference(0).getKey());
        assertEquals("总开关后面第一行是那行要求", "pref_alerts_required_note",
                screen.getPreference(1).getKey());
        Preference note = hub.findPreference("pref_alerts_required_note");
        assertNotNull("少了那行要求", note);
        assertFalse("那行只是说明，点不动", note.isSelectable());
        assertEquals("说明写的就是「要有哨兵模式」的那段话",
                hub.getString(R.string.set_alerts_required_note_summary),
                String.valueOf(note.getSummary()));
        assertEquals("说明的标题", hub.getString(R.string.set_alerts_required_note_title),
                String.valueOf(note.getTitle()));

        int noteAt = indexIn(screen, "pref_alerts_required_note");
        int guardAt = indexIn(screen, "pref_alerts_guard");
        int everywhereAt = indexIn(screen, "pref_alerts_guard_everywhere");
        int transportAt = indexIn(screen, "pref_alerts_transport");
        assertTrue("要求那一行该在忘了开哨兵之前", noteAt >= 0 && noteAt < guardAt);
        assertTrue("忘了开哨兵该排在「警报发到哪儿」前面: " + guardAt + " / " + transportAt,
                guardAt > 0 && guardAt < transportAt);
        assertEquals("「在家也开哨兵」紧跟着它，两行不拆开", guardAt + 1, everywhereAt);
        assertTrue("这一对在总开关那一屏上", hub.findPreference("pref_alerts_guard").getParent() == screen);
        assertTrue(shown(hub, "pref_alerts_guard"));
        assertTrue(shown(hub, "pref_alerts_guard_everywhere"));

        // 行尾 = 那一档 · 「要有哨兵模式」。只写一档就是没写要求，只写要求就是没写现在选的是哪一档。
        String summary = String.valueOf(hub.findPreference("pref_alerts_guard").getSummary());
        assertTrue("行尾要先写现在哪一档: " + summary,
                summary.contains(hub.getString(R.string.set_alerts_guard_mode_off)));
        assertTrue("行尾要写「要有哨兵模式」: " + summary,
                summary.contains(hub.getString(R.string.set_alerts_required_hint)));

        // 换一档：行尾跟着换（下拉的值存进了 AppConfig，行尾不是永远写着默认那档）。
        // Change the mode: the summary follows the stored mode.
        SettingsPreferenceFragment ask = alertsPage(true, com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM,
                config -> config.setAlertsGuardMode(
                        com.kooo.evcam.alarm.guard.GuardPolicy.Mode.ASK));
        String askSummary = String.valueOf(ask.findPreference("pref_alerts_guard").getSummary());
        assertTrue("换到「先问我」，行尾要写这一档: " + askSummary,
                askSummary.contains(ask.getString(R.string.set_alerts_guard_mode_ask)));
        assertFalse("换了一档就不许还写着「关」: " + askSummary,
                askSummary.contains(ask.getString(R.string.set_alerts_guard_mode_off)));

        // 哨兵那一页上再也没有这一对：一行只在一页上。
        // The pair is gone from the Sentry page: nothing may appear on two pages.
        SettingsPreferenceFragment sentry = alertsChildPage(Shots.DAY_EN, true,
                com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM, "pref_alerts_sentry",
                CHILD_SENTRY, R.string.set_alerts_page_sentry, config -> {
                });
        assertNull("忘了开哨兵那一行搬到总开关那一页了", sentry.findPreference("pref_alerts_guard"));
        assertNull("「在家也开哨兵」也跟着走了", sentry.findPreference("pref_alerts_guard_everywhere"));
        assertNull("家的位置在通知那一页", sentry.findPreference("pref_alerts_guard_home"));

        // 换成 ntfy：这一对收起来（按钮是 callback_query，ntfy 没有回程），通知那一页照旧摆 ntfy 块。
        // On ntfy the pair folds away, and the ntfy block still shows on its own page.
        SettingsPreferenceFragment ntfy = alertsPage(true, com.kooo.evcam.alarm.ntfy.NtfyGate.NTFY);
        assertFalse("ntfy 时忘了开哨兵该收起来", shown(ntfy, "pref_alerts_guard"));
        assertFalse("ntfy 时「在家也开哨兵」也该收起来", shown(ntfy, "pref_alerts_guard_everywhere"));
        assertTrue("要求那一行不跟着收起：它说的是前提", shown(ntfy, "pref_alerts_required_note"));

        // 总开关关着：这一对也收起，那行要求留着（正是该读它的时候）。
        // Master off: the pair folds away and the requirement line stays.
        SettingsPreferenceFragment off = alertsPage(false, com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM);
        assertFalse("总开关关着时忘了开哨兵收起", shown(off, "pref_alerts_guard"));
        assertTrue("总开关关着时那行要求还在", shown(off, "pref_alerts_required_note"));

        // 收尾：这些都在同一份设置里，别把后面的测试带跑。
        new AppConfig(RuntimeEnvironment.getApplication()).setAlertsGuardMode(
                com.kooo.evcam.alarm.guard.GuardPolicy.Mode.OFF);
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

    /**
     * 家的位置只在真的读得到它的时候出现（业主 2026-11-19）：两个用处，一个都不用时收起。
     *
     * <p>家的坐标一共只用两处：「在家不发消息」那一条排除（开关关着才用），和「忘了开哨兵」
     * （不是「关」才用 —— 在家问不问是它自己的规则）。两样都不用的时候还摆着一行「把这里记成家」，
     * 等于叫车主去记一个没有任何一行会读的点。藏着的时候，这一页上也不许有别的行提着一个家。</p>
     *
     * <p>三种组合都验：在家也发通知开着 + 忘了开哨兵关着 → 收起；后者不是关着 → 在；前者关着 → 在。
     * 位置钉在「在家也发通知」下一行，行尾写记下没记下。</p>
     *
     * The home row appears exactly while something reads it: the at-home notification exclusion (only while
     * that switch is off) or the Forgot Sentry mode (any mode but off). With neither in use it folds away.
     */
    @Test
    public void theHomePointRowShowsOnlyWhileSomethingReadsIt() {
        RuntimeEnvironment.setQualifiers(Shots.DAY_EN);

        // 两样都不用家：收起。Neither use: hidden.
        SettingsPreferenceFragment unused = notificationsPage(config -> {
            config.setAlertsNotifyAtHome(true);
            config.setAlertsGuardMode(com.kooo.evcam.alarm.guard.GuardPolicy.Mode.OFF);
        });
        assertFalse("在家也发通知开着、忘了开哨兵关着：这一行收起",
                shown(unused, "pref_alerts_guard_home"));

        // 只有「忘了开哨兵」在用：在。The Sentry mode alone needs it.
        SettingsPreferenceFragment guardOn = notificationsPage(config -> {
            config.setAlertsNotifyAtHome(true);
            config.setAlertsGuardMode(com.kooo.evcam.alarm.guard.GuardPolicy.Mode.ASK);
        });
        assertTrue("忘了开哨兵不是关着：这一行要在", shown(guardOn, "pref_alerts_guard_home"));

        // 只有「在家不发消息」在用：在。而且紧跟在「在家也发通知」后面，行尾写家的位置记下没记下。
        // Only the at-home exclusion: visible, right under the switch, with its distance-free summary.
        SettingsPreferenceFragment atHomeOff = notificationsPage(config -> {
            config.setAlertsNotifyAtHome(false);
            config.setAlertsGuardMode(com.kooo.evcam.alarm.guard.GuardPolicy.Mode.OFF);
        });
        assertTrue("在家不发消息要用家：这一行要在", shown(atHomeOff, "pref_alerts_guard_home"));
        androidx.preference.PreferenceGroup page = atHomeOff.getPreferenceScreen();
        assertEquals("家的位置紧跟在「在家也发通知」后面",
                indexIn(page, "pref_alerts_notify_at_home") + 1,
                indexIn(page, "pref_alerts_guard_home"));
        Preference home = atHomeOff.findPreference("pref_alerts_guard_home");
        assertNotNull("那一行得接上线", home.getOnPreferenceClickListener());
        assertEquals("没记过家就写家的位置忘掉了",
                atHomeOff.getString(R.string.set_alerts_guard_home_none),
                String.valueOf(home.getSummary()));

        // 记下一个假的家：行尾换成「记下了」，仍然不提距离（数在 alarm.GuardHome 一处）。
        // Record a fake home: the summary says so, and it still says no distance.
        new AppConfig(RuntimeEnvironment.getApplication()).setAlertsGuardHome(31.2304f, 121.4737f);
        SettingsPreferenceFragment recorded = notificationsPage(config -> {
            config.setAlertsNotifyAtHome(false);
            config.setAlertsGuardMode(com.kooo.evcam.alarm.guard.GuardPolicy.Mode.OFF);
        });
        assertEquals("记过家就写记下了",
                recorded.getString(R.string.set_alerts_guard_home_set),
                String.valueOf(recorded.findPreference("pref_alerts_guard_home").getSummary()));

        // 一行只在一页上：它挂在通知那一页上，不在总开关那一页、不在哨兵那一页。
        // One page only: it hangs off the Notifications page, not the hub or the Sentry page.
        SettingsPreferenceFragment hub = alertsPage(true, com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM);
        assertTrue("家的位置挂在通知那一页上",
                hub.findPreference("pref_alerts_guard_home").getParent()
                        == hub.findPreference(CHILD_NOTIFICATIONS));

        // 收尾：这些都在同一份设置里，别把后面的测试带跑。
        AppConfig config = new AppConfig(RuntimeEnvironment.getApplication());
        config.clearAlertsGuardHome();
        config.setAlertsNotifyAtHome(false);
        config.setAlertsGuardMode(com.kooo.evcam.alarm.guard.GuardPolicy.Mode.OFF);
    }

    /**
     * 两条出口各一行向导，排在凭据那几行上面（业主 2026-11-19）：第一行是「怎么开始配」，标题按配好了没有换。
     *
     * <p>「配好了没有」问的是真出口那一份判断（令牌形状 + 会话号；话题形状 + 免费方案提醒接受过），
     * 不是「框里有没有字」—— 只看框的话，换过话题之后标题会写着已配好，而每一条都被出口拒绝掉。</p>
     *
     * <p>点一下进的就是原来那个向导（{@code TelegramSetupDialog} / {@code NtfySetupDialog}）：这里把「点那一行」
     * 弹出来的框和「直接调那个向导」弹出来的框比一遍，字和按钮都一样才算通过。</p>
     *
     * One wizard row per exit, above the credential rows, with the title following the real readiness. Both
     * launch the existing wizards, checked by comparing the dialog the row opens with the one the wizard's own
     * entry point opens.
     */
    @Test
    public void eachExitHasItsOwnSetupRow() {
        RuntimeEnvironment.setQualifiers(Shots.DAY_EN);

        // ---- Telegram：没配过时写「设置 Telegram」，在令牌 / 会话号 / 找会话号上面。
        // Telegram with nothing filled in: "Set up Telegram", above the credential rows.
        SettingsPreferenceFragment empty = notificationsPage(config -> {
            config.setAlertsTelegramToken("");
            config.setAlertsTelegramChatId("");
        });
        Preference setupRow = empty.findPreference("pref_alerts_telegram_setup");
        assertNotNull("少了 Telegram 向导那一行", setupRow);
        assertTrue("走 Telegram 时这一行要在", setupRow.isVisible());
        assertEquals("没配过：就写「设置 Telegram」",
                empty.getString(R.string.set_alerts_telegram_setup_title),
                String.valueOf(setupRow.getTitle()));
        androidx.preference.PreferenceGroup telegramGroup =
                (androidx.preference.PreferenceGroup) empty.findPreference("cat_alerts_telegram");
        assertNotNull("少了 Telegram 那一组", telegramGroup);
        for (String below : new String[]{"pref_alerts_token", "pref_alerts_chat",
                "pref_alerts_find_chat"}) {
            assertTrue("向导那一行该在 " + below + " 上面",
                    indexIn(telegramGroup, "pref_alerts_telegram_setup") < indexIn(telegramGroup, below));
        }
        assertNotNull("那一行得接上线", setupRow.getOnPreferenceClickListener());

        // 配好了（令牌像令牌、会话号有）：标题换成「已配好 —— 再走一遍」。
        // Configured: the title says it is set up and offers to run it again.
        SettingsPreferenceFragment configured = notificationsPage(config -> {
            config.setAlertsTelegramToken("123456789:AAHexampleTOKENvalue0123456789");
            config.setAlertsTelegramChatId("123456789");
        });
        Preference configuredRow = configured.findPreference("pref_alerts_telegram_setup");
        assertEquals("配好了就写「已配好 —— 再走一遍」",
                configured.getString(R.string.set_alerts_telegram_setup_again_title),
                String.valueOf(configuredRow.getTitle()));

        // 点这一行弹出来的框，和直接调 TelegramSetupDialog 弹出来的是同一屏。
        // The dialog the row opens is the one TelegramSetupDialog opens.
        configuredRow.performClick();
        Shots.settle();
        Dialog viaRow = latestDialog();
        String fromRow = dialogWords(viaRow);
        String rowNext = dialogButton(viaRow);
        viaRow.dismiss();
        TelegramSetupDialog.show(configured,
                new AppConfig(RuntimeEnvironment.getApplication()), () -> { });
        Shots.settle();
        Dialog direct = latestDialog();
        assertEquals("点那一行进的就是那个向导（同一屏的字）", dialogWords(direct), fromRow);
        assertEquals("同一个向导的第一步（同一颗按钮）", dialogButton(direct), rowNext);
        assertTrue("那一屏就是令牌那一步: " + fromRow,
                fromRow.contains(configured.getString(R.string.set_alerts_wizard_bot_title)));
        direct.dismiss();

        // ---- ntfy：同样一行，同样换标题，同样进原来那个向导。
        // ntfy: the same row, the same title swap, the same existing wizard.
        String topic = "zeekrsc_kymoy9m40cuQYXmHabcdefghijklmnopqrstu";
        SettingsPreferenceFragment ntfyEmpty = ntfyPage(config -> {
            config.setAlertsNtfyTopic("");
            config.setAlertsNtfyRiskAck("");
        });
        Preference ntfySetupRow = ntfyEmpty.findPreference("pref_alerts_ntfy_setup");
        assertNotNull("少了 ntfy 向导那一行", ntfySetupRow);
        assertTrue("走 ntfy 时这一行要在", ntfySetupRow.isVisible());
        assertEquals("没配过：就写「设置 ntfy」",
                ntfyEmpty.getString(R.string.set_alerts_ntfy_setup_title),
                String.valueOf(ntfySetupRow.getTitle()));
        assertTrue("ntfy 向导那一行在话题那一行上面",
                indexIn((androidx.preference.PreferenceGroup) ntfyEmpty.findPreference("cat_alerts_ntfy"),
                        "pref_alerts_ntfy_setup")
                        < indexIn((androidx.preference.PreferenceGroup) ntfyEmpty.findPreference("cat_alerts_ntfy"),
                        "pref_alerts_ntfy_topic"));

        // 话题有了、提醒也接受过：才算配好了（只填了话题不算）。
        // A topic with the warning accepted is what "set up" means here; a topic alone is not.
        SettingsPreferenceFragment ntfyHalf = ntfyPage(config -> {
            config.setAlertsNtfyTopic(topic);
            config.setAlertsNtfyRiskAck("");
        });
        assertEquals("话题填了、提醒没接受，还不算配好",
                ntfyHalf.getString(R.string.set_alerts_ntfy_setup_title),
                String.valueOf(ntfyHalf.findPreference("pref_alerts_ntfy_setup").getTitle()));

        SettingsPreferenceFragment ntfyReady = ntfyPage(config -> {
            config.setAlertsNtfyTopic(topic);
            config.setAlertsNtfyRiskAck(com.kooo.evcam.alarm.ntfy.NtfyRisk.ackFor(topic));
        });
        Preference ntfyReadyRow = ntfyReady.findPreference("pref_alerts_ntfy_setup");
        assertEquals("配好了就写「已配好 —— 再走一遍」",
                ntfyReady.getString(R.string.set_alerts_ntfy_setup_again_title),
                String.valueOf(ntfyReadyRow.getTitle()));

        // 配好了的那条路回头走一遍是先念风险 -> 不，是先看代码那一步；这里比的是「同一个向导」。
        // With a topic already accepted the wizard opens its code step, so compare the row's dialog with the
        // one the wizard's own entry point opens in the same state.
        ntfyReadyRow.performClick();
        Shots.settle();
        Dialog ntfyViaRow = latestDialog();
        // 那一屏上有一个随机四位数（给隔壁手机填的），两份不可能一样：比字之前先把它抹掉。
        // That screen carries a random four-digit code, so the code is blanked before the words are compared.
        String ntfyFromRow = dialogWords(ntfyViaRow).replaceAll("\\d{4}", "####");
        String ntfyPositive = dialogButton(ntfyViaRow);
        assertTrue("那一屏就是「收到一条通知了吗」那一步: " + ntfyFromRow,
                ntfyFromRow.contains(ntfyReady.getString(R.string.set_alerts_ntfy_code_title)));
        ntfyViaRow.dismiss();
        NtfySetupDialog.show(ntfyReady, new AppConfig(RuntimeEnvironment.getApplication()), () -> { });
        Shots.settle();
        Dialog ntfyDirect = latestDialog();
        assertEquals("点那一行进的就是那个向导（同一屏的字）",
                dialogWords(ntfyDirect).replaceAll("\\d{4}", "####"), ntfyFromRow);
        assertEquals("同一个向导的那一屏（同一颗按钮）", dialogButton(ntfyDirect), ntfyPositive);
        ntfyDirect.dismiss();

        // 走 Telegram 时 ntfy 那一行不出现（它跟那一组一起收着）。
        // On the Telegram exit the ntfy row is not on screen: it goes with its group.
        assertFalse("走 Telegram 时 ntfy 向导那一行不该出现",
                shown(configured, "pref_alerts_ntfy_setup"));

        // 收尾：这些都在同一份设置里，别把后面的测试带跑。
        AppConfig config = new AppConfig(RuntimeEnvironment.getApplication());
        config.setAlertsNtfyTopic("");
        config.setAlertsNtfyRiskAck("");
    }

    /** 通知那一页（走 Telegram），改一点设置再开。The Notifications page with the Telegram exit. */
    private SettingsPreferenceFragment notificationsPage(java.util.function.Consumer<AppConfig> tweak) {
        return alertsChildPage(Shots.DAY_EN, true, com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM,
                "pref_alerts_notifications", CHILD_NOTIFICATIONS,
                R.string.set_alerts_page_notifications, tweak);
    }

    private static Dialog latestDialog() {
        Dialog dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull("这一步没有弹出对话框", dialog);
        assertTrue("这一步的对话框关着", dialog.isShowing());
        return dialog;
    }

    /** 框里所有文字接起来：是同一个框，字就得一样。All the dialog's words in one string. */
    private static String dialogWords(Dialog dialog) {
        StringBuilder all = new StringBuilder();
        collectDialogTexts(dialog.getWindow().getDecorView(), all);
        return all.toString();
    }

    private static void collectDialogTexts(android.view.View root, StringBuilder all) {
        if (root instanceof android.widget.TextView) {
            if (all.length() > 0) {
                all.append(' ');
            }
            all.append(((android.widget.TextView) root).getText());
        }
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectDialogTexts(group.getChildAt(i), all);
            }
        }
    }

    /** 框上那颗「下一步 / 收到」按钮的字。The dialog's positive button text. */
    private static String dialogButton(Dialog dialog) {
        assertTrue("这个框该是 AlertDialog: " + dialog, dialog instanceof androidx.appcompat.app.AlertDialog);
        android.widget.Button button =
                ((androidx.appcompat.app.AlertDialog) dialog).getButton(
                        android.content.DialogInterface.BUTTON_POSITIVE);
        assertNotNull("这个框没有那颗按钮", button);
        return String.valueOf(button.getText());
    }

    /** 停车警报下面那三页的 key（和 PreferencesFragment 里的常量一致）。 */
    private static final String CHILD_NOTIFICATIONS = "screen_alerts_notifications";
    private static final String CHILD_SENTRY = "screen_alerts_sentry";
    private static final String CHILD_CLIMATE = "screen_alerts_climate";

    /** 总开关那一页上的三行入口。 */
    private static final String[] HUB_ROWS =
            {"pref_alerts_notifications", "pref_alerts_sentry", "pref_alerts_climate_page"};

    /**
     * 总开关那一页：开关、出口、三行入口，别的什么都没有（4.6，ui-spec §十六）。
     *
     * <p>三块内容各在下一层的一页上：那三页本身在这一屏上是收着的，所以一屏里不会同时摆三套互不相干的东西。
     * 行尾写的是现在的情形，不是固定的一句话。</p>
     *
     * <p>4.18 起没有那行单独的状态行（{@code pref_alerts_channel_status}）：出口的选择就写在出口那一行的行尾上。</p>
     *
     * The hub: master switch, exit, then three entry rows and nothing else. The separate status row is
     * gone since 4.18: the exit states itself on its own row.
     */
    @Test
    public void theHubListsThreePagesAndNothingElse() {
        RuntimeEnvironment.setQualifiers(Shots.DAY_EN);
        SettingsPreferenceFragment hub = alertsPage(true, com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM);
        androidx.preference.PreferenceGroup screen = hub.getPreferenceScreen();
        String[] rows = {"pref_alerts_notifications", "pref_alerts_sentry", "pref_alerts_climate_page"};
        int last = indexIn(screen, "pref_alerts_transport");
        assertTrue("出口该在这一页上", last >= 0);
        assertNull("4.18 起没有那行单独的状态行", hub.findPreference("pref_alerts_channel_status"));
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
        assertTrue("车厢温度那一行该写现在的目标温度，一位小数: " + climate,
                climate.contains(tenths(now.getAlertsClimateTargetC())));
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

        // 总开关关着：只剩开关、那行要求、出口和暂停那一行，三行入口一起收起来。
        // Master off: only the first rows are left; the three entry rows fold away.
        SettingsPreferenceFragment off = alertsPage(false,
                com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM, config -> config.setAlertsPaused(true));
        for (String key : new String[]{"pref_alerts", "pref_alerts_required_note",
                "pref_alerts_transport"}) {
            assertTrue("总开关关着时 " + key + " 还该在", shown(off, key));
        }
        assertFalse("总开关关着时不摆暂停那一行（老规矩）", shown(off, "pref_alerts_paused"));
        for (String key : rows) {
            assertFalse("总开关关着时 " + key + " 该收起来", shown(off, key));
        }
    }

    /**
     * 「警报发到哪儿」的行尾写现在真的发到哪儿：Telegram / ntfy 公开话题 / ntfy 付费档。
     *
     * <p>4.18 拿掉了那行单独的状态行，选择就写在这一行上；和 hub 上那道行尾、发送那一头是同一份判断
     * （{@code AppConfig.isAlertsNtfy / isAlertsNtfyPaid}），所以行尾不能还是一个固定句子。</p>
     *
     * The transport row's summary states the exit in use: Telegram, ntfy free, or ntfy paid. The separate
     * status row is gone, so this row has to carry it — and it must read the same source the sender does.
     */
    @Test
    public void theTransportRowSaysWhereAlertsGo() {
        RuntimeEnvironment.setQualifiers(Shots.DAY_EN);
        SettingsPreferenceFragment page = alertsPage(true, com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM);
        Preference transport = page.findPreference("pref_alerts_transport");
        assertNotNull("少了出口那一行", transport);
        AppConfig config = new AppConfig(RuntimeEnvironment.getApplication());

        assertEquals("选的是 Telegram 就写 Telegram",
                page.getString(R.string.set_alerts_transport_now_telegram),
                String.valueOf(transport.getSummary()));

        // 行尾是当场读设置的（SummaryProvider），所以换一下存着的值就能看到另两句。
        // The summary is read live, so changing the stored value shows the other two sentences.
        config.setAlertsTransport(com.kooo.evcam.alarm.ntfy.NtfyGate.NTFY);
        config.setAlertsNtfyPaid(false);
        assertEquals("ntfy 免费档写公开话题",
                page.getString(R.string.set_alerts_transport_now_ntfy_public),
                String.valueOf(transport.getSummary()));

        config.setAlertsNtfyPaid(true);
        assertEquals("ntfy 付费档要写付费档",
                page.getString(R.string.set_alerts_transport_now_ntfy_paid),
                String.valueOf(transport.getSummary()));

        // 收尾：档位存在同一份设置里，别把后面的测试带跑。
        config.setAlertsNtfyPaid(false);
    }

    /**
     * 三页的末行「回到警报」真的退回去：返回栈空了、总开关那一页回来了、标题区也跟着退。
     *
     * <p>它点的是外壳的返回栈（{@code getParentFragmentManager().popBackStack()}），和标题区那颗返回键
     * 走同一条路，所以两者不会各退一层。行尾写的是从哪一页回去，所以三页共用一行也不会说错话。</p>
     *
     * The back row on each page pops the shell's back stack: the stack empties, the hub comes back, and the
     * shell's title follows (it already listens for back-stack changes).
     */
    @Test
    public void theBackRowPopsTheChildPageBackToTheHub() {
        RuntimeEnvironment.setQualifiers(Shots.DAY_EN);
        SettingsPreferenceFragment hub = alertsPage(true, com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM);
        SettingsShellFragment shell = (SettingsShellFragment) hub.getParentFragment();
        assertNotNull("那一页该在外壳里", shell);
        String[] rows = {"pref_alerts_notifications", "pref_alerts_sentry", "pref_alerts_climate_page"};
        int[] from = {R.string.set_alerts_back_from_notifications,
                R.string.set_alerts_back_from_sentry, R.string.set_alerts_back_from_climate};
        for (int i = 0; i < rows.length; i++) {
            Preference entry = hub.findPreference(rows[i]);
            assertNotNull("少了入口那一行 " + rows[i], entry);
            entry.getOnPreferenceClickListener().onPreferenceClick(entry);
            Shots.settle();
            assertEquals(rows[i] + " 该进返回栈", 1,
                    shell.getChildFragmentManager().getBackStackEntryCount());
            SettingsPreferenceFragment child = (SettingsPreferenceFragment) shell.getChildFragmentManager()
                    .findFragmentById(R.id.settings_detail);
            assertNotNull(child);
            Preference back = child.findPreference("pref_alerts_back");
            assertNotNull(rows[i] + " 少了末行「回到警报」", back);
            assertEquals("行尾要写从哪一页回去", child.getString(from[i]),
                    String.valueOf(back.getSummary()));

            assertEquals(Boolean.TRUE, back.getOnPreferenceClickListener().onPreferenceClick(back));
            Shots.settle();

            assertEquals("点了「回到警报」就该退回总开关那一页", 0,
                    shell.getChildFragmentManager().getBackStackEntryCount());
            SettingsPreferenceFragment now = (SettingsPreferenceFragment) shell.getChildFragmentManager()
                    .findFragmentById(R.id.settings_detail);
            assertNotNull("该回到总开关那一页", now);
            assertNotNull("总开关那一页的行都在", now.findPreference("pref_alerts_notifications"));
            android.widget.TextView title = shotActivity.findViewById(R.id.settings_title);
            assertNotNull("外壳的标题区该在", title);
            assertEquals("标题区跟着退回分区名",
                    now.getString(R.string.set_section_alerts), String.valueOf(title.getText()));
        }
    }

    /**
     * 通知那一页上关于命令的都在一组里（4.18）：排在两条出口后面、「检查」前面。
     *
     * <p>组里三样：「回应命令」（Telegram 那一份）、「警报里的按钮」（两条路都有，默认开）、
     * 以及 ntfy 的命令那两行（开发者选项 + 付费档才在）。</p>
     *
     * One commands group after both exits: the Telegram command switch, the buttons switch (default on,
     * both exits), and the ntfy rows where those apply.
     */
    @Test
    public void theCommandsGroupHoldsEveryCommandRow() {
        RuntimeEnvironment.setQualifiers(Shots.DAY_EN);
        android.content.Context context = RuntimeEnvironment.getApplication();
        try {
            DeveloperMode.unlock(context, "6651");
            SettingsPreferenceFragment page = ntfyPage(config -> {
                config.setAlertsNtfyPaid(true);
                config.setAlertsNtfyCommands(true);
            });
            androidx.preference.PreferenceGroup screen = page.getPreferenceScreen();
            assertTrue("命令那一组该看得见", shown(page, "cat_alerts_commands"));
            assertTrue("命令那一组排在两条出口后面",
                    indexIn(screen, "cat_alerts_commands") > indexIn(screen, "cat_alerts_ntfy"));
            assertTrue("命令那一组排在「检查」前面",
                    indexIn(screen, "cat_alerts_commands") < indexIn(screen, "cat_alerts_checks"));
            androidx.preference.PreferenceGroup group = page.findPreference("cat_alerts_commands");
            assertNotNull("少了命令那一组", group);
            assertTrue("ntfy 的命令那一行该在这一组里",
                    indexIn(group, "pref_alerts_ntfy_commands") >= 0);
            assertTrue("那条解释也在这一组里",
                    indexIn(group, "pref_alerts_ntfy_commands_note") >= 0);
            androidx.preference.SwitchPreferenceCompat buttons = page.findPreference("pref_alerts_buttons");
            assertNotNull("少了「警报里的按钮」那一行", buttons);
            assertTrue("它在命令这一组里", indexIn(group, "pref_alerts_buttons") >= 0);
            assertTrue("它该看得见", shown(page, "pref_alerts_buttons"));
            assertTrue("默认开", buttons.isChecked());
            assertFalse("走 ntfy 时 Telegram 那一份命令开关不出现",
                    shown(page, "pref_alerts_commands"));
        } finally {
            DeveloperMode.lock(context);
        }

        // Telegram：组里是「回应命令」+「警报里的按钮」，ntfy 那两行不在。
        // Telegram: the group holds the command switch and the buttons switch; the ntfy rows are gone.
        SettingsPreferenceFragment telegram = alertsChildPage(Shots.DAY_EN, true,
                com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM, "pref_alerts_notifications",
                CHILD_NOTIFICATIONS, R.string.set_alerts_page_notifications, config -> {
                });
        androidx.preference.PreferenceGroup telegramGroup =
                telegram.findPreference("cat_alerts_commands");
        assertNotNull("走 Telegram 时命令那一组也得在", telegramGroup);
        assertTrue("Telegram 的命令开关该在这一组里",
                indexIn(telegramGroup, "pref_alerts_commands") >= 0);
        assertTrue("按钮那一行两条路都在", indexIn(telegramGroup, "pref_alerts_buttons") >= 0);
        assertFalse("走 Telegram 时 ntfy 的命令行不该出现",
                shown(telegram, "pref_alerts_ntfy_commands"));

        // 收尾：这几项存在同一份设置里。
        AppConfig config = new AppConfig(RuntimeEnvironment.getApplication());
        config.setAlertsNtfyPaid(false);
        config.setAlertsNtfyCommands(false);
    }

    /**
     * 数据量说明在哨兵页的最上面，而且不跟着「发不发短片」收起（4.18 从短视频那一组提到页首）。
     *
     * <p>它说的是这一页发出去的所有东西一共多贵，不只是那段短片 —— 短片关着、图也关着的时候，它照样在。</p>
     *
     * The data note sits at the top of the Sentry page and survives the clip and picture switches: it prices
     * everything that page sends, not just the clip.
     */
    @Test
    public void theDataNoteStaysAtTheTopOfTheSentryPage() {
        RuntimeEnvironment.setQualifiers(Shots.DAY_EN);
        SettingsPreferenceFragment sentry = alertsChildPage(Shots.DAY_EN, true,
                com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM, "pref_alerts_sentry",
                CHILD_SENTRY, R.string.set_alerts_page_sentry, config -> {
                    config.setAlertsClipSeconds(0);
                    config.setAlertsPictureEnabled(false);
                });
        Preference note = sentry.findPreference("pref_alerts_clip_data");
        assertNotNull("少了数据量说明那一行", note);
        assertTrue("短片关着、图也关着，它也照样在", note.isVisible());
        assertFalse("它只是说明，点不动", note.isSelectable());
        androidx.preference.PreferenceGroup screen = sentry.getPreferenceScreen();
        assertEquals("它在页首那条说明后面", 1, indexIn(screen, "pref_alerts_clip_data"));
        assertTrue("它在「什么时候发」那一组前面",
                indexIn(screen, "pref_alerts_clip_data") < indexIn(screen, "cat_alerts_when"));
        assertTrue("它不在短视频那一组里了",
                indexIn(sentry.findPreference("cat_alerts_clip"), "pref_alerts_clip_data") < 0);

        // 收尾：这几项存在同一份设置里。
        AppConfig config = new AppConfig(RuntimeEnvironment.getApplication());
        config.setAlertsPictureEnabled(true);
        config.setAlertsClipSeconds(0);
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
        assertEquals("目标温度的默认值", 22f, fresh.getAlertsClimateTargetC(), 0f);
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
        // 三页都在最上面有一行说明（不可点、上面没有组名），通知那页的「在家也发通知」紧排在它后面。
        // Every page opens with its explainer; the at-home switch sits right under the notifications one.
        assertFirstRowIsTheNote(notifications, "pref_alerts_notifications_note");
        assertEquals("说明后面就是「在家也发通知」", "pref_alerts_notify_at_home",
                notifications.getPreferenceScreen().getPreference(1).getKey());
        assertLastRowIsTheBackRow(notifications);
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
        assertFirstRowIsTheNote(sentry, "pref_alerts_sentry_note");
        // 数据量说明在页首（4.18）：紧跟在那条说明后面，在任何组名之前。
        // The data note sits directly under the page's explainer, above every category.
        assertEquals("数据量说明紧跟在页首那条说明后面", "pref_alerts_clip_data",
                sentry.getPreferenceScreen().getPreference(1).getKey());
        assertFalse("数据量说明只是说明，点不动",
                sentry.findPreference("pref_alerts_clip_data").isSelectable());
        assertLastRowIsTheBackRow(sentry);
        File sentryShot = Shots.save(shotActivity, "settings-alerts-sentry-" + tag);
        assertTrue(sentryShot + " 是空的", sentryShot.length() > 10_000);

        // 车厢温度：第一行是这一页的说明（三页一样），第二行才是开关；四个数值是拖动条，其余几项还是下拉框；检查不在这一页。
        SettingsPreferenceFragment climate = alertsChildPage(qualifiers, true,
                com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM, "pref_alerts_climate_page",
                CHILD_CLIMATE, R.string.set_alerts_page_climate,
                config -> config.setAlertsClimateEnabled(true));
        androidx.preference.PreferenceGroup screen = climate.getPreferenceScreen();
        assertNull("页名已经说了这一页是什么，组名不再重复一遍",
                climate.findPreference("cat_alerts_climate"));
        assertEquals("第一行是这一页的说明（三页同一个位置）", "pref_alerts_climate_note",
                screen.getPreference(0).getKey());
        assertEquals("第二行才是这一组自己的开关", "pref_alerts_climate",
                screen.getPreference(1).getKey());
        sliderShows(climate, "pref_alerts_climate_hot", 22, 40, 30,
                climate.getString(R.string.opt_alerts_climate_above, 30));
        sliderShows(climate, "pref_alerts_climate_cold", -10, 18, 5,
                climate.getString(R.string.opt_alerts_climate_up_to, 5));
        // 目标温度那根是半度一格的：一格半度，所以 22.0 是第 44 格，行尾写一位小数。
        // The target slider is half a degree per tick: 22.0 is tick 44, and the summary carries one decimal.
        sliderShows(climate, "pref_alerts_climate_target", 32, 60, 44, "22.0 °C");
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
        assertLastRowIsTheBackRow(climate);
        File climateShot = Shots.save(shotActivity, "settings-alerts-climate-" + tag);
        assertTrue(climateShot + " 是空的", climateShot.length() > 10_000);

        // 拖一下：值是写进 AppConfig 的，行尾跟着变（拖动条自己不存值）
        androidx.preference.SeekBarPreference hot = climate.findPreference("pref_alerts_climate_hot");
        hot.setValue(34);
        assertEquals(Boolean.TRUE, hot.callChangeListener(34));
        assertEquals("拖完就存下了", 34,
                new AppConfig(RuntimeEnvironment.getApplication()).getAlertsClimateHotC());
        assertEquals("行尾跟着变", climate.getString(R.string.opt_alerts_climate_above, 34),
                String.valueOf(hot.getSummary()));

        // 目标温度那根：拖到第 45 格就是 22.5 度（一格半度），行尾写成一位小数。
        // The target slider: tick 45 is 22.5 °C (one tick is half a degree), and the summary says one decimal.
        androidx.preference.SeekBarPreference target = climate.findPreference("pref_alerts_climate_target");
        target.setValue(45);
        assertEquals(Boolean.TRUE, target.callChangeListener(45));
        assertEquals("拖到第 45 格就存下 22.5", 22.5f,
                new AppConfig(RuntimeEnvironment.getApplication()).getAlertsClimateTargetC(), 0f);
        assertEquals("行尾写一位小数", "22.5 °C", String.valueOf(target.getSummary()));
        target.setValue(44);
        assertEquals(Boolean.TRUE, target.callChangeListener(44));
        assertEquals("第 44 格就是 22.0", 22f,
                new AppConfig(RuntimeEnvironment.getApplication()).getAlertsClimateTargetC(), 0f);
        assertEquals("22.0 也写一位小数，和车上的设定值一样", "22.0 °C",
                String.valueOf(target.getSummary()));

        // 范围外的一律夹到最近的一头（不再是「不在单子上就回默认值」）
        AppConfig config = new AppConfig(RuntimeEnvironment.getApplication());
        config.setAlertsClimateHotC(99);
        assertEquals("太热夹到 40", 40, config.getAlertsClimateHotC());
        config.setAlertsClimateHotC(5);
        assertEquals("太热夹到 22", 22, config.getAlertsClimateHotC());
        config.setAlertsClimateColdC(-40);
        assertEquals("太冷夹到 -10", -10, config.getAlertsClimateColdC());
        config.setAlertsClimateColdC(30);
        assertEquals("太冷夹到 18", 18, config.getAlertsClimateColdC());
        config.setAlertsClimateTargetC(5f);
        assertEquals("目标温度夹到 16", 16f, config.getAlertsClimateTargetC(), 0f);
        config.setAlertsClimateTargetC(15.9f);
        assertEquals("15.9 夹到 16.0", 16f, config.getAlertsClimateTargetC(), 0f);
        config.setAlertsClimateTargetC(30.4f);
        assertEquals("30.4 夹到 30.0", 30f, config.getAlertsClimateTargetC(), 0f);
        config.setAlertsClimateTargetC(22.3f);
        assertEquals("22.3 存进去读出来是 22.5", 22.5f, config.getAlertsClimateTargetC(), 0f);
        config.setAlertsClimateTargetC(19.5f);
        assertEquals("半度存得下", 19.5f, config.getAlertsClimateTargetC(), 0f);
        config.setAlertsClimateSocFloor(5);
        assertEquals("电量下限夹到 20", 20, config.getAlertsClimateSocFloor());
        config.setAlertsClimateSocFloor(150);
        assertEquals("电量下限夹到 99", 99, config.getAlertsClimateSocFloor());

        // 收尾：这些都在同一份设置里，别把后面的测试带跑。
        config.setAlertsClimateEnabled(false);
        config.setAlertsClimateHotC(30);
        config.setAlertsClimateColdC(5);
        config.setAlertsClimateTargetC(22f);
        config.setAlertsClimateSocFloor(20);
        config.setAlertsClipSeconds(0);
    }

    /** 温度写成一位小数（和界面上一模一样：{@code Locale.US}，小数点不跟着系统语言变）。
     *
     * A temperature with one decimal, exactly as the settings row writes it. */
    private static String tenths(float celsius) {
        return String.format(java.util.Locale.US, "%.1f", celsius);
    }

    /**
     * 这一页的第一行是那一页的说明：不可点，而且上面没有组名。
     *
     * <p>三页都是这个形状（ui-spec §十六），所以三个调用处说的是同一句话 —— 哪一页的说明被挤到下面、
     * 或者被包进一个组里，这里就红。</p>
     *
     * The page opens with its explainer: not selectable, and no category above it. All three pages share the
     * shape, so the same check says the same thing three times.
     */
    private static void assertFirstRowIsTheNote(SettingsPreferenceFragment page, String key) {
        androidx.preference.PreferenceGroup screen = page.getPreferenceScreen();
        assertEquals("第一行该是说明 " + key, key, screen.getPreference(0).getKey());
        Preference note = page.findPreference(key);
        assertNotNull("少了说明那一行 " + key, note);
        assertFalse(key + " 只是说明，点不动", note.isSelectable());
        assertTrue(key + " 该看得见", note.isVisible());
        assertFalse(key + " 上面不该有组名",
                screen.getPreference(0) instanceof androidx.preference.PreferenceCategory);
    }

    /**
     * 三页的末行都是「回到警报」：普通一行、点得动，行尾写从哪一页回去。
     *
     * <p>三页共用同一个 key（它们从不同时读进来），行尾那一句跟着这一页读的是哪一棵子树。</p>
     *
     * Every child page ends with the back row: a plain Preference, wired, and its summary names the page.
     */
    private static void assertLastRowIsTheBackRow(SettingsPreferenceFragment page) {
        androidx.preference.PreferenceGroup screen = page.getPreferenceScreen();
        int last = screen.getPreferenceCount() - 1;
        assertEquals("末行该是「回到警报」", "pref_alerts_back", screen.getPreference(last).getKey());
        Preference back = page.findPreference("pref_alerts_back");
        assertNotNull("少了「回到警报」那一行", back);
        assertTrue("只是普通一行，不是什么专用控件",
                back.getClass().equals(androidx.preference.Preference.class));
        assertNotNull("这一行该点得动", back.getOnPreferenceClickListener());
        String summary = String.valueOf(back.getSummary());
        String title = page.getString(R.string.set_alerts_back_title);
        assertFalse("行尾要写从哪一页回去", summary.isEmpty());
        assertFalse("行尾不能只重复标题", summary.equals(title));
        assertTrue("行尾该说这是从哪一页回去的: " + summary,
                summary.equals(page.getString(R.string.set_alerts_back_from_notifications))
                        || summary.equals(page.getString(R.string.set_alerts_back_from_sentry))
                        || summary.equals(page.getString(R.string.set_alerts_back_from_climate)));
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
            "pref_alerts_climate_seat_level", "pref_alerts_climate_restore",
            "pref_alerts_climate_minutes", "pref_alerts_climate_soc_floor"};

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
        assertFirstRowIsTheNote(climate, "pref_alerts_climate_note");
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

    /**
     * 「在家也发通知」只在通知那一页上，而且开关跟着存着的值。
     *
     * <p>它管的是消息；「在家要不要把哨兵模式打开」是另一行 —— 4.17 起两行都在同一页上（后者在
     * 总开关那一页，后者就在这一行下面），一个管消息一个管哨兵，所以位置靠得近但不共用一行。</p>
     *
     * The at-home switch lives on the notifications page only and follows the stored value. The row about
     * switching Sentry Mode on at home is a different row, now on the hub; the two sit close but share nothing.
     */
    @Test
    public void theAtHomeSwitchLivesOnTheNotificationsPage() {
        RuntimeEnvironment.setQualifiers(Shots.DAY_EN);
        SettingsPreferenceFragment on = alertsChildPage(Shots.DAY_EN, true,
                com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM, "pref_alerts_notifications",
                CHILD_NOTIFICATIONS, R.string.set_alerts_page_notifications,
                config -> config.setAlertsNotifyAtHome(true));
        Preference row = on.findPreference("pref_alerts_notify_at_home");
        assertNotNull("少了「在家也发通知」那一行", row);
        assertTrue("这一行该看得见", row.isVisible());
        assertTrue("开关跟着存着的值",
                ((androidx.preference.SwitchPreferenceCompat) row).isChecked());
        assertNotNull("得接上线", row.getOnPreferenceChangeListener());
        assertTrue("行尾要说清楚后果: " + row.getSummary(),
                String.valueOf(row.getSummary()).length() > 10);

        SettingsPreferenceFragment sentry = alertsChildPage(Shots.DAY_EN, true,
                com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM, "pref_alerts_sentry",
                CHILD_SENTRY, R.string.set_alerts_page_sentry, config -> {
                });
        assertNull("哨兵那一页没有这一行：它管消息，不管哨兵模式",
                sentry.findPreference("pref_alerts_notify_at_home"));
        assertNull("「在家也开哨兵」也不在哨兵那一页了（4.17 提到总开关那一页）",
                sentry.findPreference("pref_alerts_guard_everywhere"));

        // 它还在，只是在总开关那一页上：两件事分开，两个开关也不共用一行。
        // It still exists, just on the hub: two switches, two rows.
        SettingsPreferenceFragment hub = alertsPage(true, com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM);
        assertNotNull("「在家也开哨兵」该在总开关那一页上",
                hub.findPreference("pref_alerts_guard_everywhere"));

        // 收尾：这些都在同一份设置里，别把后面的测试带跑。
        new AppConfig(RuntimeEnvironment.getApplication()).setAlertsNotifyAtHome(false);
    }

    /** 走 ntfy 出口的通知那一页。The notifications page with the ntfy exit. */
    private SettingsPreferenceFragment ntfyPage(java.util.function.Consumer<AppConfig> tweak) {
        return alertsChildPage(Shots.DAY_EN, true, com.kooo.evcam.alarm.ntfy.NtfyGate.NTFY,
                "pref_alerts_notifications", CHILD_NOTIFICATIONS,
                R.string.set_alerts_page_notifications, tweak);
    }

    private ShotActivity shotActivity;
    /** 上一屏的控制器：下一屏建起来之前先把它拆掉，否则一屏一屏的视图全留在堆上。 */
    private org.robolectric.android.controller.ActivityController<ShotActivity> shotController;
    /**
     * 开一屏新的设置界面，并把上一屏拆掉。
     *
     * <p>这一个类一整套会开几十屏（每一屏都是真的视图树和真的截图位图），全留在堆上就会在运行末尾
     * 直接 OutOfMemory。上一屏的结论在前一个方法里已经用完，所以这里在开新的之前先 destroy 掉。</p>
     *
     * A fresh settings screen, with the previous one torn down: this class opens dozens of real view
     * hierarchies, and keeping every one of them alive runs the test worker out of heap near the end.
     */
    // ------------------------------------------------------------------ 更新与关于（4.19）

    /**
     * 开一屏「更新与关于」。存着的两个最新版本号先摆好（截图测试不联网，见 {@link #noNetwork}）。
     *
     * <p>两个数字是先存进去的，不是查出来的：这一页的规矩是「先显示存着的，后台再问一次」，
     * 所以这样测的正是车主离线打开时看到的那一屏。</p>
     */
    private SettingsPreferenceFragment updatePage(java.util.function.Consumer<AppConfig> tweak) {
        AppConfig config = new AppConfig(RuntimeEnvironment.getApplication());
        config.setCachedLatestUpstream("4.12.1-alpha");
        config.setCachedLatestFork("4.19.0-alerts-beta");
        tweak.accept(config);
        return updatePageNow();
    }

    /** 真的把那一屏建出来（设置已经摆好了）。Builds the page from whatever AppConfig now holds. */
    private SettingsPreferenceFragment updatePageNow() {
        RuntimeEnvironment.setQualifiers(Shots.DAY_EN);
        shotActivity = newShotActivity();
        Shots.stillFrames(shotActivity);
        SettingsShellFragment shell = new SettingsShellFragment();
        shotActivity.getSupportFragmentManager().beginTransaction()
                .replace(shotActivity.container, shell).commitNow();
        Shots.settle();
        shell.showSection("screen_update");
        Shots.settle();
        SettingsPreferenceFragment section = (SettingsPreferenceFragment) shell.getChildFragmentManager()
                .findFragmentById(R.id.settings_detail);
        assertNotNull("「更新与关于」那一屏", section);
        return section;
    }

    /**
     * 一次都没查到过（新装的车、断网）：那一行写「没能查到」，不是一直转「正在查询」，也不弹框。
     *
     * <p>「查不到」和「最新就是这一版」是两件事，不能混成一句。</p>
     */
    @Test
    public void updatePageWithoutNumbersSaysItCouldNotCheck() {
        SettingsPreferenceFragment section = updatePageNow();
        Preference row = section.findPreference("pref_update_versions");
        assertNotNull(row);
        String text = String.valueOf(row.getSummary());
        String unknown = RuntimeEnvironment.getApplication().getString(
                R.string.set_update_versions_unknown);
        assertTrue("该写「没能查到」：" + text, text.contains(unknown));
        assertFalse("不该还挂着「正在查询」：" + text,
                text.contains(RuntimeEnvironment.getApplication().getString(
                        R.string.set_update_versions_checking)));
        // 两个来源都不认得：dts88 与 fork 各一句
        assertEquals("两个来源各一句「没能查到」", 2, countOccurrences(text, unknown));
        File file = Shots.save(shotActivity, "settings-update-day-no-numbers");
        assertTrue(file + " 是空的", file.length() > 10_000);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            count++;
            at = text.indexOf(needle, at + needle.length());
        }
        return count;
    }

    /**
     * 三个数字并排：本机（带 versionCode）、上游 dts88 最新、fork 最新。
     *
     * <p>「落后多少」就靠这一行看出来，所以三个都得在，而且本机那个要带 versionCode ——
     * 能不能覆盖安装看的是那个数，不是版本名。</p>
     */
    @Test
    public void updatePageShowsInstalledAndBothLatestVersions() {
        SettingsPreferenceFragment section = updatePage(config -> {
        });
        Preference row = section.findPreference("pref_update_versions");
        assertNotNull("少了三个版本号那一行", row);
        String text = String.valueOf(row.getSummary());
        String installed = com.kooo.evcam.update.UpdateFlow.currentVersion(
                RuntimeEnvironment.getApplication());
        int code = com.kooo.evcam.update.UpdateFlow.currentVersionCode(
                RuntimeEnvironment.getApplication());
        String installedLine = RuntimeEnvironment.getApplication().getString(
                R.string.set_update_versions_installed, installed, code);
        assertTrue("本机那一行要带版本名和 versionCode：" + text, text.contains(installedLine));
        assertTrue("上游 dts88 最新的版本号该在：" + text,
                text.contains(RuntimeEnvironment.getApplication().getString(
                        R.string.set_update_versions_source, "dts88", "4.12.1-alpha")));
        assertTrue("fork 最新的版本号该在：" + text,
                text.contains(RuntimeEnvironment.getApplication().getString(
                        R.string.set_update_versions_source, "Fabbbrrr", "4.19.0-alerts-beta")));
        assertFalse("查到数字时不该还写着「查不到」：" + text,
                text.contains(RuntimeEnvironment.getApplication().getString(
                        R.string.set_update_versions_unknown)));

        section.scrollToPreference("pref_update_versions");
        File file = Shots.save(shotActivity, "settings-update-day-versions");
        assertTrue(file + " 是空的", file.length() > 10_000);
    }

    /**
     * 关掉「从 fork 取更新」得先过一道红色警告框：取消 → 还是 fork；确认 → 真的切到上游。
     *
     * <p>这一步不可逆（下一次更新就把 alerts 换掉），所以「点一下就切」和「没有警告」都是错的。</p>
     */
    @Test
    public void turningTheSourceOffWarnsFirstAndCancelKeepsTheFork() {
        SettingsPreferenceFragment section = updatePage(config -> {
        });
        AppConfig config = new AppConfig(RuntimeEnvironment.getApplication());
        assertTrue("默认就是从 fork 取（4.19）", config.isUpdateFromFork());

        androidx.preference.SwitchPreferenceCompat fork = section.findPreference("pref_update_fork");
        assertNotNull("少了「从哪儿取更新」那一行", fork);
        assertEquals("开着时那一行写 fork",
                R.string.dev_update_fork_title, titleRes(fork));

        assertFalse("关掉不能直接就关了", fork.callChangeListener(false));
        Shots.settle();
        android.app.Dialog dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull("关掉之前该先弹一个警告框", dialog);
        dialog.dismiss();
        Shots.settle();
        assertTrue("取消之后还是从 fork 取", config.isUpdateFromFork());

        assertFalse(fork.callChangeListener(false));
        Shots.settle();
        androidx.appcompat.app.AlertDialog warn =
                (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull(warn);
        Shots.save(warn, "settings-update-day-switch-to-upstream");
        warn.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();
        Shots.settle();
        assertFalse("确认之后才切成上游", config.isUpdateFromFork());
        assertEquals("关了之后那一行写上游", R.string.set_update_fork_off_title, titleRes(fork));
        assertFalse("不从 fork 取时，fork 那两行收起来",
                shown(section, "pref_update_fork_channel"));
        assertFalse(shown(section, "pref_update_repo"));
    }

    /** 那一行现在用的是哪个标题（{@code Preference} 只给 CharSequence，这里对回资源 id）。 */
    private static int titleRes(Preference row) {
        String title = String.valueOf(row.getTitle());
        for (int id : new int[] {R.string.dev_update_fork_title, R.string.set_update_fork_off_title}) {
            if (title.contentEquals(RuntimeEnvironment.getApplication().getText(id))) {
                return id;
            }
        }
        throw new AssertionError("认不出这一行的标题：" + title);
    }

    /**
     * fork 仓库只收 {@code owner/repo} 或 {@code https://github.com/owner/repo}，存下来的就是那一种写法；
     * 换仓库把「fork 最新」那个数字清掉（它是上一个仓库的）。
     */
    @Test
    public void theForkRepositoryIsNormalisedAndGarbageIsNotStored() {
        SettingsPreferenceFragment section = updatePage(config -> {
        });
        AppConfig config = new AppConfig(RuntimeEnvironment.getApplication());
        assertEquals("默认仓库", "Fabbbrrr/zeekr-shortcut-car", config.getUpdateForkRepo());
        assertTrue("fork 那行说明里写着现在的仓库：" + section.findPreference("pref_update_repo").getSummary(),
                String.valueOf(section.findPreference("pref_update_repo").getSummary())
                        .contains("Fabbbrrr/zeekr-shortcut-car"));

        assertFalse("认不出来的仓库名不存", config.setUpdateForkRepo("not a repo at all"));
        assertEquals("没存下来就还是默认那个", "Fabbbrrr/zeekr-shortcut-car", config.getUpdateForkRepo());
        assertFalse(config.setUpdateForkRepo("http://github.com/someone/zeekr-shortcut-car"));
        assertEquals("http 不认", "Fabbbrrr/zeekr-shortcut-car", config.getUpdateForkRepo());

        assertTrue("一整条网址认得出来",
                config.setUpdateForkRepo("https://github.com/someone/zeekr-shortcut-car"));
        assertEquals("存的是 owner/repo", "someone/zeekr-shortcut-car", config.getUpdateForkRepo());
        assertEquals("换了仓库就把上一个仓库的数字清掉", "", config.getCachedLatestFork());
    }

    /** fork 接收哪一档：四档都在（自动 / Alpha / Beta / 正式版），选了就存下来，并真的改成那两档。 */
    @Test
    public void theForkChannelOffersFourChoicesAndStoresOne() {
        SettingsPreferenceFragment section = updatePage(config -> {
        });
        androidx.preference.ListPreference channel = section.findPreference("pref_update_fork_channel");
        assertNotNull("少了「从 fork 接收哪一档」那一行", channel);
        assertEquals("四档", 4, channel.getEntries().length);
        assertEquals("默认是自动", "auto", channel.getValue());
        assertTrue("自动那一档的名字在" + java.util.Arrays.toString(channel.getEntries()),
                java.util.Arrays.toString(channel.getEntries()).contains("Auto"));

        channel.callChangeListener("alpha");
        Shots.settle();
        AppConfig config = new AppConfig(RuntimeEnvironment.getApplication());
        assertEquals("选了就存下来", "alpha", config.getUpdateChannel());
        com.kooo.evcam.update.UpdateChannel.Gates gates = com.kooo.evcam.update.UpdateSource
                .gates(config, com.kooo.evcam.update.UpdateFlow.currentVersion(
                        RuntimeEnvironment.getApplication()));
        assertTrue("alpha 那一档收 beta", gates.includeBeta);
        assertTrue("alpha 那一档也收 alpha", gates.includeAlpha);
    }

    private ShotActivity newShotActivity() {
        if (shotController != null) {
            shotController.pause().stop().destroy();
        }
        shotController = Robolectric.buildActivity(ShotActivity.class).setup();
        return shotController.get();
    }

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
        shotActivity = newShotActivity();
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
        ShotActivity activity = newShotActivity();
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
        ShotActivity activity = newShotActivity();
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

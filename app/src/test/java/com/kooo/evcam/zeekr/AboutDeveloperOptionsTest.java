/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.kooo.evcam.R;
import com.kooo.evcam.settings.DeveloperMode;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowDialog;

/**
 * 「关于与致谢」里的开发者选项入口（4.19）：先弹警告框，确认才解锁，不要密码。
 *
 * <h3>钉住的是什么</h3>
 *
 * <ol>
 *   <li>没解锁时那一行写「打开开发者选项」，<b>点一下不会直接打开</b> —— 先出对话框；</li>
 *   <li>看到警告之后点确认才真的打开（{@link DeveloperMode#enableFromWarning}），并且存下来了；</li>
 *   <li>打开之后那一行写「开发者选项已打开」，旁边那颗「关掉」把它锁回去（原来的 {@code lock}）；</li>
 *   <li>密码那条路没坏：{@code unlock} 用对的密码照样能开。</li>
 * </ol>
 *
 * <p>用 plain Application：{@code ZeekrShortcutApp} 会在进程起来时把开发者选项直接打开，
 * 那样就看不出「本来关着」这一半了。</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class AboutDeveloperOptionsTest {

    private Application app;

    @Before
    public void startLocked() {
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("developer_mode", android.content.Context.MODE_PRIVATE)
                .edit().clear().commit();
        DeveloperMode.lock(app);
        assertFalse("这一条测的是「本来关着」", DeveloperMode.isUnlocked());
    }

    /** 对话框的按钮是往主线程的 handler 里排队调监听的：点完要让它跑一次。 */
    private static void settle() {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    private AboutActivity open() {
        return Robolectric.buildActivity(AboutActivity.class).setup().get();
    }

    private static TextView row(AboutActivity activity) {
        View view = activity.findViewById(R.id.about_developer_row);
        assertNotNull("「关于」页上少了开发者选项那一行（activity_about.xml 的 about_developer_row）", view);
        return (TextView) view;
    }

    @Test
    public void theRowSaysItIsOffAndTappingItAsksFirst() {
        AboutActivity activity = open();
        assertEquals(activity.getString(R.string.dev_enable_action), row(activity).getText().toString());
        assertEquals("关着时不该有「关掉」那颗",
                View.GONE, activity.findViewById(R.id.about_developer_off).getVisibility());

        row(activity).performClick();
        settle();

        Dialog dialog = ShadowDialog.getLatestDialog();
        assertNotNull("点这一行应当先弹一个警告框", dialog);
        assertTrue(dialog.isShowing());
        assertFalse("光弹框不算打开", DeveloperMode.isUnlocked());
        assertFalse("取消之前不能解锁", persisted());
    }

    @Test
    public void confirmingTheWarningUnlocksAndSaysSo() {
        AboutActivity activity = open();
        row(activity).performClick();
        settle();
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        String message = String.valueOf(dialog.findViewById(android.R.id.message) != null
                ? ((TextView) dialog.findViewById(android.R.id.message)).getText() : "");
        assertTrue("框里要写明是试验性的、风险自负: " + message,
                message.contains("试验性") || message.contains("experimental"));

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        settle();

        assertTrue("确认之后开发者选项打开", DeveloperMode.isUnlocked());
        assertTrue("而且存下来了（重启、更新之后还开着，见 DeveloperMode）", persisted());
        assertEquals(activity.getString(R.string.dev_enable_on_row), row(activity).getText().toString());
        assertEquals("开着时旁边出现「关掉」那颗",
                View.VISIBLE, activity.findViewById(R.id.about_developer_off).getVisibility());

        activity.findViewById(R.id.about_developer_off).performClick();
        settle();
        Dialog lock = ShadowDialog.getLatestDialog();
        assertNotNull(lock);
        ((AlertDialog) lock).getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        settle();
        assertFalse("「关掉」走的是原来的 lock", DeveloperMode.isUnlocked());
        assertEquals(activity.getString(R.string.dev_enable_action), row(activity).getText().toString());
    }

    /** 密码那条路照旧：警告框只是多出来的一条路，不是把密码换掉。 */
    @Test
    public void thePasswordPathStillWorks() {
        assertFalse(DeveloperMode.unlock(app, "0000"));
        assertTrue(DeveloperMode.unlock(app, "6651"));
        assertTrue(DeveloperMode.isUnlocked());

        AboutActivity activity = open();
        assertEquals("密码打开之后那一行也写「已打开」",
                activity.getString(R.string.dev_enable_on_row), row(activity).getText().toString());
    }

    private boolean persisted() {
        SharedPreferences prefs = app.getSharedPreferences("developer_mode",
                android.content.Context.MODE_PRIVATE);
        return prefs.getBoolean("unlocked", false);
    }
}

/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.button;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSettings;

import java.lang.reflect.Method;

/**
 * {@link MediaMove#voice} 报成功只报真能起得来的（F-06.05）：安卓会默默吞掉后台的 Activity 启动。
 *
 * <p>Robolectric 的 {@code Settings.canDrawOverlays} 默认没有权限；没有权限、主界面又不在前台
 * （{@code StallWatch.isForeground()} 默认 false）时报失败，让 SmartButtons 弹「做不了」的提示。
 * 给了权限那一半是对照：行为不许变。</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class MediaMoveTest {

    private final Context app = RuntimeEnvironment.getApplication();

    /** 同沙箱里别的用例会改 StallWatch 的前台标志；自己钉住，测完还原。 */
    private Object foregroundRestored;

    @Before
    public void pinForeground() throws Exception {
        Method m = com.kooo.evcam.camera.StallWatch.class.getDeclaredMethod("setForeground", boolean.class);
        m.setAccessible(true);
        foregroundRestored = com.kooo.evcam.camera.StallWatch.isForeground();
        m.invoke(null, false);
    }

    @After
    public void restoreForeground() throws Exception {
        Method m = com.kooo.evcam.camera.StallWatch.class.getDeclaredMethod("setForeground", boolean.class);
        m.setAccessible(true);
        m.invoke(null, foregroundRestored);
    }

    @Test
    public void voiceReportsFailureWhenBackgroundStartIsBlocked() {
        ShadowSettings.setCanDrawOverlays(false);
        assertFalse(MediaMove.VOICE.perform(app));
    }

    @Test
    public void voiceStillReportsSuccessWithOverlayPermission() {
        ShadowSettings.setCanDrawOverlays(true);
        assertTrue(MediaMove.VOICE.perform(app));
    }
}

/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.recovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.camera.StallWatch;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import java.lang.reflect.Field;

/**
 * 亮屏接回主界面（{@link Recovery#bringBackUiAfterScreenOn}，F-07.08）：拉不起来的那一次不算数。
 *
 * <p>以前这里先清「因熄屏退下去」的记号、后拉界面。{@code startActivity} 抛了（底下只记一行日志就完了），
 * 记号却已经没了 —— 这一趟之后再没人把界面接回来，人上车看到的是车机桌面。钉住：抛了就什么都别动，
 * 下一轮亮屏、保活才会再来一次。界面还在前面那一支照旧清掉，那是有意的（见原注释）。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class RecoveryScreenOffFlagTest {

    /** 一个还没有任何一次拉起的基准值：动了就说明动了，没动就说明没动。 */
    private static final long NEVER = -1L;

    private Application app;

    /** 只有 {@code startActivity} 会抛的上下文：其余照旧走 Robolectric 的应用上下文。 */
    private static final class ThrowingContext extends ContextWrapper {
        ThrowingContext(Context base) {
            super(base);
        }

        @Override
        public void startActivity(Intent intent) {
            throw new IllegalStateException("background activity start refused");
        }
    }

    private static long dedupe() throws Exception {
        Field field = Recovery.class.getDeclaredField("broughtUpAtMs");
        field.setAccessible(true);
        return field.getLong(null);
    }

    private static void setDedupe(long value) throws Exception {
        Field field = Recovery.class.getDeclaredField("broughtUpAtMs");
        field.setAccessible(true);
        field.setLong(null, value);
    }

    /** {@code StallWatch.setForeground} 是相机管家包内的，测试在 recovery 包里 —— 直接摆那个字段。 */
    private static void setInFront(boolean value) throws Exception {
        Field field = StallWatch.class.getDeclaredField("foreground");
        field.setAccessible(true);
        field.setBoolean(null, value);
    }

    @Before
    public void setUp() throws Exception {
        app = RuntimeEnvironment.getApplication();
        setDedupe(NEVER);
        setInFront(false);
        new AppConfig(app).setUiLeftForScreenOff(true);
    }

    @After
    public void tearDown() throws Exception {
        setInFront(false);
        setDedupe(0L);
        new AppConfig(app).setUiLeftForScreenOff(false);
    }

    @Test
    public void aRefusedLaunchLeavesEverythingForTheNextPass() throws Exception {
        Recovery.bringBackUiAfterScreenOn(new ThrowingContext(app));

        assertTrue("拉失败的，记号要留着：下一轮才会再试", new AppConfig(app).didUiLeaveForScreenOff());
        assertEquals("拉失败的，重复窗口不许往前走", NEVER, dedupe());
        assertNull("什么也没拉起来", Shadows.shadowOf(app).getNextStartedActivity());
    }

    @Test
    public void aLaunchThatWorkedClearsTheFlagAndOpensTheWindow() throws Exception {
        Recovery.bringBackUiAfterScreenOn(app);

        assertFalse("起来了，记号就该清掉", new AppConfig(app).didUiLeaveForScreenOff());
        assertTrue("起来了，这一阵不再为接录像另拉一次", dedupe() != NEVER);
        assertNotNull("拉的得是主界面", Shadows.shadowOf(app).getNextStartedActivity());
    }

    @Test
    public void theUiStillInTheFrontClearsTheFlagWithoutLaunching() throws Exception {
        setInFront(true);

        Recovery.bringBackUiAfterScreenOn(app);

        assertFalse("界面还在前面：不用拉，但记号要清（有意的）", new AppConfig(app).didUiLeaveForScreenOff());
        assertNull("这一支不该拉", Shadows.shadowOf(app).getNextStartedActivity());
    }

    @Test
    public void nothingToDoWhenTheFlagWasNeverSet() throws Exception {
        new AppConfig(app).setUiLeftForScreenOff(false);

        Recovery.bringBackUiAfterScreenOn(new ThrowingContext(app));

        assertFalse("没有记号就什么都不做（抛不抛都一样）", new AppConfig(app).didUiLeaveForScreenOff());
        assertEquals(NEVER, dedupe());
    }
}

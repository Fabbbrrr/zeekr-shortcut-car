/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.sound;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * F-04.05：欠载上报规则（{@link SoundOutput#shouldReport}）——没到顶每次涨都说一句；
 * 缓冲已经在顶上加不动了，每攒够 {@link SoundOutput#REPORT_STEP_AT_CAP} 才再说一句，吵总线不能刷爆黑匣子。
 *
 * <p>这里喂假计数跑规则本身（{@code watch()} 要真 AudioTrack，单测里没有）。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class SoundOutputReportRuleTest {

    @Test
    public void applicationIsResolvable() {
        Context app = RuntimeEnvironment.getApplication();
        assertFalse(app == null);
    }

    @Test
    public void everyIncreaseBelowCapReports() {
        assertTrue(SoundOutput.shouldReport(false, 1, 1));
        assertTrue(SoundOutput.shouldReport(false, 3, 3));
    }

    @Test
    public void noIncreaseNoReport() {
        assertFalse(SoundOutput.shouldReport(false, 0, 99));
        assertFalse(SoundOutput.shouldReport(true, 0, 999));
    }

    @Test
    public void atCapStopsReportingUntilItJumpsByFifty() {
        // 到顶后一次小涨：不说（上次说到 0，这次才涨 1）
        assertFalse(SoundOutput.shouldReport(true, 1, 1));
        // 攒够 50 才再说一句
        assertFalse(SoundOutput.shouldReport(true, 1, SoundOutput.REPORT_STEP_AT_CAP - 1));
        assertTrue(SoundOutput.shouldReport(true, 1, SoundOutput.REPORT_STEP_AT_CAP));
        // 一次跳变超过 50 也算
        assertTrue(SoundOutput.shouldReport(true, SoundOutput.REPORT_STEP_AT_CAP + 5, SoundOutput.REPORT_STEP_AT_CAP + 5));
        assertEquals(50, SoundOutput.REPORT_STEP_AT_CAP);
    }

    /** 按 watch() 的用法把规则串起来跑一遍：到顶前的涨都说，到顶后每攒够 50 才说。 */
    @Test
    public void ruleSequenceBelowCapThenAtCap() {
        int lastReported = 0;
        // 到顶前：三次涨，三次都说
        for (int delta = 1; delta <= 3; delta++) {
            assertTrue(SoundOutput.shouldReport(false, delta, delta));
            lastReported += delta;
        }
        assertEquals(6, lastReported);
        // 到顶后：小涨不说，攒到 50 才说
        assertFalse(SoundOutput.shouldReport(true, 10, 10));
        assertFalse(SoundOutput.shouldReport(true, 20, 30));
        assertTrue(SoundOutput.shouldReport(true, 20, 50));
    }
}

/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link CodecVideoRecorder} 快速恢复的梯子（F-01.02）：前 6 次 5 秒一次，到顶之后回到正常的分段时间隔，慢下来继续试。
 *
 * <p>判错的代价：没有上限，一个真坏了的编码器会被 5 秒一次重建到进程结束（写不进文件的裁判报过一次就不再看了）；
 * 到顶就停手，编码器自己再也没有机会好起来。这里只看隔多久 —— 排程本身（{@code Handler.postDelayed}）是 final，
 * 换不了假 handler，所以测的是算延迟那一头。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class RecoveryRetryLadderTest {

    private static final long FAST_MS = 5_000L;
    private static final int FAST_LIMIT = 6;

    @Test
    public void sixFastRetriesThenTheNormalCadence() throws Exception {
        CodecVideoRecorder recorder = new CodecVideoRecorder("test", 640, 480);

        List<Long> delays = new ArrayList<>();
        for (int attempt = 1; attempt <= 9; attempt++) {
            setAttempts(recorder, attempt);
            delays.add(delay(recorder));
        }

        assertEquals(Arrays.asList(FAST_MS, FAST_MS, FAST_MS, FAST_MS, FAST_MS, FAST_MS,
                60_000L, 60_000L, 60_000L), delays);
    }

    @Test
    public void theSlowStepFollowsTheConfiguredCadence() throws Exception {
        CodecVideoRecorder recorder = new CodecVideoRecorder("test", 640, 480);
        recorder.setSegmentDuration(30_000L);

        setAttempts(recorder, FAST_LIMIT); // 第 6 次：还在快速档
        assertEquals(FAST_MS, delay(recorder));

        setAttempts(recorder, FAST_LIMIT + 1);
        assertEquals(30_000L, delay(recorder));
    }

    private static long delay(CodecVideoRecorder recorder) throws Exception {
        Method method = CodecVideoRecorder.class.getDeclaredMethod("recoveryRetryDelayMs");
        method.setAccessible(true);
        return (Long) method.invoke(recorder);
    }

    private static void setAttempts(CodecVideoRecorder recorder, int attempts) throws Exception {
        Field field = CodecVideoRecorder.class.getDeclaredField("recoveryAttempts");
        field.setAccessible(true);
        field.setInt(recorder, attempts);
    }
}

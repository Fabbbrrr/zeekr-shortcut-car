/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.climate;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 自动关那一下的规则（{@link ClimateOffGuard}）：读到停着才写、读不到就等、等满了就说话。
 *
 * <h3>为什么值得单独钉</h3>
 *
 * <p>真的事故是这样来的：锁车十分钟之后车机睡了，{@code latest()} 和挡位都是 null —— 那正是要关空调的
 * 一刻，却被当成「车在走」跳过去了。空调于是开了一整夜，而回执上早就写了关的时刻。
 * 这三个分支（写 / 再等 / 放弃）就是那条规则的全文，所以这里把三个都钉住，连「30 秒」和「3 倍」两个数
 * 一起钉死 —— 数一改，这里就红。</p>
 *
 * The rule that failed in the field: ten minutes after locking, the head unit is asleep, so the snapshot and
 * the gear are null — exactly the moment the climate had to be switched off, and it was treated as "driving".
 * All three branches are pinned here, together with the 30 s interval and the 3 × bound.
 */
public class ClimateOffGuardTest {

    @Test
    public void aParkedReadingMeansWriteNow() {
        assertEquals(ClimateOffGuard.Step.WRITE,
                ClimateOffGuard.step(0L, 0L, 10, true));
        assertEquals("到了界线也是一样：只要这一次读到停着就写",
                ClimateOffGuard.Step.WRITE,
                ClimateOffGuard.step(0L, ClimateOffGuard.boundMs(10) + 60_000L, 10, true));
    }

    @Test
    public void anUnreadableStateMeansRetryNotSkip() {
        long firstAt = 1_000L;
        assertEquals("刚读不到：等 30 秒再来",
                ClimateOffGuard.Step.RETRY, ClimateOffGuard.step(firstAt, firstAt, 10, false));
        assertEquals("半小时之前都还在问",
                ClimateOffGuard.Step.RETRY,
                ClimateOffGuard.step(firstAt, firstAt + ClimateOffGuard.boundMs(10) - 1, 10, false));
    }

    @Test
    public void theBoundIsThreeTimesTheConfiguredMinutes() {
        assertEquals("10 分钟 → 30 分钟", 30 * 60_000L, ClimateOffGuard.boundMs(10));
        assertEquals(15 * 60_000L, ClimateOffGuard.boundMs(5));
        assertEquals(45 * 60_000L, ClimateOffGuard.boundMs(15));
        long firstAt = 5_000L;
        assertEquals("到点就放弃，不再写",
                ClimateOffGuard.Step.GIVE_UP,
                ClimateOffGuard.step(firstAt, firstAt + ClimateOffGuard.boundMs(10), 10, false));
    }

    @Test
    public void theRetryIntervalIsThirtySeconds() {
        assertEquals("读不到状态时隔 30 秒再问一次", 30_000L, ClimateOffGuard.RETRY_MS);
    }

    @Test
    public void aNonsenseMinuteCountStillGivesAChanceToRetry() {
        assertEquals("分钟数不合法也不留一个「立刻放弃」的角落",
                3 * 60_000L, ClimateOffGuard.boundMs(0));
        assertEquals(3 * 60_000L, ClimateOffGuard.boundMs(-5));
    }
}

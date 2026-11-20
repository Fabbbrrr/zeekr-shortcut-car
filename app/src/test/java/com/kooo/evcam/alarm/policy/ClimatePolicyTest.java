/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.kooo.evcam.alarm.policy.ClimatePolicy.Kind;
import com.kooo.evcam.alarm.policy.ClimatePolicy.Verdict;

/**
 * 车内温度警报（{@link ClimatePolicy}）：热 / 冷要连续新鲜读数满窗才发，安全那条一刻不等，但它有次数上限。
 *
 * <p>每条规则一个断言，名字就是那句话 —— 测试挂了的时候不用再回去读实现。</p>
 *
 * Cabin temperature alerts ({@link ClimatePolicy}): hot / cold need a full window of fresh samples,
 * the safety rule waits for nothing but is capped.
 *
 * <p>One assertion per rule, and the name is the sentence -- when a test fails there is no need to go
 * back and read the implementation.</p>
 */
public class ClimatePolicyTest {

    private static final float HOT_C = 30f;
    private static final float COLD_C = 5f;
    private static final float SAFETY_C = 40f;
    private static final float SAFETY_OCCUPIED_C = 35f;
    private static final float BAND_C = 3f;
    private static final int SOC_FLOOR = 20;
    private static final int SOC_OK = 80;
    private static final long HOLD_MS = 10 * 60_000L;
    private static final long REPEAT_MS = 15 * 60_000L;
    private static final long STALE_MS = 10 * 60_000L;
    private static final int SAFETY_MAX = 3;

    private static ClimatePolicy policy() {
        return new ClimatePolicy(HOT_C, COLD_C, SAFETY_C, SAFETY_OCCUPIED_C, BAND_C,
                SOC_FLOOR, HOLD_MS, REPEAT_MS, STALE_MS, SAFETY_MAX);
    }

    /** 一个普通读数：停着、没开空调、没人、没暂停、不安静、电量够。 */
    private static Verdict read(ClimatePolicy policy, Float cabinC, long nowMs) {
        return policy.decide(cabinC, null, SOC_OK, true, false, false, false, false, nowMs);
    }

    private static Kind kind(ClimatePolicy policy, Float cabinC, long nowMs) {
        return read(policy, cabinC, nowMs).kind;
    }

    /** 空调开着时的读数：热 / 冷不发，而且计时作废。 */
    private static Verdict hvacOn(ClimatePolicy policy, Float cabinC, long nowMs) {
        return policy.decide(cabinC, null, SOC_OK, true, true, false, false, false, nowMs);
    }

    /** 电量见底时的读数。 */
    private static Verdict lowBattery(ClimatePolicy policy, Float cabinC, long nowMs) {
        return policy.decide(cabinC, null, SOC_FLOOR - 1, true, false, false, false, false, nowMs);
    }

    /** 安静时段里的读数（舒适那一类不发，安全那一类照发）。 */
    private static Verdict quiet(ClimatePolicy policy, Float cabinC, long nowMs) {
        return policy.decide(cabinC, null, SOC_OK, true, false, false, false, true, nowMs);
    }

    @Test
    public void aMovingCarNeverAlertsEvenAtFiftyDegrees() {
        assertEquals(Kind.NONE, policy().decide(50f, null, SOC_OK, false, false, true, false, true, 0)
                .kind);
    }

    @Test
    public void aPausedOwnerGetsNothingEvenAtFiftyDegrees() {
        assertEquals(Kind.NONE, policy().decide(50f, null, SOC_OK, true, false, true, true, false, 0)
                .kind);
    }

    @Test
    public void noReadingIsNoAlert() {
        assertEquals(Kind.NONE, kind(policy(), null, 0));
    }

    @Test
    public void hotNeedsTheWholeHoldWindowOfFreshSamples() {
        ClimatePolicy policy = policy();
        read(policy, 33f, 0);
        read(policy, 33f, 300_000L);
        assertEquals(Kind.NONE, kind(policy, 33f, 599_999L));
        assertEquals(Kind.HOT, kind(policy, 33f, 600_000L));
    }

    @Test
    public void aStaleGapRestartsTheHoldSoAnOldReadingCannotCarryIt() {
        ClimatePolicy policy = policy();
        read(policy, 33f, 0);
        read(policy, 33f, 300_000L);
        // 11 分钟没有读数：那 10 分钟的热不算数，计时从这一刻重新开始
        // eleven minutes with no reading: that ten minutes of heat does not count, restart here
        assertEquals(Kind.NONE, kind(policy, 33f, 960_000L));
        read(policy, 33f, 1_200_000L);
        assertEquals(Kind.NONE, kind(policy, 33f, 1_559_999L));
        assertEquals(Kind.HOT, kind(policy, 33f, 1_560_000L));
    }

    @Test
    public void aSteadyReadingThatKeepsArrivingIsNeverStale() {
        ClimatePolicy policy = policy();
        for (long now = 0; now < HOLD_MS; now += 60_000L) {
            assertEquals("60 秒一个读数，9 分钟时还不够", Kind.NONE, kind(policy, 33f, now));
        }
        assertEquals(Kind.HOT, kind(policy, 33f, HOLD_MS));
    }

    @Test
    public void hotFiresOncePerCrossingAndRearmsOnlyBelowTheBand() {
        ClimatePolicy policy = policy();
        read(policy, 33f, 0);
        assertEquals(Kind.HOT, kind(policy, 33f, HOLD_MS));
        assertEquals("刚发过，同一次不重复", Kind.NONE, kind(policy, 33f, 660_000L));
        assertEquals("只降了 2.5 度，还在回差里", Kind.NONE, kind(policy, 27.5f, 720_000L));
        assertEquals("降到 hotC - bandC 才算降下来", Kind.NONE, kind(policy, 27f, 780_000L));
        read(policy, 31f, 840_000L);
        read(policy, 31f, 1_140_000L);
        assertEquals(Kind.HOT, kind(policy, 31f, 1_440_000L));
    }

    @Test
    public void coldIsTheMirrorOfHotWithItsOwnHysteresis() {
        ClimatePolicy policy = policy();
        read(policy, 0f, 0);
        assertEquals(Kind.COLD, kind(policy, 0f, HOLD_MS));
        assertEquals("刚发过", Kind.NONE, kind(policy, 0f, 660_000L));
        assertEquals("升到 coldC + bandC 才算回来", Kind.NONE, kind(policy, 8f, 720_000L));
        read(policy, 2f, 780_000L);
        assertEquals(Kind.COLD, kind(policy, 2f, 1_380_000L));
    }

    @Test
    public void hvacOnSuppressesHotButNoSafety() {
        ClimatePolicy policy = policy();
        Verdict last = null;
        for (long now = 0; now <= 12 * 60_000L; now += 60_000L) {
            last = policy.decide(35f, null, SOC_OK, true, true, false, false, false, now);
        }
        assertEquals(Kind.NONE, last.kind);
    }

    @Test
    public void aBatteryBelowTheFloorSuppressesCold() {
        ClimatePolicy policy = policy();
        Verdict last = null;
        for (long now = 0; now <= 12 * 60_000L; now += 60_000L) {
            last = policy.decide(0f, null, SOC_FLOOR - 1, true, false, false, false, false, now);
        }
        assertEquals(Kind.NONE, last.kind);
    }

    /**
     * 空调在半天里打开：那几分钟不能算进那一窗。只看「最后一句是不是 NONE」的测试会把这条错过去 ——
     * 旧写法到点就发，因为计时从前面那次热一直数着。
     */
    @Test
    public void switchingHvacOnMidHoldRestartsTheHold() {
        ClimatePolicy policy = policy();
        read(policy, 33f, 0);
        read(policy, 33f, 300_000L);
        assertEquals(Kind.NONE, hvacOn(policy, 33f, 350_000L).kind);
        assertEquals("空调开着那一段时间不能算进那一窗", Kind.NONE, kind(policy, 33f, 600_000L));
        read(policy, 33f, 800_000L);
        read(policy, 33f, 1_000_000L);
        assertEquals("从空调关掉那一刻重新数满", Kind.NONE, kind(policy, 33f, 1_199_999L));
        assertEquals(Kind.HOT, kind(policy, 33f, 1_200_000L));
    }

    /** 同上，电量见底也把那一窗作废。 */
    @Test
    public void aBatteryUnderTheFloorMidHoldRestartsTheHold() {
        ClimatePolicy policy = policy();
        read(policy, 33f, 0);
        read(policy, 33f, 300_000L);
        assertEquals(Kind.NONE, lowBattery(policy, 33f, 350_000L).kind);
        assertEquals("电量见底那一段时间不能算进那一窗", Kind.NONE, kind(policy, 33f, 600_000L));
        read(policy, 33f, 800_000L);
        read(policy, 33f, 1_000_000L);
        assertEquals(Kind.NONE, kind(policy, 33f, 1_199_999L));
        assertEquals(Kind.HOT, kind(policy, 33f, 1_200_000L));
    }

    /** 停一趟回来，上一个停车段的「发过了」不能跟着过来。 */
    @Test
    public void resetClearsTheHotLatch() {
        ClimatePolicy policy = policy();
        read(policy, 33f, 0);
        assertEquals(Kind.HOT, kind(policy, 33f, HOLD_MS));
        assertEquals("发过了，这一趟不再发", Kind.NONE, kind(policy, 33f, HOLD_MS + 60_000L));
        policy.reset();
        read(policy, 33f, HOLD_MS + 120_000L);
        assertEquals("重置之后要重新数满一窗", Kind.NONE,
                kind(policy, 33f, 2 * HOLD_MS + 60_000L));
        assertEquals(Kind.HOT, kind(policy, 33f, 2 * HOLD_MS + 120_000L));
    }

    @Test
    public void resetClearsTheColdLatch() {
        ClimatePolicy policy = policy();
        read(policy, 0f, 0);
        assertEquals(Kind.COLD, kind(policy, 0f, HOLD_MS));
        assertEquals("发过了", Kind.NONE, kind(policy, 0f, HOLD_MS + 60_000L));
        policy.reset();
        read(policy, 0f, HOLD_MS + 120_000L);
        assertEquals(Kind.NONE, kind(policy, 0f, 2 * HOLD_MS + 60_000L));
        assertEquals(Kind.COLD, kind(policy, 0f, 2 * HOLD_MS + 120_000L));
    }

    /**
     * 安全那条的间隔从上一条算起，不是从「条件又成立」算起：中间降下来一趟再热回去，
     * 不能把上限绕过去（不然清一下就是一条新警报）。
     */
    @Test
    public void theSafetyRepeatCountsFromTheLastAlertNotFromTheCondition() {
        ClimatePolicy policy = policy();
        assertEquals(Kind.SAFETY, kind(policy, 45f, 0));
        assertEquals("读数是下来了", Kind.NONE, kind(policy, 22f, 60_000L));
        assertEquals("还没到一个间隔，回来也不发", Kind.NONE, kind(policy, 45f, 120_000L));
        assertEquals(Kind.SAFETY, kind(policy, 45f, REPEAT_MS));
    }

    /**
     * 安静时段只压舒适那一类，而且不占掉那一段：天亮以后重新数满一窗就发。
     * 锁上的话（旧写法）安静时段一结束就立刻发，因为「发过了」的标记已经立起来了。
     */
    @Test
    public void quietHoursSuppressComfortWithoutLatching() {
        ClimatePolicy policy = policy();
        assertEquals("quiet hours", quiet(policy, 33f, 0).reason);
        assertEquals("quiet hours", quiet(policy, 33f, 300_000L).reason);
        assertEquals("满窗也不发", Kind.NONE, quiet(policy, 33f, 600_000L).kind);
        assertEquals("安全那条不看安静时段", Kind.SAFETY, quiet(policy, 45f, 660_000L).kind);

        ClimatePolicy later = policy();
        quiet(later, 33f, 0);
        quiet(later, 33f, 300_000L);
        read(later, 33f, 660_000L);
        assertEquals("安静结束以后要重新数满一窗", Kind.NONE, kind(later, 33f, 1_199_999L));
        assertEquals(Kind.HOT, kind(later, 33f, 1_260_000L));
    }

    @Test
    public void safetyIgnoresQuietHours() {
        assertEquals(Kind.SAFETY, policy().decide(36f, null, SOC_OK, true, false, true, false, true, 0)
                .kind);
    }

    @Test
    public void safetyStillRespectsAPause() {
        assertEquals(Kind.NONE, policy().decide(45f, null, SOC_OK, true, false, true, true, false, 0)
                .kind);
    }

    @Test
    public void safetyBypassesBothHvacAndTheBatteryFloor() {
        assertEquals(Kind.SAFETY, policy().decide(45f, null, SOC_FLOOR - 1, true, true, false, false,
                true, 0).kind);
    }

    @Test
    public void safetyNeedsNoHoldButRepeatsOnlyEveryRepeatWindow() {
        ClimatePolicy policy = policy();
        assertEquals(Kind.SAFETY, kind(policy, 45f, 0));
        assertEquals("还不到一个间隔", Kind.NONE, kind(policy, 45f, REPEAT_MS - 1));
        assertEquals(Kind.SAFETY, kind(policy, 45f, REPEAT_MS));
    }

    @Test
    public void safetyStopsAtThreePerParkSession() {
        ClimatePolicy policy = policy();
        assertEquals(Kind.SAFETY, kind(policy, 45f, 0));
        assertEquals(Kind.SAFETY, kind(policy, 45f, REPEAT_MS));
        assertEquals(Kind.SAFETY, kind(policy, 45f, 2 * REPEAT_MS));
        assertEquals("第 3 条已经发过，这一趟到此为止", Kind.NONE, kind(policy, 45f, 3 * REPEAT_MS));
    }

    @Test
    public void resetGivesTheNextParkSessionItsFullQuotaAgain() {
        ClimatePolicy policy = policy();
        kind(policy, 45f, 0);
        kind(policy, 45f, REPEAT_MS);
        kind(policy, 45f, 2 * REPEAT_MS);
        policy.reset();
        assertEquals(Kind.SAFETY, kind(policy, 45f, 3 * REPEAT_MS));
    }

    @Test
    public void everyReasonIsPlainAscii() {
        ClimatePolicy policy = policy();
        String[] reasons = {
                policy.decide(50f, null, SOC_OK, false, false, true, false, true, 0).reason,
                policy.decide(50f, null, SOC_OK, true, false, true, true, false, 0).reason,
                policy.decide(null, null, SOC_OK, true, false, false, false, false, 0).reason,
                policy.decide(35f, null, SOC_OK, true, true, false, false, false, 0).reason,
                policy.decide(35f, null, 1, true, false, false, false, false, 0).reason,
                policy.decide(22f, null, SOC_OK, true, false, false, false, false, 1).reason,
                policy.decide(35f, null, SOC_OK, true, false, false, false, false, 2).reason,
                policy.decide(45f, null, SOC_OK, true, true, false, false, true, 3).reason,
                policy.decide(45f, null, SOC_OK, true, true, false, false, true, 4).reason,
                policy.decide(0f, null, SOC_OK, true, false, false, false, false, 5).reason
        };
        for (String reason : reasons) {
            assertTrue("黑匣子只收 ASCII：" + reason, reason.matches("[\\x20-\\x7e]*"));
        }
    }
}

/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.climate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.telemetry.CarAccess;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 气候写车（{@link ClimateSource}）：关、开的次序、范围、单位、读回、出错。车辆接口、常量名、等待都是假的
 * （号码随便编的，只看规则）；等是假的，测试不睡。
 */
public class ClimateSourceTest {

    private static final int FRONT = 0x8;
    private static final int DRIVER = 0x1;

    private static final int POWER = 0x821;
    private static final int TEMP = 0xA01;
    private static final int TEMP_MIN = 0xA02;
    private static final int TEMP_MAX = 0xA03;
    private static final int TEMP_STEP = 0xA04;
    private static final int TEMP_UNIT = 0xA05;
    /** 温度单位常量在车机上的值（真的由 {@code CarNames} 取，测试里随便给一个）。 */
    private static final int UNIT_C = 1;
    private static final int UNIT_F = 0x10;
    private static final int SEAT_HEAT = 0xB01;
    private static final int SEAT_VENT = 0xB02;

    // ================================================================= 关

    @Test
    public void offWritesZeroAndReadsBackToDone() {
        FakeCar car = car();
        car.putInt(POWER, FRONT, 1);
        FakeClock clock = new FakeClock();

        ClimateSource.Report report = ClimateSource.apply(car, names(), clock, off(false));

        assertEquals(ClimateSource.Outcome.DONE, report.outcome);
        assertFalse(report.powerOn);
        assertTrue(car.calls.contains("write " + POWER + "@" + FRONT + "=0"));
        assertEquals("one 250 ms step then the switch reads 0", 1, clock.sleeps);
        assertEquals(250, clock.slept);
    }

    @Test
    public void offZeroesBothSeatFunctionsWhenSeats() {
        FakeCar car = car();
        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), off(true));

        assertEquals(ClimateSource.Outcome.DONE, report.outcome);
        assertTrue(car.calls.contains("write " + POWER + "@" + FRONT + "=0"));
        assertTrue(car.calls.contains("write " + SEAT_HEAT + "@" + DRIVER + "=0"));
        assertTrue(car.calls.contains("write " + SEAT_VENT + "@" + DRIVER + "=0"));
    }

    @Test
    public void offWritesNoSeatFunctionWhenPlanSaysNoSeats() {
        FakeCar car = car();
        ClimateSource.apply(car, names(), new FakeClock(), off(false));

        assertFalse(car.calls.contains("write " + SEAT_HEAT + "@" + DRIVER + "=0"));
        assertFalse(car.calls.contains("write " + SEAT_VENT + "@" + DRIVER + "=0"));
    }

    @Test
    public void offNeverSettlesIsNoChangeAfterThreeSeconds() {
        FakeCar car = car();
        car.putInt(POWER, FRONT, 1);
        car.ignored.add(writeKey(POWER, FRONT));
        FakeClock clock = new FakeClock();

        ClimateSource.Report report = ClimateSource.apply(car, names(), clock, off(false));

        assertEquals(ClimateSource.Outcome.NO_CHANGE, report.outcome);
        assertTrue(report.powerOn);
        assertEquals(12, clock.sleeps);
        assertEquals(3000, clock.slept);
        assertTrue(report.detail, report.detail.contains("back power 0x1"));
    }

    // ================================================================= 开：读不到范围

    /**
     * 这台 7X 的现实（4.14.0 车上）：范围那几个号两个通道都不回值。
     *
     * <p>4.14.0 的规矩是「读不到范围就不写」，于是每一条 {@code ac on} 都回「车机不报温度范围」，
     * 整条路走不通。现在照车主说的整度写，写完用读回说话。</p>
     *
     * The 7X reality that 4.14.0 shipped against: the range ids answer on neither channel. The old rule
     * refused to write, so every "ac on" answered "this head unit will not report its temperature range".
     * The owner's whole degree is written now, and the readback reports what the car holds.
     */
    @Test
    public void anUnknownRangeStillWritesTheRequestedTarget() {
        FakeCar car = car();
        car.floats.remove(TEMP_MIN + "@" + DRIVER);
        car.floats.remove(TEMP_MAX + "@" + DRIVER);
        car.floats.remove(TEMP_STEP + "@" + DRIVER);

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(22f, true, true));

        assertEquals(ClimateSource.Outcome.DONE, report.outcome);
        assertEquals(22f, report.targetC, 1e-3f);
        assertTrue(car.calls.contains("writeFloat " + TEMP + "@" + DRIVER + "=22.0"));
        assertTrue(report.detail, report.detail.contains("range unknown"));
    }

    /**
     * 范围读不到时，车主说的半度原样写下去 —— 这台车的设定值就停在 19.5，四舍五入成 20 就是改他的温度。
     *
     * <p>与「范围已知、步长 0.5」那条的区别就在这：车没说自己的格子时，0.5 是底线，不是整度。</p>
     *
     * With no range from the car, the owner's half degree is written unchanged: this car holds 19.5, and
     * rounding that to 20 rewrites their setpoint. Unlike the "step 0.5 known" case, when the car says
     * nothing about its grid, 0.5 is the floor — not a whole degree.
     */
    @Test
    public void anUnknownRangeWritesAHalfDegreeUnchanged() {
        FakeCar car = car();
        car.floats.remove(TEMP_MIN + "@" + DRIVER);
        car.floats.remove(TEMP_MAX + "@" + DRIVER);
        car.floats.remove(TEMP_STEP + "@" + DRIVER);

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(22.5f, false, false));

        assertEquals(ClimateSource.Outcome.DONE, report.outcome);
        assertEquals("半度不能被平成 22 或 23", 22.5f, report.targetC, 1e-3f);
        assertTrue(car.calls.contains("writeFloat " + TEMP + "@" + DRIVER + "=22.5"));
        assertTrue(report.detail, report.detail.contains("range unknown"));
    }

    /** 范围读不到、车主说的是 22.3（只可能是设置里存的旧值）：照 0.5 对齐到 22.5，不是四舍五入成 22。 */
    @Test
    public void anUnknownRangeSnapsAnOffGridTargetToHalfADegree() {
        FakeCar car = car();
        car.floats.remove(TEMP_MIN + "@" + DRIVER);
        car.floats.remove(TEMP_MAX + "@" + DRIVER);
        car.floats.remove(TEMP_STEP + "@" + DRIVER);

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(22.3f, false, false));

        assertEquals(ClimateSource.Outcome.DONE, report.outcome);
        assertEquals(22.5f, report.targetC, 1e-3f);
        assertTrue(car.calls.contains("writeFloat " + TEMP + "@" + DRIVER + "=22.5"));
    }

    /** 一档 0.5 度的车：22.3 落在 22.5 那一格，而不是四舍五入回 22。 */
    @Test
    public void aKnownHalfStepSnapsAQuarterToTheUpperHalf() {
        FakeCar car = car();

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(22.3f, false, false));

        assertEquals(ClimateSource.Outcome.DONE, report.outcome);
        assertEquals(22.5f, report.targetC, 1e-3f);
        assertTrue(car.calls.contains("writeFloat " + TEMP + "@" + DRIVER + "=22.5"));
    }

    /**
     * 车机不说范围时的纯函数：半度原样、别的照 0.5 对齐，10–35 之外不写。
     *
     * The pure rule for "the car states no range": a half is kept, anything else snaps to 0.5, and
     * outside 10–35 nothing is written.
     */
    @Test
    public void atLeastPlausibleSnapsToHalfDegrees() {
        assertEquals(Float.valueOf(22.5f), ClimateSource.atLeastPlausible(22.5f));
        assertEquals("存了 22.3 读回来是 22.5", Float.valueOf(22.5f), ClimateSource.atLeastPlausible(22.3f));
        assertEquals(Float.valueOf(23f), ClimateSource.atLeastPlausible(22.9f));
        assertEquals(Float.valueOf(19.5f), ClimateSource.atLeastPlausible(19.5f));
        assertEquals(null, ClimateSource.atLeastPlausible(9.5f));
        assertEquals(null, ClimateSource.atLeastPlausible(35.5f));
        assertEquals(null, ClimateSource.atLeastPlausible(Float.NaN));
    }

    /**
     * 回执和黑匣子那一行都写一位小数：22.5 不能印成 22 或者 23。
     *
     * The receipt line and the black-box line both print one decimal: 22.5 never comes out as 22 or 23.
     */
    @Test
    public void reportPrintsOneDecimal() {
        ClimateSource.Report half = new ClimateSource.Report(
                ClimateSource.Outcome.DONE, 22.5f, true, true, false, "x");
        assertTrue(half.toString(), half.toString().contains("target=22.5"));
        ClimateSource.Report whole = new ClimateSource.Report(
                ClimateSource.Outcome.DONE, 22f, true, true, false, "x");
        assertTrue(whole.toString(), whole.toString().contains("target=22.0"));
    }

    @Test
    public void aRangeThatOnlyTheIntegerChannelAnswersIsUsed() {
        FakeCar car = car();
        car.floats.remove(TEMP_MIN + "@" + DRIVER);
        car.floats.remove(TEMP_MAX + "@" + DRIVER);
        car.floats.remove(TEMP_STEP + "@" + DRIVER);
        car.putInt(TEMP_MIN, DRIVER, 16);
        car.putInt(TEMP_MAX, DRIVER, 31);

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(22.4f, true, true));

        assertEquals(ClimateSource.Outcome.DONE, report.outcome);
        // 整型通道没有 0.5，按 1 度算：22.4 → 22.0
        assertEquals(22f, report.targetC, 1e-3f);
        assertTrue(report.detail, report.detail.contains("range 16.0..31.0"));
    }

    @Test
    public void aMissingStepNameDoesNotBlockTheWrite() {
        FakeCar car = car();

        ClimateSource.Report report = ClimateSource.apply(car, car.names.without("HVAC_FUNC_TEMP_STEP"),
                new FakeClock(), on(22f, true, true));

        assertEquals(ClimateSource.Outcome.DONE, report.outcome);
        assertTrue(car.calls.contains("writeFloat " + TEMP + "@" + DRIVER + "=22.0"));
    }

    // ================================================================= 开：单位

    /**
     * 实车报的那一台，就是 4.15.0 的 bug：{@code HVAC_FUNC_TEMP_UNIT} 回华氏，可它的设定值是 19.5。
     *
     * <p>4.15.0 只看单位号就拒，于是这辆摄氏度显示的车每一条 {@code ac} 都回「车报的是华氏度」。
     * 现在单位按设定值判：19.5 °F 这种设定不存在，所以是摄氏度 —— 写 22.0，不换算，也不拒。</p>
     *
     * The car that reported this, and the whole of 4.15.0's bug: the unit id answers Fahrenheit while the
     * setpoint holds 19.5. 4.15.0 refused on the unit id, so every "ac" from this Celsius car answered
     * "the car reports Fahrenheit". The unit now comes from the setpoint — 19.5 °F is not a setpoint anyone
     * can hold — so 22.0 is written in Celsius.
     */
    @Test
    public void aFahrenheitUnitIdWithACelsiusSetpointWritesCelsius() {
        FakeCar car = car();
        car.putInt(TEMP_UNIT, DRIVER, UNIT_F);
        car.putFloat(TEMP, DRIVER, 19.5f);

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(22f, true, true));

        assertEquals(ClimateSource.Outcome.DONE, report.outcome);
        assertEquals(22f, report.targetC, 1e-3f);
        assertTrue(car.calls.toString(), car.calls.contains("writeFloat " + TEMP + "@" + DRIVER + "=22.0"));
        assertFalse("判成华氏就会写 71.6：" + car.calls,
                car.calls.contains("writeFloat " + TEMP + "@" + DRIVER + "=71.6"));
        assertTrue(report.detail, report.detail.contains("setpoint before 19.5, celsius"));
    }

    /**
     * 单位号读得到、可 {@code TEMPERATURE_UNIT_C} 那个常量不在：以前一律拒。
     *
     * <p>设定值读得到的时候单位号根本没参与判断，缺这个常量就什么都不影响。</p>
     *
     * The unit id reads a value but the {@code TEMPERATURE_UNIT_C} constant is missing: this used to be a
     * blanket refusal. When the setpoint is readable the unit id plays no part at all, so a missing Celsius
     * constant changes nothing.
     */
    @Test
    public void aMissingCelsiusConstantDoesNotBlockACelsiusSetpoint() {
        FakeCar car = car();
        car.putInt(TEMP_UNIT, DRIVER, UNIT_F);
        car.putFloat(TEMP, DRIVER, 19.5f);

        ClimateSource.Report report = ClimateSource.apply(car, names().without("TEMPERATURE_UNIT_C"),
                new FakeClock(), on(22f, true, true));

        assertEquals(ClimateSource.Outcome.DONE, report.outcome);
        assertEquals(22f, report.targetC, 1e-3f);
        assertTrue(car.calls.toString(), car.calls.contains("writeFloat " + TEMP + "@" + DRIVER + "=22.0"));
        assertTrue(report.detail, report.detail.contains("unit id 0x10 (c=?"));
    }

    /** 设定值 68 就是华氏车：写下去的是换算过的 71.6，回执还是 22 °C。 */
    @Test
    public void aFahrenheitSetpointGetsTheConvertedTargetAndACelsiusReceipt() {
        FakeCar car = car();
        car.putFloat(TEMP, DRIVER, 68f);

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(22f, true, false));

        assertEquals(ClimateSource.Outcome.DONE, report.outcome);
        assertEquals("回执说的是摄氏度", 22f, report.targetC, 1e-3f);
        assertTrue(car.calls.toString(), car.calls.contains("writeFloat " + TEMP + "@" + DRIVER + "=71.6"));
        assertTrue(report.detail, report.detail.contains("setpoint before 68.0, fahrenheit"));
    }

    /**
     * 半度也要换算：华氏车写 22.5 °C 就是 72.5 °F，不是 71 也不是 73；回执还是 22.5 °C。
     *
     * <p>换算式本身没改，但半度是新的 —— 以前只有整度走这条，所以单独钉一条。</p>
     *
     * Halves convert too: 22.5 °C on a Fahrenheit car is 72.5 °F, not 71 or 73, and the receipt still says
     * 22.5 °C. The formula is unchanged, but halves are new on this path, so they get their own check.
     */
    @Test
    public void aFahrenheitSetpointConvertsAHalfDegreeTarget() {
        FakeCar car = car();
        car.putFloat(TEMP, DRIVER, 68f);

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(22.5f, true, false));

        assertEquals(ClimateSource.Outcome.DONE, report.outcome);
        assertEquals("回执说的是摄氏度", 22.5f, report.targetC, 1e-3f);
        assertTrue(car.calls.toString(), car.calls.contains("writeFloat " + TEMP + "@" + DRIVER + "=72.5"));
        assertTrue(report.detail, report.detail.contains("setpoint before 68.0, fahrenheit"));
    }

    /** 设定值读不到，单位号才有话语权：单位号说华氏，就按华氏换算着写。 */
    @Test
    public void anUnreadableSetpointFallsBackToTheFahrenheitUnitId() {
        FakeCar car = car();
        car.putInt(TEMP_UNIT, DRIVER, UNIT_F);
        car.nullCustomReads.add(TEMP);

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(22f, true, false));

        assertEquals(ClimateSource.Outcome.DONE, report.outcome);
        assertEquals(22f, report.targetC, 1e-3f);
        assertTrue(car.calls.toString(), car.calls.contains("writeFloat " + TEMP + "@" + DRIVER + "=71.6"));
        assertTrue(report.detail, report.detail.contains("setpoint before ?, fahrenheit"));
    }

    /** 设定值读不到、单位号也读不到：按摄氏度写，不猜华氏。 */
    @Test
    public void anUnreadableSetpointAndNoUnitIdMeansCelsius() {
        FakeCar car = car();
        car.nullCustomReads.add(TEMP);

        ClimateSource.Report report = ClimateSource.apply(car, names().without("HVAC_FUNC_TEMP_UNIT"),
                new FakeClock(), on(22f, true, false));

        assertEquals(ClimateSource.Outcome.DONE, report.outcome);
        assertEquals(22f, report.targetC, 1e-3f);
        assertTrue(car.calls.toString(), car.calls.contains("writeFloat " + TEMP + "@" + DRIVER + "=22.0"));
        assertTrue(report.detail, report.detail.contains("setpoint before ?, celsius"));
    }

    /**
     * 单位的分界：设定值 10–45 是摄氏度，45 以上是华氏，设定值读不到才看单位号。
     *
     * The boundary: a 10–45 setpoint is Celsius, above 45 is Fahrenheit, and the unit id only speaks when
     * the setpoint cannot be read.
     */
    @Test
    public void theUnitComesFromTheSetpointWithTheUnitIdAsTheFallback() {
        assertFalse("19.5 在摄氏范围里", ClimateSource.speaksFahrenheit(19.5f, UNIT_F, UNIT_F));
        assertFalse("45 还算摄氏度", ClimateSource.speaksFahrenheit(45f, UNIT_F, UNIT_F));
        assertTrue("46 以上一定是华氏", ClimateSource.speaksFahrenheit(46f, UNIT_C, UNIT_F));
        assertTrue("读不到才看单位号", ClimateSource.speaksFahrenheit(null, UNIT_F, UNIT_F));
        assertFalse(ClimateSource.speaksFahrenheit(null, UNIT_C, UNIT_F));
        assertFalse("两个都没有就是摄氏度", ClimateSource.speaksFahrenheit(null, null, null));
        assertEquals(32f, ClimateSource.celsiusToFahrenheit(0f), 1e-4f);
        assertEquals(71.6f, ClimateSource.celsiusToFahrenheit(22f), 1e-4f);
    }

    /** 华氏车的读回也按华氏比：半档 0.25 °C 的容差换算成 0.45 °F，±0.4 算做到。 */
    @Test
    public void aFahrenheitReadBackWithinToleranceIsDone() {
        for (float offset : new float[] {0.4f, -0.4f}) {
            FakeCar car = car();
            car.putFloat(TEMP, DRIVER, 68f);
            car.readBackOffset.put(TEMP + "@" + DRIVER, offset);
            FakeClock clock = new FakeClock();

            ClimateSource.Report report = ClimateSource.apply(car, names(), clock, on(22f, true, false));

            assertEquals("读回 71.6 " + offset + " 该算做到", ClimateSource.Outcome.DONE, report.outcome);
            assertEquals(22f, report.targetC, 1e-3f);
            assertEquals("第一次读回就对上了", 1, clock.sleeps);
        }
    }

    /** 华氏车的读回超出容差（±0.5 °F）就是没做到，哪怕读数看起来也像个整度。 */
    @Test
    public void aFahrenheitReadBackBeyondToleranceIsNoChange() {
        for (float offset : new float[] {0.5f, -0.5f}) {
            FakeCar car = car();
            car.putFloat(TEMP, DRIVER, 68f);
            car.readBackOffset.put(TEMP + "@" + DRIVER, offset);

            ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(22f, true, false));

            assertEquals("读回 71.6 " + offset + " 该算没变", ClimateSource.Outcome.NO_CHANGE, report.outcome);
            assertTrue("开关是开的，只是温度没对上", report.powerOn);
            assertEquals(22f, report.targetC, 1e-3f);
        }
    }

    // ================================================================= 开：范围

    @Test
    public void onRefusesTargetOutsideTheRangeByMoreThanOneStep() {
        FakeCar car = car();   // 16.0–31.0，一档 0.5

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(35f, true, true));

        assertEquals(ClimateSource.Outcome.REFUSED, report.outcome);
        assertNoWrites(car);
        assertEquals("没写就没有设定温度", 0f, report.targetC, 0f);
        assertTrue(report.detail, report.detail.contains("outside range"));
    }

    /** 以前超出一步以内夹回范围再写：那会写一个车主没要的温度。现在范围之外一律不写。 */
    @Test
    public void onRefusesATargetOneStepAboveTheRange() {
        FakeCar car = car();

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(31.4f, false, false));

        assertEquals(ClimateSource.Outcome.REFUSED, report.outcome);
        assertNoWrites(car);
    }

    @Test
    public void onRefusesATargetOneStepBelowTheRange() {
        FakeCar car = car();

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(15.7f, false, false));

        assertEquals(ClimateSource.Outcome.REFUSED, report.outcome);
        assertNoWrites(car);
    }

    @Test
    public void onSnapsToTheCarsFiveDegreeStep() {
        FakeCar car = car();
        car.putFloat(TEMP_STEP, DRIVER, 5f);

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(22.4f, false, false));

        assertEquals(ClimateSource.Outcome.DONE, report.outcome);
        assertEquals(21f, report.targetC, 1e-3f);   // 16 + round(6.4 / 5) * 5
        assertTrue(car.calls.contains("writeFloat " + TEMP + "@" + DRIVER + "=21.0"));
    }

    @Test
    public void onSnapsToAWholeDegreeStep() {
        FakeCar car = car();
        car.putFloat(TEMP_STEP, DRIVER, 1f);

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(22.4f, false, false));

        assertEquals(ClimateSource.Outcome.DONE, report.outcome);
        assertEquals(22f, report.targetC, 1e-3f);
        assertTrue(car.calls.contains("writeFloat " + TEMP + "@" + DRIVER + "=22.0"));
    }

    /** 这台车一档 0.5 度：22.4 落在 22.5 那一格上（不是 22.0 —— 整数通道给不出这个数）。 */
    @Test
    public void onSnapsToTheCarsHalfDegreeStep() {
        FakeCar car = car();

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(22.4f, false, false));

        assertEquals(ClimateSource.Outcome.DONE, report.outcome);
        assertEquals(22.5f, report.targetC, 1e-3f);
        assertTrue(car.calls.contains("writeFloat " + TEMP + "@" + DRIVER + "=22.5"));
    }

    @Test
    public void onKeepsATargetAlreadyOnTheCarsGrid() {
        FakeCar car = car();

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(23f, false, false));

        assertEquals(ClimateSource.Outcome.DONE, report.outcome);
        assertEquals(23f, report.targetC, 1e-3f);
        assertTrue(car.calls.contains("writeFloat " + TEMP + "@" + DRIVER + "=23.0"));
    }

    /** 半步写下去原样出现在浮点通道上：整型通道只会把 22.5 截成 22。 */
    @Test
    public void onWritesAHalfDegreeTargetThroughTheFloatChannel() {
        FakeCar car = car();

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(22.5f, false, false));

        assertEquals(ClimateSource.Outcome.DONE, report.outcome);
        assertEquals(22.5f, report.targetC, 1e-3f);
        assertTrue(car.calls.contains("writeFloat " + TEMP + "@" + DRIVER + "=22.5"));
        assertTrue("范围、步长都从浮点通道读：" + report.detail,
                report.detail.contains("range 16.0..31.0 step 0.50"));
    }

    // ================================================================= 开：读回

    /** 读回在半档以内（这里 0.4 档 = 0.2 度）算做到，正负都一样。 */
    @Test
    public void onReadBackWithinHalfAStepIsDone() {
        for (float offset : new float[] {0.2f, -0.2f}) {
            FakeCar car = car();
            car.readBackOffset.put(TEMP + "@" + DRIVER, offset);
            FakeClock clock = new FakeClock();

            ClimateSource.Report report = ClimateSource.apply(car, names(), clock, on(22.5f, false, false));

            assertEquals("读回 22.5 " + (offset > 0 ? "+" : "-") + " 0.2 该算做到",
                    ClimateSource.Outcome.DONE, report.outcome);
            assertEquals("第一次读回就对上了", 1, clock.sleeps);
        }
    }

    /** 读回超过半档（这里 0.6 档 = 0.3 度）就是没做到，哪怕读数看起来也像个整档。 */
    @Test
    public void onReadBackBeyondHalfAStepIsNoChange() {
        for (float offset : new float[] {0.3f, -0.3f}) {
            FakeCar car = car();
            car.readBackOffset.put(TEMP + "@" + DRIVER, offset);

            ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(22.5f, false, false));

            assertEquals("读回 22.5 " + (offset > 0 ? "+" : "-") + " 0.3 该算没变",
                    ClimateSource.Outcome.NO_CHANGE, report.outcome);
            assertTrue("开关是开的，只是温度没对上", report.powerOn);
            assertEquals(22.5f, report.targetC, 1e-3f);
        }
    }

    // ================================================================= 开：座椅档位

    @Test
    public void onSeatLevelZeroClampsToTheFirstStep() {
        FakeCar car = car();

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(),
                new ClimateSource.Plan(true, 22f, true, true, false, 0));

        assertEquals(ClimateSource.Outcome.DONE, report.outcome);
        assertTrue(car.calls.toString(),
                car.calls.contains("write " + SEAT_HEAT + "@" + DRIVER + "=" + (SEAT_HEAT + 1)));
        assertFalse(car.calls.contains("write " + SEAT_HEAT + "@" + DRIVER + "=" + SEAT_HEAT));
    }

    @Test
    public void onSeatLevelNineClampsToTheTopStep() {
        FakeCar car = car();

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(),
                new ClimateSource.Plan(true, 22f, true, false, true, 9));

        assertEquals(ClimateSource.Outcome.DONE, report.outcome);
        assertTrue(car.calls.toString(),
                car.calls.contains("write " + SEAT_VENT + "@" + DRIVER + "=" + (SEAT_VENT + 3)));
    }

    // ================================================================= 开：单位那个号不在

    /** 车机没有 {@code HVAC_FUNC_TEMP_UNIT}：当「不报单位」，照常写（设定值读得到，单位就不看它）。 */
    @Test
    public void onMissingUnitFunctionStillWrites() {
        FakeCar car = car();

        ClimateSource.Report report = ClimateSource.apply(car, names().without("HVAC_FUNC_TEMP_UNIT"),
                new FakeClock(), on(22f, true, true));

        assertEquals(ClimateSource.Outcome.DONE, report.outcome);
        assertEquals(22f, report.targetC, 1e-3f);
        assertTrue(car.calls.contains("writeFloat " + TEMP + "@" + DRIVER + "=22.0"));
        assertTrue(report.detail, report.detail.contains("no HVAC_FUNC_TEMP_UNIT"));
    }

    // ================================================================= 开：写

    @Test
    public void onWritesSetpointThenPowerAndReadsDone() {
        FakeCar car = car();
        FakeClock clock = new FakeClock();

        ClimateSource.Report report = ClimateSource.apply(car, names(), clock, on(22f, true, true));

        assertEquals(ClimateSource.Outcome.DONE, report.outcome);
        assertEquals(22f, report.targetC, 1e-3f);
        assertTrue(report.powerOn);
        int setpoint = car.calls.indexOf("writeFloat " + TEMP + "@" + DRIVER + "=22.0");
        int power = car.calls.indexOf("write " + POWER + "@" + FRONT + "=1");
        assertTrue("setpoint before power: " + car.calls, setpoint >= 0 && setpoint < power);
        assertTrue("power before seat: " + car.calls,
                power < car.calls.indexOf("write " + SEAT_HEAT + "@" + DRIVER + "=" + (SEAT_HEAT + 2)));
        assertEquals(1, clock.sleeps);
    }

    @Test
    public void onRefusedSetpointIsRefusedAndNoPowerWrite() {
        FakeCar car = car();
        car.customWrites = false;

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(22f, true, true));

        assertEquals(ClimateSource.Outcome.REFUSED, report.outcome);
        assertFalse(car.calls.contains("write " + POWER + "@" + FRONT + "=1"));
        assertFalse(car.calls.contains("write " + SEAT_HEAT + "@" + DRIVER + "=" + (SEAT_HEAT + 2)));
    }

    @Test
    public void onPowerWriteAcceptedButReadbackZeroIsNoChange() {
        FakeCar car = car();
        car.ignored.add(writeKey(POWER, FRONT));
        FakeClock clock = new FakeClock();

        ClimateSource.Report report = ClimateSource.apply(car, names(), clock, on(22f, true, true));

        assertEquals(ClimateSource.Outcome.NO_CHANGE, report.outcome);
        assertFalse(report.powerOn);
        assertTrue("开关那一笔车机受理了：读回看不到也是我们让它开的，自动关靠的就是这一位",
                report.powerWrote);
        assertTrue("座椅那一笔写了也受理了", report.seatWrote);
        assertEquals(22f, report.targetC, 1e-3f);
        assertEquals(12, clock.sleeps);
        assertTrue(report.detail, report.detail.contains("back power 0x0"));
        assertTrue(report.detail, report.detail.contains("want 22.0"));
    }

    /** 开关一笔写下去、车机拒了：{@code powerWrote} 必须说「没受理」（否则自动关会排在一个根本没开的车上，无害；
     * 反过来才是坏事 —— 受理了却说没受理，自动关就没了）。 */
    @Test
    public void aRefusedPowerWriteSaysSoInTheReport() {
        FakeCar car = car();
        car.refused.add(writeKey(POWER, FRONT));

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(22f, true, true));

        assertEquals(ClimateSource.Outcome.REFUSED, report.outcome);
        assertFalse(report.powerWrote);
    }

    /** 关：开关那一笔受理了就是 accepted，接到 {@code powerWrote} 上。 */
    @Test
    public void anAcceptedOffWriteReportsPowerWrote() {
        FakeCar car = car();

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), off(true));

        assertEquals(ClimateSource.Outcome.DONE, report.outcome);
        assertTrue(report.powerWrote);
    }

    @Test
    public void onRefusedPowerWriteIsRefused() {
        FakeCar car = car();
        car.refused.add(writeKey(POWER, FRONT));

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(22f, true, true));

        assertEquals(ClimateSource.Outcome.REFUSED, report.outcome);
        assertFalse(car.calls.contains("write " + SEAT_HEAT + "@" + DRIVER + "=" + (SEAT_HEAT + 2)));
    }

    @Test
    public void onRefusedSeatWriteKeepsTheClimateDone() {
        FakeCar car = car();
        car.refused.add(writeKey(SEAT_HEAT, DRIVER));

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(22f, true, true));

        assertEquals(ClimateSource.Outcome.DONE, report.outcome);
        assertTrue(report.detail, report.detail.contains("seat write refused"));
        assertTrue(report.detail, report.detail.contains("climate kept"));
        assertFalse("座椅没写进去：回执就不许说它", report.seatWrote);
    }

    /** 反方向：座椅真的写了并受理了，{@code seatWrote} 就是 true（否则上面那一条是空过）。 */
    @Test
    public void anAcceptedSeatWriteIsReportedAsSuch() {
        FakeCar car = car();

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(22f, true, true));

        assertEquals(ClimateSource.Outcome.DONE, report.outcome);
        assertTrue(report.seatWrote);
    }

    /** 不需要座椅时就不能声称写了座椅。 */
    @Test
    public void noSeatPlanMeansSeatWroteIsFalse() {
        FakeCar car = car();

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(22f, false, true));

        assertEquals(ClimateSource.Outcome.DONE, report.outcome);
        assertFalse(report.seatWrote);
    }

    @Test
    public void onVentilationOnlyWhenNoHeat() {
        FakeCar car = car();
        ClimateSource.Plan plan = new ClimateSource.Plan(true, 22f, true, false, true, 1);

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), plan);

        assertEquals(ClimateSource.Outcome.DONE, report.outcome);
        assertTrue(car.calls.contains("write " + SEAT_VENT + "@" + DRIVER + "=" + (SEAT_VENT + 1)));
        assertFalse(car.calls.contains("write " + SEAT_HEAT + "@" + DRIVER + "=" + (SEAT_HEAT + 1)));
    }

    @Test
    public void onNoSeatWriteWhenPlanSaysNoSeats() {
        FakeCar car = car();

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(22f, false, true));

        assertEquals(ClimateSource.Outcome.DONE, report.outcome);
        assertFalse(car.calls.contains("write " + SEAT_HEAT + "@" + DRIVER + "=" + (SEAT_HEAT + 2)));
        assertFalse(car.calls.contains("write " + SEAT_VENT + "@" + DRIVER + "=" + (SEAT_VENT + 2)));
    }

    @Test
    public void onMissingSeatFunctionDoesNotFailTheClimate() {
        FakeCar car = car();
        car.names.without("HVAC_FUNC_SEAT_HEATING");

        ClimateSource.Report report = ClimateSource.apply(car, car.names, new FakeClock(), on(22f, true, true));

        assertEquals(ClimateSource.Outcome.DONE, report.outcome);
        assertTrue(report.detail, report.detail.contains("no seat heating"));
    }

    /**
     * 开关读到 1、设定值读不回：结果是 {@code DONE}（空调确实开了），不是「没变」。
     *
     * <p>以前这里回 NO_CHANGE，两个后果：聊天里说了一句假话（车其实开了），自动关也排不上。
     * 读到的值仍然记在 detail 里。</p>
     */
    @Test
    public void onUnknownSetpointReadbackIsDoneWithANote() {
        FakeCar car = car();
        // 范围读得到、设定值读不回：通道在，那几个温度号就是不回值。
        car.nullCustomReads.add(TEMP);

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(22f, true, true));

        assertEquals(ClimateSource.Outcome.DONE, report.outcome);
        assertTrue(report.powerOn);
        assertTrue(report.detail, report.detail.contains("setpoint ?"));
        assertTrue(report.detail, report.detail.contains("power on, setpoint unreadable"));
    }

    /**
     * 开关读到 1、设定温度那个号整个适配层都不回值（这台 7X 就是）：空调确实开了，结果是 {@code DONE}，
     * 不是「没变」。当成没变会回一句假话，而且自动关排不上。
     *
     * <p>和上面那一条的区别在假车：上面是范围那几个号读得到、只有设定值不报；这一条是整条浮点通通不报。
     * 两条走的是同一个分支，但进来的情况不同 —— 4.14.0 在车上跘到的是这一个（适配层全空）。</p>
     *
     * Power reads 1 and every float id answers nothing at all on this adapter: the climate is on, so this is
     * DONE with a note, not NO_CHANGE — which would be a false receipt and would lose the auto-off. The fake
     * differs from the test above: there only the setpoint is silent, here the whole float channel is.
     */
    @Test
    public void powerOnWithAnUnreadableSetpointIsDoneNotNoChange() {
        FakeCar car = car();
        car.customReads = false;

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(22f, true, true));

        assertEquals(ClimateSource.Outcome.DONE, report.outcome);
        assertTrue(report.powerOn);
        assertTrue(report.powerWrote);
        assertEquals(22f, report.targetC, 1e-3f);
        assertTrue(report.detail, report.detail.contains("power on, setpoint unreadable"));
    }

    /** 设定值读得到但不对（22.0 → 26.0）：这是「没做到」，不是「读不到」。 */
    @Test
    public void aMismatchedSetpointStaysNoChangeEvenWithPowerOn() {
        FakeCar car = car();
        // 设定值写下去是 22.0，读回却是 26.0：读到的是个数，只是不对 —— 这就是「没做到」。
        car.readBackOffset.put(TEMP + "@" + DRIVER, 4f);

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(22f, false, false));

        assertEquals(ClimateSource.Outcome.NO_CHANGE, report.outcome);
        assertTrue("开关读回是 1", report.powerOn);
        assertFalse(report.seatWrote);
        assertTrue(report.detail, report.detail.contains("want 22.0"));
    }

    // ================================================================= 缺东西、出问题

    @Test
    public void missingPowerFunctionIsUnavailable() {
        FakeCar car = car();

        ClimateSource.Report report = ClimateSource.apply(car, names().without("HVAC_FUNC_POWER"),
                new FakeClock(), on(22f, true, true));

        assertEquals(ClimateSource.Outcome.UNAVAILABLE, report.outcome);
        assertNoWrites(car);
    }

    @Test
    public void missingTemperatureFunctionIsUnavailable() {
        FakeCar car = car();

        ClimateSource.Report report = ClimateSource.apply(car, names().without("HVAC_FUNC_TEMP"),
                new FakeClock(), on(22f, true, true));

        assertEquals(ClimateSource.Outcome.UNAVAILABLE, report.outcome);
        assertNoWrites(car);
    }

    @Test
    public void throwingCarCallIsUnavailableNotACrash() {
        FakeCar car = car();
        car.throwOn.add(TEMP_MIN);

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(22f, true, true));

        assertEquals(ClimateSource.Outcome.UNAVAILABLE, report.outcome);
        assertNoWrites(car);
        assertTrue(report.detail, report.detail.contains("failed"));
    }

    @Test
    public void noFloatChannelAtAllMeansTheWriteIsRefusedNotCrashed() {
        FakeCar car = car();
        car.customWrites = false;
        car.customReads = false;

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(22f, true, true));

        // 没有浮点通道 = 这台车机不能写温度：说清楚，不是「不报范围」。
        assertEquals(ClimateSource.Outcome.REFUSED, report.outcome);
        assertTrue(report.detail, report.detail.contains("write temp"));
    }

    @Test
    public void missingArgumentsAreUnavailable() {
        assertEquals(ClimateSource.Outcome.UNAVAILABLE,
                ClimateSource.apply(null, names(), new FakeClock(), on(22f, true, true)).outcome);
        assertEquals(ClimateSource.Outcome.UNAVAILABLE,
                ClimateSource.apply(car(), null, new FakeClock(), on(22f, true, true)).outcome);
        assertEquals(ClimateSource.Outcome.UNAVAILABLE,
                ClimateSource.apply(car(), names(), null, on(22f, true, true)).outcome);
        assertEquals(ClimateSource.Outcome.UNAVAILABLE,
                ClimateSource.apply(car(), names(), new FakeClock(), null).outcome);
    }

    // ================================================================= 名字、明细

    @Test
    public void idsAndZonesComeFromNames() {
        FakeCar car = car();

        ClimateSource.apply(car, car.names, new FakeClock(), on(22f, true, true));

        assertTrue(car.names.asked.contains("IHvac.HVAC_FUNC_POWER"));
        assertTrue(car.names.asked.contains("IHvac.HVAC_FUNC_TEMP"));
        assertTrue(car.names.asked.contains("IHvac.HVAC_FUNC_TEMP_MIN"));
        assertTrue(car.names.asked.contains("IHvac.HVAC_FUNC_TEMP_MAX"));
        assertTrue(car.names.asked.contains("IHvac.HVAC_FUNC_TEMP_STEP"));
        assertTrue(car.names.asked.contains("IHvac.HVAC_FUNC_TEMP_UNIT"));
        assertTrue(car.names.asked.contains("IHvac.TEMPERATURE_UNIT_C"));
        assertTrue(car.names.asked.contains("IHvac.HVAC_FUNC_SEAT_HEATING"));
        assertTrue(car.names.asked.contains("IHvac.HVAC_FUNC_SEAT_VENTILATION"));
        assertEquals(List.of("zone:ROW_1", "zone:ROW_1_LEFT"), car.names.zonesAsked);
        assertTrue("ids in hex: " + car.calls, car.calls.contains("writeFloat " + TEMP + "@" + DRIVER + "=22.0"));
    }

    @Test
    public void detailIsAsciiAndCarriesIdsAndValues() {
        FakeCar car = car();

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(22f, true, true));

        assertNotNull(report.detail);
        for (int i = 0; i < report.detail.length(); i++) {
            char c = report.detail.charAt(i);
            assertTrue("non-ascii at " + i + ": " + report.detail, c >= 0x20 && c < 0x7F);
        }
        assertTrue(report.detail, report.detail.contains("0x" + Integer.toHexString(POWER).toUpperCase()));
        assertTrue(report.detail, report.detail.contains("0x" + Integer.toHexString(TEMP).toUpperCase()));
        assertTrue(report.detail, report.detail.contains("read min@0x1=16.00"));
        assertTrue(report.detail, report.detail.contains("write temp"));
    }

    @Test
    public void detailStaysAsciiWhenTheErrorTextIsNot() {
        FakeCar car = car();
        car.throwOn.add(TEMP_MIN);
        car.throwText = "车机 出错";

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(22f, true, true));

        assertEquals(ClimateSource.Outcome.UNAVAILABLE, report.outcome);
        for (int i = 0; i < report.detail.length(); i++) {
            char c = report.detail.charAt(i);
            assertTrue("non-ascii at " + i + ": " + report.detail, c >= 0x20 && c < 0x7F);
        }
    }

    @Test
    public void snappingUsesTheCarsRange() {
        assertEquals(Float.valueOf(22f), ClimateSource.snapped(22f, 16, 30, 1));
        assertEquals(Float.valueOf(22f), ClimateSource.snapped(22.4f, 16, 30, 1));
        assertEquals(Float.valueOf(21f), ClimateSource.snapped(22.4f, 16, 30, 5));
        assertEquals("半步的车把 22.4 对到 22.5", Float.valueOf(22.5f),
                ClimateSource.snapped(22.4f, 16, 31, 0.5f));
        assertEquals("整数通道给不出这个值", Float.valueOf(22.5f),
                ClimateSource.snapped(22.5f, 16, 31, 0.5f));
        assertEquals(Float.valueOf(30f), ClimateSource.snapped(30f, 16, 30, 1));
        assertEquals("范围外一步都不行", null, ClimateSource.snapped(30.6f, 16, 30, 1));
        assertEquals(null, ClimateSource.snapped(31.5f, 16, 30, 1));
        assertEquals(null, ClimateSource.snapped(15.4f, 16, 30, 1));
        assertEquals(null, ClimateSource.snapped(14.9f, 16, 30, 1));
        assertEquals(null, ClimateSource.snapped(22f, 16, 30, 0));
        assertEquals(null, ClimateSource.snapped(22f, 30, 16, 1));
    }

    // ================================================================= 假的

    private static ClimateSource.Plan off(boolean seats) {
        return new ClimateSource.Plan(false, 0f, seats, true, false, 2);
    }

    private static ClimateSource.Plan on(float targetC, boolean seats, boolean heat) {
        return new ClimateSource.Plan(true, targetC, seats, heat, !heat, 2);
    }

    private static String writeKey(int id, int zone) {
        return id + "@" + zone;
    }

    private static void assertNoWrites(FakeCar car) {
        for (String call : car.calls) {
            assertFalse("unexpected write: " + car.calls, call.startsWith("write "));
            assertFalse("unexpected float write: " + car.calls, call.startsWith("writeFloat "));
        }
    }

    /** 车机照 {@link #car()} 给的范围：16.0–31.0，一档 0.5（温度类的号走浮点通道，整数读会把 0.5 截成 0）。 */
    private static FakeCar car() {
        FakeCar car = new FakeCar();
        car.putFloat(TEMP_MIN, DRIVER, 16f);
        car.putFloat(TEMP_MAX, DRIVER, 31f);
        car.putFloat(TEMP_STEP, DRIVER, 0.5f);
        car.putInt(TEMP_UNIT, DRIVER, UNIT_C);
        car.putInt(POWER, FRONT, 0);
        car.putFloat(TEMP, DRIVER, 21f);
        return car;
    }

    private static FakeNames names() {
        return new FakeNames();
    }

    /** 常量名 → 号码：真的走 {@code CarNames}，测试里随便编。 */
    private static final class FakeNames implements ClimateSource.Names {
        final Map<String, Integer> functions = new HashMap<>();
        final Map<String, Integer> zones = new HashMap<>();
        final List<String> asked = new ArrayList<>();
        final List<String> zonesAsked = new ArrayList<>();

        FakeNames() {
            functions.put("IHvac.HVAC_FUNC_POWER", POWER);
            functions.put("IHvac.HVAC_FUNC_TEMP", TEMP);
            functions.put("IHvac.HVAC_FUNC_TEMP_MIN", TEMP_MIN);
            functions.put("IHvac.HVAC_FUNC_TEMP_MAX", TEMP_MAX);
            functions.put("IHvac.HVAC_FUNC_TEMP_STEP", TEMP_STEP);
            functions.put("IHvac.HVAC_FUNC_TEMP_UNIT", TEMP_UNIT);
            functions.put("IHvac.TEMPERATURE_UNIT_C", UNIT_C);
            functions.put("IHvac.TEMPERATURE_UNIT_F", UNIT_F);
            functions.put("IHvac.HVAC_FUNC_SEAT_HEATING", SEAT_HEAT);
            functions.put("IHvac.HVAC_FUNC_SEAT_VENTILATION", SEAT_VENT);
            zones.put("ROW_1", FRONT);
            zones.put("ROW_1_LEFT", DRIVER);
        }

        FakeNames without(String field) {
            functions.remove("IHvac." + field);
            return this;
        }

        @Override
        public Integer function(String iface, String field) {
            asked.add(iface + "." + field);
            return functions.get(iface + "." + field);
        }

        @Override
        public int zone(String name) {
            zonesAsked.add("zone:" + name);
            Integer zone = zones.get(name);
            return zone == null ? 0 : zone;
        }
    }

    /** 假的等待：睡是假的，只记下睡了几次、多久。 */
    private static final class FakeClock implements ClimateSource.Clock {
        int sleeps;
        long slept;

        @Override
        public void sleep(long ms) {
            sleeps++;
            slept += ms;
        }
    }

    /**
     * 假的车辆接口：值按「号@区域」存；写了照写进去，也可以不受理（{@link #refused}）、受理了不变（{@link #ignored}）、
     * 读的时候抛（{@link #throwOn}）。浮点通道可以整个没有（{@link #customWrites}、{@link #customReads}）；
     * 读回可以比写下去的多（少）一点（{@link #readBackOffset}）。
     */
    private static final class FakeCar implements CarAccess {
        final FakeNames names = new FakeNames();
        final Map<String, Integer> ints = new HashMap<>();
        final Map<String, Float> floats = new HashMap<>();
        /** 读回时加上的零头：车机把设定值存成了不是原样的数。 */
        final Map<String, Float> readBackOffset = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        final Set<String> refused = new HashSet<>();
        final Set<String> ignored = new HashSet<>();
        final Set<Integer> throwOn = new HashSet<>();
        /** 浮点通道里少数几个号不回报值（范围报、设定值不报）。 */
        final Set<Integer> nullCustomReads = new HashSet<>();
        boolean customWrites = true;
        boolean customReads = true;
        String throwText = "boom";

        void putInt(int id, int zone, int value) {
            ints.put(writeKey(id, zone), value);
        }

        void putFloat(int id, int zone, float value) {
            floats.put(writeKey(id, zone), value);
        }

        @Override
        public Integer read(int id) {
            calls.add("readPlain " + id);
            return ints.get(String.valueOf(id));
        }

        @Override
        public Integer read(int id, int zone) {
            calls.add("read " + id + "@" + zone);
            if (throwOn.contains(id)) {
                throw new IllegalStateException(throwText);
            }
            return ints.get(writeKey(id, zone));
        }

        @Override
        public boolean write(int id, int value) {
            calls.add("writePlain " + id + "=" + value);
            return true;
        }

        @Override
        public boolean write(int id, int zone, int value) {
            calls.add("write " + id + "@" + zone + "=" + value);
            if (refused.contains(writeKey(id, zone))) {
                return false;
            }
            if (!ignored.contains(writeKey(id, zone))) {
                ints.put(writeKey(id, zone), value);
            }
            return true;
        }

        @Override
        public String status(int id, int zone) {
            return "active";
        }

        @Override
        public Integer config(int n) {
            // 这次合并进来的车辆接口多了「查配置」那一项；温度这条路不用它。
            return null;
        }

        @Override
        public Float sensor(int type) {
            return null;
        }

        @Override
        public Float readFloat(int id, int zone) {
            calls.add("readFloat " + id + "@" + zone);
            if (throwOn.contains(id)) {
                throw new IllegalStateException(throwText);
            }
            if (!customReads || nullCustomReads.contains(id)) {
                return null;
            }
            Float value = floats.get(writeKey(id, zone));
            Float offset = readBackOffset.get(writeKey(id, zone));
            if (value == null || offset == null) {
                return value;
            }
            return Float.valueOf(value + offset);
        }

        @Override
        public boolean writeFloat(int id, int zone, float value) {
            calls.add(String.format(java.util.Locale.US, "writeFloat %d@%d=%.1f", id, zone, value));
            if (!customWrites) {
                return false;
            }
            if (!ignored.contains(writeKey(id, zone))) {
                floats.put(writeKey(id, zone), value);
            }
            return true;
        }
    }
}

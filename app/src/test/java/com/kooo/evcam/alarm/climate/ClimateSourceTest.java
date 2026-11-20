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

    @Test
    public void onRefusesWhenMinValueIsMissingAndWritesNothing() {
        FakeCar car = car();
        car.floats.remove(TEMP_MIN + "@" + DRIVER);

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(22f, true, true));

        assertEquals(ClimateSource.Outcome.NO_RANGE, report.outcome);
        assertNoWrites(car);
        assertEquals(0f, report.targetC, 0f);
    }

    @Test
    public void onRefusesWhenStepIsUnreadable() {
        FakeCar car = car();
        car.names.without("HVAC_FUNC_TEMP_STEP");

        ClimateSource.Report report = ClimateSource.apply(car, car.names, new FakeClock(), on(22f, true, true));

        assertEquals(ClimateSource.Outcome.NO_RANGE, report.outcome);
        assertNoWrites(car);
    }

    // ================================================================= 开：单位

    @Test
    public void onRefusesWhenUnitIsNotCelsiusAndWritesNothing() {
        FakeCar car = car();
        car.putInt(TEMP_UNIT, DRIVER, UNIT_F);

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(22f, true, true));

        assertEquals(ClimateSource.Outcome.WRONG_UNIT, report.outcome);
        assertNoWrites(car);
    }

    @Test
    public void onRefusesWhenUnitReadsAValueButCelsiusConstantIsMissing() {
        FakeCar car = car();

        ClimateSource.Report report = ClimateSource.apply(car, names().without("TEMPERATURE_UNIT_C"),
                new FakeClock(), on(22f, true, true));

        assertEquals(ClimateSource.Outcome.WRONG_UNIT, report.outcome);
        assertNoWrites(car);
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

    /** 车机没有 {@code HVAC_FUNC_TEMP_UNIT}：当「不报单位」，照常写（不是 WRONG_UNIT，也不是 NO_RANGE）。 */
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
        assertEquals(22f, report.targetC, 1e-3f);
        assertEquals(12, clock.sleeps);
        assertTrue(report.detail, report.detail.contains("back power 0x0"));
        assertTrue(report.detail, report.detail.contains("want 22.0"));
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

    @Test
    public void onUnknownSetpointReadbackIsNoChange() {
        FakeCar car = car();
        // 范围读得到、设定值读不回：通道在，那几个温度号就是不回值。
        car.nullCustomReads.add(TEMP);

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(22f, true, true));

        assertEquals(ClimateSource.Outcome.NO_CHANGE, report.outcome);
        assertTrue(report.powerOn);
        assertTrue(report.detail, report.detail.contains("setpoint ?"));
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
    public void defaultFloatChannelMeansNoRangeSoNothingIsWritten() {
        FakeCar car = car();
        car.customWrites = false;
        car.customReads = false;

        ClimateSource.Report report = ClimateSource.apply(car, names(), new FakeClock(), on(22f, true, true));

        assertEquals("读不到范围就不写", ClimateSource.Outcome.NO_RANGE, report.outcome);
        assertNoWrites(car);
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

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
 * 空调探针（{@link ClimateProbe}）：区域、状态、写、读回、恢复、出错。
 *
 * <p>车辆接口、常量名、等待都是假的，号码随便编的（只看规则）；等是假的，测试不睡。每一条都钉住<b>方向</b>：
 * 主驾被拒就该落在副驾上、开关开过就得关回去、本来就开着的就得留着 —— 把行为反过来，这些断言都是红的。</p>
 *
 * The probe over a fake car: zones, status, writes, readback, restore, and errors. Every assertion pins the
 * direction, so reversing the behaviour turns it red: a refusal on the driver zone must land on the
 * passenger zone, power the probe switched on must go back off, and power that was already on stays on.
 */
public class ClimateProbeTest {

    private static final int FRONT = 0x8;
    private static final int DRIVER = 0x1;
    private static final int PASSENGER = 0x4;

    private static final int POWER = 0x821;
    private static final int TEMP = 0xA01;
    private static final int TEMP_MIN = 0xA02;
    private static final int TEMP_MAX = 0xA03;
    private static final int TEMP_STEP = 0xA04;
    private static final int TEMP_UNIT = 0xA05;
    private static final int UNIT_C = 1;
    private static final int UNIT_F = 0x10;
    private static final int SEAT_HEAT = 0xB01;
    private static final int SEAT_VENT = 0xB02;
    private static final int AIRCON = 0xC01;
    private static final int AUTO = 0xC02;

    // ================================================================= 设定温度落在哪个区域

    /**
     * 主驾那一笔被拒、副驾那一笔受理：报告要写清楚落在副驾上，车上也只有副驾那个值变了。
     *
     * <p>这就是车上「ac 22 什么都没改」要问出来的那一件事：同一个功能号，主驾不受理，副驾受理 ——
     * 换句话说拒的不是号码，是区域。</p>
     *
     * The driver write is refused and the passenger write accepted: the report must say which zone took it,
     * and only that zone's value may have changed. This is the thing worth asking about "ac 22 changed
     * nothing": the id is fine, the zone was not.
     */
    @Test
    public void aRefusedDriverSetpointFallsBackToThePassengerZone() {
        FakeCar car = car();
        car.refuseFloat(TEMP, DRIVER);

        ClimateProbe.Report report = ClimateProbe.run(car, car.names, new FakeClock(), 22f);

        assertTrue(report.text(), report.says("write TEMP ROW_1_LEFT=22.0 refused"));
        assertTrue(report.text(), report.says("write TEMP ROW_1_RIGHT=22.0 ok"));
        assertTrue("要说出来落在哪个区域: " + report.text(),
                report.says("temp accepted zone=ROW_1_RIGHT"));
        assertTrue("副驾那个值真的写过 22.0: " + car.writes,
                car.writes.contains("f:" + TEMP + "@" + PASSENGER + "=22.0"));
        assertTrue("写完之后车上的设定温度要还原: " + report.text(),
                report.says("temp restored to 21.0"));
        assertEquals("副驾被他设的 21.0 回到了原样", Float.valueOf(21f),
                car.floats.get("f:" + TEMP + "@" + PASSENGER));
        assertEquals("主驾那个值没被动过", Float.valueOf(21f), car.floats.get("f:" + TEMP + "@" + DRIVER));
    }

    /** 主驾受理的时候不去碰副驾：反过来（先试副驾、或者两个都写）这条就红。 */
    @Test
    public void anAcceptedDriverSetpointLeavesThePassengerZoneAlone() {
        FakeCar car = car();

        ClimateProbe.Report report = ClimateProbe.run(car, car.names, new FakeClock(), 22f);

        assertTrue(report.text(), report.says("temp accepted zone=ROW_1_LEFT"));
        assertTrue(car.writes.contains("f:" + TEMP + "@" + DRIVER + "=22.0"));
        assertFalse("副驾没被写: " + car.writes,
                car.writes.contains("f:" + TEMP + "@" + PASSENGER + "=22.0"));
        assertTrue("写完之后车上的设定温度要还原: " + report.text(),
                report.says("temp restored to 21.0"));
        assertEquals(Float.valueOf(21f), car.floats.get("f:" + TEMP + "@" + DRIVER));
        assertEquals("副驾没被写", Float.valueOf(21f), car.floats.get("f:" + TEMP + "@" + PASSENGER));
    }

    /**
     * 落地在副驾时，恢复用的是<b>副驾自己</b>原来那个值，不是主驾读到的那个。
     *
     * <p>两个区域各停一个温度：主驾 21.0、副驾 24.0。这一笔落在副驾上，就得把副驾放回 24.0；
     * 拿主驾的 21.0 去恢复等于改了副驾的温度。</p>
     *
     * The restore uses the accepting zone's own previous value, not the driver zone's: a car holding 21.0 on
     * the driver side and 24.0 on the passenger side must end with both unchanged.
     */
    @Test
    public void theSetpointIsRestoredFromTheZoneThatTookIt() {
        FakeCar car = car();
        car.putFloat(TEMP, PASSENGER, 24f);
        car.refuseFloat(TEMP, DRIVER);

        ClimateProbe.Report report = ClimateProbe.run(car, car.names, new FakeClock(), 22f);

        assertTrue(report.text(), report.says("temp accepted zone=ROW_1_RIGHT"));
        assertTrue("恢复的是副驾原来的 24.0: " + report.text(), report.says("temp restored to 24.0"));
        assertEquals(Float.valueOf(24f), car.floats.get("f:" + TEMP + "@" + PASSENGER));
        assertEquals(Float.valueOf(21f), car.floats.get("f:" + TEMP + "@" + DRIVER));
    }

    // ================================================================= 功能号的状态

    /**
     * 前排说 notavailable、主驾说 active：两行各说各的，换过来就红。
     *
     * <p>这正是「应不应写这个区域」的那一问：ECARX 的 {@code isFunctionSupported} 以前从来没问过。</p>
     *
     * Front says notavailable while the driver zone says active: each zone reports its own answer, so
     * swapping them turns this red.
     */
    @Test
    public void powerStatusIsReportedPerZone() {
        FakeCar car = car();
        car.statuses.put(POWER + "@" + FRONT, "notavailable");
        car.statuses.put(POWER + "@" + DRIVER, "active");

        ClimateProbe.Report report = ClimateProbe.run(car, car.names, new FakeClock(), 22f);

        assertTrue(report.text(), report.says("status POWER ROW_1=notavailable"));
        assertTrue(report.text(), report.says("status POWER ROW_1_LEFT=active"));
        assertTrue(report.text(), report.says("status POWER ROW_1_RIGHT="));
        assertTrue("不带区域那一问没有 API，要说清楚: " + report.text(),
                report.says("status POWER nozone=n/a"));
        assertTrue(report.text(), report.says("status TEMP ROW_1_LEFT="));
        assertTrue(report.text(), report.says("status TEMP ROW_1_RIGHT="));
    }

    /** 车机不回话（问不了）是 null：报告写 unaskable，不写 active 也不写 notactive。 */
    @Test
    public void anUnaskableStatusIsReportedAsUnaskable() {
        FakeCar car = car();
        car.statuses.put(POWER + "@" + FRONT, null);

        ClimateProbe.Report report = ClimateProbe.run(car, car.names, new FakeClock(), 22f);

        assertTrue(report.text(), report.says("status POWER ROW_1=unaskable"));
        assertFalse(report.text(), report.says("status POWER ROW_1=active"));
    }

    // ================================================================= 开关的恢复

    /**
     * 本来是关着的：探针把它打开，最后必须写回 0，而且车上的值是 0 —— 最后那一笔写就是关。
     *
     * <p>不恢复的话，车主点一下探针就等于让空调自己开起来，而且没有十分钟后自己关那一套兜着。</p>
     *
     * It was off: the probe switches it on and must write 0 back, and the car must read 0 — the last write
     * is the one that turns it off. Without that, tapping the probe starts the climate with no auto-off behind it.
     */
    @Test
    public void powerTheProbeSwitchedOnIsSwitchedBackOff() {
        FakeCar car = car();
        car.putInt(POWER, FRONT, 0);

        ClimateProbe.Report report = ClimateProbe.run(car, car.names, new FakeClock(), 22f);

        assertTrue(report.text(), report.says("write POWER ROW_1=1 ok"));
        assertTrue("要说出来恢复成了什么: " + report.text(), report.says("power was 0, restored 0"));
        assertEquals("车上的开关得是关着的", Integer.valueOf(0), car.ints.get("i:" + POWER + "@" + FRONT));
        assertEquals("最后一笔写是关（不然空调留在开着）",
                "i:" + POWER + "@" + FRONT + "=0", car.writes.get(car.writes.size() - 1));
        assertTrue(report.text(), report.says("back POWER ROW_1=0 after "));
    }

    /**
     * 本来就开着：留着开着，并且明说没动 —— 探针不该替车主关空调。
     *
     * 反过来（把本来开着的关掉）这条就红：车上读到 0，而且多了一笔写。
     */
    @Test
    public void powerThatWasAlreadyOnIsLeftOn() {
        FakeCar car = car();
        car.putInt(POWER, FRONT, 1);

        ClimateProbe.Report report = ClimateProbe.run(car, car.names, new FakeClock(), 22f);

        assertTrue(report.text(), report.says("power was 1 before, left on"));
        assertEquals("本来就开着，留着", Integer.valueOf(1), car.ints.get("i:" + POWER + "@" + FRONT));
        assertFalse("不该写关: " + car.writes, car.writes.contains("i:" + POWER + "@" + FRONT + "=0"));
    }

    /** 前排那一笔被拒、主驾受理：开的是主驾那一笔，恢复也恢复在主驾上（区域跟着那一笔走）。 */
    @Test
    public void thePowerIsRestoredOnTheZoneThatAcceptedIt() {
        FakeCar car = car();
        car.refuseInt(POWER, FRONT);

        ClimateProbe.Report report = ClimateProbe.run(car, car.names, new FakeClock(), 22f);

        assertTrue(report.text(), report.says("write POWER ROW_1=1 refused"));
        assertTrue(report.text(), report.says("write POWER ROW_1_LEFT=1 ok"));
        assertEquals(Integer.valueOf(0), car.ints.get("i:" + POWER + "@" + DRIVER));
        assertEquals("前排那一路没写过 1", Integer.valueOf(0), car.ints.get("i:" + POWER + "@" + FRONT));
        assertEquals("最后一笔是主驾那一笔关",
                "i:" + POWER + "@" + DRIVER + "=0", car.writes.get(car.writes.size() - 1));
    }

    /** 前排、主驾都被拒，不带区域的那一路受理：走第三笔，恢复也走不带区域的那一路。 */
    @Test
    public void theUnzonedWriteIsTheLastResort() {
        FakeCar car = car();
        car.refuseInt(POWER, FRONT);
        car.refuseInt(POWER, DRIVER);

        ClimateProbe.Report report = ClimateProbe.run(car, car.names, new FakeClock(), 22f);

        assertTrue(report.text(), report.says("write POWER nozone=1 ok"));
        assertTrue(report.text(), report.says("power accepted nozone"));
        assertEquals(Integer.valueOf(0), car.plain.get(String.valueOf(POWER)));
    }

    // ================================================================= 出错、缺号

    /**
     * 车接口抛异常：变成一行 ERROR，探针照旧往下走（写、读回、恢复都还在）。
     *
     * <p>一个探针在第一步崩掉等于什么都没说；「每一步都包着」就是这个意思。</p>
     *
     * A throwing car call becomes an ERROR line and the probe carries on: a probe that dies on the first
     * call says nothing at all.
     */
    @Test
    public void aThrowingCarCallBecomesALineAndTheProbeGoesOn() {
        FakeCar car = car();
        car.throwOnRead.add(TEMP_MIN);

        ClimateProbe.Report report = ClimateProbe.run(car, car.names, new FakeClock(), 22f);

        assertTrue(report.text(), report.says("ERROR read TEMP_MIN"));
        assertTrue("抛了也要接着写: " + report.text(), report.says("write TEMP ROW_1_LEFT=22.0 ok"));
        assertTrue("抛了也要恢复开关: " + report.text(), report.says("power was 0, restored 0"));
        assertEquals(Integer.valueOf(0), car.ints.get("i:" + POWER + "@" + FRONT));
    }

    /** {@code status} 抛异常也只是一行：这一问本来就可能没有这个方法。 */
    @Test
    public void aThrowingStatusBecomesALine() {
        FakeCar car = car();
        car.throwOnStatus = true;

        ClimateProbe.Report report = ClimateProbe.run(car, car.names, new FakeClock(), 22f);

        assertTrue(report.text(), report.says("ERROR status POWER@0x8"));
        assertTrue("状态问不了，别的照旧: " + report.text(), report.says("write TEMP ROW_1_LEFT=22.0 ok"));
    }

    /** 这台车机上没有这个常量：报告写 missing，接着探下一个号。 */
    @Test
    public void aMissingFunctionNameIsReportedAsMissing() {
        FakeCar car = car();
        car.names.without("HVAC_FUNC_AC");

        ClimateProbe.Report report = ClimateProbe.run(car, car.names, new FakeClock(), 22f);

        assertTrue(report.text(), report.says("func HVAC_FUNC_AC missing"));
        assertFalse("有号码的那个不该写 missing: " + report.text(),
                report.says("func HVAC_FUNC_POWER missing"));
        assertTrue(report.text(), report.says("func HVAC_FUNC_POWER=0x821"));
    }

    /** 少了开关那个号：开关一笔都不写（写不了），设定温度、读回、恢复那些照样跑。 */
    @Test
    public void aMissingPowerIdWritesNoSwitchAtAll() {
        FakeCar car = car();
        car.names.without("HVAC_FUNC_POWER");

        ClimateProbe.Report report = ClimateProbe.run(car, car.names, new FakeClock(), 22f);

        assertTrue(report.text(), report.says("func HVAC_FUNC_POWER missing"));
        assertTrue(report.text(), report.says("write POWER nozone: no id, nothing written"));
        for (String write : car.writes) {
            assertFalse("开关那个号都没有，不该写: " + write, write.startsWith("i:" + POWER));
        }
    }

    // ================================================================= 形状

    /** 一行一件事、每一行都是 ASCII、都不长：车主是拍它的，字太宽就拍不全。 */
    @Test
    public void everyLineIsShortAsciiAndCarriesOneFact() {
        FakeCar car = car();
        car.refuseFloat(TEMP, DRIVER);

        ClimateProbe.Report report = ClimateProbe.run(car, car.names, new FakeClock(), 22.5f);

        assertNotNull(report.lines);
        assertFalse("不能是空的", report.lines.isEmpty());
        for (String line : report.lines) {
            assertFalse("有空行", line.trim().isEmpty());
            assertTrue("不是 ASCII：" + line, isAscii(line));
            assertTrue("太长（" + line.length() + "）：" + line, line.length() <= 80);
        }
        assertTrue(report.text(), report.says("climate probe configured target=22.5"));
    }

    // ================================================================= 那个 20

    /**
     * 「target 20」那个误会：那一行写的是<b>设置里的默认值</b>，不是上一条命令要的温度。
     *
     * <p>车主发的是 {@code ac 22}，探针却印了 target 20 —— 他会以为车把 22 改成了 20。
     * 现在配置那个写 {@code configured target=20.0}，命令那个写 {@code your last ac target=22.0}，
     * 两行都在，谁也不冒充谁。</p>
     *
     * The 20 that was never asked for: that line is the configured default, not what the last command
     * wanted. The owner sent {@code ac 22} and the probe printed target 20, so it read as if the car had
     * moved the setpoint. Configured and commanded are now two lines, and neither pretends to be the other.
     */
    @Test
    public void theConfiguredDefaultAndTheLastCommandGetOneLineEach() {
        FakeCar car = car();

        ClimateProbe.Report report = ClimateProbe.run(car, car.names, new FakeClock(), 20f, 22f);

        assertTrue("配置的那一个: " + report.text(), report.says("climate probe configured target=20.0"));
        assertTrue("命令的那一个: " + report.text(), report.says("your last ac target=22.0"));
        assertFalse("旧的那一行不能再印：它分不清哪一个是哪一个: " + report.text(),
                report.says("climate probe target="));
    }

    /** 还没下过 {@code ac}（传 0）：命令那一行就不出现，也不印成 {@code your last ac target=0.0}。 */
    @Test
    public void withNoLastCommandThereIsNoCommandedLine() {
        FakeCar car = car();

        ClimateProbe.Report report = ClimateProbe.run(car, car.names, new FakeClock(), 22f, 0f);

        assertTrue(report.says("climate probe configured target=22.0"));
        assertFalse("没下过命令就不该有这一行: " + report.text(),
                report.says("your last ac target="));
        // 老的四参数入口就是这个意思：只有配置值。
        assertFalse(ClimateProbe.run(car, car.names, new FakeClock(), 22f).says("your last ac target="));
    }

    /** 一次探针不会睡过头：每一觉都只是读回那一步，最长 3 秒。 */
    @Test
    public void nothingSleepsLongerThanTheReadback() {
        FakeCar car = car();
        FakeClock clock = new FakeClock();

        ClimateProbe.run(car, car.names, clock, 22f);

        assertFalse("探针不该一次都不读回", clock.sleeps.isEmpty());
        for (Long slept : clock.sleeps) {
            assertEquals("每一觉都是读回那一步", 250L, slept.longValue());
        }
    }

    /** 少参数不是崩：一行 ERROR。 */
    @Test
    public void missingArgumentsAreALineNotACrash() {
        ClimateProbe.Report report = ClimateProbe.run(null, names(), new FakeClock(), 22f);

        assertEquals(1, report.lines.size());
        assertTrue(report.lines.get(0), report.lines.get(0).startsWith("ERROR"));
    }

    // ================================================================= 假的

    private static boolean isAscii(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c < 0x20 || c >= 0x7F) {
                return false;
            }
        }
        return true;
    }

    /** 车机：16.0–31.0，一档 0.5；设定值停在 21.0；开关关着；范围、单位都读得到。 */
    private static FakeCar car() {
        FakeCar car = new FakeCar();
        car.putFloat(TEMP_MIN, DRIVER, 16f);
        car.putFloat(TEMP_MAX, DRIVER, 31f);
        car.putFloat(TEMP_STEP, DRIVER, 0.5f);
        car.putInt(TEMP_UNIT, DRIVER, UNIT_C);
        car.putFloat(TEMP, DRIVER, 21f);
        car.putFloat(TEMP, PASSENGER, 21f);
        car.putInt(POWER, FRONT, 0);
        car.putInt(POWER, DRIVER, 0);
        car.putInt(POWER, PASSENGER, 0);
        car.plain.put(String.valueOf(POWER), 0);
        car.statuses.put(POWER + "@" + FRONT, "active");
        car.statuses.put(POWER + "@" + DRIVER, "active");
        car.statuses.put(POWER + "@" + PASSENGER, "active");
        car.statuses.put(TEMP + "@" + DRIVER, "active");
        car.statuses.put(TEMP + "@" + PASSENGER, "active");
        return car;
    }

    private static FakeNames names() {
        return new FakeNames();
    }

    /** 常量名 → 号码：真的走 {@code CarNames} 那条路，测试里随便编。 */
    private static final class FakeNames implements ClimateSource.Names {
        final Map<String, Integer> functions = new HashMap<>();
        final Map<String, Integer> zones = new HashMap<>();

        FakeNames() {
            functions.put("IHvac.HVAC_FUNC_POWER", POWER);
            functions.put("IHvac.HVAC_FUNC_TEMP", TEMP);
            functions.put("IHvac.HVAC_FUNC_TEMP_MIN", TEMP_MIN);
            functions.put("IHvac.HVAC_FUNC_TEMP_MAX", TEMP_MAX);
            functions.put("IHvac.HVAC_FUNC_TEMP_STEP", TEMP_STEP);
            functions.put("IHvac.HVAC_FUNC_TEMP_UNIT", TEMP_UNIT);
            functions.put("IHvac.TEMPERATURE_UNIT_C", UNIT_C);
            functions.put("IHvac.TEMPERATURE_UNIT_F", UNIT_F);
            functions.put("IHvac.HVAC_FUNC_AC", AIRCON);
            functions.put("IHvac.HVAC_FUNC_AUTO", AUTO);
            functions.put("IHvac.HVAC_FUNC_SEAT_HEATING", SEAT_HEAT);
            functions.put("IHvac.HVAC_FUNC_SEAT_VENTILATION", SEAT_VENT);
            zones.put("ROW_1", FRONT);
            zones.put("ROW_1_LEFT", DRIVER);
            zones.put("ROW_1_RIGHT", PASSENGER);
        }

        FakeNames without(String field) {
            functions.remove("IHvac." + field);
            return this;
        }

        @Override
        public Integer function(String iface, String field) {
            return functions.get(iface + "." + field);
        }

        @Override
        public int zone(String name) {
            Integer zone = zones.get(name);
            return zone == null ? 0 : zone;
        }
    }

    /** 假的等待：睡是假的，只记下每一觉睡了多久。 */
    private static final class FakeClock implements ClimateSource.Clock {
        final List<Long> sleeps = new ArrayList<>();

        @Override
        public void sleep(long ms) {
            sleeps.add(ms);
        }
    }

    /**
     * 假的车辆接口：值按「号@区域」存，写进去就改；可以按号 + 区域拒写（{@link #refuseInt} /
     * {@link #refuseFloat}）、按号抛异常（{@link #throwOnRead}）、{@link #statuses} 里没填的就是 null。
     */
    private static final class FakeCar implements CarAccess {
        final FakeNames names = new FakeNames();
        final Map<String, Integer> ints = new HashMap<>();
        final Map<String, Float> floats = new HashMap<>();
        /** 不带区域的那一路（{@code read(int)} / {@code write(int, int)}）。 */
        final Map<String, Integer> plain = new HashMap<>();
        /** 「号@区域」→ 车机的话；没填就是问不了（null）。 */
        final Map<String, String> statuses = new HashMap<>();
        final Set<String> refused = new HashSet<>();
        final Set<Integer> throwOnRead = new HashSet<>();
        /** 每一笔写，按发生的次序（「i:」整型、「f:」浮点）。 */
        final List<String> writes = new ArrayList<>();
        boolean throwOnStatus;

        void putInt(int id, int zone, int value) {
            ints.put("i:" + id + "@" + zone, value);
        }

        void putFloat(int id, int zone, float value) {
            floats.put("f:" + id + "@" + zone, value);
        }

        void refuseInt(int id, int zone) {
            refused.add("i:" + id + "@" + zone);
        }

        void refuseFloat(int id, int zone) {
            refused.add("f:" + id + "@" + zone);
        }

        @Override
        public Integer read(int id) {
            return plain.get(String.valueOf(id));
        }

        @Override
        public Integer read(int id, int zone) {
            if (throwOnRead.contains(id)) {
                throw new IllegalStateException("boom");
            }
            return ints.get("i:" + id + "@" + zone);
        }

        @Override
        public boolean write(int id, int value) {
            String key = "plain:" + id;
            writes.add("p:" + id + "=" + value);
            if (refused.contains(key)) {
                return false;
            }
            plain.put(String.valueOf(id), value);
            return true;
        }

        @Override
        public boolean write(int id, int zone, int value) {
            String key = "i:" + id + "@" + zone;
            writes.add(key + "=" + value);
            if (refused.contains(key)) {
                return false;
            }
            ints.put(key, value);
            return true;
        }

        @Override
        public Float readFloat(int id, int zone) {
            if (throwOnRead.contains(id)) {
                throw new IllegalStateException("boom");
            }
            return floats.get("f:" + id + "@" + zone);
        }

        @Override
        public boolean writeFloat(int id, int zone, float value) {
            String key = "f:" + id + "@" + zone;
            writes.add(String.format(java.util.Locale.US, "%s=%.1f", key, value));
            if (refused.contains(key)) {
                return false;
            }
            floats.put(key, value);
            return true;
        }

        @Override
        public String status(int id, int zone) {
            if (throwOnStatus) {
                throw new IllegalStateException("no such method");
            }
            return statuses.get(id + "@" + zone);
        }

        @Override
        public Integer config(int n) {
            return null;
        }

        @Override
        public Float sensor(int type) {
            return null;
        }
    }
}

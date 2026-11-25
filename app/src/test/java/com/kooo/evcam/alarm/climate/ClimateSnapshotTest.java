/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to this code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.climate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
 * 快照那一趟读（{@link ClimateSnapshot}）：只读不写、读不到的记成未知、占位值不算值、读到什么存什么
 * （快照这一头不换算单位）。车辆接口、常量名、等待都是假的（号码随便编的，只看规则）；等是假的，测试不睡。
 *
 * <h3>为什么这几条非钉不可</h3>
 *
 * <p>自动关要把「车主原本的设置」原样放回去，靠的就是这一趟读。读不到就是未知（未知是合法的答案），
 * 未知不许变成任何一个编出来的默认值 —— 一旦这里开始猜，放回车主车上的就是一个他没要过的设置。
 * 占位值 253、255 是适配层的「这个号不回值」，不是读数（recirculation findings §2、
 * {@link ClimateSource} 类注释）：当成值放回去，就是往车上写一个编出来的档位。
 * 读数原样存，单位那一刀由写的那条路（{@link ClimateSource#speaksFahrenheit}）来砍，这边不许先砍。</p>
 *
 * The read-only pass behind restore. What cannot be read stays unknown — a legal grade — and an unknown
 * must never become an invented default, or the restore puts back a setting the owner never had. 253 and
 * 255 are the adapter's "this id answers nothing", not readings; storing them as values writes an invented
 * level back to the car. A reading is stored verbatim — the unit cut belongs to the write path
 * ({@link ClimateSource#speaksFahrenheit}), not to this side.
 */
public class ClimateSnapshotTest {

    private static final int FRONT = 0x8;
    private static final int DRIVER = 0x1;
    private static final int PASSENGER = 0x4;
    private static final int ROW_2 = 0x2;

    private static final int POWER = 0x821;
    private static final int TEMP = 0xA01;
    private static final int SEAT_HEAT = 0xB01;
    private static final int SEAT_VENT = 0xB02;
    /** 内循环 / 外循环 / 自动那三个号之外的「读不到」占位值（假车上随便给一个，由 {@link FakeNames} 发给被测代码）。 */
    private static final int CIRC = 0xB03;
    private static final int TEMP_UNIT = 0xA05;
    private static final int UNIT_F = 0x10;

    /** 快照九个字段都在假车上读得到的那一版：值见 {@link #car()}，动过的字段由各个测试自己改。 */
    private static final int ALL_FIELDS = 9;

    // ================================================================= 只读

    /**
     * 读这一趟一笔都不写车：它是快照，不是动作。写了，就是还没到自动关就先把车主的设置改了。
     *
     * The read pass never writes: it is a snapshot, not an action. A write here would change an owner
     * setting before the auto-off ever fired.
     */
    @Test
    public void theReadPassNeverWrites() {
        FakeCar car = car();

        ClimateSnapshot.Snapshot s = ClimateSnapshot.read(car, names());

        assertEquals(ALL_FIELDS, s.knownCount());
        assertNoWrites(car);
    }

    // ================================================================= 未知

    /**
     * 读不到的字段：存 null，自动关那一趟不许为它写车，而且要在明细里点它的名字（黑匣子要知道是哪个没放回）。
     *
     * <p>这一条要能红：把「读不到」当成 0（当成「关」）写回去，车主的座椅就自己关了。别的字段照写，
     * 证明「没写」不是整趟空过。明细断言钉的是字段名的字面（ASCII，小写）—— 句子本身不限，见执行日志。</p>
     *
     * An unreadable field is stored as null: restore must not write a car value for it, and must name it
     * in detail (ASCII) — the black box has to show which field did not come back. Other drifted fields
     * are still written, so the "not written" is not a vacuous pass. The assertion pins the field's name
     * literally; the surrounding wording is not fixed (see the execution log).
     */
    @Test
    public void anUnknownFieldProducesNoWrite() {
        FakeCar car = car();
        car.ints.remove(String.valueOf(CIRC));   // 这一台车机上循环模式这个号不回值 —— 未知

        ClimateSnapshot.Snapshot s = ClimateSnapshot.read(car, names());

        assertNull(s.circulation);
        assertEquals("八个字段仍是已知的（别把 null 断成全场空过）", ALL_FIELDS - 1, s.knownCount());

        drift(car);   // 其余字段都动了：照理都要写回去，唯独那个未知的不许写
        ClimateSource.applyRestore(car, names(), new FakeClock(), s);

        assertFalse("读不到的那个号一笔都不许写: " + car.calls,
                car.calls.toString().contains("writePlain " + CIRC + "="));
        assertTrue("别的动了的字段照写: " + car.calls,
                car.calls.contains("writeFloat " + TEMP + "@" + DRIVER + "=22.0"));
    }

    /** 上面那条的明细检查单独走一遍：明细从 applyRestore 的报告来（Report 是 {@link ClimateSource} 的）。 */
    @Test
    public void anUnknownFieldIsNamedInDetailWhenRestored() {
        FakeCar car = car();
        car.ints.remove(String.valueOf(CIRC));

        ClimateSnapshot.Snapshot s = ClimateSnapshot.read(car, names());
        drift(car);
        ClimateSource.Report report = ClimateSource.applyRestore(car, names(), new FakeClock(), s);

        assertTrue("明细要点出是哪一个未知: " + report.detail,
                report.detail.toLowerCase(java.util.Locale.US).contains("circulation"));
        for (int i = 0; i < report.detail.length(); i++) {
            char c = report.detail.charAt(i);
            assertTrue("non-ascii at " + i + ": " + report.detail, c >= 0x20 && c < 0x7F);
        }
    }

    // ================================================================= 占位值

    /**
     * 253、255 是适配层的「这个号不回值」，不是读数：存成未知，绝不写回车上。
     *
     * <p>这台 7X 的整型通道就是这么骗人的（{@link ClimateSource} 类注释：HVAC_FUNC_TEMP 的整数读只回占位
     * 值 253；recirculation findings §2 同样的两个号）。当成档位存下来，自动关就会往车上写一个编出来的
     * 255。读的那一刻之后把通道改成真值再跑自动关：真存了占位值的实现会在这一刻写车。</p>
     *
     * 253 and 255 are the adapter's "no value" answers, not readings: stored as unknown and never written
     * back. This 7X's integer channel answers exactly that way (ClimateSource class doc: the integer read
     * for HVAC_FUNC_TEMP only ever returns 253; recirculation findings §2, the same two numbers). Read as
     * levels, restore would write an invented 255 to the car. The channels are changed to real values
     * after the snapshot and restore runs: an implementation that stored a placeholder writes at that point.
     */
    @Test
    public void aPlaceholderReadingIsStoredAsUnknown() {
        FakeCar car = car();
        car.putInt(SEAT_HEAT, DRIVER, 253);
        car.putInt(CIRC, 255);

        ClimateSnapshot.Snapshot s = ClimateSnapshot.read(car, names());

        assertNull("座椅那一笔读回 253 是「不回值」", s.seatHeatLeft);
        assertNull("循环那一笔读回 255 是「不回值」", s.circulation);
        assertEquals(ALL_FIELDS - 2, s.knownCount());

        // 读的那一刻之后才动这两个通道：正确的实现（未知）什么都不写；把占位值当了值的实现会写回 253/255。
        car.putInt(SEAT_HEAT, DRIVER, SEAT_HEAT + 2);
        car.putInt(CIRC, CIRC + 1);
        ClimateSource.applyRestore(car, names(), new FakeClock(), s);

        assertFalse("未知的字段不许写: " + car.calls,
                car.calls.toString().contains("write " + SEAT_HEAT + "@" + DRIVER + "="));
        assertFalse(car.calls.toString().contains("writePlain " + CIRC + "="));
    }

    /** 一个号读成占位值不许把别的字段也带崩：别的照存、照写回。 */
    @Test
    public void theOtherFieldsAreKeptWhenOneReadIsAPlaceholder() {
        FakeCar car = car();
        car.putInt(CIRC, 255);

        ClimateSnapshot.Snapshot s = ClimateSnapshot.read(car, names());

        assertNull(s.circulation);
        assertEquals(Integer.valueOf(1), s.rearPower);
        assertEquals(Float.valueOf(22f), s.driverSetpointC);

        car.putInt(SEAT_VENT, DRIVER, SEAT_VENT + 3);   // 动一下，让它必须写回快照里的 0 档
        ClimateSource.applyRestore(car, names(), new FakeClock(), s);

        assertTrue("快照里的 0 档是真值，照写回: " + car.calls,
                car.calls.contains("write " + SEAT_VENT + "@" + DRIVER + "=0"));
        assertFalse(car.calls.toString().contains("writePlain " + CIRC + "="));
    }

    // ================================================================= 零是真值

    /**
     * 0 是真读数（关 / 0 档），不是「没读到」：照存，自动关照着写回。
     *
     * <p>把 0 当成没有，就会把本来关着的 rear power「保持」成不知所谓 —— 而车主上车看到的就该是关着的零。</p>
     *
     * A zero is a reading (off / level 0), not an absence: it is stored as a value, and restore writes it
     * back. Nulling zeros would leave a genuinely-off rear zone as a shrug, and the owner would get in to
     * find the wrong thing.
     */
    @Test
    public void aLegitimateZeroIsAKnownValue() {
        FakeCar car = car();
        car.putInt(POWER, FRONT, 0);
        car.putInt(POWER, ROW_2, 0);
        car.putInt(SEAT_HEAT, DRIVER, 0);

        ClimateSnapshot.Snapshot s = ClimateSnapshot.read(car, names());

        assertEquals(Integer.valueOf(0), s.frontPowerC);
        assertEquals(Integer.valueOf(0), s.rearPower);
        assertEquals(Integer.valueOf(0), s.seatHeatLeft);

        // 动一下开关那边的读数（自动关那一步本来就会把它关掉）：0 是已知值，照写回。
        car.putInt(SEAT_HEAT, DRIVER, SEAT_HEAT + 1);
        car.putInt(POWER, ROW_2, 1);
        ClimateSource.applyRestore(car, names(), new FakeClock(), s);

        assertTrue("0 是真值，照写回: " + car.calls,
                car.calls.contains("write " + SEAT_HEAT + "@" + DRIVER + "=0"));
        assertTrue(car.calls.toString(), car.calls.contains("write " + POWER + "@" + ROW_2 + "=0"));
        assertFalse("前排开关不在写回名单里: " + car.calls,
                car.calls.toString().contains("write " + POWER + "@" + FRONT));
    }

    // ================================================================= 原样存

    /**
     * 华氏车（设定值读回 68 / 73.4 —— 45 以上那一档，按写那条路的规矩是华氏）：快照存的是通道上的原数，
     * 不减回去；单位那一刀归写的那条路砍，不在这边预砍。
     *
     * <p>这一条钉的是「快照存的是车在那个通道上hold的数」。这边先换算，那边再一换算，写回去的就成了
     * 两次换算 —— 正是 4.15.0 那类 bug 的形状。</p>
     *
     * On a Fahrenheit car (setpoints 68 / 73.4 — above the write path's Fahrenheit line) the snapshot keeps
     * the channel's number as read. Converting on this side and converting on the write side is a
     * double-conversion — the exact shape of the 4.15.0 bug family.
     */
    @Test
    public void aReadValueIsKeptVerbatimOnAFahrenheitCar() {
        FakeCar car = car();
        car.putFloat(TEMP, DRIVER, 68f);
        car.putFloat(TEMP, PASSENGER, 73.4f);
        car.putInt(TEMP_UNIT, DRIVER, UNIT_F);   // HVAC_FUNC_TEMP_UNIT 那个号（假车上 0xA05）

        ClimateSnapshot.Snapshot s = ClimateSnapshot.read(car, names());

        assertEquals(Float.valueOf(68f), s.driverSetpointC);
        assertEquals(Float.valueOf(73.4f), s.passengerSetpointC);
        assertEquals(ALL_FIELDS, s.knownCount());

        // 谁都没动过 —— 读回来还是原样 —— 所以自动关那一趟一笔都不写（改了才写）。
        ClimateSource.applyRestore(car, names(), new FakeClock(), s);
        assertNoWrites(car);
    }

    // ================================================================= 计数

    /** {@code knownCount} 数的就是「不是 null 的字段」：九个里读回七个，就是七。 */
    @Test
    public void knownCountCountsTheFieldsThatAreNotNull() {
        FakeCar car = car();
        car.floats.remove(TEMP + "@" + DRIVER);
        car.ints.remove(writeKey(SEAT_HEAT, DRIVER));

        ClimateSnapshot.Snapshot s = ClimateSnapshot.read(car, names());

        assertNull(s.driverSetpointC);
        assertNull(s.seatHeatLeft);
        assertEquals(7, s.knownCount());
        assertTrue(ClimateSnapshot.anyKnown(s));
    }

    /** 全空的快照：没有一个已知，{@code anyKnown} 是 false —— 自动关那一趟对它什么都不做。 */
    @Test
    public void anyKnownIsFalseForAFullyUnknownSnapshot() {
        FakeCar car = new FakeCar();   // 什么都不回的适配层

        ClimateSnapshot.Snapshot s = ClimateSnapshot.read(car, names());

        assertNull(s.frontPowerC);
        assertEquals(0, s.knownCount());
        assertFalse(ClimateSnapshot.anyKnown(s));
        assertNoWrites(car);
    }

    /** 只要有一个字段是读到的，{@code anyKnown} 就是 true：一行放回也比全空强，值得跑那一趟。 */
    @Test
    public void anyKnownIsTrueWhenAtLeastOneFieldIsKnown() {
        FakeCar car = new FakeCar();
        car.putInt(POWER, ROW_2, 1);   // 只有这一项读得到

        ClimateSnapshot.Snapshot s = ClimateSnapshot.read(car, names());

        assertEquals(1, s.knownCount());
        assertTrue(ClimateSnapshot.anyKnown(s));
        assertNoWrites(car);
    }

    // ================================================================= 名字、区域

    /**
     * 号、区域、常量名全从 {@link ClimateSource.Names} 来（测试里随便编）：前排两个区域、后排区域、
     * 还有循环模式那个号都要真的问到 —— 写死的号一旦漏进来，这条就红。
     *
     * Every id, zone and constant is resolved through {@link ClimateSource.Names}: both front zones, the
     * second row, and the circulation constant are really asked for. A hardcoded id slipping in goes red
     * here.
     */
    @Test
    public void everyFieldIsResolvedThroughNamesAndZones() {
        FakeCar car = car();

        ClimateSnapshot.Snapshot s = ClimateSnapshot.read(car, car.names);

        assertEquals(ALL_FIELDS, s.knownCount());
        assertTrue(car.names.asked.contains("IHvac.HVAC_FUNC_POWER"));
        assertTrue(car.names.asked.contains("IHvac.HVAC_FUNC_TEMP"));
        assertTrue(car.names.asked.contains("IHvac.HVAC_FUNC_SEAT_HEATING"));
        assertTrue(car.names.asked.contains("IHvac.HVAC_FUNC_SEAT_VENTILATION"));
        assertTrue(car.names.asked.contains("IHvac.HVAC_FUNC_CIRCULATION"));
        for (String zone : new String[] {"zone:ROW_1", "zone:ROW_1_LEFT", "zone:ROW_1_RIGHT",
                "zone:ROW_2"}) {
            assertTrue("区域要从 Names 问: " + car.names.zonesAsked, car.names.zonesAsked.contains(zone));
        }
        assertNoWrites(car);
    }

    // ================================================================= 假车、假名字、假等待

    /** 快照读出来之后的「车主命令 + 自动关」：把所有通道改成别的值，于是每一个已知字段都必须被写回。 */
    private static void drift(FakeCar car) {
        car.putFloat(TEMP, DRIVER, 25f);
        car.putFloat(TEMP, PASSENGER, 25f);
        car.putInt(SEAT_HEAT, DRIVER, 0);
        car.putInt(SEAT_VENT, DRIVER, SEAT_VENT + 3);
        car.putInt(SEAT_HEAT, PASSENGER, SEAT_HEAT + 3);
        car.putInt(SEAT_VENT, PASSENGER, 0);
        car.putInt(CIRC, CIRC + 2);
        car.putInt(POWER, FRONT, 0);
        car.putInt(POWER, ROW_2, 0);
    }

    private static void assertNoWrites(FakeCar car) {
        for (String call : car.calls) {
            assertFalse("unexpected write: " + car.calls, call.startsWith("write "));
            assertFalse("unexpected write: " + car.calls, call.startsWith("writeFloat "));
            assertFalse("unexpected write: " + car.calls, call.startsWith("writePlain "));
        }
    }

    private static String writeKey(int id, int zone) {
        return id + "@" + zone;
    }

    /** 九个字段都读得到的假车：前排开关 1、设定值 22.0（两区）、主驾加热 2 档、副驾通风 2 档、内循环、后排 1。 */
    private static FakeCar car() {
        FakeCar car = new FakeCar();
        car.putInt(POWER, FRONT, 1);
        car.putFloat(TEMP, DRIVER, 22f);
        car.putFloat(TEMP, PASSENGER, 22f);
        car.putInt(SEAT_HEAT, DRIVER, SEAT_HEAT + 2);
        car.putInt(SEAT_VENT, DRIVER, 0);
        car.putInt(SEAT_HEAT, PASSENGER, 0);
        car.putInt(SEAT_VENT, PASSENGER, SEAT_VENT + 2);
        car.putInt(CIRC, CIRC + 1);
        car.putInt(POWER, ROW_2, 1);
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
            functions.put("IHvac.HVAC_FUNC_SEAT_HEATING", SEAT_HEAT);
            functions.put("IHvac.HVAC_FUNC_SEAT_VENTILATION", SEAT_VENT);
            functions.put("IHvac.HVAC_FUNC_CIRCULATION", CIRC);
            functions.put("IHvac.HVAC_FUNC_TEMP_UNIT", TEMP_UNIT);
            zones.put("ROW_1", FRONT);
            zones.put("ROW_1_LEFT", DRIVER);
            zones.put("ROW_1_RIGHT", PASSENGER);
            zones.put("ROW_2", ROW_2);
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
        /** 浮点通道上被拒的号（车机说这个区域不受理）：整型和浮点是两条路。 */
        final Set<String> refusedFloats = new HashSet<>();
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

        void putInt(int id, int value) {
            ints.put(String.valueOf(id), value);
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
            if (refused.contains(String.valueOf(id))) {
                return false;
            }
            if (!ignored.contains(String.valueOf(id))) {
                ints.put(String.valueOf(id), value);
            }
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
            // 这次合并进来的车辆接口多了「查配置」那一项；快照这条路不用它。
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
            if (!customWrites || refusedFloats.contains(writeKey(id, zone))) {
                return false;
            }
            if (!ignored.contains(writeKey(id, zone))) {
                floats.put(writeKey(id, zone), value);
            }
            return true;
        }
    }
}

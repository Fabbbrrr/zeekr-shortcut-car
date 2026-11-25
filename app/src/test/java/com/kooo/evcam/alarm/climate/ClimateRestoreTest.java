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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 自动关之后的回放（{@link ClimateSource#applyRestore}）：只写动过又已知的那些字段，号、区域、值一个一个钉住；
 * 读回来跟快照一样的不许动笔；单位那一刀照写那条路的规矩（{@link ClimateSource#speaksFahrenheit}）来砍；
 * 一笔被拒不许把后面拦住（要被拒了停下来，车主剩下的设置就永远回不来）；全空的快照一笔都不写。
 *
 * <h3>为什么这几条非钉不可</h3>
 *
 * <p>这是「车回原样」那句话兑现的地方：车主回来看到的是快照里那一份，不是编出来的默认值。前排开关不在
 * 写回名单里（「绝不为车主自动开空调」还作数）；一个号试一次、不排队等读回（写不动就认，别在车机最可能
 * 睡着的时候打读回拉锯）。被拒的那一笔要说出来 —— 说了才有黑匣子，别说成成功。</p>
 *
 * Where the "the car comes back as you left it" promise is kept: only fields that drifted and are known
 * get written, ids/zones/values pinned one by one; a field that reads back equal to the snapshot is left
 * alone; the unit cut follows the write path's rule ({@link ClimateSource#speaksFahrenheit}); one
 * refusal must not stop the rest of the pass — stopping on a refusal is how an owner's remaining settings
 * never come back; a fully-null snapshot writes nothing. Front power is not on the write-back list
 * ("never start climate automatically" still holds); one attempt per field, no dwell loop. A refused
 * write is said out loud — that is what the black box is for.
 */
public class ClimateRestoreTest {

    private static final int FRONT = 0x8;
    private static final int DRIVER = 0x1;
    private static final int PASSENGER = 0x4;
    private static final int ROW_2 = 0x2;

    private static final int POWER = 0x821;
    private static final int TEMP = 0xA01;
    private static final int SEAT_HEAT = 0xB01;
    private static final int SEAT_VENT = 0xB02;
    private static final int CIRC = 0xB03;
    private static final int TEMP_UNIT = 0xA05;
    private static final int UNIT_C = 1;
    private static final int UNIT_F = 0x10;

    // ================================================================= 写回名单

    /**
     * 快照里知道、而且跟现在不一样：一笔一笔写回去，号、区域、值都要对得上（主副驾的档位各归各的，
     * 串了区域就是给错的座椅放错的档位）。前排开关不在名单里（第 3 条单独钉）。
     *
     * Known in the snapshot and different now: every one gets its snapshot value written back with the
     * right id, zone and value — the two seats' levels stay on their own sides; mixing zones puts the
     * wrong level on the wrong seat. Front power is not on the list (pinned on its own below).
     */
    @Test
    public void everyDriftedKnownFieldGetsItsSnapshotValueWrittenBack() {
        FakeCar car = car();

        ClimateSnapshot.Snapshot s = ClimateSnapshot.read(car, names());
        assertEquals(9, s.knownCount());
        ownerCommandAndAutoOff(car);

        ClimateSource.applyRestore(car, names(), new FakeClock(), s);

        assertTrue(car.calls.toString(), car.calls.contains("writeFloat " + TEMP + "@" + DRIVER + "=22.0"));
        assertTrue(car.calls.toString(), car.calls.contains("writeFloat " + TEMP + "@" + PASSENGER + "=22.0"));
        assertTrue(car.calls.toString(), car.calls.contains("write " + SEAT_HEAT + "@" + DRIVER + "=2819"));
        assertTrue(car.calls.toString(), car.calls.contains("write " + SEAT_VENT + "@" + DRIVER + "=0"));
        assertTrue(car.calls.toString(), car.calls.contains("write " + SEAT_HEAT + "@" + PASSENGER + "=0"));
        assertTrue(car.calls.toString(), car.calls.contains("write " + SEAT_VENT + "@" + PASSENGER + "=2820"));
        assertTrue(car.calls.toString(), car.calls.contains("writePlain " + CIRC + "=2820"));
        assertTrue(car.calls.toString(), car.calls.contains("write " + POWER + "@" + ROW_2 + "=1"));
    }

    /**
     * 主驾加热、副驾加热各归各：值从快照里各自的字段来，区域从_names_里各自的号来 —— 两条串了这条就红。
     *
     * Driver heat and passenger heat keep their own levels on their own zones: each value comes from its
     * own snapshot field, each zone from its own name. Cross either wire and this goes red.
     */
    @Test
    public void eachSeatKeepsItsOwnLevelOnItsOwnZone() {
        FakeCar car = car();

        ClimateSnapshot.Snapshot s = ClimateSnapshot.read(car, names());
        ownerCommandAndAutoOff(car);
        ClimateSource.applyRestore(car, names(), new FakeClock(), s);

        assertTrue("主驾那笔要带主驾的档位: " + car.calls,
                car.calls.contains("write " + SEAT_HEAT + "@" + DRIVER + "=2819"));
        assertTrue("副驾那笔要带副驾自己的（快照里是 0 档）: " + car.calls,
                car.calls.contains("write " + SEAT_HEAT + "@" + PASSENGER + "=0"));
    }

    /**
     * 读回来跟快照一样：不动笔。没动的设置被写一遍，车主看到的就不是他没动过的那一项了（这是全等，
     * 不涉容差，怎么比都该判「一样」）。别的字段照写，证明这条不是空过。
     *
     * A field that reads back equal to the snapshot is not touched: writing something that never moved
     * means the owner gets back a setting they never had. This is exact equality — no tolerance involved.
     * The other fields are still written, so this is not a vacuous pass.
     */
    @Test
    public void aFieldAlreadyEqualAtReadbackIsSkipped() {
        FakeCar car = car();

        ClimateSnapshot.Snapshot s = ClimateSnapshot.read(car, names());
        ownerCommandAndAutoOff(car);
        car.putFloat(TEMP, PASSENGER, 22f);   // 只有副驾那一侧没被动过 —— 它不许被写
        ClimateSource.applyRestore(car, names(), new FakeClock(), s);

        assertFalse("没动的不许写: " + car.calls,
                car.calls.toString().contains("writeFloat " + TEMP + "@" + PASSENGER + "="));
        assertTrue("动了的照写: " + car.calls,
                car.calls.contains("writeFloat " + TEMP + "@" + DRIVER + "=22.0"));
        assertTrue(car.calls.toString(), car.calls.contains("write " + SEAT_HEAT + "@" + DRIVER + "=2819"));
    }

    /**
     * 前排开关被记住了，但永远不写回去：「绝不为车主自动开空调」还作数 —— 自动关把车关上之后，
     * 就该是关着的，哪怕快照里它是 1。别的字段照写（这条不是空过）。
     *
     * Front power is remembered but never written back: "never start climate automatically" still holds —
     * after the auto-off the car stays off even though the snapshot saw it on. Other fields are still
     * written, so this is not a vacuous pass.
     */
    @Test
    public void theFrontPowerIsCapturedButNeverWrittenBack() {
        FakeCar car = car();

        ClimateSnapshot.Snapshot s = ClimateSnapshot.read(car, names());
        assertEquals(Integer.valueOf(1), s.frontPowerC);
        ownerCommandAndAutoOff(car);   // 车主的命令把温度改了，自动关把前排关上（开关现在是 0）

        ClimateSource.applyRestore(car, names(), new FakeClock(), s);

        assertFalse("前排开关不许被写回 1: " + car.calls,
                car.calls.toString().contains("write " + POWER + "@" + FRONT + "="));
        assertTrue("别的动了的字段照写: " + car.calls,
                car.calls.contains("writeFloat " + TEMP + "@" + DRIVER + "=22.0"));
    }

    // ================================================================= 全空

    /**
     * 全空的快照（这台适配层什么都不回）：一笔都不写，也不许崩。未知是合法的答案，不许被当成任何默认值。
     *
     * A fully-null snapshot (this adapter answers nothing): no writes at all, no crash. Unknown is legal
     * and must never be read as some default.
     */
    @Test
    public void aFullyUnknownSnapshotWritesNothing() {
        FakeCar car = new FakeCar();

        ClimateSnapshot.Snapshot s = ClimateSnapshot.read(car, names());

        assertEquals(0, s.knownCount());
        assertFalse(ClimateSnapshot.anyKnown(s));
        ClimateSource.applyRestore(car, names(), new FakeClock(), s);
        assertNoWrites(car);
    }

    /** null 快照（进程没了、没快照就是没快照）：同样一笔不写、不崩。 */
    @Test
    public void aNullSnapshotWritesNothing() {
        FakeCar car = car();

        ClimateSource.applyRestore(car, names(), new FakeClock(), null);

        assertNoWrites(car);
    }

    /**
     * 快照时读不到的字段，哪怕自动关那一刻车机又开始回值了，也不许写：快照说不知道，就是不写。
     *
     * A field unknown at snapshot time stays unwritten even if the head unit starts answering again by the
     * auto-off: what the snapshot does not know, it does not write back.
     */
    @Test
    public void anUnknownFieldIsNotWrittenBackEvenWhenTheCarStartsAnswering() {
        FakeCar car = car();
        car.ints.remove(String.valueOf(CIRC));   // 快照那一刻：循环模式不回值

        ClimateSnapshot.Snapshot s = ClimateSnapshot.read(car, names());
        assertNull(s.circulation);
        ownerCommandAndAutoOff(car);   // 现在它会回了（2821）—— 不许因此写它

        ClimateSource.applyRestore(car, names(), new FakeClock(), s);

        assertFalse("未知的那个号一笔都不许写: " + car.calls,
                car.calls.toString().contains("writePlain " + CIRC + "="));
        assertTrue("别的动了的照写: " + car.calls,
                car.calls.contains("write " + POWER + "@" + ROW_2 + "=1"));
    }

    // ================================================================= 单位

    /**
     * 华氏车（45 以上那一档 —— 设定值 68 说华氏）：写回去的是那个通道自己的数 68.0，两边一样；
     * 快照存的是通道原数（读那一头不换算），所以回放也是原数对原数 —— 谁都不许多砍一刀。
     *
     * <p>两种合理的实现（原样写回 / 存摄氏再按规矩换回去）最后落到通道上的都是 68.0；多砍或漏砍的都到
     * 不了 68.0。这一条钉的是通道上真正出现的那个数。</p>
     *
     * A Fahrenheit car (setpoint 68 — above the Fahrenheit line): what its channel gets back is its own
     * number, 68.0, on both front zones. The snapshot stores the channel verbatim (no cut at read time),
     * so the replay is number-for-number — neither side may cut twice. The two sane implementations
     * (verbatim, or store-as-Celsius-then-convert-back) both land on 68.0 on the channel; an extra or a
     * missing conversion does not. This pins the number that actually reaches the channel.
     */
    @Test
    public void aFahrenheitCarGetsBackTheNumberItsChannelHolds() {
        FakeCar car = car();
        car.putFloat(TEMP, DRIVER, 68f);
        car.putFloat(TEMP, PASSENGER, 68f);
        car.putInt(TEMP_UNIT, DRIVER, UNIT_F);

        ClimateSnapshot.Snapshot s = ClimateSnapshot.read(car, names());
        assertEquals(Float.valueOf(68f), s.driverSetpointC);
        assertEquals(Float.valueOf(68f), s.passengerSetpointC);
        ownerCommandAndAutoOff(car);   // 车主的命令在两个通道上写下 71.6 —— 跟快照不一样了

        ClimateSource.applyRestore(car, names(), new FakeClock(), s);

        assertTrue("华氏通道收回它自己的 68.0: " + car.calls,
                car.calls.contains("writeFloat " + TEMP + "@" + DRIVER + "=68.0"));
        assertTrue(car.calls.toString(), car.calls.contains("writeFloat " + TEMP + "@" + PASSENGER + "=68.0"));
        assertFalse("多砍一刀换算就成 154.4: " + car.calls,
                car.calls.toString().contains("=154.4") || car.calls.toString().contains("=154.3"));
        assertFalse("读那一头先换算、写这一头不砍，就成 20.0: " + car.calls,
                car.calls.toString().contains("=20.0"));
    }

    /**
     * 单位的分界照写那条路的规矩：设定值说了算（10–45 是摄氏），设定值读不到才轮到单位号 —— 4.15.0
     * 就是只看单位号错杀的那台（它报华氏，可设定值是 19.5）。所以这台车收回的还是 19.5，不是换算的 67.1。
     *
     * The unit rule is the write path's rule: the setpoint decides (10–45 is Celsius), the unit id only
     * speaks when the setpoint cannot be read — the very rule 4.15.0 broke by trusting the unit id alone
     * on this car (it reports Fahrenheit while holding 19.5). So this car gets 19.5 back, not the
     * converted 67.1. The pure-function mirrors below are the same rule the write path applies.
     */
    @Test
    public void theUnitRuleComesFromTheSetpointNotTheUnitId() {
        FakeCar car = car();
        car.putFloat(TEMP, DRIVER, 19.5f);
        car.putFloat(TEMP, PASSENGER, 19.5f);
        car.putInt(TEMP_UNIT, DRIVER, UNIT_F);

        ClimateSnapshot.Snapshot s = ClimateSnapshot.read(car, names());
        assertEquals(Float.valueOf(19.5f), s.driverSetpointC);
        ownerCommandAndAutoOff(car);   // 两个通道被改成 25.0（这台是摄氏车）

        ClimateSource.applyRestore(car, names(), new FakeClock(), s);

        assertTrue("摄氏车的通道收回 19.5: " + car.calls,
                car.calls.contains("writeFloat " + TEMP + "@" + DRIVER + "=19.5"));
        assertTrue(car.calls.toString(), car.calls.contains("writeFloat " + TEMP + "@" + PASSENGER + "=19.5"));
        assertFalse("只看单位号就会写 67.1: " + car.calls,
                car.calls.toString().contains("=67.1") || car.calls.toString().contains("=67.2"));
        // 分界本身跟写那条路一致（同一把尺，测试里直接照面）：
        assertFalse("19.5 在摄氏范围里", ClimateSource.speaksFahrenheit(19.5f, UNIT_C, UNIT_F));
        assertTrue("68 才是华氏", ClimateSource.speaksFahrenheit(68f, UNIT_C, UNIT_F));
    }

    // ================================================================= 被拒

    /**
     * 一笔被车机拒了：说出来（ASCII），但不停 —— 后面的字段一个一个照写满。被拒了停下就是：车主剩下的
     * 设置永远回不来，而且明细里什么都看不到。
     *
     * One write refused by the car: say so (ASCII) and do not stop — every remaining field is still
     * attempted. Stopping on a refusal is how the owner's other settings never come back, with nothing in
     * the black box to show for it.
     */
    @Test
    public void aRefusedWriteDoesNotStopThePassAndIsNoted() {
        FakeCar car = car();
        car.refused.add(writeKey(SEAT_HEAT, DRIVER));   // 主驾加热这一笔车机不受理

        ClimateSnapshot.Snapshot s = ClimateSnapshot.read(car, names());
        ownerCommandAndAutoOff(car);
        FakeClock clock = new FakeClock();
        ClimateSource.Report report = ClimateSource.applyRestore(car, names(), clock, s);

        assertTrue("被拒的那一笔也要真的试过: " + car.calls,
                car.calls.contains("write " + SEAT_HEAT + "@" + DRIVER + "=2819"));
        assertTrue("后面的不许停: " + car.calls,
                car.calls.contains("writeFloat " + TEMP + "@" + DRIVER + "=22.0")
                        && car.calls.contains("write " + SEAT_VENT + "@" + DRIVER + "=0")
                        && car.calls.contains("write " + SEAT_HEAT + "@" + PASSENGER + "=0")
                        && car.calls.contains("write " + SEAT_VENT + "@" + PASSENGER + "=2820")
                        && car.calls.contains("writePlain " + CIRC + "=2820")
                        && car.calls.contains("write " + POWER + "@" + ROW_2 + "=1"));
        assertTrue("被拒要在明细里说得出: " + report.detail,
                report.detail.toLowerCase(Locale.US).contains("refused"));
        for (int i = 0; i < report.detail.length(); i++) {
            char c = report.detail.charAt(i);
            assertTrue("non-ascii at " + i + ": " + report.detail, c >= 0x20 && c < 0x7F);
        }
    }

    /**
     * 一个号试一次：每一个动过的字段恰好一笔，不排队等读回、不拉锯（车机最可能睡着的时候正是自动关
     *  firing 的时候 —— {@code ClimateOffGuard} 存在的理由；等读回就是一场风暴）。假等待一次都没被叫醒。
     *
     * One attempt per field: exactly one write per drifted field, no dwell loop, no readback storm (the
     * head unit is most likely asleep exactly when the auto-off fires — the whole reason ClimateOffGuard
     * exists). The fake clock is never woken.
     */
    @Test
    public void everyDriftedFieldGetsExactlyOneAttemptAndThePassDoesNotDwell() {
        FakeCar car = car();

        ClimateSnapshot.Snapshot s = ClimateSnapshot.read(car, names());
        ownerCommandAndAutoOff(car);
        FakeClock clock = new FakeClock();
        ClimateSource.applyRestore(car, names(), clock, s);

        for (String expected : List.of(
                "writeFloat " + TEMP + "@" + DRIVER + "=22.0",
                "writeFloat " + TEMP + "@" + PASSENGER + "=22.0",
                "write " + SEAT_HEAT + "@" + DRIVER + "=2819",
                "write " + SEAT_VENT + "@" + DRIVER + "=0",
                "write " + SEAT_HEAT + "@" + PASSENGER + "=0",
                "write " + SEAT_VENT + "@" + PASSENGER + "=2820",
                "writePlain " + CIRC + "=2820",
                "write " + POWER + "@" + ROW_2 + "=1")) {
            assertEquals("一个号就试一次: " + expected, 1, count(car.calls, expected));
        }
        assertEquals("不许在等待上 dwell（假等待一次都没被叫醒）", 0, clock.sleeps);
    }

    /** 名字里没有的号不试：连读都没读出来（快照里是未知），到写回这一头自然一笔不写；别的照写。 */
    @Test
    public void aFieldMissingFromNamesIsNotAttempted() {
        FakeCar car = car();
        FakeNames names = names().without("HVAC_FUNC_SEAT_VENTILATION");

        ClimateSnapshot.Snapshot s = ClimateSnapshot.read(car, names);
        assertNull("没有这个常量，通风两个字段都读不出来", s.seatVentLeft);
        assertNull(s.seatVentRight);
        ownerCommandAndAutoOff(car);
        ClimateSource.applyRestore(car, names, new FakeClock(), s);

        assertFalse("没有的号一笔都不许写: " + car.calls,
                car.calls.toString().contains("write " + SEAT_VENT + "@"));
        assertTrue("别的照写: " + car.calls, car.calls.contains("write " + SEAT_HEAT + "@" + DRIVER + "=2819"));
        assertTrue(car.calls.toString(), car.calls.contains("write " + SEAT_HEAT + "@" + PASSENGER + "=0"));
    }

    // ================================================================= 假车、假名字、假等待

    /**
     * 「车主的命令 + 自动关」：温度两区改成 25.0、四个座椅位归 0、循环改成外循环、前排关上、后排关上 ——
     * 于是快照里每个跟现在不一样的已知字段都到了「该写回」的那一刻。
     */
    private static void ownerCommandAndAutoOff(FakeCar car) {
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

    private static int count(List<String> calls, String exact) {
        int n = 0;
        for (String call : calls) {
            if (call.equals(exact)) {
                n++;
            }
        }
        return n;
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

    /**
     * 快照那一头的假车（九个字段都回值）：前排开着、两区 22.0、主驾加热 2 档、主驾通风 0、副驾加热 0、
     * 副驾通风 2 档、内循环、后排开着。值跟 {@code ownerCommandAndAutoOff} 改过之后的错开，才逼得出写回。
     */
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
            functions.put("IHvac.TEMPERATURE_UNIT_C", UNIT_C);
            functions.put("IHvac.TEMPERATURE_UNIT_F", UNIT_F);
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
            // 这次合并进来的车辆接口多了「查配置」那一项；回放这条路不用它。
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
            calls.add(String.format(Locale.US, "writeFloat %d@%d=%.1f", id, zone, value));
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

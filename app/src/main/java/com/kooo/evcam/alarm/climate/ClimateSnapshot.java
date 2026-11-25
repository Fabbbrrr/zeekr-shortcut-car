/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.climate;

import com.kooo.evcam.telemetry.CarAccess;

import java.util.Locale;

/**
 * 气候快照（{@code docs/climate-alerts/RESTORE.md} §3）：动笔写车之前，把「马上要改的那几个号」挨个读一遍、存下来；
 * 自动关那一炮打响以后，照着它把车放回去。
 *
 * <p><b>只读。</b>{@link #read} 一笔都不写，所以它不等（没有 clock 这个参数）：读就是读，回了话就算数。快照只活在进程里
 * （成本写在 RESTORE.md §2）：进程半路没了，调度器跟着没了，下一次自动关就是干脆的关 —— 那个新进程里什么都没被改掉，这正是老实的样子。</p>
 *
 * <p><b>null 是合法的答案。</b>读不到就是 null（这台适配层很多号就是不回话）；null 存成 unknown，到了恢复那一关就是<b>不写</b>，
 * 并且在 {@link Snapshot#detail} 里点了名。绝不拿默认值填坑（no-guess 规则，落在代码里，不是落在纪律里）。</p>
 *
 * <p><b>253、255 是占位值，不是读数</b>（适配层的「没有值」回答，recirculation findings §2）：记进 detail、存成 unknown，
 * 永远不写回去。</p>
 *
 * <p><b>读到的照原样存</b>：不换算单位、不按步长对齐、不夹范围 —— 那个通道上存着什么就是什么（换算留给写那一关，照
 * {@link ClimateSource#speaksFahrenheit}）。所以 {@code driverSetpointC} 这类名字带着 C，存的其实是通道原值：
 * 只有摄氏度车的这两件事才正好重合。</p>
 *
 * <p><b>座椅、内循环这几个号本来就不可靠</b>：不可靠是「经常读不到」，不是「会骗人」—— 读到了就认（{@code ClimateSource}
 * 反过来说过同一条规矩）。所以这里不用 {@link ClimateSource#plausible} 那套（-20..60）去筛它们：座椅的值是「功能号 + 档位」，
 * 天生就在那之外。这里只挡 253、255 两个占位值。</p>
 *
 * <p>纯 Java，没有安卓的东西：车接口、常量名都是参数，测试换上假的（{@code ClimateSnapshotTest}）。</p>
 *
 * <p><b>A climate snapshot.</b> Before the first write of an owner command the fields it is about to change are read,
 * one by one, and kept; when the auto-off fires, the car is put back to them.</p>
 *
 * <p><b>Read-only.</b> {@link #read} writes nothing, so it does not wait (there is no clock parameter): a read is a
 * read and an answer counts. The snapshot lives in process memory only (the cost is written down in RESTORE.md §2):
 * if the process dies mid-window the scheduler dies with it and the next auto-off is a plain off — which is the honest
 * shape, because nothing was overwritten in that new process.</p>
 *
 * <p><b>null is a legal answer.</b> A field that cannot be read is null — much of this adapter answers nothing — and
 * it is stored as unknown: an unknown field is not written back at restore time and is named in
 * {@link Snapshot#detail}. No invented defaults (the no-guess rule, enforced in code rather than in discipline).</p>
 *
 * <p><b>253 and 255 are placeholders, not readings</b> (the adapter's "no value" answers, recirculation findings §2):
 * they are recorded in detail, stored as unknown, and never restored.</p>
 *
 * <p><b>What is read is stored verbatim</b>: no unit conversion, no snapping, no clamping — the channel holds what it
 * holds, and the conversion belongs to the write ({@link ClimateSource#speaksFahrenheit}). The C in
 * {@code driverSetpointC} is therefore the Celsius case only.</p>
 *
 * <p><b>The seat and circulation ids are unreliable on this adapter</b> — unreliable means often null, not lying; a
 * value that is read is trusted, the same rule {@code ClimateSource} already applies in reverse. So the -20..60
 * plausibility window is not used here: a raw seat value is the function id plus the level, which lives far outside
 * it. Only the 253 and 255 placeholders are refused.</p>
 *
 * <p>Pure Java on purpose: no Android imports, so the rules run under a plain JVM unit test.</p>
 */
public final class ClimateSnapshot {

    /** 不带区域的那个通道在这一列里的写法（{@link CarAccess#read(int)} / {@link CarAccess#write(int, int)}）。 */
    static final String NO_ZONE = "nozone";

    /** 适配层的「没有值」回答（recirculation findings §2）：读到它们等于什么都没读到，存成 unknown。 */
    static final int PLACEHOLDER_253 = 253;
    /** 同上，另一个占位值。 */
    static final int PLACEHOLDER_255 = 255;

    private ClimateSnapshot() {
    }

    /**
     * 不可变的值对象。null = 这台车机上读不到（合法，而且不会被写回去）。号码、区域、原值照 RESTORE.md §3 那一栏。
     *
     * <p>{@link #detail} 是这一趟读的流水（ASCII，进黑匣子）：哪个号没有、哪个号回了占位值，都在这儿点名。</p>
     *
     * Immutable value object. null = unknown on this head unit (legal, and not written back). {@link #detail} is the
     * read pass's log (ASCII, black box): which id is missing and which one answered a placeholder are named there.
     */
    public static final class Snapshot {
        /** {@code HVAC_FUNC_POWER} @ {@code ROW_1}：可能本来就是 0，也可能是 null；恢复不动前排开关这一笔。 */
        public final Integer frontPowerC;
        /** 浮点通道 @ {@code ROW_1_LEFT}：通道原值，没换算。 */
        public final Float driverSetpointC;
        /** 浮点通道 @ {@code ROW_1_RIGHT}：通道原值，没换算。 */
        public final Float passengerSetpointC;
        /** 存的原始值（功能号 + 档位，0 就是关）。 */
        public final Integer seatHeatLeft;
        /** 同上，主驾通风。 */
        public final Integer seatVentLeft;
        /** 同上，副驾加热。 */
        public final Integer seatHeatRight;
        /** 同上，副驾通风。 */
        public final Integer seatVentRight;
        /** 原始值（id + 1 内循环 / + 2 外循环 / + 3 自动），默认区域那一档。 */
        public final Integer circulation;
        /** {@code HVAC_FUNC_POWER} @ {@code ROW_2}。 */
        public final Integer rearPower;
        /** ASCII，只进黑匣子：这一趟读了什么、什么没读到。 */
        public final String detail;

        public Snapshot(Integer frontPowerC, Float driverSetpointC, Float passengerSetpointC,
                        Integer seatHeatLeft, Integer seatVentLeft, Integer seatHeatRight,
                        Integer seatVentRight, Integer circulation, Integer rearPower, String detail) {
            this.frontPowerC = frontPowerC;
            this.driverSetpointC = driverSetpointC;
            this.passengerSetpointC = passengerSetpointC;
            this.seatHeatLeft = seatHeatLeft;
            this.seatVentLeft = seatVentLeft;
            this.seatHeatRight = seatHeatRight;
            this.seatVentRight = seatVentRight;
            this.circulation = circulation;
            this.rearPower = rearPower;
            this.detail = detail == null ? "" : detail;
        }

        /** 不是 null 的有几个（一个都没有就什么都恢复不了）。 */
        public int knownCount() {
            int n = 0;
            Integer[] values = {frontPowerC, seatHeatLeft, seatVentLeft, seatHeatRight, seatVentRight,
                    circulation, rearPower};
            for (Integer value : values) {
                if (value != null) {
                    n++;
                }
            }
            if (driverSetpointC != null) {
                n++;
            }
            if (passengerSetpointC != null) {
                n++;
            }
            return n;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "snapshot known=%d power=%s temp=%s/%s seats=%s/%s/%s/%s "
                            + "circ=%s rear=%s (%s)",
                    knownCount(), frontPowerC, driverSetpointC, passengerSetpointC, seatHeatLeft,
                    seatVentLeft, seatHeatRight, seatVentRight, circulation, rearPower, detail);
        }
    }

    // ================================================================= 读

    /**
     * 读一遍：照 {@link Snapshot} 那一栏的号、区域、通道，挨个读，一个都不写。读不到的存 null 并点名；
     * 占位值（{@link #PLACEHOLDER_253}、{@link #PLACEHOLDER_255}）也算读不到。不抛：车接口出了错，剩下的照读，
     * 出错的记一笔。
     *
     * <p>读一遍就是 {@link ClimateSource#applyRestore} 开头那次「先读回来对一遍」用的那趟路，两边共用同一套规矩，
     * 所以快照里存的东西和恢复时对照的东西是同一个口径。</p>
     *
     * <p>Read the fields of {@link Snapshot} one by one through the car and the names, and write nothing. A read that
     * answers nothing is stored as null and named; a placeholder answer is the same thing. Never throws.</p>
     *
     * <p>This is the same pass {@link ClimateSource#applyRestore} uses for its read-back, so the snapshot and the
     * readback are measured by one set of rules.</p>
     */
    public static Snapshot read(CarAccess access, ClimateSource.Names names) {
        if (access == null || names == null) {
            return new Pass(null, null).snapshot("missing argument");
        }
        Pass pass = new Pass(access, names);
        try {
            pass.readAll();
        } catch (RuntimeException e) {
            pass.note("failed: " + e);
        }
        return pass.snapshot(null);
    }

    /** 至少有一个字段是知道的（全都不知道就什么都恢复不了）。 */
    public static boolean anyKnown(Snapshot s) {
        return s != null && s.knownCount() > 0;
    }

    /** 这个读数是不是占位值（253 / 255）：是就等于没读到。整数、浮点两个通道都算。 */
    static boolean placeholder(int value) {
        return value == PLACEHOLDER_253 || value == PLACEHOLDER_255;
    }

    /** 同上，浮点通道（照原样比，不做算术 —— 占位值就是那两个数）。 */
    static boolean placeholder(float value) {
        return value == (float) PLACEHOLDER_253 || value == (float) PLACEHOLDER_255;
    }

    // ================================================================= 一趟读

    /** 一趟读的读写（只读）、记一笔（ASCII，进黑匣子）。 */
    private static final class Pass {
        private final CarAccess access;
        private final ClimateSource.Names names;
        private final StringBuilder detail = new StringBuilder();

        private Integer frontPower;
        private Float driverSetpoint;
        private Float passengerSetpoint;
        private Integer seatHeatLeft;
        private Integer seatVentLeft;
        private Integer seatHeatRight;
        private Integer seatVentRight;
        private Integer circulation;
        private Integer rearPower;

        Pass(CarAccess access, ClimateSource.Names names) {
            this.access = access;
            this.names = names;
        }

        void note(String text) {
            if (detail.length() > 0) {
                detail.append("; ");
            }
            detail.append(text);
        }

        /** 挨个读一遍：号码先取齐，再按通道读；读不到、占位值都存 null。 */
        void readAll() {
            int front = zone(ClimateSource.FRONT_ZONE);
            int driver = zone(ClimateSource.DRIVER_ZONE);
            int passenger = zone(ClimateSource.PASSENGER_ZONE);
            int rear = zone(ClimateSource.REAR_ZONE);
            note(String.format(Locale.US, "zone front=%s driver=%s passenger=%s rear=%s",
                    ClimateSource.hex(front), ClimateSource.hex(driver), ClimateSource.hex(passenger),
                    ClimateSource.hex(rear)));

            Integer power = function("HVAC_FUNC_POWER");
            Integer temp = function("HVAC_FUNC_TEMP");
            Integer heat = function("HVAC_FUNC_SEAT_HEATING");
            Integer vent = function("HVAC_FUNC_SEAT_VENTILATION");
            Integer circ = function("HVAC_FUNC_CIRCULATION");

            frontPower = read(power, front, "power front");
            driverSetpoint = readFloat(temp, driver, "temp left");
            passengerSetpoint = readFloat(temp, passenger, "temp right");
            seatHeatLeft = read(heat, driver, "seat heat left");
            seatVentLeft = read(vent, driver, "seat vent left");
            seatHeatRight = read(heat, passenger, "seat heat right");
            seatVentRight = read(vent, passenger, "seat vent right");
            circulation = readPlain(circ, "circulation");
            rearPower = read(power, rear, "power rear");
        }

        // ------------------------------------------------------------- 名字、区域

        /** 功能号；这台车机没有就记一笔、返回 null（号都没有，那个字段自然也就是 unknown）。 */
        Integer function(String field) {
            Integer id = names.function(ClimateSource.IFACE, field);
            if (id == null) {
                note("no " + field);
            }
            return id;
        }

        int zone(String name) {
            return names.zone(name);
        }

        // ------------------------------------------------------------- 读（只读）

        /** 带区域读一个整型的号；读不到、或者读到占位值，都是 null（点了名）。 */
        Integer read(Integer id, int zone, String label) {
            if (id == null) {
                return null;
            }
            Integer v = access.read(id, zone);
            return store(label, ClimateSource.hex(zone), v);
        }

        /** 不带区域读一个整型的号（内循环走的就是这一路，照 recirculation findings §2 那条验证过的读法）。 */
        Integer readPlain(Integer id, String label) {
            if (id == null) {
                return null;
            }
            Integer v = access.read(id);
            return store(label, NO_ZONE, v);
        }

        /** 读一个浮点通道的号（设定温度只有这个通道带着小数）。 */
        Float readFloat(Integer id, int zone, String label) {
            if (id == null) {
                return null;
            }
            Float v = access.readFloat(id, zone);
            if (v == null) {
                note("read " + label + "@" + ClimateSource.hex(zone) + "=? unknown");
                return null;
            }
            if (placeholder(v)) {
                note(String.format(Locale.US, "read %s@%s=%s placeholder, unknown", label,
                        ClimateSource.hex(zone), floatText(v)));
                return null;
            }
            note("read " + label + "@" + ClimateSource.hex(zone) + "=" + floatText(v));
            return v;
        }

        /** 整型读数的归宿：读到占位值就是 unknown（照原样的数照原样留）。 */
        private Integer store(String label, String where, Integer v) {
            if (v == null) {
                note("read " + label + "@" + where + "=? unknown");
                return null;
            }
            if (placeholder(v.intValue())) {
                note("read " + label + "@" + where + "=" + v + " placeholder, unknown");
                return null;
            }
            note("read " + label + "@" + where + "=" + v);
            return v;
        }

        Snapshot snapshot(String extra) {
            if (extra != null) {
                note(extra);
            }
            return new Snapshot(frontPower, driverSetpoint, passengerSetpoint, seatHeatLeft,
                    seatVentLeft, seatHeatRight, seatVentRight, circulation, rearPower,
                    ClimateSource.ascii(detail.toString()));
        }

        private static String floatText(Float value) {
            return value == null ? "?" : String.format(Locale.US, "%.2f", value);
        }
    }
}

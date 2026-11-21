/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.climate;

import com.kooo.evcam.telemetry.CarAccess;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 空调探针：车上回「车机拒绝了这条命令，什么都没改」的时候，用它把「为什么」问出来。
 *
 * <p>黑匣子里记的是哪一笔被拒了（{@code ClimateSource.Report#detail}），不是为什么。这里问三样平时不问的东西：
 * ECARX 的 {@code isFunctionSupported}（{@link CarAccess#status}）在这个功能号、这个区域上答什么；整数通道读回来
 * 是什么；以及厂商自己的应用会用的那几个区域（主驾 {@code 0x01}、副驾 {@code 0x04}、前排 {@code 0x08}）上，
 * 同一笔写是不是有的受、有的拒。</p>
 *
 * <p>读的多、写的少：一次设定温度（主驾，拒了再试副驾）和一次开关（前排 → 主驾 → 不带区域，受了一个就停）。
 * 写完都读回，然后把车恢复原样 —— <b>开关最后一定写回开始时的值</b>：开始时是 0 就写 0，读不到也写 0；
 * 只有开始时读到就是 1 才留着，并明说「本来开着，没动」。设定温度写下去之前记下车上原来那个值，写成功了
 * 又跟原来不一样，就把原来那个值写回去。</p>
 *
 * <p>{@link Report#lines} 全是 ASCII、一行一件事、每行都短 —— 车主是拍张照片发出来的，不是读给它听。
 * 纯 Java，没有安卓的东西：车接口、常量名、等待都是参数，测试换上假的、不睡（{@code ClimateProbeTest}）。</p>
 *
 * <p>每一步都单独包着：车接口抛 {@link RuntimeException} 就变成一行 {@code ERROR ...}，探针继续往下走 ——
 * 一个探针在第一步崩掉，等于什么都没说。等待只有读回那一段（{@link ClimateSource#BACK_MS}，
 * 每 {@link ClimateSource#BACK_STEP_MS} 看一次），没有更长的睡。</p>
 *
 * <p><b>A climate probe.</b> When the car answers "the car refused the command, nothing was changed", the
 * black box records which write was refused, never why. This asks the three things the write path never
 * asks: what ECARX's {@code isFunctionSupported} says about that id on that zone, what the integer channel
 * reads back, and whether the other zones the vendor's own apps use answer differently.</p>
 *
 * <p>It reads a lot and writes little: one setpoint (driver, then passenger if refused) and one power
 * switch (front → driver → unzoned, stopping at the first accepted). Every write is read back, and the car
 * is put back afterwards — <b>power always ends at the value read at the start</b>: 0 goes back to 0, an
 * unreadable value is switched off too, and only a start value of 1 is left alone and said so. The setpoint
 * held before the write is restored when the write changed it.</p>
 *
 * <p>Every step is wrapped on its own: a {@code RuntimeException} from the car interface becomes an
 * {@code ERROR ...} line and the probe carries on. The only waiting is the bounded readback.</p>
 */
public final class ClimateProbe {

    /** 副驾区域（{@code ROW_1_RIGHT}，0x04）：厂商自己的应用也往这里写。 */
    static final String PASSENGER_ZONE = "ROW_1_RIGHT";

    /** 没有最近一次的目标温度时用的值。 */
    static final float DEFAULT_TARGET_C = 22f;

    /** 不带区域的那一路在这一列里的写法（{@code CarAccess.read(int)} / {@code write(int, int)}）。 */
    static final String NO_ZONE = "nozone";

    private ClimateProbe() {
    }

    /** 探完了：{@link #lines} 一行一件事，ASCII。 */
    public static final class Report {
        public final List<String> lines;

        Report(List<String> lines) {
            this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
        }

        /** 有一行含着这段字没有（测试、调用方都拿它问「说没说这一件」）。 */
        public boolean says(String needle) {
            for (String line : lines) {
                if (line.contains(needle)) {
                    return true;
                }
            }
            return false;
        }

        /** 用换行连起来：对话框正文、黑匣子都写它。 */
        public String text() {
            StringBuilder out = new StringBuilder();
            for (String line : lines) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(line);
            }
            return out.toString();
        }

        @Override
        public String toString() {
            return text();
        }
    }

    // ================================================================= 探

    /**
     * 探一次。不抛：车接口出的错都是 {@link Report} 里的一行。
     *
     * @param targetC 车主现在设的目标温度（摄氏度）；不确定就传 {@link #DEFAULT_TARGET_C}
     */
    public static Report run(CarAccess access, ClimateSource.Names names, ClimateSource.Clock clock,
                             float targetC) {
        if (access == null || names == null || clock == null) {
            List<String> lines = new ArrayList<>();
            lines.add("ERROR missing argument: probe not run");
            return new Report(lines);
        }
        Run run = new Run(access, names, clock);
        try {
            run.go(targetC);
        } catch (RuntimeException e) {
            run.add("ERROR stopped: " + e);
        }
        return new Report(run.lines);
    }

    // ================================================================= 一次探

    /** 一次探针的读、写、记一行。 */
    private static final class Run {
        private final CarAccess access;
        private final ClimateSource.Names names;
        private final ClimateSource.Clock clock;
        private final List<String> lines = new ArrayList<>();

        private int frontZone;
        private int driverZone;
        private int passengerZone;

        /** {@code HVAC_FUNC_POWER} / {@code HVAC_FUNC_TEMP}：探针一直在用，改名之外的地方不重取。 */
        private Integer power;
        private Integer temp;

        /** 两个区域各自在写之前车上那个值（读不到是 null）：恢复要恢复那一区域自己的值。 */
        private Float tempDriverBefore;
        private Float tempPassengerBefore;
        /** 写成功了那个区域原来的值；没写成功是 null。 */
        private Float tempRestoreTo;
        /** 设定温度写到了哪个区域（都没成是 0）。 */
        private int tempAcceptedZone;

        Run(CarAccess access, ClimateSource.Names names, ClimateSource.Clock clock) {
            this.access = access;
            this.names = names;
            this.clock = clock;
        }

        void add(String line) {
            lines.add(ClimateSource.ascii(line));
        }

        void go(float targetC) {
            Float plausible = ClimateSource.atLeastPlausible(targetC);
            float target = plausible == null ? DEFAULT_TARGET_C : plausible;
            if (Math.abs(target - targetC) > ClimateSource.EPSILON) {
                add(String.format(Locale.US, "target %.1f is outside what a person sets, probing %.1f",
                        targetC, target));
            }
            add(String.format(Locale.US, "climate probe target=%.1f", target));

            frontZone = zone(ClimateSource.FRONT_ZONE);
            driverZone = zone(ClimateSource.DRIVER_ZONE);
            passengerZone = zone(PASSENGER_ZONE);

            power = function("HVAC_FUNC_POWER");
            temp = function("HVAC_FUNC_TEMP");
            Integer tempMin = function("HVAC_FUNC_TEMP_MIN");
            Integer tempMax = function("HVAC_FUNC_TEMP_MAX");
            Integer tempStep = function("HVAC_FUNC_TEMP_STEP");
            Integer tempUnit = function("HVAC_FUNC_TEMP_UNIT");
            function("TEMPERATURE_UNIT_C");
            function("TEMPERATURE_UNIT_F");
            function("HVAC_FUNC_AC");
            function("HVAC_FUNC_AUTO");
            function("HVAC_FUNC_SEAT_HEATING");
            function("HVAC_FUNC_SEAT_VENTILATION");

            // 车机说这个号在这个区域上现在能不能用（ECARX 的 isFunctionSupported）；问不了是 null，照 null 报。
            status("POWER", power, ClimateSource.FRONT_ZONE, frontZone);
            status("POWER", power, ClimateSource.DRIVER_ZONE, driverZone);
            status("POWER", power, PASSENGER_ZONE, passengerZone);
            // 不带区域的问法车接口没有（CarAccess.status 只收区域）：说清楚，不要假装问过。
            add("status POWER " + NO_ZONE + "=n/a: no unzoned call");
            status("TEMP", temp, ClimateSource.DRIVER_ZONE, driverZone);
            status("TEMP", temp, PASSENGER_ZONE, passengerZone);

            // 整数通道现在是多少：开关在三个区域上，还有不带区域的那一路。
            Integer powerFrontBefore = readInt(power, frontZone, "POWER", ClimateSource.FRONT_ZONE);
            Integer powerDriverBefore = readInt(power, driverZone, "POWER", ClimateSource.DRIVER_ZONE);
            Integer powerPassengerBefore =
                    readInt(power, passengerZone, "POWER", PASSENGER_ZONE);
            Integer powerPlainBefore = readIntPlain(power, "POWER");

            // 设定温度走浮点通道；范围、步长、单位两个通道都读（有的适配层只在一个通道上回）。
            tempDriverBefore = readFloat(temp, driverZone, "TEMP", ClimateSource.DRIVER_ZONE);
            tempPassengerBefore = readFloat(temp, passengerZone, "TEMP", PASSENGER_ZONE);
            readInt(tempMin, driverZone, "TEMP_MIN", ClimateSource.DRIVER_ZONE);
            readFloat(tempMin, driverZone, "TEMP_MIN", ClimateSource.DRIVER_ZONE);
            readInt(tempMax, driverZone, "TEMP_MAX", ClimateSource.DRIVER_ZONE);
            readFloat(tempMax, driverZone, "TEMP_MAX", ClimateSource.DRIVER_ZONE);
            readInt(tempStep, driverZone, "TEMP_STEP", ClimateSource.DRIVER_ZONE);
            readFloat(tempStep, driverZone, "TEMP_STEP", ClimateSource.DRIVER_ZONE);
            readInt(tempUnit, driverZone, "TEMP_UNIT", ClimateSource.DRIVER_ZONE);
            readFloat(tempUnit, driverZone, "TEMP_UNIT", ClimateSource.DRIVER_ZONE);

            // 写：先设定温度（主驾，拒了试副驾），把那笔写回原值，再开关，最后把开关恢复。
            // 恢复的顺序是「温度在前、开关在后」：设定温度那一笔过去也可能把空调叫醒，开关恢复必须是最后一笔。
            tempAcceptedZone = setpoint(target);
            restoreTemp();
            powerSwitch(powerFrontBefore, powerDriverBefore, powerPassengerBefore, powerPlainBefore);
        }

        /** 设定温度：主驾试一次，被拒再试副驾。返回写成功的区域（都没成是 0）。 */
        private int setpoint(float target) {
            if (temp == null) {
                add("write TEMP " + NO_ZONE + ": no id, nothing written");
                return 0;
            }
            if (writeFloat(temp, driverZone, target, "TEMP", ClimateSource.DRIVER_ZONE)) {
                backFloat(temp, driverZone, target, "TEMP", ClimateSource.DRIVER_ZONE);
                add("temp accepted zone=" + ClimateSource.DRIVER_ZONE);
                tempRestoreTo = tempDriverBefore;
                return driverZone;
            }
            backFloat(temp, driverZone, target, "TEMP", ClimateSource.DRIVER_ZONE);
            if (writeFloat(temp, passengerZone, target, "TEMP", PASSENGER_ZONE)) {
                backFloat(temp, passengerZone, target, "TEMP", PASSENGER_ZONE);
                add("temp accepted zone=" + PASSENGER_ZONE);
                tempRestoreTo = tempPassengerBefore;
                return passengerZone;
            }
            backFloat(temp, passengerZone, target, "TEMP", PASSENGER_ZONE);
            add("temp accepted zone=none");
            return 0;
        }

        /**
         * 开关：前排 → 主驾 → 不带区域，受了一个就停；然后照开始读到的那一个值恢复。
         *
         * <p>开始时读到 1：本来就开着，留着，明说没动。读到 0：写完再写回 0。读不到：也写 0 ——
         * 留在开着的状态比多写一笔更糟。</p>
         *
         * The power switch: front, then driver, then unzoned, stopping at the first accepted; then put back to
         * the value read at the start — 1 is left alone and said so, 0 is written back, and an unreadable
         * start value is switched off too, because leaving the climate on is worse than one more write.
         */
        private void powerSwitch(Integer frontBefore, Integer driverBefore, Integer passengerBefore,
                                 Integer plainBefore) {
            Integer before = firstKnown(frontBefore, driverBefore, passengerBefore, plainBefore);
            if (power == null) {
                add("write POWER " + NO_ZONE + ": no id, nothing written");
                return;
            }

            int acceptedZone = 0;
            String acceptedWhere = null;
            boolean acceptedPlain = false;
            if (writeInt(power, frontZone, 1, "POWER", ClimateSource.FRONT_ZONE)) {
                acceptedZone = frontZone;
                acceptedWhere = ClimateSource.FRONT_ZONE;
                backInt(power, frontZone, 1, "POWER", ClimateSource.FRONT_ZONE);
            } else if (writeInt(power, driverZone, 1, "POWER", ClimateSource.DRIVER_ZONE)) {
                acceptedZone = driverZone;
                acceptedWhere = ClimateSource.DRIVER_ZONE;
                backInt(power, driverZone, 1, "POWER", ClimateSource.DRIVER_ZONE);
            } else if (writePlain(power, 1, "POWER")) {
                backPlainInt(power, 1, "POWER");
                acceptedPlain = true;
                add("power accepted " + NO_ZONE);
            } else {
                add("power accepted nowhere");
            }

            if (acceptedZone == 0 && !acceptedPlain) {
                // 一笔都没受：车上没变，没有要恢复的。
                return;
            }
            if (before != null && before.intValue() == 1) {
                add("power was 1 before, left on");
                return;
            }
            int back = before == null ? 0 : before.intValue();
            if (acceptedPlain) {
                writePlain(power, back, "POWER");
                backPlainInt(power, back, "POWER");
            } else {
                writeInt(power, acceptedZone, back, "POWER", acceptedWhere);
                backInt(power, acceptedZone, back, "POWER", acceptedWhere);
            }
            if (before == null) {
                add("power before unreadable, switched off anyway");
            } else {
                add("power was " + before + ", restored " + back);
            }
        }

        /** 设定温度写成功了又跟原来不一样：把原来那个值写回去，别让车主的目标温度被探针改掉。 */
        private void restoreTemp() {
            if (temp == null || tempAcceptedZone == 0 || tempRestoreTo == null) {
                return;
            }
            Float now;
            try {
                now = access.readFloat(temp, tempAcceptedZone);
            } catch (RuntimeException e) {
                add("ERROR read TEMP before restore@" + ClimateSource.hex(tempAcceptedZone) + ": " + e);
                return;
            }
            if (now != null && Math.abs(now - tempRestoreTo) <= ClimateSource.EPSILON) {
                return;
            }
            String where = whereOf(tempAcceptedZone);
            if (writeFloat(temp, tempAcceptedZone, tempRestoreTo, "TEMP", where)) {
                backFloat(temp, tempAcceptedZone, tempRestoreTo, "TEMP", where);
                add("temp restored to " + floatText(tempRestoreTo));
            } else {
                add("temp restore refused, car left at " + floatText(now));
            }
        }

        // ------------------------------------------------------------- 名字、区域

        Integer function(String field) {
            try {
                Integer id = names.function(ClimateSource.IFACE, field);
                add(id == null ? "func " + field + " missing"
                        : "func " + field + "=" + ClimateSource.hex(id));
                return id;
            } catch (RuntimeException e) {
                add("ERROR func " + field + ": " + e);
                return null;
            }
        }

        int zone(String name) {
            try {
                int zone = names.zone(name);
                add("zone " + name + "=" + ClimateSource.hex(zone));
                return zone;
            } catch (RuntimeException e) {
                add("ERROR zone " + name + ": " + e);
                return 0;
            }
        }

        // ------------------------------------------------------------- 读

        void status(String label, Integer id, String where, int zone) {
            if (id == null) {
                add("status " + label + " " + where + " skipped: no id");
                return;
            }
            try {
                String status = access.status(id, zone);
                add("status " + label + " " + where + "=" + (status == null ? "unaskable" : status));
            } catch (RuntimeException e) {
                add("ERROR status " + label + "@" + ClimateSource.hex(zone) + ": " + e);
            }
        }

        Integer readInt(Integer id, int zone, String label, String where) {
            if (id == null) {
                return null;
            }
            try {
                Integer value = access.read(id, zone);
                add("read " + label + " " + where + "="
                        + (value == null ? "?" : String.valueOf(value)));
                return value;
            } catch (RuntimeException e) {
                add("ERROR read " + label + "@" + ClimateSource.hex(zone) + ": " + e);
                return null;
            }
        }

        Integer readIntPlain(Integer id, String label) {
            if (id == null) {
                return null;
            }
            try {
                Integer value = access.read(id);
                add("read " + label + " " + NO_ZONE + "="
                        + (value == null ? "?" : String.valueOf(value)));
                return value;
            } catch (RuntimeException e) {
                add("ERROR read " + label + " " + NO_ZONE + ": " + e);
                return null;
            }
        }

        Float readFloat(Integer id, int zone, String label, String where) {
            if (id == null) {
                return null;
            }
            try {
                Float value = access.readFloat(id, zone);
                add("readf " + label + " " + where + "=" + floatText(value));
                return value;
            } catch (RuntimeException e) {
                add("ERROR readf " + label + "@" + ClimateSource.hex(zone) + ": " + e);
                return null;
            }
        }

        // ------------------------------------------------------------- 写

        boolean writeInt(Integer id, int zone, int value, String label, String where) {
            try {
                boolean accepted = access.write(id, zone, value);
                add("write " + label + " " + where + "=" + value + (accepted ? " ok" : " refused"));
                return accepted;
            } catch (RuntimeException e) {
                add("ERROR write " + label + "@" + ClimateSource.hex(zone) + ": " + e);
                return false;
            }
        }

        boolean writePlain(Integer id, int value, String label) {
            try {
                boolean accepted = access.write(id, value);
                add("write " + label + " " + NO_ZONE + "=" + value + (accepted ? " ok" : " refused"));
                return accepted;
            } catch (RuntimeException e) {
                add("ERROR write " + label + " " + NO_ZONE + ": " + e);
                return false;
            }
        }

        boolean writeFloat(Integer id, int zone, float value, String label, String where) {
            try {
                boolean accepted = access.writeFloat(id, zone, value);
                add(String.format(Locale.US, "write %s %s=%.1f %s", label, where, value,
                        accepted ? "ok" : "refused"));
                return accepted;
            } catch (RuntimeException e) {
                add("ERROR write " + label + "@" + ClimateSource.hex(zone) + ": " + e);
                return false;
            }
        }

        // ------------------------------------------------------------- 读回

        /** 读回一个整型的号：最多 {@link ClimateSource#BACK_MS}，每 {@link ClimateSource#BACK_STEP_MS} 看一次。 */
        void backInt(Integer id, int zone, int want, String label, String where) {
            Integer seen = null;
            long waited = 0;
            for (long step = ClimateSource.BACK_STEP_MS; step <= ClimateSource.BACK_MS;
                 step += ClimateSource.BACK_STEP_MS) {
                clock.sleep(ClimateSource.BACK_STEP_MS);
                waited = step;
                seen = quietInt(id, zone);
                if (seen != null && seen == want) {
                    break;
                }
            }
            add("back " + label + " " + where + "=" + (seen == null ? "?" : String.valueOf(seen))
                    + " after " + waited + " ms");
        }

        void backPlainInt(Integer id, int want, String label) {
            Integer seen = null;
            long waited = 0;
            for (long step = ClimateSource.BACK_STEP_MS; step <= ClimateSource.BACK_MS;
                 step += ClimateSource.BACK_STEP_MS) {
                clock.sleep(ClimateSource.BACK_STEP_MS);
                waited = step;
                try {
                    seen = access.read(id);
                } catch (RuntimeException e) {
                    add("ERROR back read " + label + " " + NO_ZONE + ": " + e);
                    return;
                }
                if (seen != null && seen == want) {
                    break;
                }
            }
            add("back " + label + " " + NO_ZONE + "="
                    + (seen == null ? "?" : String.valueOf(seen)) + " after " + waited + " ms");
        }

        /** 读回一个浮点的号；车机只收半度，容差照 {@code ClimateSource} 那一档。 */
        void backFloat(Integer id, int zone, float want, String label, String where) {
            Float seen = null;
            long waited = 0;
            for (long step = ClimateSource.BACK_STEP_MS; step <= ClimateSource.BACK_MS;
                 step += ClimateSource.BACK_STEP_MS) {
                clock.sleep(ClimateSource.BACK_STEP_MS);
                waited = step;
                seen = quietFloat(id, zone);
                if (seen != null && Math.abs(seen - want) <= 0.5f + ClimateSource.EPSILON) {
                    break;
                }
            }
            add("back " + label + " " + where + "=" + floatText(seen) + " after " + waited + " ms");
        }

        /** 读回里的读：不另记一行（{@link #backInt} 自己记那一行）。 */
        private Integer quietInt(Integer id, int zone) {
            try {
                return access.read(id, zone);
            } catch (RuntimeException e) {
                add("ERROR back read@" + ClimateSource.hex(zone) + ": " + e);
                return null;
            }
        }

        private Float quietFloat(Integer id, int zone) {
            try {
                return access.readFloat(id, zone);
            } catch (RuntimeException e) {
                add("ERROR back readf@" + ClimateSource.hex(zone) + ": " + e);
                return null;
            }
        }

        // ------------------------------------------------------------- 小工具

        /** 区域号码认回名字（三个都是探针自己取的，认不出来就报号码）。 */
        private String whereOf(int zone) {
            if (zone == frontZone) {
                return ClimateSource.FRONT_ZONE;
            }
            if (zone == driverZone) {
                return ClimateSource.DRIVER_ZONE;
            }
            if (zone == passengerZone) {
                return PASSENGER_ZONE;
            }
            return ClimateSource.hex(zone);
        }

        private static String floatText(Float value) {
            return value == null ? "?" : String.format(Locale.US, "%.1f", value);
        }

        /** 第一个读得到的值：开始时开关是多少就照它说（前排优先）。 */
        private static Integer firstKnown(Integer... values) {
            for (Integer value : values) {
                if (value != null) {
                    return value;
                }
            }
            return null;
        }
    }
}

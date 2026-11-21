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
 * 气候告警的写车（T2）：空调开关、设定温度、主驾座椅加热或通风。只有车主下的命令走这里，自动的只许关（见
 * {@code docs/climate-alerts/DECISIONS.md}）。
 *
 * <ul>
 *   <li>设定温度走浮点通道（{@link CarAccess#writeFloat}）：温度这类号车机只收小数，整型的
 *       {@code setFunctionValue} 对 {@code HVAC_FUNC_TEMP} 恒回 false（读也只回占位值 253），没有整数回退。</li>
 *   <li>能设多少度、一档多少度是从车上读的（{@code HVAC_FUNC_TEMP_MIN/MAX/STEP}，主驾区域）：车不同、地区不同，
 *       16–31 这种数不能写死在代码里。车机不说（这台 7X 就不说）就照车主说的整度写，写完用读回说话 ——
 *       4.14.0 要「先读到范围才肯写」，在车上就是每一条 ac on 都回「车机不报温度范围」。单位同理，读到的不是
 *       单位按设定值判（见 {@link #speaksFahrenheit}），判成华氏就换算后写。</li>
 *   <li>车机说受理了不等于做到了：写完 3 秒内每 250 毫秒读回开关和设定温度，都对上才算做成（{@link Outcome#DONE}），
 *       否则是 {@link Outcome#NO_CHANGE}，读到的值记在 {@link Report#detail} 里。</li>
 *   <li>功能号、区域照系统的常量名取（{@link Names}），这台车机上没有的就是做不了。纯 Java，没有安卓的东西：车接口、
 *       常量名、等待都是参数，测试换上假的、不睡（{@code ClimateSourceTest}）。</li>
 *   <li>{@link Report#detail} 只放 ASCII 和号码、读到的值，进黑匣子；给人看的字在 {@code strings.xml}。</li>
 * </ul>
 *
 * <p>Writes for the climate alerts (ticket T2): power, setpoint, driver seat heat or ventilation. Only an owner
 * command comes through here — the automatic path may switch the climate off and nothing else.</p>
 *
 * <p><b>Why the float channel.</b> The setpoint goes through {@link CarAccess#writeFloat}
 * ({@code setCustomizeFunctionValue}): temperature-style functions take decimals only, and the integer
 * {@code setFunctionValue} channel is refused by the vendor for {@code HVAC_FUNC_TEMP} — it always answers
 * {@code false}, and reading it back returns the placeholder 253. There is no integer fallback, by decision.</p>
 *
 * <p><b>Why the range is read from the car.</b> How many degrees the unit accepts and how far one step goes are read
 * from {@code HVAC_FUNC_TEMP_MIN / MAX / STEP} on the driver zone, every time. Cars and markets differ, so a hardcoded
 * 16–31 would be a guess; a guess that is wrong writes a temperature the unit clamps or rejects silently. A missing
 * range is used when it answers (the integer channel is tried too); when it does not, the owner's whole degree is
 * written and the readback reports what the car actually holds, which is what 4.14.1 fixes. A non-Celsius unit
 * reading is inferred from the setpoint it holds (10–45 Celsius, above 45 Fahrenheit) and converted when needed.</p>
 *
 * <p>Every car call sits in one try/catch: a {@link RuntimeException} coming out of the vendor binding (or a reflection
 * failure inside it) becomes {@link Outcome#UNAVAILABLE}, never a crash. A {@code null} from
 * {@link CarAccess#readFloat} and a {@code false} from {@link CarAccess#writeFloat} mean "this head unit cannot",
 * not "something broke".</p>
 *
 * <p>Pure Java on purpose: no Android imports, so the rules run under a plain JVM unit test.</p>
 */
public final class ClimateSource {

    /** 功能号在哪个接口上（ECARX 的 {@code IHvac}）。 */
    static final String IFACE = "IHvac";
    /** 前排区域（{@code ROW_1}，0x08）：开关写在这里。 */
    static final String FRONT_ZONE = "ROW_1";
    /** 主驾区域（{@code ROW_1_LEFT}，0x01）：设定温度和座椅写在这里。 */
    static final String DRIVER_ZONE = "ROW_1_LEFT";

    /** 读回最多等多久。 */
    static final long BACK_MS = 3_000;
    /** 读回多久看一次。 */
    static final long BACK_STEP_MS = 250;
    /** 读回看几次。 */
    static final int BACK_STEPS = (int) (BACK_MS / BACK_STEP_MS);
    /** 温度是小数量，比大小、比读回都留一点富余。 */
    static final float EPSILON = 1e-3f;

    /** 座椅档位最多几档（档位的值是功能号加档位）。 */
    static final int MAX_LEVEL = 3;

    private ClimateSource() {
    }

    /** 一次写车的结果。 */
    public enum Outcome {
        /** 读回变成写的值了。 */
        DONE,
        /** 车机受理了，车上没变（或者读不到）。 */
        NO_CHANGE,
        /** 没写：车机不受理、设定温度超出车上读到的范围、缺坐标。 */
        REFUSED,
        /** 做不了：没有这个功能号，或者车辆接口出了错。 */
        UNAVAILABLE,
    }

    /** 功能号、区域从哪来（真的是 {@code CarNames}；测试里换成假的）。 */
    public interface Names {
        Integer function(String iface, String field);

        int zone(String name);
    }

    /** 等一会儿（真的是 {@code SystemClock.sleep}；测试里不等，只记下等了多久）。 */
    public interface Clock {
        void sleep(long ms);
    }

    /** 要写什么。 */
    public static final class Plan {
        /** true：打开空调并设温度；false：关掉。 */
        public final boolean on;
        /** 设定温度（摄氏度），只有 {@link #on} 时用。 */
        public final float targetC;
        /** false：一个座椅的号都不写。 */
        public final boolean seats;
        /** 写座椅加热。 */
        public final boolean heat;
        /** 写座椅通风。 */
        public final boolean vent;
        /** 1..3，加热、通风都用它。 */
        public final int level;

        public Plan(boolean on, float targetC, boolean seats, boolean heat, boolean vent, int level) {
            this.on = on;
            this.targetC = targetC;
            this.seats = seats;
            this.heat = heat;
            this.vent = vent;
            this.level = level;
        }
    }

    /** 写完了怎么样。 */
    public static final class Report {
        public final Outcome outcome;
        /** 写下去的设定温度（照车上的步长对齐之后）；没写是 0。 */
        public final float targetC;
        /** 读回来的开关。 */
        public final boolean powerOn;
        /**
         * 开关那一笔车机受理了（读回不一定看到）。读回读不到的时候，这是「我们确实让它开了」的唯一凭据 ——
         * 自动关就靠它排上（车机睡了之后读回全是空的，按读回排就等于永不关）。
         *
         * The power write was accepted by the head unit. The readback may still be unreadable, and after
         * the head unit sleeps it always is, so this is what the auto-off hangs on.
         */
        public final boolean powerWrote;
        /** 写座椅那一笔（有的话）车机受理了。没写座椅、或者被拒了，都是 false。 */
        public final boolean seatWrote;
        /** ASCII，只进黑匣子：功能号（号码用十六进制）和读到的值。 */
        public final String detail;

        public Report(Outcome outcome, float targetC, boolean powerOn, boolean powerWrote,
                      boolean seatWrote, String detail) {
            this.outcome = outcome;
            this.targetC = targetC;
            this.powerOn = powerOn;
            this.powerWrote = powerWrote;
            this.seatWrote = seatWrote;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "%s target=%.1f power=%s wrote=%s seat=%s (%s)",
                    outcome, targetC, powerOn, powerWrote, seatWrote, detail);
        }
    }

    // ================================================================= 做

    /**
     * 写一次车：{@link Plan#on} 是打开（读范围、写设定温度、写开关、写座椅、读回），是关就写 0、读回。
     * 不抛：出了问题都是 {@link Outcome#UNAVAILABLE}。
     */
    public static Report apply(CarAccess access, Names names, Clock clock, Plan plan) {
        if (access == null || names == null || clock == null || plan == null) {
            return new Report(Outcome.UNAVAILABLE, 0f, false, false, false, "missing argument");
        }
        Run run = new Run(access, names, clock);
        try {
            return run.go(plan);
        } catch (RuntimeException e) {
            run.note("failed: " + e);
            return run.report(Outcome.UNAVAILABLE, 0f, false);
        }
    }

    // ================================================================= 一次写车

    /** 一次写车的读写、记一笔（ASCII，进黑匣子）。 */
    private static final class Run {
        private final CarAccess access;
        private final Names names;
        private final Clock clock;
        private final StringBuilder detail = new StringBuilder();
        /** 开关那一笔受理了没有（{@link Report#powerWrote}）。 */
        private boolean powerWrote;
        /** 座椅那一笔写了并且受理了没有（{@link Report#seatWrote}）。 */
        private boolean seatWrote;

        Run(CarAccess access, Names names, Clock clock) {
            this.access = access;
            this.names = names;
            this.clock = clock;
        }

        void note(String text) {
            if (detail.length() > 0) {
                detail.append("; ");
            }
            detail.append(text);
        }

        Report report(Outcome outcome, float target, boolean powerOn) {
            return new Report(outcome, target, powerOn, powerWrote, seatWrote, ascii(detail.toString()));
        }

        Report go(Plan plan) {
            int front = zone(FRONT_ZONE);
            int driver = zone(DRIVER_ZONE);
            note(String.format(Locale.US, "zone front=%s driver=%s", hex(front), hex(driver)));
            Integer power = function("HVAC_FUNC_POWER");
            if (power == null) {
                return report(Outcome.UNAVAILABLE, 0f, false);
            }
            note("power " + hex(power));
            return plan.on ? on(plan, power, front, driver) : off(plan, power, front, driver);
        }

        // ------------------------------------------------------------- 关

        /** 关：开关写 0，座椅写 0；读回开关是 0 算做成，别的都是没生效。 */
        private Report off(Plan plan, int power, int front, int driver) {
            boolean accepted = write(power, front, 0);
            powerWrote = accepted;
            if (plan.seats) {
                seatOff(driver);
            } else {
                note("no seats");
            }
            Integer seen = null;
            for (long waited = BACK_STEP_MS; waited <= BACK_MS; waited += BACK_STEP_MS) {
                clock.sleep(BACK_STEP_MS);
                seen = read(power, front);
                if (seen != null && seen == 0) {
                    note("back power " + hex(seen) + " after " + waited + " ms");
                    return report(Outcome.DONE, 0f, false);
                }
            }
            note("back power " + hex(seen) + " after " + BACK_MS + " ms" + (accepted ? "" : ", write refused"));
            return report(Outcome.NO_CHANGE, 0f, seen != null && seen == 1);
        }

        /** 关座椅：加热、通风都写 0；不受理不影响空调，记一笔。 */
        private void seatOff(int driver) {
            Integer heat = function("HVAC_FUNC_SEAT_HEATING");
            Integer vent = function("HVAC_FUNC_SEAT_VENTILATION");
            if (heat != null) {
                if (write(heat, driver, 0)) {
                    seatWrote = true;
                } else {
                    note("seat heating off refused");
                }
            }
            if (vent != null) {
                if (write(vent, driver, 0)) {
                    seatWrote = true;
                } else {
                    note("seat ventilation off refused");
                }
            }
        }

        // ------------------------------------------------------------- 开

        /** 开：先读范围，再写设定温度、写开关、写座椅，最后读回开关和设定温度。 */
        private Report on(Plan plan, int power, int front, int driver) {
            Integer temp = function("HVAC_FUNC_TEMP");
            Integer minId = function("HVAC_FUNC_TEMP_MIN");
            Integer maxId = function("HVAC_FUNC_TEMP_MAX");
            Integer stepId = function("HVAC_FUNC_TEMP_STEP");
            Integer unitId = function("HVAC_FUNC_TEMP_UNIT");
            Integer unitC = function("TEMPERATURE_UNIT_C");
            Integer heat = function("HVAC_FUNC_SEAT_HEATING");
            Integer vent = function("HVAC_FUNC_SEAT_VENTILATION");
            if (temp == null) {
                return report(Outcome.UNAVAILABLE, 0f, false);
            }

            // 范围是「车上说过的话」，不是开空调的前提：这台 7X 的适配层对
            // HVAC_FUNC_TEMP_MIN / MAX / STEP 不回值（实车 4.14.0 的「车机不报温度范围」就是这么来的），
            // 读不到就照车主说的那个整度写，写完用读回来说话。硬要范围才肯写，等于这条路永远走不通。
            //
            // 两个通道都试：温度类走浮点；有的适配层把范围当整型回（16 .. 31，步长 1 或 0）。
            // The range is something the car may say, not a precondition for switching the AC on: this
            // 7X adapter answers nothing for TEMP_MIN/MAX/STEP, which is exactly what produced 4.14.0's
            // "will not report its temperature range". When it is unreadable the owner's whole degree is
            // written and the readback does the talking. Both channels are tried: temperature is float,
            // but some adapters answer the range on the integer channel.
            Float min = readFloat(minId, driver, "min");
            Float max = readFloat(maxId, driver, "max");
            Float step = readFloat(stepId, driver, "step");
            boolean rangeKnown = min != null && max != null && step != null && step > 0f && min <= max;
            if (!rangeKnown) {
                Float minInt = readFloatOrInt(minId, driver, "min(int)");
                Float maxInt = readFloatOrInt(maxId, driver, "max(int)");
                Float stepInt = readFloatOrInt(stepId, driver, "step(int)");
                if (minInt != null && maxInt != null && minInt <= maxInt && maxInt - minInt >= 1f) {
                    min = minInt;
                    max = maxInt;
                    // 整型通道没有 0.5：读到 0 或者读不到就按 1 度算，读回那一关会用半度容差兜住。
                    step = stepInt != null && stepInt > 0f ? stepInt : 1f;
                    rangeKnown = true;
                }
            }
            if (rangeKnown) {
                note(String.format(Locale.US, "range %.1f..%.1f step %.2f", min, max, step));
            } else {
                note("range unknown, writing the requested target");
            }

            // 说话用哪种单位：**看车现在设的是多少度**，不是看单位那个号。
            // 这台 7X 报的是华氏（HVAC_FUNC_TEMP_UNIT 回 TEMPERATURE_UNIT_F），可它的设定值是 19.5 ——
            // 华氏车不可能有 19.5°F 的设定，所以按设定值判：10–45 是摄氏度，超过 45 是华氏。
            // 单位号只在设定值读不到时当兜底，而且不再「不是摄氏就拒」：4.15.0 就是那样把
            // 一辆摄氏度显示的车判成华氏，每一条 ac 都回「The car reports Fahrenheit」。
            //
            // Which unit the car speaks: **from the setpoint it currently holds**, not from the unit id.
            // This 7X reports TEMPERATURE_UNIT_F while its setpoint is 19.5, and a Fahrenheit car cannot
            // hold 19.5 °F, so 10–45 means Celsius and above 45 means Fahrenheit. The unit id is only a
            // fallback when the setpoint is unreadable — and it no longer refuses on its own, which is
            // what 4.15.0 did to a Celsius-displaying car.
            Integer unit = unitId == null ? null : access.read(unitId, driver);
            Integer unitF = function("TEMPERATURE_UNIT_F");
            Float before = access.readFloat(temp, driver);
            boolean fahrenheit = speaksFahrenheit(before, unit, unitF);
            note(String.format(Locale.US, "unit id %s (c=%s f=%s), setpoint before %s, %s",
                    hex(unit), hex(unitC), hex(unitF),
                    before == null ? "?" : String.format(Locale.US, "%.1f", before),
                    fahrenheit ? "fahrenheit" : "celsius"));

            Float target = rangeKnown ? snapped(plan.targetC, min, max, step) : atLeastPlausible(plan.targetC);
            if (target == null) {
                note(String.format(Locale.US, "target %.1f outside range", plan.targetC));
                return report(Outcome.REFUSED, 0f, false);
            }

            float written_value = fahrenheit ? celsiusToFahrenheit(target) : target;
            boolean written = access.writeFloat(temp, driver, written_value);
            note(String.format(Locale.US, "write temp %s@%s=%.1f %s %s", hex(temp), hex(driver),
                    written_value, fahrenheit ? "F" : "C", written ? "ok" : "refused"));
            if (!written) {
                return report(Outcome.REFUSED, 0f, false);
            }

            boolean powered = write(power, front, 1);
            powerWrote = powered;
            if (!powered) {
                return report(Outcome.REFUSED, target, false);
            }

            seatOn(plan, driver, heat, vent);

            float tolerance = rangeKnown ? step / 2f : 0.5f;
            return back(power, front, temp, driver, target, written_value,
                    fahrenheit ? tolerance * 9f / 5f : tolerance);
        }

        /** 座椅：只写一个号（加热优先），档位的值是功能号加档位；不受理不影响空调。 */
        private void seatOn(Plan plan, int driver, Integer heat, Integer vent) {
            if (!plan.seats) {
                note("no seats");
                return;
            }
            boolean useHeat = plan.heat;
            boolean useVent = !plan.heat && plan.vent;
            if (!useHeat && !useVent) {
                note("no seat channel");
                return;
            }
            Integer id = useHeat ? heat : vent;
            if (id == null) {
                note(useHeat ? "no seat heating" : "no seat ventilation");
                return;
            }
            int level = Math.max(1, Math.min(MAX_LEVEL, plan.level));
            if (write(id, driver, id + level)) {
                seatWrote = true;
            } else {
                note("seat write refused, climate kept");
            }
        }

        /** 读回：{@link #BACK_MS} 内开关读到 1、设定温度在容差以内算做成。 */
        private Report back(int power, int front, int temp, int driver, float target,
                           float writtenValue, float half) {
            Integer seenPower = null;
            Float seenTemp = null;
            for (long waited = BACK_STEP_MS; waited <= BACK_MS; waited += BACK_STEP_MS) {
                clock.sleep(BACK_STEP_MS);
                seenPower = read(power, front);
                seenTemp = access.readFloat(temp, driver);
                if (seenPower != null && seenPower == 1 && seenTemp != null
                        && Math.abs(seenTemp - writtenValue) <= half + EPSILON) {
                    note(String.format(Locale.US, "back power %s setpoint %.1f after %d ms",
                            hex(seenPower), seenTemp, waited));
                    return report(Outcome.DONE, target, true);
                }
            }
            note(String.format(Locale.US, "back power %s setpoint %s want %.1f after %d ms",
                    hex(seenPower), seenTemp == null ? "?" : String.format(Locale.US, "%.1f", seenTemp),
                    target, BACK_MS));
            if (seenPower != null && seenPower == 1 && seenTemp == null) {
                // 开关读到 1、设定值这个号这台车机就是不回（208 号这类 id 在整个适配层上是空的）：
                // 空调确实开了。把它当成「没变」等于回一句假话，还会让自动关排不上。
                // Power reads 1 and the setpoint id simply answers nothing on this adapter: the climate is
                // on. Calling that NO_CHANGE would be a false receipt and would lose the auto-off.
                note("power on, setpoint unreadable");
                return report(Outcome.DONE, target, true);
            }
            return report(Outcome.NO_CHANGE, target, seenPower != null && seenPower == 1);
        }

        // ------------------------------------------------------------- 读写

        /** 功能号；这台车机没有就记一笔、返回 null。 */
        Integer function(String field) {
            Integer id = names.function(IFACE, field);
            if (id == null) {
                note("no " + field);
            }
            return id;
        }

        int zone(String name) {
            return names.zone(name);
        }

        Integer read(int id, int zone) {
            return access.read(id, zone);
        }

        /** 读一个整型的号、记一笔（读数用十进制，单位那种枚举用十六进制）。 */
        Integer read(Integer id, int zone, String label) {
            if (id == null) {
                return null;
            }
            Integer v = access.read(id, zone);
            note("read " + label + "@" + hex(zone) + "=" + (v == null ? "?" : String.valueOf(v)));
            return v;
        }

        /** 先浮点、再整型：范围这几个号有的适配层只在一个通道上回。 */
        Float readFloatOrInt(Integer id, int zone, String label) {
            if (id == null) {
                return null;
            }
            Integer v = read(id, zone, label);
            // 占位值不是读数：车上实测范围那几个号整型通道回 255（= 未知），
            // 4.16.1 把它当成「范围就是 255..255」，于是每一条 ac 22 都被判成超出范围拒掉。
            // A placeholder is not a reading: on the car these ids answer 255 on the integer channel,
            // and 4.16.1 read that as "the range is 255..255", so every ac 22 was refused as out of range.
            if (v == null || !plausible(v)) {
                return null;
            }
            return (float) v;
        }

        /** 读一个浮点通道的号、记一笔（范围、步长走它：温度类只有这个通道带着小数）。 */
        Float readFloat(Integer id, int zone, String label) {
            if (id == null) {
                return null;
            }
            Float v = access.readFloat(id, zone);
            note(String.format(Locale.US, "read %s@%s=%s", label, hex(zone),
                    v == null ? "?" : String.format(Locale.US, "%.2f", v)));
            return v;
        }

        /** 写一个整型的号、记一笔。车机受理了返回 true。 */
        boolean write(int id, int zone, int value) {
            boolean accepted = access.write(id, zone, value);
            note(String.format(Locale.US, "write %s@%s=%d %s", hex(id), hex(zone), value,
                    accepted ? "ok" : "refused"));
            return accepted;
        }
    }

    // ================================================================= 纯函数

    /**
     * 车机不说范围时用：只接受一个人类真会设的温度，照 0.5 对齐后返回，越界返回 null。
     *
     * <p>这不是在猜车上的范围 —— 猜范围指的是写一个谁都没读到的号码；这里是「车主说了 22.5，就写 22.5」，
     * 越界的话车机自己会拒，读回那一关也能看出来。对齐到半度而不是整度，因为这台车的设定值真的停在 19.5：
     * 把 19.5 四舍五入成 20 就是替车主改温度，而 0.5 是这种车都肯接受的格子。</p>
     *
     * When the car states no range: accept a temperature a person would actually set, snap it to 0.5 and
     * return it. This is not guessing at the car's range — a guess would be an unread number; here the
     * owner asked for 22.5 and 22.5 is written, and the car's own refusal shows up in the readback. It
     * snaps to half a degree rather than a whole one because this car really holds 19.5: rounding that to
     * 20 rewrites the owner's setpoint, while 0.5 is a grid every such unit accepts.
     */
    static Float atLeastPlausible(float targetC) {
        if (Float.isNaN(targetC) || targetC < 10f || targetC > 35f) {
            return null;
        }
        return Math.round(targetC * 2f) / 2f;
    }

    /**
     * 车说哪种单位：设定值 10–45 是摄氏度，超过 45 是华氏；设定值读不到时才看单位号。
     *
     * <p>45 这个分界来自「人不会把空调设到 45 °C」：车上的最高档是 31–32，45 以上一定是华氏。</p>
     *
     * Which unit: a 10–45 setpoint is Celsius, above 45 is Fahrenheit; the unit id is the fallback only
     * when the setpoint cannot be read. Nobody sets a cabin to 45 °C — the top stop is 31–32 — so above
     * 45 must be Fahrenheit.
     */
    /**
     * 一个整型读数像不像温度范围：-20..60 之外、或者本来就是占位值的，都不是。
     *
     * <p>分界照这台车：最低 16、最高 31；-20..60 给别的车型留出余量，又挡住 255、-1、-65535。</p>
     *
     * Whether an integer reading could be a temperature range: outside -20..60, or a placeholder, it
     * cannot be. The car sits at 16..31; -20..60 leaves room for other models and blocks 255/-1/-65535.
     */
    static boolean plausible(int value) {
        return value >= -20 && value <= 60;
    }

    static boolean speaksFahrenheit(Float setpointBefore, Integer unit, Integer unitF) {
        if (setpointBefore != null) {
            return setpointBefore > 45f;
        }
        return unit != null && unitF != null && unit.equals(unitF);
    }

    static float celsiusToFahrenheit(float celsius) {
        return celsius * 9f / 5f + 32f;
    }

    /**
     * 照车上的范围、步长算要写的温度：范围之外就是不写（返回 null，车要的是它自己的范围），
     * 范围内的零头照步长对齐，对齐的起点是最小值。
     *
     * <p>以前允许「超出一步以内」，夹回范围再写：那会写一个车主没要的温度（他要 17，车给 16，还是写了）。
     * 范围是从车上读的，超出就是不写。</p>
     *
     * Work out the setpoint from the car's own range and step: outside the range is a refusal (null — the
     * car's range is the authority), and a fraction inside it snaps to the grid anchored at the minimum.
     *
     * <p>It used to tolerate one step outside and clamp, which writes a temperature the owner did not ask
     * for. The range comes from the car, so outside it means no write.</p>
     */
    static Float snapped(float targetC, float min, float max, float step) {
        if (step <= 0f || min > max || Float.isNaN(targetC)) {
            return null;
        }
        if (targetC < min - EPSILON || targetC > max + EPSILON) {
            return null;
        }
        float clamped = Math.max(min, Math.min(max, targetC));
        int n = Math.round((clamped - min) / step);
        if (n < 0) {
            n = 0;
        }
        float snapped = min + n * step;
        while (snapped > max + EPSILON && n > 0) {
            n--;
            snapped = min + n * step;
        }
        return snapped;
    }

    /** 只留 ASCII（黑匣子里不能有别的）。 */
    static String ascii(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(c >= 0x20 && c < 0x7F ? c : '?');
        }
        return out.toString();
    }

    static String hex(Integer v) {
        return v == null ? "?" : String.format(Locale.US, "0x%X", v);
    }
}

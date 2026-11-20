/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.telemetry;

/**
 * 车辆接口的读写（{@link Telemetry#carAccess}）：只有灵动贴的动作用（驾驶模式和其他车控动作，项目所有者 2026-10-30、11-04）。
 *
 * <p>都是同步的 binder 调用，可以在任何线程上叫，但会等车机回话：别在主线程上叫。读不到、写不进返回 null / false，不抛。
 * 车机说「受理」不等于做到了（Lab findings §2.48、§2.50）：要读回来看。</p>
 */
public interface CarAccess {

    /** 不带区域读一个功能号；读不到是 null。 */
    Integer read(int id);

    /** 带区域读一个功能号；读不到（或者这台车机没有带区域的读法）是 null。 */
    Integer read(int id, int zone);

    /** 不带区域写一个功能号：车机受理了返回 true。 */
    boolean write(int id, int value);

    /** 带区域写一个功能号：车机受理了返回 true（这台车机没有带区域的写法也是 false）。 */
    boolean write(int id, int zone, int value);

    /**
     * 车机说这个号在这个区域上现在能不能用（ECARX 的 {@code isFunctionSupported}：active / notactive / notavailable / error）；
     * 问不了（这台车机上没有这个方法、出了错）是 null。关门照 Lab findings §2.93：不是 active 不写。
     */
    String status(int id, int zone);

    /** 一个传感器现在的值；读不到（含占位值）是 null。 */
    Float sensor(int type);

    /**
     * 浮点通道读一个功能号（ECARX 的 {@code getCustomizeFunctionValue(id, zone)}）：温度这类只收小数的号走它，
     * 整型的 {@code getFunctionValue} 对 {@code HVAC_FUNC_TEMP} 恒回占位值 253。
     * 这台车机没有这个方法（或者调不通）是 null。
     *
     * <p>默认实现回 null：老接口（测试里的假车）不必跟着改 —— 读不到就是读不到，客户端本来就要处理 null。</p>
     *
     * The float channel, {@code getCustomizeFunctionValue(id, zone)}. Temperature-style functions that
     * only take decimals go through it; the integer {@code getFunctionValue} returns the placeholder 253
     * for {@code HVAC_FUNC_TEMP}. Null when this head unit does not have the method.
     *
     * <p>The default returns null so existing implementations (and the fakes in tests) keep compiling:
     * a missing read is something the caller already has to handle.</p>
     */
    default Float readCustom(int id, int zone) {
        return null;
    }

    /**
     * 浮点通道写一个功能号（ECARX 的 {@code setCustomizeFunctionValue(id, zone, value)}）：车机受理了返回 true。
     * 区域必须是真的区域号（0 会被车机当成「不是有效区域」拒绝）；整数通道对温度号恒回 false，没有回退。
     *
     * <p>默认实现回 false：没实现就是没写成。</p>
     *
     * The float channel, {@code setCustomizeFunctionValue(id, zone, value)}: true when the unit accepted
     * it. The zone must be a real zone (0 is refused as "not a valid zone"), and there is no integer
     * fallback for temperature — that channel always answers false.
     *
     * <p>The default returns false: not implemented is not written.</p>
     */
    default boolean writeCustom(int id, int zone, float value) {
        return false;
    }
}

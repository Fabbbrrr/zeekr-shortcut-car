/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.policy;

/**
 * 驾驶座该加热还是该通风 —— 纯算术，不碰车，便于单测（{@code ClimateSeatsTest}）。
 *
 * <p>为什么车里的温度不是唯一的依据：夏天外面三十五度，车厢设到二十二度，座椅通风是舒服的；
 * 但只要外面比车里还凉（清晨、雨后、地库），通风就是在往腰上吹凉风，还不如什么都不做。
 * 加热反过来一样：冬天外面零下，加热才对；三月份外面二十度而车里冷，那是刚上车，
 * 开一会儿就好，加热只会让人出汗。所以两条规则都同时看车里和车外，外面那一关一票否决。</p>
 *
 * <p>为什么要 {@code bandC} 的死区：设定二十二度、车里正好二十二点四，座椅一会儿加热一会儿停，
 * 车主感觉得到，而且那两条写入是有代价的。死区里的读数是「已经够好了」，什么也不写。</p>
 *
 * <p>为什么不可能同时加热和通风：车里比目标低 {@code bandC} 与比目标高 {@code bandC} 是两件互斥的事，
 * 只要 {@code bandC} 不为负就不会同时成立。真给出负的回差（配置写错了）就什么都不做 ——
 * 同时加热又通风会把座椅里的两个执行器对着干，那是硬件问题，不是舒适问题。</p>
 *
 * Whether the driver seat should be heated or ventilated: pure arithmetic, no car access, easy to
 * unit test ({@code ClimateSeatsTest}).
 *
 * <p>Why the cabin temperature alone is not enough: on a thirty-five degree summer day with the
 * climate set to twenty-two, seat ventilation is comfortable; but whenever the outside is cooler than
 * the cabin (early morning, after rain, an underground car park) ventilation blows cold air at your
 * back and doing nothing is better. Heating is the mirror image: below zero it is right, while a
 * twenty-degree March day with a cold cabin just means the car was only started, so heating only makes
 * the driver sweat. Both rules therefore look at the cabin and the outside, and the outside is a veto.</p>
 *
 * <p>Why the {@code bandC} dead band: set to twenty-two with the cabin at twenty-two point four, a seat
 * without a dead band heats, stops, heats again, which the driver feels and which costs two writes.
 * A reading inside the band means "already good enough", so nothing is written.</p>
 *
 * <p>Why heat and ventilation can never both be asked for: the cabin being more than {@code bandC}
 * below the target and more than {@code bandC} above it are mutually exclusive, so with a
 * non-negative {@code bandC} both cannot hold. If a negative band is ever configured (a bad value),
 * nothing is written -- heating and ventilating at once drives two actuators against each other, which
 * is a hardware fault, not a comfort setting.</p>
 */
public final class ClimateSeats {

    private ClimateSeats() {
    }

    /**
     * 选出来的动作，两个都为 false 就是什么都不写。
     *
     * The chosen action; both false means write nothing.
     */
    public static final class Choice {

        /** 加热。 Heat the seat. */
        public final boolean heat;

        /** 通风。 Ventilate the seat. */
        public final boolean vent;

        private Choice(boolean heat, boolean vent) {
            this.heat = heat;
            this.vent = vent;
        }
    }

    private static final Choice NEITHER = new Choice(false, false);
    private static final Choice HEAT = new Choice(true, false);
    private static final Choice VENT = new Choice(false, true);

    /**
     * 该加热还是该通风。
     *
     * @param cabinC           车内温度（℃）
     * @param targetC          设定温度（℃）
     * @param outsideC         车外温度（℃）
     * @param bandC            死区（℃）
     * @param heatOutsideMaxC  外面低于这个才加热（℃）
     * @param ventOutsideMinC  外面高于这个才通风（℃）
     *
     * Which of heat and ventilation to use.
     *
     * @param cabinC           cabin temperature in Celsius
     * @param targetC          setpoint in Celsius
     * @param outsideC         outside temperature in Celsius
     * @param bandC            dead band in Celsius
     * @param heatOutsideMaxC  heat only below this outside temperature, Celsius
     * @param ventOutsideMinC  ventilate only above this outside temperature, Celsius
     */
    public static Choice choose(float cabinC, float targetC, float outsideC, float bandC,
                                float heatOutsideMaxC, float ventOutsideMinC) {
        boolean heat = cabinC < targetC - bandC && outsideC < heatOutsideMaxC;
        boolean vent = cabinC > targetC + bandC && outsideC > ventOutsideMinC;
        if (heat && vent) {
            // 回差为负才会到这里：配置错了，两边都不动，别让两个执行器对着干。
            // Only a negative band reaches this: a bad value, so move neither rather than fight.
            return NEITHER;
        }
        if (heat) {
            return HEAT;
        }
        if (vent) {
            return VENT;
        }
        return NEITHER;
    }
}

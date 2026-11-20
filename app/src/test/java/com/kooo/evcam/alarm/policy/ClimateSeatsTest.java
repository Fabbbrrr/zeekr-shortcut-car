/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.policy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.kooo.evcam.alarm.policy.ClimateSeats.Choice;

/**
 * 座椅加热 / 通风的选择（{@link ClimateSeats}）：车里那关要过死区，车外那关有一票否决。
 *
 * <p>每条规则一个断言，名字就是那句话。</p>
 *
 * Choosing seat heating or ventilation ({@link ClimateSeats}): the cabin reading has to clear the dead
 * band and the outside reading holds a veto.
 *
 * <p>One assertion per rule, and the name is the sentence.</p>
 */
public class ClimateSeatsTest {

    private static final float TARGET_C = 22f;
    private static final float BAND_C = 2f;
    private static final float HEAT_OUTSIDE_MAX_C = 20f;
    private static final float VENT_OUTSIDE_MIN_C = 20f;

    private static Choice choose(float cabinC, float outsideC) {
        return ClimateSeats.choose(cabinC, TARGET_C, outsideC, BAND_C, HEAT_OUTSIDE_MAX_C,
                VENT_OUTSIDE_MIN_C);
    }

    @Test
    public void aColdCabinOnAColdDayHeatsTheSeat() {
        assertTrue(choose(18f, 5f).heat);
    }

    @Test
    public void theHotOutsideVetoStopsHeatingEvenWhenTheCabinIsCold() {
        assertFalse("外面 25 度，车里冷是刚上车，加热只会出汗", choose(18f, 25f).heat);
    }

    @Test
    public void aHotCabinOnAWarmDayVentsTheSeat() {
        assertTrue(choose(26f, 30f).vent);
    }

    @Test
    public void theCoolOutsideVetoStopsVentingEvenWhenTheCabinIsHot() {
        assertFalse("外面 15 度比车里凉，通风是往腰上吹凉风", choose(26f, 15f).vent);
    }

    @Test
    public void aCabinInsideTheDeadBandGetsNeither() {
        Choice choice = choose(TARGET_C, 5f);
        assertFalse(choice.heat || choice.vent);
    }

    @Test
    public void exactlyTheBandBelowTheTargetIsStillNeither() {
        Choice choice = choose(TARGET_C - BAND_C, 5f);
        assertFalse(choice.heat || choice.vent);
    }

    @Test
    public void exactlyTheBandAboveTheTargetIsStillNeither() {
        Choice choice = choose(TARGET_C + BAND_C, 30f);
        assertFalse(choice.heat || choice.vent);
    }

    @Test
    public void heatAndVentAreNeverAskedForTogetherAcrossTheWholeMatrix() {
        boolean both = false;
        for (float cabin = -10f; cabin <= 50f; cabin += 0.5f) {
            for (float outside = -20f; outside <= 50f; outside += 1f) {
                Choice choice = choose(cabin, outside);
                both = both || (choice.heat && choice.vent);
            }
        }
        assertFalse("加热与通风永远只有一个，或者一个都没有", both);
    }
}

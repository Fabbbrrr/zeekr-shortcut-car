/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.kooo.evcam.alarm.guard.GuardToken;
import com.kooo.evcam.alarm.telegram.TelegramCommand;

/** 警报照片下面那排按钮（{@link AlertButton}）。 */
public class AlertButtonTest {

    private static final String TOKEN = GuardToken.mint(1_700_000_000_000L);

    @Test
    public void everyActionRoundTrips() {
        assertEquals(AlertButton.ACTION_HIGH, AlertButton.actionFor(
                AlertButton.data(AlertButton.ACTION_HIGH, TOKEN), TOKEN));
        for (int lane = 0; lane < 4; lane++) {
            String action = AlertButton.clipAction(lane);
            assertEquals(action, AlertButton.actionFor(AlertButton.data(action, TOKEN), TOKEN));
            assertEquals(lane, AlertButton.laneOf(action));
        }
    }

    @Test
    public void anOlderAlertsButtonDoesNothing() {
        String older = GuardToken.mint(1_600_000_000_000L);
        assertNull(AlertButton.actionFor(AlertButton.data(AlertButton.ACTION_HIGH, older), TOKEN));
    }

    @Test
    public void theReminderButtonsAreNotOurs() {
        // 忘了开哨兵那套是 g:…，两套按钮不串
        assertNull(AlertButton.actionFor(GuardToken.data(GuardToken.ACTION_ON, TOKEN), TOKEN));
    }

    @Test
    public void junkAndOutOfRangeLanesDoNothing() {
        assertNull(AlertButton.actionFor(null, TOKEN));
        assertNull(AlertButton.actionFor("", TOKEN));
        assertNull(AlertButton.actionFor("a:hq", TOKEN));
        assertNull(AlertButton.actionFor("a:c4:" + TOKEN, TOKEN));
        assertNull(AlertButton.actionFor("a:c-1:" + TOKEN, TOKEN));
        assertNull(AlertButton.actionFor("a:del:" + TOKEN, TOKEN));
        assertEquals(TelegramCommand.ANY_LANE, AlertButton.laneOf("hq"));
        assertEquals(TelegramCommand.ANY_LANE, AlertButton.laneOf(null));
        assertEquals(TelegramCommand.ANY_LANE, AlertButton.laneOf("c9"));
    }

    @Test
    public void everyPayloadFitsTelegramsSixtyFourByteLimit() {
        for (int lane = 0; lane < 4; lane++) {
            String data = AlertButton.data(AlertButton.clipAction(lane), TOKEN);
            assertTrue(data, data.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 64);
        }
        for (float target : new float[] {10f, 22.5f, 35f}) {
            String data = AlertButton.data(AlertButton.climateAction(target), TOKEN);
            assertTrue(data, data.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 64);
        }
        for (String seat : new String[] {AlertButton.ACTION_SEAT_HEAT_LEFT,
                AlertButton.ACTION_SEAT_HEAT_RIGHT, AlertButton.ACTION_SEAT_VENT_LEFT,
                AlertButton.ACTION_SEAT_VENT_RIGHT, AlertButton.ACTION_SEAT_OFF}) {
            String data = AlertButton.data(seat, TOKEN);
            assertTrue(data, data.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 64);
        }
    }

    /**
     * 半度写进 callback_data：整度是 {@code ac22}，半度是 {@code ac22_5}，两条都能读回来。
     *
     * <p>不用小数点：那不是这串字里该出现的东西，而且 64 字节的上限是拿来给号码的。</p>
     *
     * A half degree inside callback_data: {@code ac22} for a whole, {@code ac22_5} for a half, and both
     * read back. No decimal point — it does not belong in that string, and the 64 bytes are for the token.
     */
    @Test
    public void aHalfDegreeButtonRoundTrips() {
        assertEquals("ac22", AlertButton.climateAction(22f));
        assertEquals("ac22_5", AlertButton.climateAction(22.5f));
        assertEquals("ac10", AlertButton.climateAction(10f));
        assertEquals("ac34_5", AlertButton.climateAction(34.5f));

        assertEquals(22f, AlertButton.climateTarget("ac22"), 0f);
        assertEquals(22.5f, AlertButton.climateTarget("ac22_5"), 0f);
        assertEquals(10.5f, AlertButton.climateTarget("ac10_5"), 0f);
        assertEquals("关掉那颗是 0", 0f, AlertButton.climateTarget(AlertButton.ACTION_CLIMATE_OFF), 0f);

        // 整条路：编出去、解回来，数字不变
        String payload = AlertButton.data(AlertButton.climateAction(22.5f), TOKEN);
        assertEquals(22.5f, AlertButton.climateTarget(AlertButton.actionFor(payload, TOKEN)), 0f);
        assertEquals("ac22_5", AlertButton.climateActionOf(payload));
    }

    /**
     * 回执下面那五颗座椅按钮：五个动作、五个 callback_data，编出去再解回来都是一个东西，而且都在 64 字节以内。
     *
     * <p>一颗按钮是一个动作，不是「几百度」：把它们塞进 {@code ac} 那个前缀里，{@code climateTarget} 就会拿一个
     * 座椅动作去解温度。</p>
     *
     * The five seat buttons under a receipt: five actions, five payloads, round-tripped and inside Telegram's
     * 64-byte limit. A seat button is an action, not a temperature — folding it into the {@code ac} prefix
     * would have the temperature parser read a seat as degrees.
     */
    @Test
    public void theSeatButtonsRoundTrip() {
        for (String action : new String[] {AlertButton.ACTION_SEAT_HEAT_LEFT,
                AlertButton.ACTION_SEAT_HEAT_RIGHT, AlertButton.ACTION_SEAT_VENT_LEFT,
                AlertButton.ACTION_SEAT_VENT_RIGHT, AlertButton.ACTION_SEAT_OFF}) {
            String payload = AlertButton.data(action, TOKEN);
            assertTrue(action, AlertButton.isSeatAction(action));
            assertEquals(action, AlertButton.actionFor(payload, TOKEN));
            assertEquals("不看号码也能认出是座椅按钮", action, AlertButton.seatActionOf(payload));
            assertTrue("长度上限 64 字节: " + payload,
                    payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 64);
            assertEquals("座椅动作没有温度：解温度必须是否", -1f, AlertButton.climateTarget(action), 0f);
            assertNull("不是空调按钮", AlertButton.climateActionOf(payload));
        }
        assertEquals("shl", AlertButton.ACTION_SEAT_HEAT_LEFT);
        assertEquals("shr", AlertButton.ACTION_SEAT_HEAT_RIGHT);
        assertEquals("svl", AlertButton.ACTION_SEAT_VENT_LEFT);
        assertEquals("svr", AlertButton.ACTION_SEAT_VENT_RIGHT);
        assertEquals("soff", AlertButton.ACTION_SEAT_OFF);
    }

    /** 陈的座椅按钮：号码对不上就不办（但能认出来，好在聊天里回一句话）。 */
    @Test
    public void anOlderAlertsSeatButtonDoesNothing() {
        String older = GuardToken.mint(1_600_000_000_000L);
        String payload = AlertButton.data(AlertButton.ACTION_SEAT_HEAT_LEFT, older);

        assertNull(AlertButton.actionFor(payload, TOKEN));
        assertEquals(AlertButton.ACTION_SEAT_HEAT_LEFT, AlertButton.seatActionOf(payload));
        assertNull("座椅动作不能被当成照片那一排的", AlertButton.actionFor(payload, ""));
        assertNull(AlertButton.seatActionOf("a:ac22:" + TOKEN));
        assertNull(AlertButton.seatActionOf("a:hq:" + TOKEN));
        assertNull(AlertButton.seatActionOf(null));
    }

    /** 陈的、认不出来的动作还是 -1：不猜温度。 A stale or unknown action is still -1: nothing is guessed. */
    @Test
    public void anUnknownClimateActionIsMinusOne() {
        assertEquals(-1f, AlertButton.climateTarget(null), 0f);
        assertEquals(-1f, AlertButton.climateTarget("ac"), 0f);
        assertEquals(-1f, AlertButton.climateTarget("ac9_5"), 0f);
        assertEquals(-1f, AlertButton.climateTarget("ac35_5"), 0f);
        assertEquals(-1f, AlertButton.climateTarget("ac22_0"), 0f);
        assertEquals(-1f, AlertButton.climateTarget("ac22_25"), 0f);
        assertEquals(-1f, AlertButton.climateTarget("ac22.5"), 0f);
        assertEquals(-1f, AlertButton.climateTarget("acabc"), 0f);
        assertEquals(-1f, AlertButton.climateTarget("hq"), 0f);
        assertNull(AlertButton.climateActionOf(AlertButton.data("ac22_0", TOKEN)));
    }
}

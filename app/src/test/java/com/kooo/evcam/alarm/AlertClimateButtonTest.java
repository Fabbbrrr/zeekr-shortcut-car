/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to this code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.alarm.guard.GuardToken;
import com.kooo.evcam.alarm.ntfy.NtfyGate;
import com.kooo.evcam.alarm.ntfy.NtfyRisk;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.concurrent.TimeUnit;

/**
 * 温度警报下面那排按钮按下去真的有人接。
 *
 * <h3>为什么单独一条</h3>
 *
 * <p>4.14.0 的空调按钮按下去什么都不发生，而且日志里写的是「旧警报的按钮，忽略」：第一道路由拿
 * 照片那一排的号码去比空调那一排的按钮，第二道又把「动作」当成「整串 callback_data」再比一次，
 * 两次都必然对不上。车里看不到这两处，只有按一下才知道，所以这里按一下。</p>
 *
 * <p>钉住三件事：<b>刚发出去的那条能按</b>、<b>号码不对的不响应</b>、<b>按下去必有回话</b>
 * （回的是「车没停」也好，是回执也好 —— 静默就是那个 bug）。</p>
 *
 * <p>The AC buttons under a temperature alert did nothing in 4.14.0: the first routing compared the photo
 * row's token against the climate row's button, and the second compared an action against a whole
 * callback_data payload, so neither could ever match. It is invisible from the car — only a press shows it,
 * so this presses.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, shadows = {ShadowNtfySender.class})
public class AlertClimateButtonTest {

    private static final String TOPIC =
            "zeekrsc_kymoy9m40cuQYXmHabcdefghijklmnopqrstu";

    private Context context;
    private AppConfig config;

    @Before
    public void setUp() throws Exception {
        // 单例活在整个沙箱里，每个测试方法换一个 Application：不清掉它，写的是上一个方法那份设置。
        // The singleton lives for the whole sandbox while every method gets a new Application.
        forgetSingleton();
        context = RuntimeEnvironment.getApplication();
        ShadowNtfySender.reset();
        config = new AppConfig(context);
        config.setAlertsEnabled(true);
        config.setAlertsTransport(NtfyGate.NTFY);
        config.setAlertsNtfyTopic(TOPIC);
        config.setAlertsNtfyRiskAck(NtfyRisk.ackFor(TOPIC));
        config.setAlertsNtfyPaid(true);
        config.setAlertsTelegramToken("");
        config.setAlertsTelegramChatId("");
        config.setAlertsClimateEnabled(true);
    }

    @Test
    public void aFreshClimateButtonIsAnswered() throws Exception {
        String token = GuardToken.mint(4242L);
        setClimateToken(token);
        String payload = AlertButton.data(AlertButton.climateAction(22), token);

        SentryAlerts.get(context).onAlertButton(payload);

        assertTrue("按下去了就该有回话（哪怕是「车没停」），静默就是 4.14.0 的那个 bug",
                ShadowNtfySender.latch.await(5, TimeUnit.SECONDS));
        assertEquals("只发一句文字", 1, ShadowNtfySender.texts);
        assertEquals("空调按钮不该拍照", 0, ShadowNtfySender.photos);
    }

    @Test
    public void theOffButtonIsAnsweredToo() throws Exception {
        String token = GuardToken.mint(4243L);
        setClimateToken(token);
        String payload = AlertButton.data(AlertButton.ACTION_CLIMATE_OFF, token);

        SentryAlerts.get(context).onAlertButton(payload);

        assertTrue(ShadowNtfySender.latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, ShadowNtfySender.texts);
    }

    @Test
    public void aButtonFromAnotherAlertIsIgnored() throws Exception {
        setClimateToken(GuardToken.mint(1L));
        String payload = AlertButton.data(AlertButton.climateAction(22), GuardToken.mint(2L));

        SentryAlerts.get(context).onAlertButton(payload);

        assertEquals("号码不对就不响应：一周前那条消息上的按钮不能开今天的空调",
                0, ShadowNtfySender.texts);
    }

    /** 单例活在整个沙箱里：清掉它，下一次 {@code get()} 才用这一次的 Application。 */
    private static void forgetSingleton() throws Exception {
        java.lang.reflect.Field field = SentryAlerts.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
    }

    /** 警报那一刻发的号是私有的；这里当成「刚发出去」的那个号填进去。 */
    private static void setClimateToken(String token) throws Exception {
        java.lang.reflect.Field field = SentryAlerts.class.getDeclaredField("climateToken");
        field.setAccessible(true);
        field.set(SentryAlerts.get(RuntimeEnvironment.getApplication()), token);
    }
}

/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.kooo.evcam.alarm.telegram.TelegramCommand;

/**
 * 车主发来的一句话怎么读（{@link TelegramCommand}）：只认配好的那个会话，只认命令、四个方向和
 * {@code guard} 那一族。
 *
 * How to read a message the owner sends ({@link TelegramCommand}): only the configured chat counts, and
 * only the commands, the four directions, and the {@code guard} family are recognized.
 */
public class TelegramCommandTest {

    @Test
    public void picturesAreAskedForInFourWays() {
        for (String word : new String[] {"pic", "photo", "snap", "snapshot", "PIC", "Photo"}) {
            TelegramCommand command = TelegramCommand.parseText(word);
            assertEquals(word, TelegramCommand.Verb.PICTURE, command.verb);
            assertEquals(word, TelegramCommand.ANY_LANE, command.lane);
        }
    }

    @Test
    public void aSlashAndABotMentionAreIgnored() {
        assertEquals(TelegramCommand.Verb.PICTURE, TelegramCommand.parseText("/pic").verb);
        assertEquals(TelegramCommand.Verb.PICTURE,
                TelegramCommand.parseText("/pic@ZeekrSentry_bot").verb);
        assertEquals("带方向也要认", 2, TelegramCommand.parseText("/pic@ZeekrSentry_bot left").lane);
    }

    @Test
    public void aDirectionPicksThatLane() {
        assertEquals(0, TelegramCommand.parseText("pic front").lane);
        assertEquals(1, TelegramCommand.parseText("pic rear").lane);
        assertEquals(2, TelegramCommand.parseText("picture left").lane);
        assertEquals(3, TelegramCommand.parseText("PHOTO RIGHT").lane);
        assertEquals("不认识的词当没写", TelegramCommand.ANY_LANE,
                TelegramCommand.parseText("pic roof").lane);
    }

    @Test
    public void statusIsItsOwnVerbAndTakesNoPicture() {
        assertEquals(TelegramCommand.Verb.STATUS, TelegramCommand.parseText("status").verb);
        assertEquals(TelegramCommand.Verb.STATUS, TelegramCommand.parseText("/state").verb);
    }

    @Test
    public void everythingElseIsUnknown() {
        assertEquals(TelegramCommand.Verb.UNKNOWN, TelegramCommand.parseText("").verb);
        assertEquals(TelegramCommand.Verb.UNKNOWN, TelegramCommand.parseText("   ").verb);
        assertEquals(TelegramCommand.Verb.UNKNOWN, TelegramCommand.parseText(null).verb);
        assertEquals(TelegramCommand.Verb.UNKNOWN, TelegramCommand.parseText("hello").verb);
        assertEquals(TelegramCommand.Verb.UNKNOWN, TelegramCommand.parseText("\uD83D\uDE00").verb);
    }

    @Test
    public void onlyTheConfiguredChatIsTheOwner() {
        assertTrue(TelegramCommand.fromOwner("7761390174", "7761390174"));
        assertTrue("负数是群组会话号，也照样按字符串比", TelegramCommand.fromOwner("-1001234", "-1001234"));
        assertFalse(TelegramCommand.fromOwner("999", "7761390174"));
        assertFalse("没配好之前谁都不是主人", TelegramCommand.fromOwner("7761390174", ""));
        assertFalse(TelegramCommand.fromOwner(null, "7761390174"));
        assertFalse(TelegramCommand.fromOwner("7761390174", null));
    }

    private static TelegramCommand.GuardAction guard(String text) {
        TelegramCommand command = TelegramCommand.parseText(text);
        assertEquals(text, TelegramCommand.Verb.GUARD, command.verb);
        return command.guard;
    }

    private static void notAGuard(String text) {
        TelegramCommand command = TelegramCommand.parseText(text);
        assertEquals(text, TelegramCommand.Verb.UNKNOWN, command.verb);
        assertEquals(text, null, command.guard);
    }

    @Test
    public void everyGuardPhraseMeansOneAction() {
        assertEquals(TelegramCommand.GuardAction.REPORT, guard("guard"));
        assertEquals(TelegramCommand.GuardAction.ON, guard("guard on"));
        assertEquals(TelegramCommand.GuardAction.OFF, guard("guard off"));
        assertEquals(TelegramCommand.GuardAction.HOME_SET, guard("guard home"));
        assertEquals(TelegramCommand.GuardAction.HOME_CLEAR, guard("guard home clear"));
        assertEquals(TelegramCommand.GuardAction.AUTO_OFF, guard("guard auto off"));
        assertEquals(TelegramCommand.GuardAction.AUTO_ASK, guard("guard auto ask"));
        assertEquals(TelegramCommand.GuardAction.AUTO_ASK_THEN_ON, guard("guard auto then"));
        assertEquals(TelegramCommand.GuardAction.AUTO_ON, guard("guard auto on"));
        assertEquals("guard then 是 guard auto then 的短写法",
                TelegramCommand.GuardAction.AUTO_ASK_THEN_ON, guard("guard then"));
    }

    @Test
    public void aSlashABotMentionCaseAndWhitespaceDoNotMatterForGuard() {
        assertEquals("群里会写成 /guard@BotName on",
                TelegramCommand.GuardAction.ON, guard("/guard@ZeekrSentry_bot on"));
        assertEquals(TelegramCommand.GuardAction.REPORT, guard("/GUARD"));
        assertEquals(TelegramCommand.GuardAction.AUTO_ASK, guard("  /Guard   Auto   ASK  "));
        assertEquals(TelegramCommand.GuardAction.HOME_CLEAR, guard("guard HOME clear"));
    }

    @Test
    public void aWordAfterGuardThatMeansNothingIsUnknown() {
        // 不猜：认不出来的词不会撑到某个最近的意思上
        // No guessing: an unrecognized word does not snap to a nearby meaning
        notAGuard("guard nonsense");
        notAGuard("guard off now");
        notAGuard("guard home on");
        notAGuard("guard auto");
        notAGuard("guard auto maybe");
        notAGuard("guard home clear now");
    }

    @Test
    public void theOlderCommandsDoNotGrowAGuardAction() {
        assertEquals(null, TelegramCommand.parseText("pic").guard);
        assertEquals(null, TelegramCommand.parseText("pic left").guard);
        assertEquals(null, TelegramCommand.parseText("status").guard);
        assertEquals(null, TelegramCommand.parseText("hello").guard);
    }

    // ------------------------------------------------------------------ 灵敏度 / sensitivity

    @Test
    public void sensitivityReportsMovesAndTakesANumber() {
        assertEquals(TelegramCommand.SensitivityAction.REPORT, sensitivity("sens"));
        assertEquals(TelegramCommand.SensitivityAction.REPORT, sensitivity("/sensitivity"));
        assertEquals(TelegramCommand.SensitivityAction.UP, sensitivity("sens up"));
        assertEquals(TelegramCommand.SensitivityAction.UP, sensitivity("sens higher"));
        assertEquals(TelegramCommand.SensitivityAction.UP, sensitivity("sens +"));
        assertEquals(TelegramCommand.SensitivityAction.DOWN, sensitivity("sens down"));
        assertEquals(TelegramCommand.SensitivityAction.DOWN, sensitivity("SENS lower"));
        assertEquals(TelegramCommand.SensitivityAction.DOWN, sensitivity("sens -"));
        assertEquals(TelegramCommand.SensitivityAction.SET, sensitivity("sens 7"));
        assertEquals(7, level("sens 7"));
        assertEquals("set 那个词是可选的", 4, level("sens set 4"));
        assertEquals(TelegramCommand.SensitivityAction.SET, sensitivity("sens 1"));
        assertEquals(1, level("sens 1"));
        assertEquals("10 是最大的一档", 10, level("sens 10"));
    }

    @Test
    public void aSlashAndABotMentionDoNotMatterForSensitivity() {
        assertEquals(TelegramCommand.SensitivityAction.UP, sensitivity("/sens@ZeekrSentry_bot up"));
        assertEquals(TelegramCommand.SensitivityAction.REPORT, sensitivity("  /SENS  "));
    }

    @Test
    public void aNumberOutsideOneToTenIsNotGuessedAt() {
        // 不猜：11 不是「最近的 10」，0 也不是 1
        // No guessing: 11 is not "10, nearly", and 0 is not 1
        notASensitivity("sens 0");
        notASensitivity("sens 11");
        notASensitivity("sens 99");
        notASensitivity("sens -3");
        notASensitivity("sens ten");
        notASensitivity("sens up up");
        notASensitivity("sens set");
        notASensitivity("sens set 0");
        notASensitivity("sens set 11");
        notASensitivity("sens auto");
    }

    @Test
    public void onlySensitivityCommandsCarryASensitivityAction() {
        assertEquals(null, TelegramCommand.parseText("pic").sensitivity);
        assertEquals(null, TelegramCommand.parseText("status").sensitivity);
        assertEquals(null, TelegramCommand.parseText("guard on").sensitivity);
        assertEquals(null, TelegramCommand.parseText("hello").sensitivity);
        // 数字只对 sens 有意义：pic 后面跟个 7 仍然是「拍一张」，不是「第 7 档」。
        // A number only means something after sens: "pic 7" is still one picture, not a level.
        TelegramCommand pic = TelegramCommand.parseText("pic 7");
        assertEquals(TelegramCommand.Verb.PICTURE, pic.verb);
        assertEquals(null, pic.sensitivity);
        assertEquals(TelegramCommand.ANY_LANE, pic.lane);
    }

    // ------------------------------------------------------------------ 暂停 / 恢复警报

    @Test
    public void alertsParsesOffOnAndPastTenseWords() {
        assertEquals(TelegramCommand.AlertsState.REPORT, alerts("alerts"));
        assertEquals(TelegramCommand.AlertsState.REPORT, alerts("/alarm"));
        assertEquals(TelegramCommand.AlertsState.OFF, alerts("alerts off"));
        assertEquals(TelegramCommand.AlertsState.OFF, alerts("alerts pause"));
        assertEquals(TelegramCommand.AlertsState.OFF, alerts("alerts stop"));
        assertEquals(TelegramCommand.AlertsState.ON, alerts("alerts on"));
        assertEquals(TelegramCommand.AlertsState.ON, alerts("alerts resume"));
        assertEquals(TelegramCommand.AlertsState.ON, alerts("ALERTS start"));
    }

    @Test
    public void aSlashAndABotMentionDoNotMatterForAlerts() {
        assertEquals(TelegramCommand.AlertsState.OFF, alerts("/alerts@ZeekrSentry_bot off"));
        assertEquals(TelegramCommand.AlertsState.REPORT, alerts("  /Alerts  "));
    }

    @Test
    public void anAlertsWordThatMeansNothingIsUnknown() {
        // 不猜：「maybe」不是开也不是关，猜一个方向比不回更糟
        // No guessing: "maybe" is neither on nor off, and guessing a direction is worse than silence
        notAlerts("alerts maybe");
        notAlerts("alerts off now");
        notAlerts("alerts on please");
        notAlerts("alerts 1");
    }

    @Test
    public void onlyAlertsCommandsCarryAnAlertsState() {
        assertEquals(null, TelegramCommand.parseText("pic").alerts);
        assertEquals(null, TelegramCommand.parseText("status").alerts);
        assertEquals(null, TelegramCommand.parseText("sens up").alerts);
        assertEquals(null, TelegramCommand.parseText("hello").alerts);
    }

    private static TelegramCommand.AlertsState alerts(String text) {
        TelegramCommand command = TelegramCommand.parseText(text);
        assertEquals("这句该是警报开关命令: " + text, TelegramCommand.Verb.ALERTS, command.verb);
        return command.alerts;
    }

    private static void notAlerts(String text) {
        assertEquals("不该认成警报开关命令: " + text,
                TelegramCommand.Verb.UNKNOWN, TelegramCommand.parseText(text).verb);
    }

    private static TelegramCommand.SensitivityAction sensitivity(String text) {
        TelegramCommand command = TelegramCommand.parseText(text);
        assertEquals("这句该是灵敏度命令: " + text, TelegramCommand.Verb.SENSITIVITY, command.verb);
        return command.sensitivity;
    }

    private static int level(String text) {
        TelegramCommand command = TelegramCommand.parseText(text);
        assertEquals("这句该是灵敏度命令: " + text, TelegramCommand.Verb.SENSITIVITY, command.verb);
        return command.sensitivityLevel;
    }

    private static void notASensitivity(String text) {
        assertEquals("不该认成灵敏度命令: " + text,
                TelegramCommand.Verb.UNKNOWN, TelegramCommand.parseText(text).verb);
    }

    // ------------------------------------------------------------------ 空调 / climate

    @Test
    public void climateReportsTurnsOnAndTurnsOff() {
        assertEquals(TelegramCommand.ClimateAction.REPORT, climate("ac").action);
        assertEquals("没说温度就是 0", 0f, climate("ac").targetC, 0f);
        assertEquals(TelegramCommand.ClimateAction.REPORT, climate("climate").action);
        assertEquals(TelegramCommand.ClimateAction.REPORT, climate("/aircon").action);
        assertEquals(TelegramCommand.ClimateAction.ON, climate("ac on").action);
        assertEquals("ac on 不带度数", 0f, climate("ac on").targetC, 0f);
        assertEquals(TelegramCommand.ClimateAction.OFF, climate("ac off").action);
    }

    @Test
    public void aClimateOnCanTakeATemperature() {
        assertEquals(23f, climate("ac on 23").targetC, 0f);
        assertEquals(TelegramCommand.ClimateAction.ON, climate("ac on 23").action);
        assertEquals(23f, climate("ac 23").targetC, 0f);
        assertEquals(TelegramCommand.ClimateAction.ON, climate("ac 23").action);
        assertEquals("下端", 10f, climate("ac 10").targetC, 0f);
        assertEquals("上端", 35f, climate("ac 35").targetC, 0f);
        assertEquals("上端带 on", 35f, climate("ac on 35").targetC, 0f);
        assertEquals("下端带 on", 10f, climate("climate on 10").targetC, 0f);
        assertEquals(22f, climate("aircon 22").targetC, 0f);
        assertEquals(20f, climate("climate 20").targetC, 0f);
    }

    /**
     * 半度收下来，而且原封不动：这台车的设定值停在 19.5，车主打的 19.5 不是「大概是整 20」。
     *
     * <p>四种写法都要：{@code ac 22.5}、{@code ac on 22.5}、{@code ac 22}、{@code ac 23}。</p>
     *
     * A half degree is accepted and kept exactly: this car holds 19.5, and 19.5 is not "about 20". All
     * four shapes work: {@code ac 22.5}, {@code ac on 22.5}, {@code ac 22} and {@code ac 23}.
     */
    @Test
    public void aClimateTargetMayBeAHalfDegree() {
        assertEquals(22.5f, climate("ac 22.5").targetC, 0f);
        assertEquals(TelegramCommand.ClimateAction.ON, climate("ac 22.5").action);
        assertEquals(22.5f, climate("ac on 22.5").targetC, 0f);
        assertEquals("上下两端的半度", 10.5f, climate("climate on 10.5").targetC, 0f);
        assertEquals(34.5f, climate("/AC@ZeekrSentry_bot 34.5").targetC, 0f);
        assertEquals("写 22.0 就是 22", 22f, climate("ac 22.0").targetC, 0f);
        assertEquals("大写、前后空白都一样", 19.5f, climate("  /Climate   ON   19.5  ").targetC, 0f);
    }

    /**
     * 半个半度、缺小数位、逗号小数点：一律听不懂，整句 {@link TelegramCommand.Verb#UNKNOWN}。
     *
     * <p>这里不四舍五入：车主写 22.25 时想的不是 22 也不是 22.5，猜一个就是替他改了温度。</p>
     *
     * A quarter degree, a missing decimal digit, a comma: all unintelligible, and the whole message is
     * UNKNOWN. Nothing is rounded — 22.25 meant neither 22 nor 22.5.
     */
    @Test
    public void aQuarterOrABrokenDecimalIsUnknown() {
        notAClimate("ac 22.25");
        notAClimate("ac on 22.25");
        notAClimate("ac 22.");
        notAClimate("ac 22,5");
        notAClimate("ac .5");
        notAClimate("ac 22.55");
        notAClimate("ac 22.2");
        notAClimate("ac 22.50");
        notAClimate("ac 9.5");
        notAClimate("ac 35.5");
        notAClimate("ac on 9.5");
        notAClimate("ac on 35.5");
        notAClimate("ac 22.5 extra");
    }

    @Test
    public void aSlashABotMentionAndCaseDoNotMatterForClimate() {
        assertEquals("群里会写成 /ac@BotName on",
                TelegramCommand.ClimateAction.ON, climate("/ac@ZeekrSentry_bot on").action);
        assertEquals(24f, climate("/ac@ZeekrSentry_bot 24").targetC, 0f);
        assertEquals(TelegramCommand.ClimateAction.REPORT, climate("  /AC  ").action);
        assertEquals(TelegramCommand.ClimateAction.OFF, climate("AirCon OFF").action);
        assertEquals(23f, climate("  /CLIMATE   ON   23  ").targetC, 0f);
        assertEquals(TelegramCommand.ClimateAction.REPORT, climate("ac").action);
        assertEquals("光写 ac 没有温度", 0f, climate("ac").targetC, 0f);
        assertEquals("ac on 不带度数", 0f, climate("ac on").targetC, 0f);
    }

    @Test
    public void aClimateWordOrNumberThatMeansNothingIsUnknown() {
        // 不猜：边界外的数不是端点，多一个词整句不认（半度那几条在 aQuarterOrABrokenDecimalIsUnknown）。
        // No guessing: an out-of-range number is not the end of it, and one extra word makes the whole
        // message unknown. The half-degree shapes have their own test.
        notAClimate("ac 9");
        notAClimate("ac 36");
        notAClimate("ac on 9");
        notAClimate("ac on 36");
        notAClimate("ac on 22 extra");
        notAClimate("ac maybe");
        notAClimate("ac 23 extra");
        notAClimate("ac off now");
        notAClimate("ac auto");
    }

    @Test
    public void aBareNumberIsNotAClimateCommand() {
        // 光一个 23 没有动词，什么也不是。
        // A bare 23 has no verb, so it means nothing.
        notAClimate("23");
        notAClimate("22.5");
    }

    @Test
    public void aClimateCommandPrintsItsActionAndTemperature() {
        assertEquals("CLIMATE:REPORT", TelegramCommand.parseText("ac").toString());
        assertEquals("CLIMATE:ON", TelegramCommand.parseText("ac on").toString());
        assertEquals("CLIMATE:ON@23", TelegramCommand.parseText("ac on 23").toString());
        assertEquals("CLIMATE:ON@23", TelegramCommand.parseText("ac 23").toString());
        assertEquals("CLIMATE:OFF", TelegramCommand.parseText("ac off").toString());
        assertEquals("CLIMATE:ON@10", TelegramCommand.parseText("ac 10").toString());
        assertEquals("CLIMATE:ON@35", TelegramCommand.parseText("ac on 35").toString());
        // 半度打在后面，整度不补 「.0」：日志和旧断言读起来一样。
        assertEquals("CLIMATE:ON@22.5", TelegramCommand.parseText("ac 22.5").toString());
        assertEquals("CLIMATE:ON@19.5", TelegramCommand.parseText("ac on 19.5").toString());
    }

    @Test
    public void onlyClimateCommandsCarryAClimate() {
        assertEquals(null, TelegramCommand.parseText("pic").climate);
        assertEquals(null, TelegramCommand.parseText("status").climate);
        assertEquals(null, TelegramCommand.parseText("sens up").climate);
        assertEquals(null, TelegramCommand.parseText("alerts on").climate);
        assertEquals(null, TelegramCommand.parseText("guard on").climate);
        assertEquals(null, TelegramCommand.parseText("hello").climate);
        assertEquals(null, TelegramCommand.parseText("ac maybe").climate);
        assertEquals(TelegramCommand.ANY_LANE, TelegramCommand.parseText("ac 23").lane);
        assertEquals(TelegramCommand.ANY_LANE, TelegramCommand.parseText("ac 22.5").lane);
    }

    private static TelegramCommand.Climate climate(String text) {
        TelegramCommand command = TelegramCommand.parseText(text);
        assertEquals("这句该是空调命令: " + text, TelegramCommand.Verb.CLIMATE, command.verb);
        return command.climate;
    }

    private static void notAClimate(String text) {
        TelegramCommand command = TelegramCommand.parseText(text);
        assertEquals("不该认成空调命令: " + text, TelegramCommand.Verb.UNKNOWN, command.verb);
        assertEquals("不是空调命令就不带 climate: " + text, null, command.climate);
    }
}

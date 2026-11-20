/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.policy;

/**
 * 车里热不热、冷不冷、有没有危险 —— 纯判断，不碰车、不碰网络，便于单测（{@code ClimatePolicyTest}）。
 *
 * <p>为什么不看一眼就发：车停在太阳下面，中午十一点到下午两点之间车厢能到五十度，
 * 但打开门散一下就好；真正要人回来处理的是「热了很久还是热」。所以热、冷两条都要
 * {@link #DEFAULT_HOLD_MS} 那么久的<b>新鲜</b>读数才算数，而安全那条一刻不等 ——
 * 车里可能坐着人，等待没有意义。</p>
 *
 * <p>为什么要有「新鲜」这件事：温度只在车机醒着的时候才报。中间睡过去一小时再醒过来，
 * 报的还是同一个数，那不代表它一小时都这么热 —— 可能刚变。所以两个非空读数之间隔了
 * 超过 {@link #DEFAULT_STALE_MS}，计时的起点就作废，重新数。反过来，
 * <b>读数一直在来、数值一直没变，不算 stale</b>：那是真的热了一小时。这两件事差得很远，
 * 混在一起的结果是要么永远不报，要么一次误报就让人把整个功能关掉。</p>
 *
 * <p>为什么发过一条还要留 {@code bandC} 的余量才重新武装：读数在阈值上下来回抖一格，
 * 没有余量就会一个小时发六条一模一样的「车里很热」。越过 {@code hotC - bandC} 才算真的降下来了。</p>
 *
 * <p>为什么空调开着、电量低的时候还压热/冷两条，却不压安全那条：空调开着说明车自己在管温度，
 * 再报一次没有信息量；电量见底时开空调本身就是车主的选择，不该被警报劝退。
 * 但「车里可能有人、五十度」不是偏好问题，那一条任何情况下都要发 —— 除了车主自己按了暂停。</p>
 *
 * <p>为什么安全那条有次数上限（{@link #DEFAULT_SAFETY_MAX_PER_PARK}）：一趟停车里最多三条，
 * 之后的每 {@link #DEFAULT_REPEAT_MS} 一条，够把人叫回来，不够把手机震到关机。
 * 安静时段（{@link QuietHours}）对这条<b>不生效</b>：半夜车里有人中暑，正是要吵醒人的时候。</p>
 *
 * <p><b>只锁方法，不锁字段之外的任何东西</b>：写的人只有一条线程（{@code SentryAlerts} 在主线程判断），
 * 加锁是便宜的保险，和 {@link AlertPolicy} 一样，不许有人从别的线程调进来。</p>
 *
 * Decides whether the cabin is too hot, too cold, or unsafe: pure logic, no car access, no network,
 * easy to unit test ({@code ClimatePolicyTest}).
 *
 * <p>Why not alert on a single reading: a car parked in the sun reaches fifty degrees between
 * eleven and two, and opening a door fixes it. What deserves a message is "still hot after a long
 * time". So hot and cold both need {@link #DEFAULT_HOLD_MS} of <b>fresh</b> samples, while the safety
 * rule waits for nothing -- somebody may be sitting in there, and waiting buys nothing.</p>
 *
 * <p>Why "fresh" needs its own rule: the temperature is only reported while the head unit is awake.
 * It sleeps for an hour, wakes up and reports the same number, and that does not mean the cabin was
 * hot for an hour -- it may have just got that way. So when two non-null samples are more than
 * {@link #DEFAULT_STALE_MS} apart, the hold start is thrown away and counting begins again. The other
 * way round, <b>samples that keep arriving with an unchanged value are not stale</b>: that really is
 * an hour of heat. The two cases are far apart, and confusing them gives either no alert ever or one
 * false alert that makes the owner turn the feature off.</p>
 *
 * <p>Why a sent alert needs {@code bandC} of travel before it re-arms: a reading that wobbles one step
 * around the threshold would otherwise send six identical "cabin is hot" messages in an hour. Only a
 * crossing past {@code hotC - bandC} counts as a real drop.</p>
 *
 * <p>Why HVAC on and a low battery suppress hot/cold but not safety: HVAC on means the car is already
 * managing the temperature, so a second message adds nothing; with the battery nearly flat, running
 * the air conditioning is the owner's call and an alert must not nag them out of it. But "somebody may
 * be inside at fifty degrees" is not a preference, and that one goes out in every case -- except when
 * the owner paused alerts.</p>
 *
 * <p>Why the safety rule is capped at {@link #DEFAULT_SAFETY_MAX_PER_PARK}: at most three per park
 * session, one per {@link #DEFAULT_REPEAT_MS} after the first, which is enough to call the owner back
 * and not enough to vibrate a phone flat. Quiet hours ({@link QuietHours}) <b>do not apply</b> to it:
 * three in the morning with a person overheating inside is exactly when a phone should ring.</p>
 *
 * <p><b>Only the methods are locked</b>: one thread writes these fields ({@code SentryAlerts} decides on
 * the main thread), the lock is cheap insurance, and like {@link AlertPolicy} nobody may call in from
 * another thread.</p>
 */
public final class ClimatePolicy {

    /**
     * 安全线（℃）：车厢到这个温度就不是「热」了，是不该待人。
     *
     * Safety line in Celsius: at this cabin temperature it is no longer "hot", it is unfit for people.
     */
    public static final float DEFAULT_SAFETY_C = 40f;

    /**
     * 有人时的安全线（℃）：低五度，因为车里坐着人时留给人的时间更少。
     *
     * Safety line with a seat occupied, Celsius: five degrees lower because a seated person has less
     * time to spare.
     */
    public static final float DEFAULT_SAFETY_OCCUPIED_C = 35f;

    /**
     * 热 / 冷要连续新鲜这么久才算数。
     *
     * How long the hot / cold reading must hold, in fresh samples, before it counts.
     */
    public static final long DEFAULT_HOLD_MS = 10 * 60_000L;

    /**
     * 安全那条重复的间隔。
     *
     * Repeat interval for the safety rule.
     */
    public static final long DEFAULT_REPEAT_MS = 15 * 60_000L;

    /**
     * 两个读数之间超过这么久就算不新鲜，计时作废。
     *
     * Two samples more than this far apart are stale, and the hold start is dropped.
     */
    public static final long DEFAULT_STALE_MS = 10 * 60_000L;

    /**
     * 回差（℃）：发过一条之后要降（升）过这么多才重新武装。
     *
     * Hysteresis band in Celsius: the reading must travel this far back before the rule re-arms.
     */
    public static final float DEFAULT_BAND_C = 3f;

    /**
     * 一趟停车里安全那条最多几条。
     *
     * Maximum number of safety alerts in one park session.
     */
    public static final int DEFAULT_SAFETY_MAX_PER_PARK = 3;

    /**
     * 判断结果。只有不是 {@link #NONE} 的才发。
     *
     * The decision. Only a kind other than {@link #NONE} sends.
     */
    public enum Kind {
        /** 什么都不发。 Nothing to send. */
        NONE,
        /** 车里太热。 The cabin is too hot. */
        HOT,
        /** 车里太冷。 The cabin is too cold. */
        COLD,
        /** 车里温度不适合待人。 The cabin is unfit for people. */
        SAFETY
    }

    /**
     * 一条判断：发什么，以及为什么（{@link #reason} 只进黑匣子，ASCII，短）。
     *
     * One decision: what to send, and why ({@link #reason} only goes into the black box, ASCII, short).
     */
    public static final class Verdict {

        /** 发什么。 What to send. */
        public final Kind kind;

        /**
         * 为什么，ASCII，短。给人看的是资源串，不是这个。
         *
         * Why, ASCII and short. The text a person reads is a string resource, not this.
         */
        public final String reason;

        private Verdict(Kind kind, String reason) {
            this.kind = kind;
            this.reason = reason;
        }
    }

    private final float hotC;
    private final float coldC;
    private final float safetyC;
    private final float safetyOccupiedC;
    private final float bandC;
    private final int socFloor;
    private final long holdMs;
    private final long repeatMs;
    private final long staleMs;
    private final int safetyMaxPerPark;

    /** 收到过非空读数没有；{@code nowMs} 可以是 0，所以不用 0 当哨兵。 */
    private boolean haveCabin;
    private long lastCabinMs;
    /** 热那次计时的起点；{@link #hotHolding} 为 false 时无意义。 */
    private boolean hotHolding;
    private long hotSinceMs;
    /** 冷那次计时的起点；{@link #coldHolding} 为 false 时无意义。 */
    private boolean coldHolding;
    private long coldSinceMs;
    /** 这条车这一趟已经发过热的那一条，等回差回来才重新武装。 */
    private boolean hotLatched;
    /** 同上，冷。 */
    private boolean coldLatched;
    /** 这一趟已经发过几条安全那条。 */
    private int safetySent;
    /** 上一条安全那条的时间；{@link #safetySent} 为 0 时无意义。 */
    private long safetyLastMs;

    /**
     * @param hotC             热线上限（℃）
     * @param coldC            冷线下限（℃）
     * @param safetyC          无人的安全线（℃）
     * @param safetyOccupiedC  有人的安全线（℃）
     * @param bandC            回差（℃）
     * @param socFloor         电量下限（%）
     * @param holdMs           热 / 冷要连续多久
     * @param repeatMs         安全那条的重复间隔
     * @param staleMs          读数多久不来算不新鲜
     * @param safetyMaxPerPark 一趟停车里安全那条最多几条
     *
     * @param hotC             hot threshold in Celsius
     * @param coldC            cold threshold in Celsius
     * @param safetyC          safety line with nobody seated, Celsius
     * @param safetyOccupiedC  safety line with a seat occupied, Celsius
     * @param bandC            hysteresis band in Celsius
     * @param socFloor         state-of-charge floor in percent
     * @param holdMs           how long hot / cold must hold
     * @param repeatMs         repeat interval for the safety rule
     * @param staleMs          how long without a reading counts as stale
     * @param safetyMaxPerPark most safety alerts in one park session
     */
    public ClimatePolicy(float hotC, float coldC, float safetyC, float safetyOccupiedC, float bandC,
                         int socFloor, long holdMs, long repeatMs, long staleMs, int safetyMaxPerPark) {
        this.hotC = hotC;
        this.coldC = coldC;
        this.safetyC = safetyC;
        this.safetyOccupiedC = safetyOccupiedC;
        this.bandC = bandC;
        this.socFloor = socFloor;
        this.holdMs = holdMs;
        this.repeatMs = repeatMs;
        this.staleMs = staleMs;
        this.safetyMaxPerPark = safetyMaxPerPark;
    }

    /**
     * 新的一趟停车：所有计时、回差、次数全部清掉。
     *
     * <p>不清的后果是一趟里发过的热警报会压住下一趟 —— 车主回家停一次、第二天早上一看还是
     * 「发过了」，那这条警报就永久废了。所以停车这一段结束（开走、或者哨兵关掉）就要调这里。</p>
     *
     * A new park session: every timer, latch and count is dropped.
     *
     * <p>Keeping them would let a hot alert from one session suppress the next one -- the owner parks
     * at home, and the following morning the rule still says "already sent", which retires the alert
     * for good. So call this when the parking episode ends (the car drives off, or Sentry goes off).</p>
     */
    public synchronized void reset() {
        haveCabin = false;
        lastCabinMs = 0;
        hotHolding = false;
        hotSinceMs = 0;
        coldHolding = false;
        coldSinceMs = 0;
        hotLatched = false;
        coldLatched = false;
        safetySent = 0;
        safetyLastMs = 0;
    }

    /**
     * 现在该发什么。
     *
     * <p>顺序是有理由的：车在开、车主暂停、没有读数 —— 这三件事让下面所有的规则都无从谈起，
     * 先出去，也不动任何计时。之后才看新鲜度（不给计时喂假的连续性），然后是安全那条
     * （它不受空调、电量压制），最后才是热 / 冷。</p>
     *
     * <p>{@code outsideC} 现在不参与判断：外面多少度只用来决定座椅要不要加热或通风
     * （{@link ClimateSeats}），而「车里热」本身不需要外面作参照。参数留着是为了让 T6 的
     * 调用点只有一处取数，不是为了以后偷偷加规则。</p>
     *
     * What to send now.
     *
     * <p>The order is deliberate: driving, an owner pause, and a missing reading each make every rule
     * below moot, so those leave first and touch no timer. Then freshness (no fake continuity is ever
     * fed into a hold), then the safety rule (HVAC and the battery do not suppress it), and hot / cold
     * last.</p>
     *
     * <p>{@code outsideC} takes no part in the decision today: the outside temperature only decides
     * whether a seat is heated or ventilated ({@link ClimateSeats}), and "the cabin is hot" needs no
     * outside reference. The parameter stays so that T6 has a single place to read telemetry from, not
     * so that a rule can be slipped in later.</p>
     *
     * @param cabinC        车内温度，可能没有
     * @param outsideC      车外温度，可能没有（本方法不用）
     * @param soc           电量百分比，可能没有
     * @param parked        停着没有
     * @param hvacOn        空调开着没有
     * @param seatOccupied  驾驶座有没有人
     * @param paused        车主暂停了警报没有
     * @param quietNow      在安静时段里没有
     * @param nowMs         {@code SystemClock.elapsedRealtime()}
     */
    public synchronized Verdict decide(Float cabinC, Float outsideC, Integer soc, boolean parked,
                                       boolean hvacOn, boolean seatOccupied, boolean paused,
                                       boolean quietNow, long nowMs) {
        if (!parked) {
            return none("not parked");
        }
        if (paused) {
            return none("paused");
        }
        if (cabinC == null) {
            return none("no reading");
        }
        float cabin = cabinC;
        noteSample(nowMs);

        if (safetyDue(cabin, seatOccupied, nowMs)) {
            safetySent++;
            safetyLastMs = nowMs;
            return seatOccupied && cabin < safetyC
                    ? new Verdict(Kind.SAFETY, "hot seat")
                    : new Verdict(Kind.SAFETY, "hot cabin");
        }

        // 空调在管温度、电量见底：热 / 冷都不发，但计时也从这里作废 ——
        // 空调一关就该重新数满 holdMs，不能拿「空调开着的时候也热」当理由立刻发一条。
        // HVAC is managing the temperature, or the battery is nearly flat: no hot / cold alert, and the
        // hold is dropped here too, so switching HVAC off means counting holdMs again instead of firing
        // at once on "it was hot while the air conditioning ran".
        if (hvacOn) {
            dropHold();
            return none("hvac on");
        }
        if (soc != null && soc < socFloor) {
            dropHold();
            return none("low battery");
        }

        if (cabin >= hotC) {
            coldHolding = false;
            if (hotLatched) {
                return none("hot latched");
            }
            if (!hotHolding) {
                hotHolding = true;
                hotSinceMs = nowMs;
            }
            if (nowMs - hotSinceMs < holdMs) {
                return none("hold");
            }
            hotHolding = false;
            hotLatched = true;
            return new Verdict(Kind.HOT, "cabin hot");
        }
        hotHolding = false;
        if (hotLatched && cabin <= hotC - bandC) {
            hotLatched = false;
        }

        if (cabin <= coldC) {
            if (coldLatched) {
                return none("cold latched");
            }
            if (!coldHolding) {
                coldHolding = true;
                coldSinceMs = nowMs;
            }
            if (nowMs - coldSinceMs < holdMs) {
                return none("hold");
            }
            coldHolding = false;
            coldLatched = true;
            return new Verdict(Kind.COLD, "cabin cold");
        }
        coldHolding = false;
        if (coldLatched && cabin >= coldC + bandC) {
            coldLatched = false;
        }
        return none("in band");
    }

    /**
     * 安全那条到点没有：条件成立、这一趟还没发满、而且离上一条够远。安静时段不看，暂停在
     * {@link #decide} 里已经挡掉了。
     *
     * Whether the safety rule is due: the condition holds, this park session has not hit the cap, and
     * the last one is far enough back. Quiet hours are not consulted; a pause was already refused
     * earlier in {@link #decide}.
     */
    private boolean safetyDue(float cabin, boolean seatOccupied, long nowMs) {
        boolean unsafe = cabin >= safetyC || (seatOccupied && cabin >= safetyOccupiedC);
        if (!unsafe || safetySent >= safetyMaxPerPark) {
            return false;
        }
        // 间隔从上一条算起，不是从「条件又成立」算起：清一下又回来不能把上限绕过去。
        // The interval counts from the last alert, not from when the condition came back, so a quick
        // clear-and-return cannot walk around the cap.
        return safetySent == 0 || nowMs - safetyLastMs >= repeatMs;
    }

    /**
     * 记一个新鲜读数：间隔超过 {@code staleMs} 就把计时起点作废。
     *
     * Note one fresh sample: a gap longer than {@code staleMs} throws the hold start away.
     */
    private void noteSample(long nowMs) {
        if (haveCabin && nowMs - lastCabinMs > staleMs) {
            dropHold();
        }
        haveCabin = true;
        lastCabinMs = nowMs;
    }

    private void dropHold() {
        hotHolding = false;
        coldHolding = false;
    }

    private static Verdict none(String reason) {
        return new Verdict(Kind.NONE, reason);
    }
}

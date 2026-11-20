/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm.climate;

/**
 * 十分钟后自己关空调的那一下：什么时候能写、什么时候再等一会儿、什么时候不写了要说话。
 *
 * <h3>为什么「读不到」不能当「不用关」</h3>
 *
 * <p>锁车十分钟之后车机早睡了：{@code Telemetry.latest()} 是 null、挡位是 null，这正是要自动关的
 * 那一刻。把「读不到」当成「车在走」就等于空调永远关不掉，而回执上已经写了它会关。</p>
 *
 * <p>所以：<b>读到 P 才写</b>（关空调是安全动作，但不确认停着就不写）；读不到、或者读到不是停着，
 * 每 {@link #RETRY_MS} 再问一次，问满 {@link #MAX_FACTOR} × minutes 还没见到「停着」就放弃，
 * 并且让车主知道没关成（聊天里一句话），不是悄悄算了。</p>
 *
 * <h3>Why "unreadable" is not "nothing to do"</h3>
 *
 * <p>Ten minutes after locking, the head unit is asleep: the snapshot is null and so is the gear. That is
 * exactly the moment the auto-off has to work. Treating "unreadable" as "the car is moving" would mean the
 * climate never switches off, while the receipt already promised a clock time.</p>
 *
 * <p>So: write only on a positive parked reading; otherwise ask again every {@link #RETRY_MS}, for up to
 * {@link #MAX_FACTOR} × the configured minutes, then give up <b>and say so</b> in the chat.</p>
 */
public final class ClimateOffGuard {

    /** 读不到状态时，隔多久再问一次。 */
    public static final long RETRY_MS = 30_000L;

    /**
     * 放弃的界线：配置的分钟数的这么多倍（10 分钟 → 30 分钟）。比回执上写的那一刻晚，
     * 但总比「读不到就永远不关」好。
     */
    public static final long MAX_FACTOR = 3;

    /** 这一次要做什么。 */
    public enum Step {
        /** 读到停着了：写。 */
        WRITE,
        /** 还读不到、或者读到的不是停着：等一会儿再问。 */
        RETRY,
        /** 等满了也没见到「停着」：不写了，回一句话。 */
        GIVE_UP
    }

    /** 现在几点（真的是 {@code SystemClock.elapsedRealtime}；测试换一个能拨的钟）。 */
    public interface Clock {
        long nowMs();
    }

    private ClimateOffGuard() {
    }

    /**
     * 这一次该怎么办。
     *
     * @param firstAtMs 第一次尝试的时刻（{@code elapsedRealtime}）
     * @param nowMs     现在
     * @param minutes   配置的「开几分钟自己关」
     * @param parked    读到「确实停着」了没有；读不到、读到不是停着都是 false
     */
    public static Step step(long firstAtMs, long nowMs, int minutes, boolean parked) {
        if (parked) {
            return Step.WRITE;
        }
        return nowMs - firstAtMs >= boundMs(minutes) ? Step.GIVE_UP : Step.RETRY;
    }

    /** 放弃的界线：3 × minutes；分钟数不合法（≤ 0）按一分钟算，不留一个「立刻放弃」的角落。 */
    public static long boundMs(int minutes) {
        long ms = Math.max(1, minutes) * 60_000L;
        return ms * MAX_FACTOR;
    }
}

/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.update;

import java.util.Locale;

/**
 * 「从 fork 接收哪一档」（设置里的 {@code update_channel}）。
 *
 * <h3>为什么另有一档</h3>
 *
 * <p>上游那一条路的「接收的版本」是所有人共用的一条规矩（正式版 / Beta / Alpha，存在
 * {@code update_include_beta}、{@code update_include_alpha}）。fork 这条路要能单独拨：等在
 * alpha 上试东西的人不想让上游那一边也跟着收 alpha，而只想跑正式版的人不该被 fork 的 beta 覆盖。
 * 所以 fork 这一档单独存（{@code update_channel}），只喂 fork 那一次检查
 * （{@code UpdateFlow}），上游那条路照旧读那两个开关。</p>
 *
 * <h3>自动这一档</h3>
 *
 * <p>「自动」= 跟着<b>装着的这一版</b>的后缀走：装着 beta 就收 beta，装着 alpha 就收 alpha，
 * 装着正式版就只收正式版。fork 现在发的是 {@code 4.18.0-fab-beta}，所以自动和以前的行为一样
 * （beta，不主动收 alpha）—— 这是默认值，谁都没选过的时候什么都不变。</p>
 */
public enum UpdateChannel {

    /** 跟着装着的这一版的后缀（默认）。 */
    AUTO("auto"),
    /** alpha、beta、正式版都收。 */
    ALPHA("alpha"),
    /** beta 和正式版（以前的行为）。 */
    BETA("beta"),
    /** 只收正式版。 */
    STABLE("stable");

    /** 存在设置里的那个词。改它等于让已经存着的选择失效，所以只加不改。 */
    public final String key;

    UpdateChannel(String key) {
        this.key = key;
    }

    /** 存着的那个词换成一档；不认得的一律回到 {@link #AUTO}（读不回来也不该把检查更新弄坏）。 */
    public static UpdateChannel fromKey(String key) {
        String wanted = key == null ? "" : key.trim().toLowerCase(Locale.US);
        for (UpdateChannel channel : values()) {
            if (channel.key.equals(wanted)) {
                return channel;
            }
        }
        return AUTO;
    }

    /** 这一次检查更新要的两个开关。 */
    public Gates gates(String installedVersion) {
        switch (effectively(installedVersion)) {
            case ALPHA:
                return new Gates(true, true);
            case BETA:
                return new Gates(true, false);
            case STABLE:
                return new Gates(false, false);
            case AUTO:
            default:
                // 走不到：effectively 已经把 AUTO 解开了
                return new Gates(true, false);
        }
    }

    /** {@link #AUTO} 换成「装着的这一版属于哪一档」。纯函数，测试直接调。 */
    public UpdateChannel effectively(String installedVersion) {
        if (this != AUTO) {
            return this;
        }
        if (VersionName.isRelease(installedVersion)) {
            return STABLE;
        }
        if (VersionName.isAlphaBetaOrRelease(installedVersion)
                && VersionName.channel(installedVersion).toLowerCase(Locale.US).startsWith("alpha")) {
            return ALPHA;
        }
        if (VersionName.isBetaOrRelease(installedVersion)) {
            // 认得出是 beta（也就是普通用户手里最常见的这一档）
            return BETA;
        }
        // 认不出来的版本号按今天的行为算（beta）：不是放宽 —— alpha 仍然要人自己选，
        // 而全部按正式版算的话，发出去的 beta 就再也收不到了
        return BETA;
    }

    /** 这一档放行的两种版本：{@code includeBeta} / {@code includeAlpha}（同 {@code GithubReleases.fetchLatest} 的两个开关）。 */
    public static final class Gates {
        public final boolean includeBeta;
        public final boolean includeAlpha;

        Gates(boolean includeBeta, boolean includeAlpha) {
            this.includeBeta = includeBeta;
            this.includeAlpha = includeAlpha;
        }
    }
}

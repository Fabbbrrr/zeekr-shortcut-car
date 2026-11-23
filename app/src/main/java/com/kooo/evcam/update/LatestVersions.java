/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.update;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.AppConfig;

/**
 * 「本机这一版、上游最新、fork 最新差多少」—— 只查版本号，不下载、不弹框。
 *
 * <h3>为什么要有它</h3>
 *
 * <p>「有没有更新」只有一个答案（{@link UpdateFlow}）。但一个从 fork 装包的人还多一个问题：
 * <b>上游走到哪儿了、fork 又走到哪儿了</b>。两个数字并排摆着，「落后多少」自己就看出来了，
 * 不用去 GitHub 上翻。所以这一页只显示数字，不显示「装不装」。</p>
 *
 * <h3>不挡界面</h3>
 *
 * <p>两个仓库各问一次，都在一个后台线程上（{@link #refresh}），回来的数字先存进
 * {@link AppConfig}，再由调用方排到界面上。断网、限流、仓库名写错，都只是「查不到」——
 * 界面上写一句，不弹对话框。存下来的数字是<b>上一次查到的</b>，所以离线时也看得见。</p>
 *
 * <h3>不会出网的场合</h3>
 *
 * <p>截图测试把 {@link #setNetworkAllowed} 关掉：那些测试要的是排布和文字，不是真的去问
 * GitHub，一个 10 秒的连接超时会把整套测试拖住。</p>
 */
public final class LatestVersions {

    private static final String TAG = "LatestVersions";

    /**
     * 这一次查到的两个数字：查不到的是空串（不是 null —— 界面上「空」就是「不知道」）。
     */
    public static final class Numbers {
        public final String upstream;
        public final String fork;

        Numbers(String upstream, String fork) {
            this.upstream = upstream == null ? "" : upstream;
            this.fork = fork == null ? "" : fork;
        }
    }

    /** 后台查完叫一声；<b>在后台线程上</b>，调用方自己排回界面。 */
    public interface Callback {
        void onNumbers(Numbers numbers);
    }

    /**
     * 进程里允不允许真的出网。截图测试关掉它 —— 测试要的是这一页长什么样，不是 GitHub 现在发到哪一版。
     *
     * <p>关掉时 {@link #refresh} 什么都不做，也不叫回调：界面就停在上一次存下来的数字上。</p>
     */
    private static volatile boolean networkAllowed = true;

    private LatestVersions() {
    }

    /** 只给测试用：关掉之后 {@link #refresh} 不再起线程、不出网。 */
    public static void setNetworkAllowed(boolean allowed) {
        networkAllowed = allowed;
    }

    public static boolean isNetworkAllowed() {
        return networkAllowed;
    }

    /** 后台查两个仓库，查完（成功与否）叫回调一次。调用方负责线程切换。 */
    public static void refresh(AppConfig config, Callback callback) {
        if (config == null) {
            return;
        }
        if (!networkAllowed) {
            // 不出网的场合（截图测试）：不起线程，也不拖延——就把存着的两个数字递回去，
            // 让调用方那一边的状态（「正在查询」）能回到正常的显示。
            // No network in tests: hand back what is cached so the caller is not left saying "checking".
            if (callback != null) {
                callback.onNumbers(new Numbers(
                        config.getCachedLatestUpstream(), config.getCachedLatestFork()));
            }
            return;
        }
        new Thread(() -> {
            Numbers numbers = fetch(config);
            if (callback != null) {
                callback.onNumbers(numbers);
            }
        }, "update-versions").start();
    }

    /** 同步查两个仓库；两个都问过了才写缓存（只把查到的那个写进去，查不到的留着上一次的）。 */
    static Numbers fetch(AppConfig config) {
        String upstream = newest(UpdateSource.upstream());
        String fork = newest(UpdateSource.fork(config.getUpdateForkRepo()));
        if (!upstream.isEmpty()) {
            config.setCachedLatestUpstream(upstream);
        }
        if (!fork.isEmpty()) {
            config.setCachedLatestFork(fork);
        }
        return new Numbers(upstream, fork);
    }

    /**
     * 那个仓库最新那一版的版本名；问不到（断网、限流、仓库名不对）就是空串。
     *
     * <p>不看通道：这一行要的是「对面走到第几版」，包括 alpha —— 挡住某几档是「推不推给这台车」
     * 的事（{@link UpdateChannel}），不是「那边有没有发」的事。</p>
     */
    private static String newest(UpdateSource.Target target) {
        try {
            GithubReleases.Release release = GithubReleases.fetchLatest(target, true, true, null);
            return release == null ? "" : release.tagName;
        } catch (Exception e) {
            // 查不到就是查不到：日志里留一行，界面上写「没能查到」，不弹框
            AppLog.w(TAG, "查不到最新版本：" + target, e);
            return "";
        }
    }
}

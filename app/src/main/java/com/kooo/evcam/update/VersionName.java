/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.update;

/**
 * 版本号比较。
 *
 * <h3>为什么不直接比字符串</h3>
 *
 * <p>本项目的 tag 长这样：{@code v0.25.2-alpha}，而应用里的 {@code versionName}
 * 是 {@code 0.25.2-alpha} —— 前缀差一个 v。而且字符串比大小会得出
 * {@code "0.9.0" > "0.25.0"}（因为 '9' > '2'），正好是「有更新却说没有」。</p>
 *
 * <p>所以按段拆开、按数字比。这部分完全不碰网络和 Android，可以单独测。</p>
 *
 * <h3>fork 标记（{@code -alerts}，以及以前的 {@code -fab}）</h3>
 *
 * <p>这个 fork 的版本号写成 {@code 4.19.0-alerts-beta}：数字段跟上游对齐，后缀里多一段
 * {@value #FORK_MARKER}。标记只做一件事 —— 说明「这个版本是这个 fork 发的」。
 * 检查更新据此分来源：开了「更新从自己的 fork 取」时只认带标记的版本，没开时只认不带标记的，
 * 所以上游的版本号哪怕比 fork 的编号大，也不会被当成一条要装上的更新。</p>
 *
 * <h3>为什么两个标记都认</h3>
 *
 * <p>标记从 {@code fab} 换成 {@value #FORK_MARKER} 不是改个名字那么简单：车机上装着的还是
 * {@code 4.18.0-fab-beta}，它<b>只认</b>旧标记发出去的 Release。新版本要是只写在 {@code -alerts}
 * 的名字下，那些车永远看不到它 —— 表现就是「一直没有更新」，而看上去什么都正常。
 * 所以：新名字用 {@value #FORK_MARKER}，旧名字（{@value #LEGACY_FORK_MARKER}）照旧认，
 * 一个名字里两段都带（{@code 4.19.0-fab-alerts-beta}）也认。等再也没有 4.18.0 在路上跑，
 * 旧标记才可以从 {@link #FORK_MARKERS} 里拿掉。</p>
 *
 * <p>标记本身不参与排序：{@code 4.19.0-alerts-beta} 和 {@code 4.19.0-beta} 比大小是相等的。
 * 排序只看数字段和通道（alpha / beta / 正式版）。来源由 {@link #isFork} 分开管，
 * 混在一个数字里分不清是谁发的。</p>
 */
public final class VersionName {

    /** 后缀里代表「这个 fork 发的版本」的那一段（2026-11-20 起；以前是 {@value #LEGACY_FORK_MARKER}）。 */
    public static final String FORK_MARKER = "alerts";

    /**
     * 老名字里的标记：4.18.0 及以前发出去的版本带的是它，而那些车只认它。
     *
     * <p>The marker every release up to 4.18.0 carried. Cars still running those builds accept
     * only this one, so dropping it would silently strand them: no release would ever match.</p>
     */
    public static final String LEGACY_FORK_MARKER = "fab";

    /** 全部认得的标记，当前的排在前面。判断「是不是这个 fork 发的」只看这里。 */
    public static final String[] FORK_MARKERS = {FORK_MARKER, LEGACY_FORK_MARKER};

    private VersionName() {
    }

    /**
     * 这个版本号是不是这个 fork 发的（后缀里有一段正好是某一个 {@link #FORK_MARKERS 标记}）。
     *
     * <p>只看整段：{@code 4.19.0-alerts-beta} 是，{@code 4.19.0-alert-beta} 不是；
     * 老名字 {@code 4.18.0-fab-beta} 也是，两段都带的 {@code 4.19.0-fab-alerts-beta} 还是。</p>
     */
    public static boolean isFork(String version) {
        for (String token : suffixTokens(version)) {
            if (isMarker(token)) {
                return true;
            }
        }
        return false;
    }

    /** 这一段是不是 fork 标记（当前那个或老名字那个）。 */
    public static boolean isMarker(String token) {
        for (String marker : FORK_MARKERS) {
            if (marker.equalsIgnoreCase(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 后缀里去掉 fork 标记之后剩下的那一段，也就是通道：{@code 4.19.0-alerts-beta} → {@code beta}，
     * {@code 4.13.0} → 空（正式版）。标记的位置不固定，{@code -alerts-beta} 和 {@code -beta-alerts} 一样，
     * 两段标记都带着时两段都去掉。
     */
    public static String channel(String version) {
        StringBuilder out = new StringBuilder();
        for (String token : suffixTokens(version)) {
            if (isMarker(token)) {
                continue;
            }
            if (out.length() > 0) {
                out.append('-');
            }
            out.append(token);
        }
        return out.toString();
    }

    /**
     * 这个版本能不能用在这次检查更新上：从 fork 取时只认带标记的，从上游取时只认不带标记的。
     *
     * <p>这一句是「打开「更新从自己的 fork 取」之后，上游的版本永远不会被当成更新」的落脚处：
     * 两边的版本永远进不了对方的列表，跟谁的编号大无关。</p>
     */
    public static boolean fitsSource(String version, boolean forkMarked) {
        return fitsSource(version, forkMarked, true);
    }

    /**
     * 同上，但可以<b>不要</b>标记（{@code requireMarker} = false）。
     *
     * <p>只有一种情况用得上：设置里把 fork 的仓库换成了别的（{@code update_fork_repo}）。
     * 别人的 fork 不会替我们带 {@code -alerts}，硬要标记等于把那个来源的版本全关掉。
     * 换了仓库之后仍然是「只认 fork 那一边」，上游那一边照样认不带标记的。</p>
     *
     * <p>The marker requirement is waived only for a fork repository other than the default one;
     * another fork will not carry our marker. The upstream branch still only accepts unmarked names.</p>
     */
    public static boolean fitsSource(String version, boolean forkMarked, boolean requireMarker) {
        if (!forkMarked) {
            // 上游那一边：带我们标记的版本不是它发的
            return !isFork(version);
        }
        return !requireMarker || isFork(version);
    }

    /**
     * 比较两个版本号。
     *
     * @return a 比 b 新返回正数，旧返回负数，一样返回 0
     */
    public static int compare(String a, String b) {
        String[] coreA = splitCore(a);
        String[] coreB = splitCore(b);

        int[] numsA = numbers(coreA[0]);
        int[] numsB = numbers(coreB[0]);
        int len = Math.max(numsA.length, numsB.length);
        for (int i = 0; i < len; i++) {
            // 位数不一样时按 0 补齐：0.25 和 0.25.0 是同一个版本
            int x = i < numsA.length ? numsA[i] : 0;
            int y = i < numsB.length ? numsB[i] : 0;
            if (x != y) {
                return x < y ? -1 : 1;
            }
        }

        // 数字段一样，再看预发布后缀。按 semver：正式版比同号的预发布版新。
        // 比的是通道，不是原始后缀：fork 标记不参与排序（见类注释）
        String channelA = channel(a);
        String channelB = channel(b);
        boolean preA = !channelA.isEmpty();
        boolean preB = !channelB.isEmpty();
        if (preA != preB) {
            return preA ? -1 : 1;
        }
        return channelA.compareTo(channelB);
    }

    /** 远端那个版本是不是比本机的新。拿不准（解析不出数字）时一律当作「不是」。 */
    public static boolean isNewer(String remote, String local) {
        if (remote == null || local == null) {
            return false;
        }
        if (numbers(splitCore(remote)[0]).length == 0
                || numbers(splitCore(local)[0]).length == 0) {
            // 认不出来的版本号不要冒充有更新 —— 误报会把用户带去装一个来路不明的包
            return false;
        }
        return compare(remote, local) > 0;
    }

    /**
     * 这个版本号属不属于「beta 或正式版」这一档。
     *
     * <p>检查更新默认推这两档：alpha 是开发过程中随手发的，数量多、稳定性没有保证，
     * 不问一声就拿它去覆盖一台正在用的车机不合适 —— 要 alpha 得在设置里打开「接收 Alpha 版」
     * （{@link #isAlphaBetaOrRelease}）。</p>
     *
     * <p>后缀既不是空也不是 beta 的一律不算 —— 包括 rc。真要发 rc，
     * 得在这里明确加上，而不是靠「看起来比 beta 新」这种默认。</p>
     */
    public static boolean isBetaOrRelease(String version) {
        if (numbers(splitCore(version)[0]).length == 0) {
            // 版本号都认不出来，谈不上属于哪一档
            return false;
        }
        String channel = channel(version);
        if (channel.isEmpty()) {
            return true;
        }
        return channel.toLowerCase(java.util.Locale.US).startsWith("beta");
    }

    /**
     * 这个版本号属不属于「alpha、beta 或正式版」：设置里打开了「接收 Alpha 版」（项目所有者 2026-10-23，默认关）
     * 时检查更新认这一档。后缀照样只认这三种，rc 之类不算。
     */
    public static boolean isAlphaBetaOrRelease(String version) {
        if (isBetaOrRelease(version)) {
            return true;
        }
        return numbers(splitCore(version)[0]).length > 0
                && channel(version).toLowerCase(java.util.Locale.US).startsWith("alpha");
    }

    /**
     * 是不是正式版：认得出版本号，而且没有预发布后缀。
     *
     * <p>设置里关掉「接收 Beta 版」之后，检查更新只认这一档。</p>
     */
    public static boolean isRelease(String version) {
        return numbers(splitCore(version)[0]).length > 0 && channel(version).isEmpty();
    }

    /**
     * 大版本：版本号的第一段（2.14.0-alpha → 2）；认不出来是 -1。
     * 导入设置只认同一个大版本导出的文件（settings.SettingsBackup，项目所有者 2026-10-19：大版本升级后配置可以失效，不做兼容）。
     */
    public static int major(String version) {
        int[] nums = numbers(splitCore(version)[0]);
        return nums.length == 0 ? -1 : nums[0];
    }

    /** 后缀按 '-' 拆开（{@code fab-beta.2} → {@code fab}、{@code beta.2}）；没有后缀时是空数组。 */
    private static String[] suffixTokens(String version) {
        String suffix = splitCore(version)[1];
        return suffix.isEmpty() ? new String[0] : suffix.split("-");
    }

    /** 拆成「数字部分」和「预发布后缀」两段，顺便吃掉前导的 v。 */
    private static String[] splitCore(String version) {
        String s = version == null ? "" : version.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        int plus = s.indexOf('+');           // 构建元数据，不参与比较
        if (plus >= 0) {
            s = s.substring(0, plus);
        }
        int dash = s.indexOf('-');
        if (dash < 0) {
            return new String[]{s, ""};
        }
        return new String[]{s.substring(0, dash), s.substring(dash + 1)};
    }

    private static int[] numbers(String core) {
        if (core.isEmpty()) {
            return new int[0];
        }
        String[] parts = core.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                return new int[0];   // 有一段不是数字，整个版本号就当作认不出来
            }
        }
        return out;
    }
}

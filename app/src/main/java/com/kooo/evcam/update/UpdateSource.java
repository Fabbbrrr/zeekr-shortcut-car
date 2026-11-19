/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.update;

import com.kooo.evcam.AppConfig;

/**
 * 更新从哪个仓库取 —— <b>只为测试自己的构建</b>，不进上游。
 *
 * <h3>为什么要有这个</h3>
 *
 * <p>改了代码要在车上验，而上游不会发我们的版本。如果只有一个来源，每改一次就得把这个 APK
 * 用 U 盘 / App Lab 手动装一遍。有了这一档，设置里拨一下就从自己的 fork 取：
 * 「检查更新」照旧，装的是自己发的 Release。</p>
 *
 * <h3>它有多独立</h3>
 *
 * <p>整件事就三个口子：这个文件、{@link GithubReleases} 里那两处（{@code useOwner} 的赋值和
 * {@code fetchLatest} 里的来源判断）、设置里那一行开关。</p>
 *
 * <h3>两个来源的版本怎么分得开</h3>
 *
 * <p>光比版本号分不开：上游已经走到 {@code 4.12}，fork 的编号还在 {@code 4.5}（fork 的编号是
 * 从它上一次同步的那一版往下接的）。上游一抬版，数字上就比 fork 大 —— 于是一台只认 fork 的车机
 * 会被告知「上游那个版本可以装」。所以 fork 发的版本在后缀里带一段 {@value VersionName#FORK_MARKER}：
 * 开了这个开关就只认带标记的版本，没开就只认不带标记的（{@link GithubReleases#fetchLatest}）。
 * 两边的编号怎样交叉都不影响谁被推。</p>
 *
 * <p>默认仍然是上游：这个开关藏在开发者选项里，而且默认关。</p>
 */
public final class UpdateSource {

    /** 上游仓库的所有者。 */
    public static final String UPSTREAM_OWNER = "dts88";

    /** 自己 fork 的所有者（测试版 Release 发在这里）。 */
    public static final String FORK_OWNER = "Fabbbrrr";

    private UpdateSource() {
    }

    /** 这一次检查更新该去问谁。 */
    public static String owner(AppConfig config) {
        return config != null && config.isUpdateFromFork() ? FORK_OWNER : UPSTREAM_OWNER;
    }

    /**
     * 问的这个仓库发的版本该不该带 fork 标记。
     *
     * <p>这是「只推自己那一档」的那一句：{@link GithubReleases} 拿它和
     * {@link VersionName#isFork} 对一下，两边的版本永远进不了对方的列表。</p>
     */
    public static boolean forkMarked(String owner) {
        return FORK_OWNER.equalsIgnoreCase(owner == null ? "" : owner.trim());
    }
}

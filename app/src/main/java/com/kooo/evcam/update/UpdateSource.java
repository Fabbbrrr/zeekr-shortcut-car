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
 * <p>整件事就三个口子：这个文件、{@link GithubReleases#useOwner} 那一行赋值、
 * 设置里那一行开关。发给上游之前把这一份删掉、那两处还原，剩下的代码一行都不用碰 ——
 * 停车警报本身不依赖它。</p>
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
}

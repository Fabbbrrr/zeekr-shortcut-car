/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.update;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 这几处「接线」没有别的东西能看出来，所以直接读源码钉住。
 *
 * <h3>钉的是哪两根线</h3>
 *
 * <ol>
 *   <li>{@code UpdateFlow} 用 {@link UpdateSource#target} 和 {@link UpdateSource#gates}：换了仓库/换了通道之后，
 *       「检查更新」必须真的按设置去问那个仓库、按那一档挑版本。要是它又回到写死的上游和那两个老开关，
 *       设置里拨的一切都只是摆设 —— 而界面上一点都看不出来（还是「检查更新」，还是没更新）。</li>
 *   <li>「更新与关于」那一页通过 {@link LatestVersions#refresh} 在后台查，<b>不自己叫</b>
 *       {@link GithubReleases#fetchLatest}：那会是一次在界面线程上的网络请求，网络卡住时整页卡死。</li>
 * </ol>
 *
 * <p>读的是源文件本身，不是某个常量：改的就是那两个文件。</p>
 */
public class UpdateWiringTest {

    @Test
    public void theCheckUsesTheConfiguredRepositoryAndChannel() throws IOException {
        String flow = source("app/src/main/java/com/kooo/evcam/update/UpdateFlow.java");
        assumeTrue("读不到 UpdateFlow.java，跳过", flow != null);
        assertTrue("UpdateFlow 要按设置挑仓库（UpdateSource.target）", flow.contains("UpdateSource.target("));
        assertTrue("UpdateFlow 要按 update_channel 挑档（UpdateSource.gates）", flow.contains("UpdateSource.gates("));
        assertTrue("问的时候要把那个仓库传下去（GithubReleases.fetchLatest(target, …)）",
                flow.contains("GithubReleases.fetchLatest(target,"));
        assertFalse("不能还留着写死的上游所有者", flow.contains("GithubReleases.useOwner("));
    }

    @Test
    public void theVersionNumbersAreFetchedInTheBackground() throws IOException {
        String settings = source(
                "app/src/main/java/com/kooo/evcam/settings/SettingsPreferenceFragment.java");
        assumeTrue("读不到 SettingsPreferenceFragment.java，跳过", settings != null);
        assertTrue("那一行要在后台查（LatestVersions.refresh）", settings.contains("LatestVersions.refresh("));
        assertFalse("页面自己不能直接叫 fetchLatest —— 那是在界面线程上出网",
                settings.contains("GithubReleases.fetchLatest("));
    }

    private static String source(String relative) throws IOException {
        File dir = new File(System.getProperty("user.dir", "."));
        for (int i = 0; i < 4 && dir != null; i++) {
            File file = new File(dir, relative);
            if (file.isFile()) {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
            dir = dir.getParentFile();
        }
        return null;
    }
}

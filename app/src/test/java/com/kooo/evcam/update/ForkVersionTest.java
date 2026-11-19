/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.update;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 这个 fork 出的包，版本号里一定带 {@code -fab} 标记。
 *
 * <h3>为什么钉在测试里</h3>
 *
 * <p>标记是「哪一边发的」唯一的凭据（{@link VersionName#isFork}）：检查更新靠它决定这个版本要不要
 * 推给一台只认 fork 的车机。发版时漏掉它，那个 Release 就永远进不了自己车机的更新列表 ——
 * 而且看上去什么都正常，只是「一直没有更新」。所以不是在注释里提醒，是让它编译出来就失败。</p>
 *
 * <p>读的是 {@code app/build.gradle.kts} 本身，不是某个赋值出来的常量：改的就是那个文件。</p>
 */
public class ForkVersionTest {

    private static final Pattern VERSION_NAME =
            Pattern.compile("(?m)^\\s*versionName\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern VERSION_CODE =
            Pattern.compile("(?m)^\\s*versionCode\\s*=\\s*(\\d+)");

    @Test
    public void theShippedVersionCarriesTheForkMarker() throws IOException {
        String gradle = buildGradle();
        String versionName = capture(VERSION_NAME, gradle);
        assertTrue("读不到 versionName，这个测试就没意义了", versionName != null);
        assertTrue("fork 的 versionName 必须带 -" + VersionName.FORK_MARKER + "（现在是 " + versionName + "），"
                + "否则打开「更新从自己的 fork 取」之后，这个 Release 不会被当成自己的版本",
                VersionName.isFork(versionName));
        assertTrue("带标记的版本号也得认得出通道（现在是 " + versionName + "）",
                VersionName.isAlphaBetaOrRelease(versionName));
        assertFalse("上游的版本号不能被当成 fork 发的（现在是 " + versionName + "）",
                VersionName.isFork(versionName.replace("-" + VersionName.FORK_MARKER, "")));
    }

    /**
     * versionCode 必须在上游那条线上继续往上走，不然装不上 —— 上游现在 371，fork 从 394 接着发。
     *
     * <p>这里只钉一个下限：要比 fork 上一次发版高（4.5.21 是 394），免得抬版本号时漏了它。</p>
     */
    @Test
    public void versionCodeKeepsClimbing() throws IOException {
        String code = capture(VERSION_CODE, buildGradle());
        assertTrue("读不到 versionCode", code != null);
        assertTrue("versionCode 现在是 " + code + "，至少要高过 4.5.21-beta 的 394",
                Integer.parseInt(code) > 394);
    }

    private static String capture(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String buildGradle() throws IOException {
        File module = findModuleRoot();
        assertTrue("找不到 app/build.gradle.kts", module != null);
        return new String(Files.readAllBytes(new File(module, "build.gradle.kts").toPath()),
                StandardCharsets.UTF_8);
    }

    /** 从工作目录往上找，直到看见 {@code src/main/res/xml/preferences.xml}。 */
    private static File findModuleRoot() {
        File dir = new File(System.getProperty("user.dir", "."));
        for (int i = 0; i < 4 && dir != null; i++) {
            if (new File(dir, "src/main/res/xml/preferences.xml").isFile()) {
                return dir;
            }
            File app = new File(dir, "app");
            if (new File(app, "src/main/res/xml/preferences.xml").isFile()) {
                return app;
            }
            dir = dir.getParentFile();
        }
        return null;
    }
}

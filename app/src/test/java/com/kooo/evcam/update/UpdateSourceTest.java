/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link UpdateSource} 的三条规矩，全在这里，不需要 Android、不需要网络：
 *
 * <ol>
 *   <li><b>仓库名</b>：{@code owner/repo} 或 {@code https://github.com/owner/repo} 认得出来并整理成一种写法；
 *       别的（http、别的站点、多余的一段路径、怪字符）一律不认 —— 存下一串拼不出 API 地址的字，
 *       结果就是「永远没有更新」；</li>
 *   <li><b>标记</b>：默认那个 fork 仓库要带 {@code -alerts}（老名字 {@code -fab} 也认）；
 *       换成别人的 fork 之后不要求了 —— 别人的 fork 不会替我们带标记；上游仍然只认不带标记的；</li>
 *   <li><b>通道</b>：从 fork 取时看 {@code update_channel}；从上游取时照旧看「接收的版本」两个开关。</li>
 * </ol>
 */
public class UpdateSourceTest {

    // ---------------------------------------------------------------- 仓库名

    @Test
    public void ownerSlashRepoIsTheCanonicalForm() {
        assertEquals("Fabbbrrr/zeekr-shortcut-car", UpdateSource.normaliseRepo("Fabbbrrr/zeekr-shortcut-car"));
        assertEquals(" 两边空白去掉 ", "Fabbbrrr/zeekr-shortcut-car",
                UpdateSource.normaliseRepo("  Fabbbrrr/zeekr-shortcut-car  "));
        assertEquals("末尾多余的斜杠去掉", "Fabbbrrr/zeekr-shortcut-car",
                UpdateSource.normaliseRepo("Fabbbrrr/zeekr-shortcut-car//"));
        assertEquals("换一个 owner 照收", "someone/zeekr-shortcut-car",
                UpdateSource.normaliseRepo("someone/zeekr-shortcut-car"));
    }

    @Test
    public void aFullGithubUrlIsTheSameThing() {
        assertEquals("Fabbbrrr/zeekr-shortcut-car",
                UpdateSource.normaliseRepo("https://github.com/Fabbbrrr/zeekr-shortcut-car"));
        assertEquals("带 www 也认", "Fabbbrrr/zeekr-shortcut-car",
                UpdateSource.normaliseRepo("https://www.github.com/Fabbbrrr/zeekr-shortcut-car"));
        assertEquals("大写的站点名也认", "Fabbbrrr/zeekr-shortcut-car",
                UpdateSource.normaliseRepo("https://GitHub.com/Fabbbrrr/zeekr-shortcut-car"));
        assertEquals(".git 去掉", "Fabbbrrr/zeekr-shortcut-car",
                UpdateSource.normaliseRepo("https://github.com/Fabbbrrr/zeekr-shortcut-car.git"));
        assertEquals("末尾斜杠去掉", "Fabbbrrr/zeekr-shortcut-car",
                UpdateSource.normaliseRepo("https://github.com/Fabbbrrr/zeekr-shortcut-car/"));
    }

    /**
     * 认不出来的一律返回 null：调用方看到 null 就不存（{@code AppConfig.setUpdateForkRepo}）。
     *
     * <p>这一步很重要 —— 存下来一个「差不多能看懂」的地址，检查更新每次都失败，
     * 而界面上只有一句「没能查到」，没人知道是仓库名写错了。</p>
     */
    @Test
    public void everythingElseIsRejectedRatherThanStored() {
        assertNull("空", UpdateSource.normaliseRepo(""));
        assertNull("只有空白", UpdateSource.normaliseRepo("   "));
        assertNull("null", UpdateSource.normaliseRepo(null));
        assertNull("只有 owner", UpdateSource.normaliseRepo("Fabbbrrr"));
        assertNull("http 不走", UpdateSource.normaliseRepo("http://github.com/Fabbbrrr/zeekr-shortcut-car"));
        assertNull("别的站点", UpdateSource.normaliseRepo("https://gitlab.com/Fabbbrrr/zeekr-shortcut-car"));
        assertNull("多余的路径段", UpdateSource.normaliseRepo("https://github.com/Fabbbrrr/zeekr-shortcut-car/tree/main"));
        assertNull("三段的 owner/repo", UpdateSource.normaliseRepo("Fabbbrrr/zeekr-shortcut-car/extra"));
        assertNull("前导斜杠", UpdateSource.normaliseRepo("/Fabbbrrr/zeekr-shortcut-car"));
        assertNull("空格", UpdateSource.normaliseRepo("Fabbbrrr /zeekr-shortcut-car"));
        assertNull("问号", UpdateSource.normaliseRepo("Fabbbrrr/zeekr-shortcut-car?x=1"));
        assertNull("冒号", UpdateSource.normaliseRepo("Fabbbrrr:zeekr-shortcut-car"));
        assertNull("只有名字", UpdateSource.normaliseRepo("zeekr-shortcut-car/"));
    }

    @Test
    public void theDefaultForkRepositoryIsWhatTheTaskSaysItIs() {
        assertEquals("Fabbbrrr/zeekr-shortcut-car", UpdateSource.DEFAULT_FORK_REPO);
        assertEquals("dts88", UpdateSource.UPSTREAM_OWNER);
    }

    // ---------------------------------------------------------------- 标记

    /** 默认那个 fork 仓库：要标记，老的新的都认；上游那一边带标记的不算。 */
    @Test
    public void theDefaultForkRepositoryStillRequiresAMarker() {
        UpdateSource.Target fork = UpdateSource.fork(UpdateSource.DEFAULT_FORK_REPO);
        assertEquals("Fabbbrrr", fork.owner);
        assertEquals("zeekr-shortcut-car", fork.repo);
        assertTrue("只认 fork 那一侧", fork.forkMarked);
        assertTrue("默认仓库要标记", fork.requireMarker);

        assertTrue(VersionName.fitsSource("4.19.0-alerts-beta", fork.forkMarked, fork.requireMarker));
        assertTrue(VersionName.fitsSource("4.18.0-fab-beta", fork.forkMarked, fork.requireMarker));
        assertFalse("上游的版本进不来",
                VersionName.fitsSource("4.12.1-alpha", fork.forkMarked, fork.requireMarker));

        UpdateSource.Target upstream = UpdateSource.upstream();
        assertFalse("上游不是 fork 那一侧", upstream.forkMarked);
        assertTrue(VersionName.fitsSource("4.12.1-alpha", upstream.forkMarked, upstream.requireMarker));
        assertFalse("带我们标记的进不了上游的列表",
                VersionName.fitsSource("4.19.0-alerts-beta", upstream.forkMarked, upstream.requireMarker));
    }

    /**
     * 换成别人的 fork：标记那一条作废（别人不会替我们带 {@code -alerts}），但仍然是「只认 fork 那一边」。
     *
     * <p>通道那一档和 sha256 校验不在这里测 —— 那两个根本没变，只换「问谁」。</p>
     */
    @Test
    public void aCustomForkRepositoryDoesNotAskForOurMarker() {
        UpdateSource.Target custom = UpdateSource.fork("someone/zeekr-shortcut-car");
        assertEquals("someone", custom.owner);
        assertTrue(custom.forkMarked);
        assertFalse("别人的 fork 不带我们的标记", custom.requireMarker);
        assertTrue("没有标记也算数", VersionName.fitsSource("4.20.0-beta",
                custom.forkMarked, custom.requireMarker));
        assertTrue("带了也认得", VersionName.fitsSource("4.20.0-alerts-beta",
                custom.forkMarked, custom.requireMarker));
    }

    /** 存着的仓库名坏了：回到默认那个，而不是带着一个拼不出地址的名字去问。 */
    @Test
    public void aBrokenRepositoryFallsBackToTheDefault() {
        UpdateSource.Target broken = UpdateSource.fork("not a repo at all");
        assertEquals(UpdateSource.DEFAULT_FORK_REPO, broken.fullName());
        assertTrue(broken.requireMarker);
        UpdateSource.Target empty = UpdateSource.fork("");
        assertEquals(UpdateSource.DEFAULT_FORK_REPO, empty.fullName());
    }

    // ---------------------------------------------------------------- 通道

    /**
     * 从 fork 取时读 {@code update_channel}：自动 = 跟着装着的那一版的后缀（今天发的是 beta，
     * 所以行为不变）；选了哪一档就是哪一档。上游那一条路照旧读那两个开关。
     */
    @Test
    public void theForkChannelAndTheUpstreamSwitchesAreSeparate() {
        assertEquals(UpdateChannel.AUTO, UpdateChannel.fromKey(null));
        assertEquals(UpdateChannel.AUTO, UpdateChannel.fromKey("nonsense"));
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromKey("stable"));
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromKey("STABLE"));
        assertEquals("stable", UpdateChannel.fromKey("STABLE").key);

        // 自动：装的是 beta → beta；装的是 alpha → alpha；装的是正式版 → 只收正式版
        assertEquals(UpdateChannel.BETA, UpdateChannel.AUTO.effectively("4.18.0-fab-beta"));
        assertEquals(UpdateChannel.ALPHA, UpdateChannel.AUTO.effectively("4.19.0-alerts-alpha"));
        assertEquals(UpdateChannel.STABLE, UpdateChannel.AUTO.effectively("4.19.0-alerts"));
        assertEquals("认不出来按今天的行为算", UpdateChannel.BETA, UpdateChannel.AUTO.effectively(""));

        assertTrue(UpdateChannel.AUTO.gates("4.18.0-fab-beta").includeBeta);
        assertFalse("自动不会主动把 alpha 装上", UpdateChannel.AUTO.gates("4.18.0-fab-beta").includeAlpha);
        assertTrue(UpdateChannel.ALPHA.gates("4.18.0-fab-beta").includeBeta);
        assertTrue(UpdateChannel.ALPHA.gates("4.18.0-fab-beta").includeAlpha);
        assertTrue(UpdateChannel.BETA.gates("4.19.0-alerts").includeBeta);
        assertFalse(UpdateChannel.BETA.gates("4.19.0-alerts").includeAlpha);
        assertFalse(UpdateChannel.STABLE.gates("4.18.0-fab-beta").includeBeta);
        assertFalse(UpdateChannel.STABLE.gates("4.18.0-fab-beta").includeAlpha);
    }
}

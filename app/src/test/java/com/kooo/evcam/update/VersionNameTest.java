/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link VersionName} 的单元测试。
 *
 * <p>这里最要紧的两条：{@code 0.9.0} 不能被判成比 {@code 0.25.0} 新
 * （按字符串比就会），以及认不出来的版本号不能冒充有更新。</p>
 */
public class VersionNameTest {

    @Test
    public void tagPrefixDoesNotCount() {
        assertTrue(VersionName.compare("v0.25.2-alpha", "0.25.2-alpha") == 0);
        assertTrue(VersionName.isNewer("v0.26.0-alpha", "0.25.2-alpha"));
    }

    /** 按字符串比会得出 0.9.0 更大 —— 那就是「有更新却说没有」。 */
    @Test
    public void twoDigitSegmentsBeatSingleDigitOnes() {
        assertTrue(VersionName.isNewer("0.25.0-alpha", "0.9.0-alpha"));
        assertFalse(VersionName.isNewer("0.9.0-alpha", "0.25.0-alpha"));
    }

    @Test
    public void sameVersionIsNotAnUpdate() {
        assertFalse(VersionName.isNewer("0.25.2-alpha", "0.25.2-alpha"));
        assertFalse(VersionName.isNewer("v0.25.2-alpha", "0.25.2-alpha"));
    }

    @Test
    public void patchAndMinorAndMajorAllCount() {
        assertTrue(VersionName.isNewer("0.25.3-alpha", "0.25.2-alpha"));
        assertTrue(VersionName.isNewer("0.26.0-alpha", "0.25.9-alpha"));
        assertTrue(VersionName.isNewer("1.0.0-alpha", "0.99.99-alpha"));
    }

    /** 段数不一样时按 0 补齐。 */
    @Test
    public void missingSegmentsCountAsZero() {
        assertTrue(VersionName.compare("0.25", "0.25.0") == 0);
        assertTrue(VersionName.isNewer("0.25.1", "0.25"));
    }

    /** 按 semver：同号的正式版比预发布版新。 */
    @Test
    public void releaseBeatsPrereleaseOfTheSameNumber() {
        assertTrue(VersionName.isNewer("0.25.2", "0.25.2-alpha"));
        assertFalse(VersionName.isNewer("0.25.2-alpha", "0.25.2"));
    }

    /**
     * 认不出来的版本号一律当作「没有更新」。
     *
     * <p>误报的代价是把人带去装一个来路不明的包，比漏报严重得多。</p>
     */
    @Test
    public void unparseableVersionsNeverLookLikeAnUpdate() {
        assertFalse(VersionName.isNewer("nightly", "0.25.2-alpha"));
        assertFalse(VersionName.isNewer("0.25.2-alpha", "nightly"));
        assertFalse(VersionName.isNewer(null, "0.25.2-alpha"));
        assertFalse(VersionName.isNewer("0.25.2-alpha", null));
        assertFalse(VersionName.isNewer("", "0.25.2-alpha"));
    }

    /** 检查更新只推 beta 与正式版。 */
    @Test
    public void onlyBetaAndReleaseCountAsUpdatable() {
        assertTrue(VersionName.isBetaOrRelease("v0.30.0-beta"));
        assertTrue(VersionName.isBetaOrRelease("0.30.0-beta.2"));
        assertTrue("正式版没有后缀", VersionName.isBetaOrRelease("1.0.0"));
        assertFalse("alpha 不推给在用的车机", VersionName.isBetaOrRelease("0.29.2-alpha"));
    }

    /**
     * rc 也不推。
     *
     * <p>这不是疏忽：真要发 rc，应当在代码里明确加上那一档，
     * 而不是靠「看起来比 beta 新」这种默认放行。</p>
     */
    @Test
    public void otherPrereleaseLabelsAreNotAssumedUpdatable() {
        assertFalse(VersionName.isBetaOrRelease("1.0.0-rc1"));
        assertFalse(VersionName.isBetaOrRelease("1.0.0-nightly"));
    }

    @Test
    public void unparseableVersionsBelongToNoChannel() {
        assertFalse(VersionName.isBetaOrRelease("nightly"));
        assertFalse(VersionName.isBetaOrRelease(""));
        assertFalse(VersionName.isBetaOrRelease(null));
    }

    /** 同号时 beta 比 alpha 新，正式版又比 beta 新。 */
    @Test
    public void channelOrderFollowsSemver() {
        assertTrue(VersionName.isNewer("0.30.0-beta", "0.30.0-alpha"));
        assertTrue(VersionName.isNewer("0.30.0", "0.30.0-beta"));
        assertTrue(VersionName.isNewer("0.30.0-beta", "0.29.2-alpha"));
    }

    @Test
    public void buildMetadataIsIgnored() {
        assertTrue(VersionName.compare("0.25.2+abc", "0.25.2") == 0);
    }

    /** 打开了「接收 Alpha 版」：alpha、beta、正式版都算，别的预发布后缀照样不算。 */
    @Test
    public void alphaCountsOnlyThroughItsOwnChannel() {
        assertTrue(VersionName.isAlphaBetaOrRelease("v2.21.0-alpha"));
        assertTrue(VersionName.isAlphaBetaOrRelease("2.11.7-beta"));
        assertTrue(VersionName.isAlphaBetaOrRelease("2.0.0"));
        assertFalse(VersionName.isAlphaBetaOrRelease("1.0.0-rc1"));
        assertFalse(VersionName.isAlphaBetaOrRelease("alpha"));
        assertFalse(VersionName.isAlphaBetaOrRelease(null));
        assertFalse("默认那一档照样不认 alpha", VersionName.isBetaOrRelease("2.21.0-alpha"));
    }

    /** 关了「接收 Beta 版」：只有正式版算，beta 和 alpha 都不算。 */
    @Test
    public void onlyReleasesCountWhenBetaIsOff() {
        assertTrue(VersionName.isRelease("1.0.0"));
        assertTrue(VersionName.isRelease("v0.60.1"));
        assertFalse(VersionName.isRelease("0.60.0-beta"));
        assertFalse(VersionName.isRelease("0.60.0-alpha"));
        assertFalse(VersionName.isRelease("nightly"));
        assertFalse(VersionName.isRelease(null));
    }

    /** 大版本是第一段：导入设置只认同一个大版本（SettingsBackup）。 */
    @Test
    public void majorIsTheFirstNumber() {
        assertTrue(VersionName.major("2.14.0-alpha") == 2);
        assertTrue(VersionName.major("v10.1") == 10);
        assertTrue(VersionName.major("3.0.0+abc") == 3);
        assertTrue(VersionName.major("nightly") == -1);
        assertTrue(VersionName.major("") == -1);
        assertTrue(VersionName.major(null) == -1);

        // fork 的版本号同样只看第一段：4.13.0-fab-beta 还是 4 时代，设置可以照旧导入
        assertTrue(VersionName.major("4.13.0-fab-beta") == 4);
    }

    // ---------------------------------------------------------------- fork 标记

    /**
     * 标记只看整段：{@code -fab} 是，{@code -fabulous} 不是。
     *
     * <p>这一句把「哪一边发的」从版本号里分出来，上游和 fork 的编号再怎样交叉都不会认错。</p>
     */
    @Test
    public void forkMarkerIsAWholeSuffixSegment() {
        assertTrue(VersionName.isFork("4.13.0-fab-beta"));
        assertTrue(VersionName.isFork("v4.13.0-fab-beta"));
        assertTrue(VersionName.isFork("4.13.0-fab"));
        assertTrue("标记写在后面也算", VersionName.isFork("4.13.0-beta-fab"));
        assertTrue(VersionName.isFork("4.13.0-FAB-beta"));
        assertFalse(VersionName.isFork("4.12.1-alpha"));
        assertFalse(VersionName.isFork("4.13.0"));
        assertFalse("不是一个词就不算", VersionName.isFork("4.13.0-fabulous-beta"));
        assertFalse(VersionName.isFork(null));
    }

    /**
     * 上游的版本号比 fork 的大时，两边仍然互不算更新。
     *
     * <p>这就是那个 bug：上游走到 4.12，fork 还在 4.5，光比数字会把上游的版本当成一条要装上的更新，
     * 哪怕开关指着自己的 fork。</p>
     */
    @Test
    public void aSourceOnlyAcceptsItsOwnVersions() {
        // 指着 fork：上游那一个再新也进不来
        assertFalse(VersionName.fitsSource("v4.12.1-alpha", true));
        assertFalse(VersionName.fitsSource("v4.4.2-beta", true));
        assertTrue(VersionName.fitsSource("v4.13.0-fab-beta", true));
        // 指着上游：fork 自己发的不进上游的列表
        assertTrue(VersionName.fitsSource("v4.12.1-alpha", false));
        assertFalse(VersionName.fitsSource("v4.13.0-fab-beta", false));
    }

    /** 标记不参与排序：同号的两边版本一样大。 */
    @Test
    public void forkMarkerDoesNotChangeTheOrder() {
        assertTrue(VersionName.compare("4.13.0-fab-beta", "4.13.0-beta") == 0);
        assertTrue(VersionName.compare("4.13.0-beta-fab", "4.13.0-fab-beta") == 0);
        assertTrue(VersionName.isNewer("4.13.1-fab-beta", "4.13.0-fab-beta"));
        assertTrue("正式版仍然比同号的 beta 新", VersionName.isNewer("4.13.0-fab", "4.13.0-fab-beta"));
    }

    /** fork 的 beta 版也归 beta 那一档：设置里「接收 beta 版」要的正是它们。 */
    @Test
    public void forkVersionsStillBelongToTheirChannel() {
        assertTrue(VersionName.isBetaOrRelease("4.13.0-fab-beta"));
        assertTrue(VersionName.isAlphaBetaOrRelease("4.13.0-fab-beta"));
        assertTrue(VersionName.isAlphaBetaOrRelease("4.13.0-fab-alpha"));
        assertFalse("关了 beta 就只有正式版", VersionName.isRelease("4.13.0-fab-beta"));
        assertTrue(VersionName.isRelease("4.13.0-fab"));
        assertFalse(VersionName.isBetaOrRelease("4.13.0-fab-rc1"));
    }

    // ---------------------------------------------------------------- 标记从 fab 换成 alerts（4.19）

    /**
     * 现在的标记是 {@code alerts}，老名字 {@code fab} 照旧认，两段都带着也认。
     *
     * <p>这一张表是那个「桥」的全部：新名字要认得，老名字不能丢（装着 4.18.0 的车只认旧标记，
     * 只认新标记的话它永远看不到新版本），两个都带的是发版时最稳的写法。</p>
     */
    @Test
    public void bothMarkersNameThisFork() {
        assertEquals("现在的标记", "alerts", VersionName.FORK_MARKER);
        assertEquals("老名字还认得", "fab", VersionName.LEGACY_FORK_MARKER);

        // 新名字
        assertTrue(VersionName.isFork("4.19.0-alerts-beta"));
        assertTrue(VersionName.fitsSource("4.19.0-alerts-beta", true));
        // 老名字：装着 4.18.0 的车只认它
        assertTrue(VersionName.isFork("4.18.0-fab-beta"));
        assertTrue(VersionName.fitsSource("4.18.0-fab-beta", true));
        // 两个都带
        assertTrue(VersionName.isFork("4.19.0-fab-alerts-beta"));
        assertTrue(VersionName.fitsSource("4.19.0-fab-alerts-beta", true));
        // 上游（dts88）发的：不是这个 fork 的
        assertFalse(VersionName.isFork("4.12.1-alpha"));
        assertFalse(VersionName.fitsSource("4.12.1-alpha", true));
        assertTrue(VersionName.fitsSource("4.12.1-alpha", false));

        // 两段标记都只是标记，不参与排序，也不算通道
        assertEquals("beta", VersionName.channel("4.19.0-alerts-beta"));
        assertEquals("beta", VersionName.channel("4.18.0-fab-beta"));
        assertEquals("beta", VersionName.channel("4.19.0-fab-alerts-beta"));
        assertTrue(VersionName.compare("4.19.0-fab-alerts-beta", "4.19.0-alerts-beta") == 0);
        assertTrue(VersionName.isBetaOrRelease("4.19.0-alerts-beta"));
        assertTrue(VersionName.isBetaOrRelease("4.19.0-fab-alerts-beta"));
        assertTrue("正式版仍旧没有后缀", VersionName.isRelease("4.19.0-alerts"));
        assertEquals(4, VersionName.major("4.19.0-alerts-beta"));

        // 差一个字母就不算：「alerts」是整个后缀段
        assertFalse(VersionName.isFork("4.19.0-alert-beta"));
        assertFalse(VersionName.isFork("4.19.0-alertsx-beta"));
        assertFalse(VersionName.isFork("4.19.0-fabulous-beta"));
    }

    /**
     * 换了仓库之后不要标记（{@code update_fork_repo} 不是默认那个）：别人的 fork 不会替我们带标记。
     *
     * <p>但「只认 fork 那一边」还在：上游那条路不能带我们的标记，反过来一样。</p>
     */
    @Test
    public void aCustomForkRepositoryWaivesOnlyTheMarker() {
        // 别人的 fork 发的是 4.20.0-beta（没有我们的标记）：换成那个仓库之后它也算数
        assertTrue(VersionName.fitsSource("4.20.0-beta", true, false));
        assertTrue(VersionName.fitsSource("4.20.0-alerts-beta", true, false));
        // 默认仓库仍然要标记
        assertFalse(VersionName.fitsSource("4.20.0-beta", true, true));
        // 上游那一边不受影响：三种写法都只有一个答案
        assertFalse(VersionName.fitsSource("4.20.0-alerts-beta", false, false));
        assertTrue(VersionName.fitsSource("4.20.0-beta", false, false));
    }
}

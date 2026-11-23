/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.update;

import com.kooo.evcam.AppConfig;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 更新从哪个仓库取，那个仓库的版本要不要带 fork 标记。
 *
 * <h3>为什么要有这一档</h3>
 *
 * <p>改了代码要在车上验，而上游不会发我们的版本。如果只有一个来源，每改一次就得把这个 APK
 * 用 U 盘 / App Lab 手动装一遍。有了这一档，设置里拨一下就从自己的 fork 取：
 * 「检查更新」照旧，装的是自己发的 Release。上到公开之后它也是「默认」：这个 fork 的包只该装
 * 这个 fork 发的东西（{@code AppConfig.isUpdateFromFork} 默认开）。</p>
 *
 * <h3>两个来源的版本怎么分得开</h3>
 *
 * <p>光比版本号分不开：上游已经走到 {@code 4.12}，fork 的编号还在 {@code 4.5}（fork 的编号是
 * 从它上一次同步的那一版往下接的）。上游一抬版，数字上就比 fork 大 —— 于是一台只认 fork 的车机
 * 会被告知「上游那个版本可以装」。所以 fork 发的版本在后缀里带一段标记（
 * {@link VersionName#FORK_MARKER}，老名字 {@link VersionName#LEGACY_FORK_MARKER} 也认）：
 * 开了这个开关就只认带标记的版本，没开就只认不带标记的（{@link GithubReleases#fetchLatest}）。
 * 两边的编号怎样交叉都不影响谁被推。</p>
 *
 * <h3>仓库可以换（{@code update_fork_repo}）</h3>
 *
 * <p>默认 {@value #DEFAULT_FORK_REPO}。换成了别人的 fork 之后，<b>标记那一条要求作废</b> ——
 * 别人的 fork 不会替我们带 {@code -alerts}，硬要标记等于把那个来源的版本全关掉（
 * {@link VersionName#fitsSource(String, boolean, boolean)}）。<b>通道那一档和 sha256 校验照旧</b>：
 * 换仓库只换「问谁」，不换「怎么信」。</p>
 */
public final class UpdateSource {

    /** 上游仓库的所有者。 */
    public static final String UPSTREAM_OWNER = "dts88";

    /** 上游的仓库名。 */
    public static final String REPO = "zeekr-shortcut-car";

    /** 这个 fork 的所有者（默认从这里取更新）。 */
    public static final String FORK_OWNER = "Fabbbrrr";

    /** 默认的 fork 仓库，写成 {@code owner/repo}。 */
    public static final String DEFAULT_FORK_REPO = FORK_OWNER + "/" + REPO;

    /** GitHub 的 owner / repo 组成：字母、数字、点、横线、下划线。 */
    private static final Pattern OWNER_OR_REPO = Pattern.compile("[A-Za-z0-9._-]+");

    /** {@code https://github.com/owner/repo}（后面允许再带路径，末尾允许 .git）。 */
    private static final Pattern GITHUB_URL = Pattern.compile(
            "^https://(?:www\\.)?github\\.com/([^/]+)/([^/]+?)(?:\\.git)?/*$", Pattern.CASE_INSENSITIVE);

    private UpdateSource() {
    }

    /**
     * 这一次检查更新的来源：问哪个仓库，以及那个仓库发的东西要不要带 fork 标记。
     *
     * <p>{@code forkMarked} = 「只认 fork 那一边的版本」；{@code requireMarker} = 「那个版本的名字里
     * 必须有我们的标记」。两者分开是因为换了仓库之后前者还成立、后者不成立了。</p>
     */
    public static final class Target {
        public final String owner;
        public final String repo;
        /** 只认 fork 那一侧的版本（上游的版本进不来）。 */
        public final boolean forkMarked;
        /** 版本名里必须带 {@link VersionName#FORK_MARKERS 我们的标记}。 */
        public final boolean requireMarker;

        Target(String owner, String repo, boolean forkMarked, boolean requireMarker) {
            this.owner = owner;
            this.repo = repo;
            this.forkMarked = forkMarked;
            this.requireMarker = requireMarker;
        }

        /** {@code owner/repo}，界面和日志用。 */
        public String fullName() {
            return owner + "/" + repo;
        }

        @Override
        public String toString() {
            return fullName() + (forkMarked ? (requireMarker ? " (fork)" : " (fork, no marker)") : " (upstream)");
        }
    }

    /** 上游那个仓库。上游发的不带我们的标记，带了就不是它发的。 */
    public static Target upstream() {
        return new Target(UPSTREAM_OWNER, REPO, false, true);
    }

    /** 设置里填的那个 fork 仓库；填坏了回到默认（不认得的一律不算数）。 */
    public static Target fork(String configuredRepo) {
        String normalised = normaliseRepo(configuredRepo);
        if (normalised == null) {
            normalised = DEFAULT_FORK_REPO;
        }
        int slash = normalised.indexOf('/');
        String owner = normalised.substring(0, slash);
        String repo = normalised.substring(slash + 1);
        // 默认仓库仍然要求标记（这是我们自己的版本号规矩）；换成别人的 fork 之后要求作废 ——
        // 别人的 fork 不会替我们带 -alerts，硬要标记就是把那个来源全关掉。通道和 sha256 校验照旧。
        // A custom fork repository waives the marker requirement only; the channel gate and the
        // sha256 digest check stay exactly as they are.
        boolean requireMarker = DEFAULT_FORK_REPO.equalsIgnoreCase(normalised);
        return new Target(owner, repo, true, requireMarker);
    }

    /** 这一次该问谁：开着「从 fork 取更新」就是 fork 那个仓库，否则上游。 */
    public static Target target(AppConfig config) {
        return config != null && config.isUpdateFromFork() ? fork(config.getUpdateForkRepo()) : upstream();
    }

    /**
     * 这次检查更新放行哪几档（{@code includeBeta} / {@code includeAlpha}）。
     *
     * <p>fork 那一条路读 {@code update_channel}（{@link UpdateChannel}）；上游那一条路照旧读
     * 「接收的版本」那两个开关 —— 这一档只为 fork 而加，不动上游的规矩。</p>
     */
    public static UpdateChannel.Gates gates(AppConfig config, String installedVersion) {
        if (config != null && config.isUpdateFromFork()) {
            return UpdateChannel.fromKey(config.getUpdateChannel()).gates(installedVersion);
        }
        boolean includeBeta = config != null && config.isUpdateBetaEnabled();
        // 「接收 Alpha 版」随「接收 Beta 版」：Beta 关着时它不算数
        return new UpdateChannel.Gates(includeBeta, includeBeta && config.isUpdateAlphaEnabled());
    }

    /**
     * 把设置里填的那一行整理成 {@code owner/repo}；认不出来返回 {@code null}（调用方不要存）。
     *
     * <ul>
     *   <li>认得：{@code owner/repo}、{@code https://github.com/owner/repo}（末尾的 .git 和多余的斜杠去掉）；</li>
     *   <li>不认得：别的站点、http、多余的一段路径、空串、怪字符。</li>
     * </ul>
     *
     * <p>返回 null 的约定是有意的：宁可把这一行退回默认仓库，也不要存下一串看着像地址、
     * 实际拼不出 API 地址的字 —— 那会变成「永远没有更新」。</p>
     */
    public static String normaliseRepo(String raw) {
        String text = raw == null ? "" : raw.trim();
        while (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.isEmpty()) {
            return null;
        }
        if (text.toLowerCase(Locale.US).startsWith("https://")) {
            Matcher matcher = GITHUB_URL.matcher(text);
            if (!matcher.matches()) {
                return null;
            }
            return ownerAndRepo(matcher.group(1), matcher.group(2));
        }
        if (text.contains("://")) {
            // http、git://、ssh 之类：只走 https，其余一律不认
            return null;
        }
        int slash = text.indexOf('/');
        if (slash <= 0 || slash != text.lastIndexOf('/') || slash == text.length() - 1) {
            return null;
        }
        String repo = text.substring(slash + 1);
        if (repo.toLowerCase(Locale.US).endsWith(".git")) {
            repo = repo.substring(0, repo.length() - 4);
        }
        return ownerAndRepo(text.substring(0, slash), repo);
    }

    private static String ownerAndRepo(String owner, String repo) {
        if (!OWNER_OR_REPO.matcher(owner).matches() || !OWNER_OR_REPO.matcher(repo).matches()) {
            return null;
        }
        if (repo.isEmpty() || "..".equals(owner) || "..".equals(repo)) {
            return null;
        }
        return owner + "/" + repo;
    }
}

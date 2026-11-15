/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.screens.ShotActivity;
import com.kooo.evcam.screens.Shots;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 点了有反应的地方，热区得够大（ui-spec §十三「状态能点」+ §三 的最小触摸目标 48dp）。
 *
 * <p>状态条上那两格（「这次按什么录」〔去 录制 → 录制画质〕、「还剩多少空间」〔去 存储 → 存储位置〕）
 * 是设置里唯一在布局里就写着 {@code selectableItemBackground} 又写着 {@code minHeight} 的地方 ——
 * 之前写的是 40dp，上下各差 4dp 按不到、高亮也填不满那一条。这里钉住：凡是自己写了 minHeight 的可点控件，
 * 那个数就得是 {@code status_bar_height}（48dp）。</p>
 *
 * <p>另一条：左栏的状态行要跟着实际状态变（ui-spec §十三），靠的是现成的回调，不是自己起轮询。
 * 这里照 {@code SettingsShotsTest} 的方式把设置打开，改一个被听的键，看不重建、不 resume 也能重写。</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class SettingsRowAlignmentTest {

    private static final Pattern TAG = Pattern.compile("<(?![?!/])[^>]*>");
    private static final Pattern DIMEN = Pattern.compile("<dimen name=\"([^\"]+)\">(\\d+)dp</dimen>");
    private static final Pattern MIN_HEIGHT = Pattern.compile("\\sandroid:minHeight=\"([^\"]*)\"");
    private static final String RIPPLE = "android:background=\"?attr/selectableItemBackground\"";

    @Test
    public void clickableRowsDeclareTheBarHeight() throws IOException {
        File res = resRoot();
        assumeTrue("定位不到资源目录，跳过", res != null);
        Map<String, Integer> dimens = dimens(new File(res, "values/dimens.xml"));
        Integer bar = dimens.get("status_bar_height");
        assumeTrue("dimens.xml 里没有 status_bar_height", bar != null);
        assertEquals("ui-spec §三：最小触摸目标是 48dp", 48, bar.intValue());

        List<String> offenders = new ArrayList<>();
        int checked = 0;
        for (File dir : layoutDirs(res)) {
            for (File file : listXml(dir)) {
                String xml = read(file.toPath()).replaceAll("(?s)<!--.*?-->", "");
                Matcher tags = TAG.matcher(xml);
                while (tags.find()) {
                    String tag = tags.group();
                    if (!tag.contains(RIPPLE)) {
                        continue;
                    }
                    Matcher minHeight = MIN_HEIGHT.matcher(tag);
                    if (!minHeight.find()) {
                        continue; // 没自己写 minHeight 的不在这一条管的事里（内容撑起来的行按内容算）
                    }
                    checked++;
                    Integer dp = resolve(minHeight.group(1), dimens);
                    if (dp == null || dp < bar) {
                        offenders.add(res.toPath().relativize(file.toPath()) + " minHeight=" + minHeight.group(1));
                    }
                }
            }
        }
        assertTrue("一条管着的都没扫到（布局改名了？）", checked > 0);
        assertTrue("这些可点的行报的 minHeight 小于 " + bar + "dp，按上去没反应: " + offenders, offenders.isEmpty());
    }

    /** 左栏的状态行跟着现成的回调变：改一个被听的键就重写，不用 resume、不用重建（ui-spec §十三）。 */
    @Test
    public void leftColumnRewritesWhenAWatchedKeyChanges() {
        RuntimeEnvironment.setQualifiers(Shots.DAY);
        ShotActivity activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(activity);
        SettingsShellFragment shell = new SettingsShellFragment();
        activity.getSupportFragmentManager().beginTransaction()
                .replace(activity.container, shell).commitNow();
        Shots.settle();
        SettingsHeadersFragment headers = (SettingsHeadersFragment)
                shell.getChildFragmentManager().findFragmentById(R.id.settings_headers);
        assertTrue("左栏没建起来", headers != null);

        AppConfig config = new AppConfig(RuntimeEnvironment.getApplication());
        boolean before = config.isScreenOffKeepRecording();
        int beforeCount = headers.refreshCount;
        config.setScreenOffKeepRecording(!before);
        Shots.settle();
        assertTrue("改了一个被听的键，左栏没跟着重写状态行", headers.refreshCount > beforeCount);

        // 注销这一对：视图拆掉之后再改，不该再收到
        int afterDestroy = headers.refreshCount;
        shell.getChildFragmentManager().beginTransaction().remove(headers).commitNow();
        Shots.settle();
        config.setScreenOffKeepRecording(before);
        Shots.settle();
        assertEquals("onDestroyView 没把监听器注销掉", afterDestroy, headers.refreshCount);
    }

    private static Integer resolve(String value, Map<String, Integer> dimens) {
        if (value.startsWith("@dimen/")) {
            return dimens.get(value.substring("@dimen/".length()));
        }
        return value.endsWith("dp") && value.length() > 2 ? Integer.parseInt(value.substring(0, value.length() - 2)) : null;
    }

    private static Map<String, Integer> dimens(File file) throws IOException {
        Map<String, Integer> out = new LinkedHashMap<>();
        Matcher m = DIMEN.matcher(read(file.toPath()));
        while (m.find()) {
            out.put(m.group(1), Integer.parseInt(m.group(2)));
        }
        return out;
    }

    private static List<File> layoutDirs(File res) {
        List<File> out = new ArrayList<>();
        File[] children = res.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory() && child.getName().startsWith("layout")) {
                    out.add(child);
                }
            }
        }
        return out;
    }

    private static List<File> listXml(File dir) {
        List<File> out = new ArrayList<>();
        File[] children = dir.listFiles((d, name) -> name.endsWith(".xml"));
        if (children != null) {
            java.util.Collections.addAll(out, children);
        }
        return out;
    }

    private static String read(java.nio.file.Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** {@code app/src/main/res}，从模块目录或仓库根目录跑都能找到；找不到返回 null。 */
    private static File resRoot() {
        for (String candidate : new String[] {".", "app"}) {
            File res = new File(candidate, "src/main/res");
            if (new File(res, "values/dimens.xml").isFile()) {
                return res;
            }
        }
        return null;
    }
}

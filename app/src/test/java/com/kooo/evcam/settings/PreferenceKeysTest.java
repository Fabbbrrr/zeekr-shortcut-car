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

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 设置界面里用到的 key，XML 里必须真有。
 *
 * <h3>为什么要有这么一条测试</h3>
 *
 * <p>{@code findPreference} 找不到 key 时返回 null，<b>不报错</b>。
 * 于是「查一下、null 就整个 return」这种写法，在 key 被改名之后会安静地
 * 让一整块界面失去响应 —— 点了没反应，日志里也没有任何线索。</p>
 *
 * <p>这件事已经发生过：0.21.0 把设置分区改成嵌套 {@code PreferenceScreen} 之后，
 * 开发者选项那一块还在找旧的 {@code cat_developer}，四个入口一个都没接上，
 * 一直到有人点了才发现。</p>
 *
 * <p>这条测试直接读源文件比对，不需要跑 Android。</p>
 */
public class PreferenceKeysTest {

    private static final Pattern XML_KEY = Pattern.compile("android:key=\"([^\"]+)\"");

    /** 代码里按 key 去拿一个 Preference 的几种写法。 */
    private static final Pattern CODE_KEY = Pattern.compile(
            "(?:findPreference|onClick|bindSwitch|bindEnum|bindSlider|bindOverlaySwitch"
                    + "|bindGigabyteLimit|bindSegmented|bindAlertsClimateList|show)\\(\\s*\"([^\"]+)\"");

    /**
     * {@code CLIMATE_ROWS} 那个数组里的每一行：它是循环着 show 的，字面量只在数组声明里出现，
     * 所以{@link #CODE_KEY}看不到它。车厢温度的行名改了一处、跟 XML 对不上，也要在这里拦下来。
     */
    private static final Pattern CLIMATE_ROWS_ARRAY = Pattern.compile(
            "CLIMATE_ROWS\\s*=\\s*\\{([^}]*)\\}");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

    @Test
    public void everyKeyUsedInCodeExistsInTheXml() throws IOException {
        File moduleRoot = findModuleRoot();
        // 找不到源码就跳过（换了工作目录的场景），不要报一个假的失败
        assumeTrue("定位不到模块根目录，跳过", moduleRoot != null);

        Set<String> declared = extract(
                new File(moduleRoot, "src/main/res/xml/preferences.xml"), XML_KEY);
        Set<String> used = new LinkedHashSet<>();
        used.addAll(extract(new File(moduleRoot,
                "src/main/java/com/kooo/evcam/settings/SettingsPreferenceFragment.java"), CODE_KEY));
        used.addAll(extractClimateRows(new File(moduleRoot,
                "src/main/java/com/kooo/evcam/settings/SettingsPreferenceFragment.java")));
        used.addAll(extract(new File(moduleRoot,
                "src/main/java/com/kooo/evcam/settings/SettingsHeadersFragment.java"), CODE_KEY));

        assumeTrue("源文件读不到，跳过", !declared.isEmpty() && !used.isEmpty());
        // 车厢温度那几行确实收到了：不然这条测试看起来绿，其实一个字面量都没看
        assertTrue("CLIMATE_ROWS 没取到：" + used,
                used.contains("pref_alerts_climate_hot"));

        Set<String> missing = new TreeSet<>(used);
        missing.removeAll(declared);
        assertTrue("这些 key 在 preferences.xml 里不存在，对应的界面会点了没反应: " + missing,
                missing.isEmpty());
    }

    private static Set<String> extract(File file, Pattern pattern) throws IOException {
        Set<String> found = new LinkedHashSet<>();
        if (!file.isFile()) {
            return found;
        }
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    /** {@code CLIMATE_ROWS} 数组里每一个字符串。 */
    private static Set<String> extractClimateRows(File file) throws IOException {
        Set<String> found = new LinkedHashSet<>();
        if (!file.isFile()) {
            return found;
        }
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Matcher array = CLIMATE_ROWS_ARRAY.matcher(text);
        while (array.find()) {
            Matcher quoted = QUOTED.matcher(array.group(1));
            while (quoted.find()) {
                found.add(quoted.group(1));
            }
        }
        return found;
    }

    /**
     * 停车警报下面那三页：声明成 {@code CHILD_*} 常量、当 {@code withRoot} 的 rootKey 用。
     * 这一类 key 不是行，所以 {@link #CODE_KEY} 看不到它们 —— 它只认 {@code findPreference("…")} 这种写法。
     * The three child pages' keys: declared as CHILD_* and used as a rootKey, which CODE_KEY cannot see.
     */
    private static final Pattern CHILD_ROOT_DECL = Pattern.compile(
            "private static final String (CHILD_[A-Z_]+) = \"([^\"]+)\"");
    private static final Pattern CHILD_ROOT_OPENED = Pattern.compile(
            "openAlertsChild\\((CHILD_[A-Z_]+),");

    /**
     * 停车警报：总开关那一页的三行入口，和它们各自打开的那一页。
     *
     * <p>三行入口是普通 {@code <Preference>}，找不到 key 时同样只是安静地不响应；下层页的 rootKey 写错时
     * {@code setPreferencesFromResource} 会直接抛异常（找不到那一棵子树）。两边都要拦下来。</p>
     *
     * The three entry rows and the pages they open: a wrong key on either side is a tap that does nothing.
     */
    @Test
    public void everyAlertsChildPageKeyExistsAndIsOpened() throws IOException {
        File moduleRoot = findModuleRoot();
        assumeTrue("定位不到模块根目录，跳过", moduleRoot != null);
        File fragment = new File(moduleRoot,
                "src/main/java/com/kooo/evcam/settings/SettingsPreferenceFragment.java");
        assumeTrue("读不到片段源码，跳过", fragment.isFile());
        String source = new String(Files.readAllBytes(fragment.toPath()), StandardCharsets.UTF_8);
        Set<String> declared = extract(
                new File(moduleRoot, "src/main/res/xml/preferences.xml"), XML_KEY);

        // 三页：常量写了一份真 key，XML 里真有，而且有一行会打开它
        Map<String, String> children = new LinkedHashMap<>();
        Matcher decl = CHILD_ROOT_DECL.matcher(source);
        while (decl.find()) {
            children.put(decl.group(1), decl.group(2));
        }
        assertEquals("停车警报下面该有三页：" + children, 3, children.size());
        Set<String> opened = new LinkedHashSet<>();
        Matcher open = CHILD_ROOT_OPENED.matcher(source);
        while (open.find()) {
            opened.add(open.group(1));
        }
        for (Map.Entry<String, String> child : children.entrySet()) {
            assertTrue(child.getValue() + " 在 preferences.xml 里不存在",
                    declared.contains(child.getValue()));
            assertTrue("没有哪一行会打开 " + child.getKey(), opened.contains(child.getKey()));
        }
        assertTrue("车厢温度那一页的 rootKey 叫 " + children.values(),
                children.containsValue("screen_alerts_climate"));

        // 三行入口：XML 里有 key，代码里点了要接上（onClick 那一行）
        for (String row : new String[] {"pref_alerts_notifications", "pref_alerts_sentry",
                "pref_alerts_climate_page"}) {
            assertTrue(row + " 在 preferences.xml 里不存在", declared.contains(row));
            assertTrue(row + " 没有接线（点了没反应）", source.contains("onClick(\"" + row + "\""));
        }

        // 下层页靠一个可选的参数认自己读哪一棵子树；父页那一条路（ARG_SECTION）不变
        assertTrue("下一层该用 ARG_ROOT 当 rootKey", source.contains("getString(ARG_ROOT)"));
        assertTrue("父页那条路还在", source.contains("getString(ARG_SECTION)"));
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

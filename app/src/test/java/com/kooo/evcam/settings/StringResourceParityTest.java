/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 每一份翻译的 strings.xml 都要和中文那份对得上。
 *
 * <h3>为什么值得测</h3>
 *
 * <p>少一条翻译，界面上就会在一片外文里冒出一句中文 —— 不报错，只是难看，
 * 而且往往要等到有人截图才被发现。语言是一份一份加的，所以这里按目录列表遍历：
 * 新增一种语言时只改这一处。</p>
 *
 * <p>更严重的是<b>占位符对不上</b>：中文写 {@code %1$s} 而英文漏了，
 * {@code getString(id, arg)} 在运行期直接抛异常，界面当场崩。
 * 这类错误编译期完全看不出来。</p>
 *
 * <h3>只做中英两份的文字</h3>
 *
 * <p>关于页这类长文字只做中文和英文：中文在 {@code values-zh/<文件>}，英文在 {@code values/<文件>}（默认，
 * 其他所有语言都落到它），文件名列在 {@link #ZH_EN_ONLY}。这两份要条目一一对得上、占位符一致；
 * 这些条目不能再出现在任何一份 strings.xml 里，否则谁覆盖谁就说不清了。</p>
 */
public class StringResourceParityTest {

    private static final Pattern STRING = Pattern.compile(
            "<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>", Pattern.DOTALL);
    private static final Pattern PLACEHOLDER = Pattern.compile("%\\d\\$[sd]");
    private static final Pattern PLURALS = Pattern.compile(
            "<plurals\\s+name=\"([^\"]+)\"[^>]*>(.*?)</plurals>", Pattern.DOTALL);
    private static final Pattern ITEM = Pattern.compile(
            "<item\\s+quantity=\"([a-z]+)\"\\s*>(.*?)</item>", Pattern.DOTALL);
    /** 复数条目里也有不带序号的 {@code %d}（「已锁定 %d 个文件」），一起算。 */
    private static final Pattern PLURAL_PLACEHOLDER = Pattern.compile("%(\\d\\$)?[sd]");

    /** 翻译目录。新增一种语言时，这里和 xml/locales_config.xml 一起改。 */
    private static final String[] TRANSLATIONS = {"values-en", "values-ms", "values-ar"};

    /**
     * 复数要写全的那几种说法（Android 照 CLDR 的复数规则挑）。阿拉伯语六种都有：0、1、2、3–10、11–99、其余；
     * 少写一种，那个数量就落到 other 上，读起来是错的语法。没列的语言只要求有 other。
     */
    private static final Map<String, String[]> QUANTITIES = new LinkedHashMap<>();

    static {
        QUANTITIES.put("values-ar", new String[]{"zero", "one", "two", "few", "many", "other"});
    }

    /** 只有中文（values-zh）和英文（values，默认）两份的文件。 */
    private static final String[] ZH_EN_ONLY = {"strings_about.xml"};

    /**
     * 还没有阿拉伯文的那一批：停车警报整个功能（fork 加的），以及它带进来的设置行。
     *
     * <p>为什么可以空着：这一批是 fork 自己的功能，上游加阿拉伯文的时候它还不存在。空着<b>不是忘
     * 了</b>——写在这里，下面用确切的条数钉着，要补阿语（或再加一条 alert 字符串）都会在这里失败，
     * 逼人说一声。阿语界面下这几行照安卓的规矩落到默认资源，也就是中文，而不是英文；
     * 这个缺陷记在 CHANGELOG 里。</p>
     */
    private static final String[] PENDING_ARABIC = {
            "alert_", "alerts_", "set_alerts_", "set_dev_alerts_", "set_section_alerts",
            "set_group_alerts_", "opt_alerts_", "cat_alerts_", "pref_alerts_", "header_alerts_",
            "action_alert", "dev_update_fork_",
            "ntfy_", "set_ntfy_", "telegram_", "set_telegram_",
    };

    /**
     * 待翻译的确切条数（实测于 2026-11-19，把上游 4.12.1 合进来时）。
     *
     * <p>数字本身就是这条测试的一部分：条目被翻译掉、或者又加了一行还没翻的停车警报文案，
     * 都要回到这里改一下 —— 少一条就是好消息，多一条得知道多的是什么。</p>
     */
    /**
     * 还没翻成阿语的条数。319 是 4.13 的数字；车厢温度那一条加了 65 条
     * （alert_climate_* / alert_event_climate_* / alert_headline_climate_* / set_alerts_climate_* /
     * opt_alerts_*），阿语照 4.13 的已知缺口留到单独一轮，所以这里跟着加，不是放宽。
     *
     * <p>4.6 的分页：停车警报拆成三页（通知 / 哨兵 / 车厢温度），加了三行入口、三个页名和几条行尾的短语
     * （{@code set_alerts_row_*} / {@code set_alerts_page_*} / {@code set_alerts_hub_*}，共 17 条），
     * 删掉了原来的分类选择器 5 条（{@code set_alerts_family_*} / {@code opt_alerts_family_*}）；
     * 384 + 17 − 5 − 1 = 395（那 1 条是本次之前从中文里删掉、一直没算进来的 {@code alert_climate_no_range}）。
     * 每一条都是停车警报的文案，阿语照旧留到单独一轮。</p>
     *
     * How many strings still have no Arabic. 319 was 4.13; the cabin-temperature feature added 65,
     * and Arabic for the Alerts screens is the documented separate pass, so the number follows
     * deliberately instead of the check being weakened. 4.6 split the alerts page into three: 17 new
     * rows/titles/summary phrases, 5 family-picker strings gone, and one string that had already been
     * deleted from Chinese without the number following it: 384 + 17 − 5 − 1 = 395.
     *
     * <p>自动关那一轮又加了两条：{@code alert_climate_stale_button}、{@code alert_climate_off_unconfirmed}（都是停车警报的文案），
     * 395 + 2 = 397。阿语照旧留到单独一轮。</p>
     *
     * <p>复审第二轮又加了两条、删了一条：{@code alert_climate_stale_off_button}（过期的「关」那颗回的话）、
     * {@code set_alerts_hub_ack_missing}（话题填了、免费方案的提醒没接受），删的是 {@code set_group_alerts_climate}
     * （车厢温度那一页的页名已经说了，组名重复，连组一起拿掉了），397 + 2 − 1 = 398。</p>
     *
     * <p>单位那一轮删了一条：{@code alert_climate_wrong_unit}（「车报的是华氏度」）—— 单位改成按设定值判，
     * 这句话不会再有，{@code Outcome.WRONG_UNIT} 也一起没了。398 − 1 = 397。</p>
     */
    private static final int PENDING_ARABIC_COUNT = 397;

    /**
     * 温度那一批占位符必须收「已经写好的数字」（{@code %1$s}），不能是 {@code %d}。
     *
     * <p>目标温度现在是半度：{@code %d} 收下 22.5 要么报异常，要么被截成 22 —— 两条都不行。
     * 回执和 {@code ac} 报告也一样：它们拿的是 {@code String.format(Locale.US, "%.1f", …)} 的结果。
     * 所以这里钉三件事：占位符是 {@code %1$s}、22.5 真的能渲染成「22.5 °C」、而且不是「22.50」。</p>
     *
     * The temperature strings take an already-written number ({@code %1$s}), never {@code %d}: the target
     * is a half degree now, and {@code %d} on 22.5 either throws or truncates to 22. The receipt and the
     * {@code ac} report are in the same batch, and both are handed {@code String.format(Locale.US, "%.1f")}
     * output. Three things are pinned: the placeholder is {@code %1$s}, 22.5 really renders as "22.5 °C",
     * and not as "22.50".
     */
    @Test
    public void climateTemperatureStringsCarryOneDecimal() throws IOException {
        File module = findModuleRoot();
        assumeTrue("定位不到模块根目录，跳过", module != null);

        Map<String, String> zh = parse(new File(module, "src/main/res/values/strings.xml"));
        assumeTrue("读不到中文 strings.xml，跳过", !zh.isEmpty());

        // 收「写好的数字」的那几条：不能是 %d，否则 22.5 装不进去。
        // These take a formatted number; %d cannot carry 22.5.
        for (String name : new String[] {"alert_climate_btn", "opt_alerts_climate_degrees",
                "set_alerts_hub_climate_target"}) {
            String text = zh.get(name);
            assertTrue("中文里少了 " + name, text != null);
            assertTrue(name + " 该收一个已经写好的数字（%1$s）: " + text, text.contains("%1$s"));
            assertFalse(name + " 不能再是 %d，半度装不进去: " + text,
                    text.contains("%d") || text.contains("%1$d"));
        }

        // 回执和报告本身写 %1$s：温度那一位是外部格式好的。
        for (String name : new String[] {"alert_climate_receipt", "alert_climate_report"}) {
            String text = zh.get(name);
            assertTrue("中文里少了 " + name, text != null);
            assertTrue(name + " 的温度位该是 %1$s: " + text, text.contains("%1$s"));
        }

        // 22.5 真的能渲染出来，而且是一位小数。
        String half = String.format(java.util.Locale.US, "%.1f", 22.5f);
        assertEquals("半个度写出来是一位小数", "22.5", half);
        assertEquals("目标 %1$s °C 渲染成 22.5 °C", "目标 22.5 °C",
                String.format(java.util.Locale.US,
                        zh.get("set_alerts_hub_climate_target"), half));
        assertEquals("按钮上也是半度", "空调 22.5°",
                String.format(java.util.Locale.US, zh.get("alert_climate_btn"), half));
        assertEquals("行尾也是半度", "22.5 °C",
                String.format(java.util.Locale.US, zh.get("opt_alerts_climate_degrees"), half));
        assertTrue("回执里的目标温度写一位小数",
                String.format(java.util.Locale.US, zh.get("alert_climate_receipt"), half, "", "10:30")
                        .contains("目标 22.5 °C"));
        assertTrue("ac 报告里的目标温度写一位小数",
                String.format(java.util.Locale.US, zh.get("alert_climate_report"),
                        "21.0", "25.0", half, "开着").contains("目标 22.5 °C"));
    }

    @Test
    public void everyChineseStringHasATranslationWithMatchingPlaceholders() throws IOException {
        File module = findModuleRoot();
        assumeTrue("定位不到模块根目录，跳过", module != null);

        Map<String, String> zh = parse(new File(module, "src/main/res/values/strings.xml"));
        assumeTrue("读不到中文 strings.xml，跳过", !zh.isEmpty());

        for (String dir : TRANSLATIONS) {
            Map<String, String> other = parse(
                    new File(module, "src/main/res/" + dir + "/strings.xml"));
            assertTrue("读不到 " + dir + "/strings.xml", !other.isEmpty());

            TreeSet<String> missing = new TreeSet<>(zh.keySet());
            missing.removeAll(other.keySet());
            if ("values-ar".equals(dir)) {
                assertPendingArabic(missing);
                continue;
            }
            assertTrue("这些条目没有 " + dir + " 翻译，那个界面上会露出中文: " + missing,
                    missing.isEmpty());

            TreeSet<String> stray = new TreeSet<>(other.keySet());
            stray.removeAll(zh.keySet());
            assertTrue("这些 " + dir + " 条目在中文里没有对应，多半是改名后忘了删: " + stray,
                    stray.isEmpty());

            List<String> mismatched = new ArrayList<>();
            for (Map.Entry<String, String> entry : zh.entrySet()) {
                if (!placeholders(entry.getValue())
                        .equals(placeholders(other.get(entry.getKey())))) {
                    mismatched.add(entry.getKey());
                }
            }
            assertTrue("这些条目和 " + dir + " 的占位符不一致，运行期格式化会抛异常: "
                    + mismatched, mismatched.isEmpty());
        }
    }

    /**
     * 阿拉伯文那份现在允许空着 {@link #PENDING_ARABIC} 列出的那批，其余一条不许空；条的数目还对得上 {@link #PENDING_ARABIC_COUNT}。
     */
    private static void assertPendingArabic(TreeSet<String> missing) {
        TreeSet<String> unexplained = new TreeSet<>();
        for (String name : missing) {
            if (!isPendingArabic(name)) {
                unexplained.add(name);
            }
        }
        assertTrue("这些条目没有 values-ar 翻译，也不在待翻译清单里（PENDING_ARABIC）: "
                + unexplained, unexplained.isEmpty());

        assertTrue("待翻译的条数变了：现在是 " + missing.size() + "，PENDING_ARABIC_COUNT 写的是 "
                        + PENDING_ARABIC_COUNT + "。少一条是补上了阿语，把那个数字改小；"
                        + "多一条是新加的停车警报文案，得知道是哪一条: " + missing,
                missing.size() == PENDING_ARABIC_COUNT);
    }

    private static boolean isPendingArabic(String name) {
        for (String prefix : PENDING_ARABIC) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 复数（{@code <plurals>}）同样要每种语言都有、占位符对得上。
     *
     * <p>中文和马来文只有 {@code other} 一条，英文另有 {@code one}，阿拉伯文六种都写（{@link #QUANTITIES}）。每种语言都必须有 {@code other}
     * （任何数量都能落到它）；{@code other} 的占位符要和中文的一致，其他条目不能多出中文没有的占位符
     * （{@code one} 可以不写数字，但不能多要参数）。</p>
     */
    @Test
    public void everyChinesePluralHasATranslationWithMatchingPlaceholders() throws IOException {
        File module = findModuleRoot();
        assumeTrue("定位不到模块根目录，跳过", module != null);

        Map<String, Map<String, String>> zh = parsePlurals(
                new File(module, "src/main/res/values/strings.xml"));
        for (Map.Entry<String, Map<String, String>> entry : zh.entrySet()) {
            assertTrue("中文复数 " + entry.getKey() + " 缺 other 条目",
                    entry.getValue().containsKey("other"));
        }

        for (String dir : TRANSLATIONS) {
            Map<String, Map<String, String>> other = parsePlurals(
                    new File(module, "src/main/res/" + dir + "/strings.xml"));

            TreeSet<String> missing = new TreeSet<>(zh.keySet());
            missing.removeAll(other.keySet());
            assertTrue("这些复数条目没有 " + dir + " 翻译: " + missing, missing.isEmpty());

            TreeSet<String> stray = new TreeSet<>(other.keySet());
            stray.removeAll(zh.keySet());
            assertTrue("这些 " + dir + " 复数条目在中文里没有对应: " + stray, stray.isEmpty());

            List<String> mismatched = new ArrayList<>();
            for (Map.Entry<String, Map<String, String>> entry : zh.entrySet()) {
                TreeSet<String> expected = pluralPlaceholders(entry.getValue().get("other"));
                Map<String, String> items = other.get(entry.getKey());
                for (String quantity : QUANTITIES.getOrDefault(dir, new String[0])) {
                    if (!items.containsKey(quantity)) {
                        mismatched.add(entry.getKey() + "/" + quantity + "（缺）");
                    }
                }
                if (!items.containsKey("other")
                        || !pluralPlaceholders(items.get("other")).equals(expected)) {
                    mismatched.add(entry.getKey() + "/other");
                }
                for (Map.Entry<String, String> item : items.entrySet()) {
                    if (!expected.containsAll(pluralPlaceholders(item.getValue()))) {
                        mismatched.add(entry.getKey() + "/" + item.getKey());
                    }
                }
            }
            assertTrue("这些复数条目在 " + dir + " 里缺了说法，或者占位符不一致（运行期格式化会出错）: "
                    + mismatched, mismatched.isEmpty());
        }
    }

    @Test
    public void zhEnOnlyFilesMatchAndStayOutOfTheTranslations() throws IOException {
        File module = findModuleRoot();
        assumeTrue("定位不到模块根目录，跳过", module != null);

        for (String name : ZH_EN_ONLY) {
            Map<String, String> en = parse(new File(module, "src/main/res/values/" + name));
            Map<String, String> zh = parse(new File(module, "src/main/res/values-zh/" + name));
            assertTrue("读不到 values/" + name, !en.isEmpty());
            assertTrue("读不到 values-zh/" + name, !zh.isEmpty());
            assertTrue(name + " 的中英两份条目对不上: 英文 " + new TreeSet<>(en.keySet())
                    + " / 中文 " + new TreeSet<>(zh.keySet()), en.keySet().equals(zh.keySet()));
            for (Map.Entry<String, String> entry : zh.entrySet()) {
                assertTrue(name + " 里 " + entry.getKey() + " 的中英占位符不一致",
                        placeholders(entry.getValue()).equals(placeholders(en.get(entry.getKey()))));
            }
            for (String dir : stringDirs(false)) {
                TreeSet<String> twice = new TreeSet<>(en.keySet());
                twice.retainAll(parse(new File(module, "src/main/res/" + dir + "/strings.xml")).keySet());
                assertTrue(dir + "/strings.xml 里又出现了只做中英两份的条目: " + twice, twice.isEmpty());
            }
        }
    }

    /** 中文（values）和每一份翻译；{@code withZhOnly} 时再加上只放中文长文字的 values-zh。 */
    private static List<String> stringDirs(boolean withZhOnly) {
        List<String> dirs = new ArrayList<>();
        dirs.add("values");
        dirs.addAll(java.util.Arrays.asList(TRANSLATIONS));
        if (withZhOnly) {
            dirs.add("values-zh");
        }
        return dirs;
    }

    /**
     * 界面上的数字一律 0–9（项目所有者 2026-11-18，ui-text-style §4）：阿拉伯文翻译里不写 ٠–٩，
     * 任何一份都不写全角数字这类别的数字。
     */
    @Test
    public void everyLanguageWritesDigits0to9() throws IOException {
        File module = findModuleRoot();
        assumeTrue("定位不到模块根目录，跳过", module != null);
        List<String> offenders = new ArrayList<>();
        for (String dir : stringDirs(true)) {
            File[] files = new File(module, "src/main/res/" + dir).listFiles(
                    (d, n) -> n.startsWith("strings") && n.endsWith(".xml"));
            if (files == null) {
                continue;
            }
            for (File file : files) {
                String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                for (Map.Entry<String, String> entry : parse(file).entrySet()) {
                    if (otherDigits(entry.getValue())) {
                        offenders.add(dir + ":" + entry.getKey());
                    }
                }
                for (Map.Entry<String, Map<String, String>> plural : parsePlurals(file).entrySet()) {
                    for (Map.Entry<String, String> item : plural.getValue().entrySet()) {
                        if (otherDigits(item.getValue())) {
                            offenders.add(dir + ":" + plural.getKey() + "/" + item.getKey());
                        }
                    }
                }
                assertTrue(file + " 是空的", !text.isEmpty());
            }
        }
        assertTrue("这些字符串里有 0–9 以外的数字: " + offenders, offenders.isEmpty());
    }

    private static boolean otherDigits(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isDigit(c) && (c < '0' || c > '9')) {
                return true;
            }
        }
        return false;
    }

    private static TreeSet<String> placeholders(String text) {
        TreeSet<String> found = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(text == null ? "" : text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    private static TreeSet<String> pluralPlaceholders(String text) {
        TreeSet<String> found = new TreeSet<>();
        Matcher matcher = PLURAL_PLACEHOLDER.matcher(text == null ? "" : text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    /** 键 → （quantity → 文字）。 */
    private static Map<String, Map<String, String>> parsePlurals(File file) throws IOException {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        if (!file.isFile()) {
            return out;
        }
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Matcher plurals = PLURALS.matcher(text);
        while (plurals.find()) {
            Map<String, String> items = new LinkedHashMap<>();
            Matcher item = ITEM.matcher(plurals.group(2));
            while (item.find()) {
                items.put(item.group(1), item.group(2));
            }
            out.put(plurals.group(1), items);
        }
        return out;
    }

    private static Map<String, String> parse(File file) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (!file.isFile()) {
            return out;
        }
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Matcher matcher = STRING.matcher(text);
        while (matcher.find()) {
            out.put(matcher.group(1), matcher.group(2));
        }
        return out;
    }

    private static File findModuleRoot() {
        File dir = new File(System.getProperty("user.dir", "."));
        for (int i = 0; i < 4 && dir != null; i++) {
            if (new File(dir, "src/main/res/values/strings.xml").isFile()) {
                return dir;
            }
            File app = new File(dir, "app");
            if (new File(app, "src/main/res/values/strings.xml").isFile()) {
                return app;
            }
            dir = dir.getParentFile();
        }
        return null;
    }

    /**
     * 撇号必须转义。
     *
     * <p>{@code app's} 这样的写法会让 aapt 直接失败：
     * 「Invalid unicode escape sequence in string」—— 报的位置和错的东西都对不上，
     * 每次都要重新想一遍才认出来是撇号。这条测试替 CI 挡住它。</p>
     */
    @Test
    public void apostrophesAreEscaped() throws IOException {
        File module = findModuleRoot();
        assumeTrue("定位不到模块根目录，跳过", module != null);

        List<String> offenders = new ArrayList<>();
        List<File> files = new ArrayList<>();
        for (String dir : stringDirs(true)) {
            File[] inDir = new File(module, "src/main/res/" + dir).listFiles(
                    (d, n) -> n.startsWith("strings") && n.endsWith(".xml"));
            if (inDir != null) {
                files.addAll(java.util.Arrays.asList(inDir));
            }
        }
        for (File file : files) {
            String dir = file.getParentFile().getName() + "/" + file.getName();
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Matcher matcher = STRING.matcher(text);
            while (matcher.find()) {
                if (hasBareApostrophe(matcher.group(2))) {
                    offenders.add(dir + ":" + matcher.group(1));
                }
            }
            // 复数条目里的每一条也一样
            Matcher plurals = PLURALS.matcher(text);
            while (plurals.find()) {
                Matcher item = ITEM.matcher(plurals.group(2));
                while (item.find()) {
                    if (hasBareApostrophe(item.group(2))) {
                        offenders.add(dir + ":" + plurals.group(1) + "/" + item.group(1));
                    }
                }
            }
        }
        assertTrue("这些字符串里的撇号没有转义，aapt 会直接编不过（写成 \\' 即可）: "
                + offenders, offenders.isEmpty());
    }

    private static boolean hasBareApostrophe(String body) {
        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) == '\'' && (i == 0 || body.charAt(i - 1) != '\\')) {
                return true;
            }
        }
        return false;
    }

}

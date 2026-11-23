/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.screens;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import com.kooo.evcam.R;
import com.kooo.evcam.zeekr.AboutActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;

/**
 * 「关于与致谢」的「开源与来源」一段（项目所有者 2026-11-15）：贡献者里有 lelelolo，下面列着随应用分发的第三方开源组件和许可证。
 * 中英各一张（关于页只有这两种文字）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AboutShotsTest {

    @Test
    public void creditsByDayInChinese() {
        shoot(Shots.DAY, "about-credits-zh", "lelelolo", "NanoHTTPD");
    }

    @Test
    public void creditsByDayInEnglish() {
        shoot(Shots.DAY.replace("zh-rCN-", "en-"), "about-credits-en", "lelelolo", "Media3 ExoPlayer");
    }

    /** 关于页没有阿拉伯文：阿拉伯语界面显示英文那份（同马来文；ui-text-style §5）。 */
    @Test
    public void creditsInArabicShowTheEnglishPage() {
        shoot(Shots.DAY_AR, "about-credits-ar", "lelelolo", "Media3 ExoPlayer");
    }

    /**
     * 页尾的开发者选项入口（4.19）：一行按钮，写着「打开开发者选项」。
     *
     * <p>这一张图看的是它排在哪儿、和上面那一大段的关系 —— 它是一行看得见的按钮，不是藏在标题里。
     * The developer-options entry at the foot of the page: a real button, not a hidden tap target.
     */
    @Test
    public void developerRowByDayInChinese() {
        developerRow(Shots.DAY, "about-developer-zh", "打开开发者选项");
    }

    @Test
    public void developerRowByDayInEnglish() {
        developerRow(Shots.DAY.replace("zh-rCN-", "en-"), "about-developer-en", "Enable developer options");
    }

    /**
     * 点那一行弹出的警告框：主操作是红的（和删除、改系统配置同一档），两句话写清楚风险。
     *
     * <p>这一张图是给 4.19 的审核看的：打开开发者选项不要密码，但必须先把这句话看完。
     * The confirmation dialog: red primary button, the risk spelled out before anything unlocks.
     */
    @Test
    public void developerWarningDialogByDay() {
        RuntimeEnvironment.setQualifiers(Shots.DAY.replace("zh-rCN-", "en-"));
        AboutActivity activity = Robolectric.buildActivity(AboutActivity.class).setup().get();
        Shots.settle();
        assertFalse("这一条看的是「本来关着」",
                com.kooo.evcam.settings.DeveloperMode.isUnlocked());
        activity.findViewById(R.id.about_developer_row).performClick();
        Shots.settle();
        android.app.Dialog dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull("点那一行该弹警告框", dialog);
        File file = Shots.save(dialog, "about-developer-warning-en");
        assertTrue(file + " 是空的", file.length() > 5_000);
        dialog.dismiss();
        Shots.settle();
    }

    private void developerRow(String qualifiers, String name, String label) {
        RuntimeEnvironment.setQualifiers(qualifiers);
        AboutActivity activity = Robolectric.buildActivity(AboutActivity.class).setup().get();
        Shots.settle();
        View row = activity.findViewById(R.id.about_developer_row);
        assertNotNull("页上少了开发者选项那一行", row);
        assertTrue("那一行该写着「" + label + "」，现在是 " + ((TextView) row).getText(),
                ((TextView) row).getText().toString().contentEquals(label));
        // 滚到页尾
        View scroller = row;
        while (scroller != null && !(scroller instanceof ScrollView)
                && !(scroller instanceof androidx.core.widget.NestedScrollView)) {
            scroller = scroller.getParent() instanceof View ? (View) scroller.getParent() : null;
        }
        // 滚到页尾。先画一张（排一次版），不然此时量到的总高还没算上最后那一行，
        // 滚不到真正的底 —— 关于页那一条截图测试也是这么做的。
        File warm = Shots.save(activity, name);
        if (scroller instanceof ScrollView) {
            // 滚到底：给一个超过总高很多的值，ScrollView 自己夹到最底
            ((ScrollView) scroller).scrollTo(0, Integer.MAX_VALUE / 2);
            Shots.settle();
        }
        File file = Shots.save(activity, name);
        assertTrue(file + " 是空的", file.length() > 10_000 && warm.length() > 10_000);
    }

    private void shoot(String qualifiers, String name, String... mustContain) {
        RuntimeEnvironment.setQualifiers(qualifiers);
        AboutActivity activity = Robolectric.buildActivity(AboutActivity.class).setup().get();
        Shots.settle();
        TextView libraries = activity.findViewById(R.id.about_credits_libraries);
        assertNotNull(libraries);
        TextView contributors = null;
        View parent = (View) libraries.getParent();
        String all = "";
        if (parent instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) parent;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (group.getChildAt(i) instanceof TextView) {
                    all += ((TextView) group.getChildAt(i)).getText() + "\n";
                }
            }
        }
        for (String text : mustContain) {
            assertTrue("少了 " + text, all.contains(text));
        }
        // 滚到「开源与来源」那一段
        View scroller = parent;
        while (scroller != null && !(scroller instanceof ScrollView)
                && !(scroller instanceof androidx.core.widget.NestedScrollView)) {
            scroller = scroller.getParent() instanceof View ? (View) scroller.getParent() : null;
        }
        File first = Shots.save(activity, name);
        if (scroller != null) {
            final View target = libraries;
            int y = target.getTop() - 600;
            scroller.scrollTo(0, Math.max(0, y));
        }
        File file = Shots.save(activity, name);
        assertTrue(file + " 是空的", file.length() > 10_000 && first.length() > 10_000);
    }
}

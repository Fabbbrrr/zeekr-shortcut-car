/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 全选（{@link ExpandablePhotoGroupAdapter#selectAll}）到底选了什么。
 *
 * <p>列表只<b>显示</b>展开的日期里的组，全选却把收起来的那些一起选了 —— 勾看得见的是少数，
 * 计数说的是全部。钉住今天的行为，删除确认框因此要单独说出收起来的那几个。</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class ExpandablePhotoGroupAdapterSelectAllTest {

    /** 钉住的是<b>今天</b>的行为：全选不看展开状态，收起来的日期里的组一起算进来。 */
    @Test
    public void selectAllTakesCollapsedSectionsToo() {
        Context context = RuntimeEnvironment.getApplication();
        DateSection<PhotoGroup> collapsed = section(3);
        DateSection<PhotoGroup> expanded = section(1);
        expanded.toggleExpanded();
        assertFalse(collapsed.isExpanded());
        assertTrue(expanded.isExpanded());

        PhotoGroup hiddenOne = group("20260110_100000");
        PhotoGroup hiddenTwo = group("20260110_100500");
        collapsed.addItem(hiddenOne);
        collapsed.addItem(hiddenTwo);
        PhotoGroup shown = group("20260112_100000");
        expanded.addItem(shown);

        List<DateSection<PhotoGroup>> sections = new ArrayList<>();
        sections.add(collapsed);
        sections.add(expanded);
        ExpandablePhotoGroupAdapter adapter = new ExpandablePhotoGroupAdapter(context, sections);

        adapter.selectAll();

        assertEquals(3, adapter.getSelectedCount());
        assertTrue(adapter.getSelectedGroups().contains(hiddenOne));
        assertTrue(adapter.getSelectedGroups().contains(hiddenTwo));
        assertTrue(adapter.getSelectedGroups().contains(shown));

        // 屏幕上看不见的就是这一批，删除确认框说的是这个数
        assertEquals(2, adapter.getSelectedCountInCollapsedSections());
    }

    /** 没有收起来的日期时，全选不该多说什么。 */
    @Test
    public void nothingIsHiddenWhenEverySectionIsOpen() {
        Context context = RuntimeEnvironment.getApplication();
        DateSection<PhotoGroup> open = section(0);
        open.addItem(group("20260112_100000"));
        // 今天默认就是展开的
        assertTrue(open.isExpanded());

        List<DateSection<PhotoGroup>> sections = new ArrayList<>();
        sections.add(open);
        ExpandablePhotoGroupAdapter adapter = new ExpandablePhotoGroupAdapter(context, sections);

        adapter.selectAll();

        assertEquals(1, adapter.getSelectedCount());
        assertEquals(0, adapter.getSelectedCountInCollapsedSections());
    }

    /** 日期分组：默认只展开今天，过去的都是收起来的。 */
    private static DateSection<PhotoGroup> section(int daysAgo) {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_YEAR, -daysAgo);
        Date date = calendar.getTime();
        return new DateSection<>(new SimpleDateFormat("yyyyMMdd", Locale.US).format(date), date);
    }

    private static PhotoGroup group(String timestampPrefix) {
        return new PhotoGroup(timestampPrefix);
    }
}

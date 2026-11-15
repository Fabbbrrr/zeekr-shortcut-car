/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.kooo.evcam.AppConfig;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 扫描的形状（F-07.05）：列目录、每一个文件的 stat、读锁定清单都挪到了后台线程上，
 * 交回主线程的只有这一份结果（见 {@link PhotoPlaybackActivity#updatePhotoList}）。
 *
 * <p>钉的是分组的形状、扫描顺手记下的修改时间（绑定缩略图时查的就是它），还有开关关着时
 * 锁定清单照旧给空集合。慢了多少（U 盘 exFAT 上那点开销）单元测试说不了，要在车机上量。</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class PhotoPlaybackScanTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private final Context context = RuntimeEnvironment.getApplication();

    /** 两天的照片：新的那天在前；同一秒的几路算一组；不是照片的不算。 */
    @Test
    public void groupsByDayNewestFirst() throws IOException {
        File dir = folder.newFolder("photos");
        write(dir, "20260110_100000_surround.jpg", 100);
        write(dir, "20260110_100000_cabinfront.jpg", 200);
        write(dir, "20260112_090000_surround.jpg", 50);
        write(dir, "locked.txt", 7);                                   // 不是照片
        write(dir, "20260112_090000_surround.mp4", 9);                  // 录像归录像

        PhotoPlaybackActivity.Scan scan = PhotoPlaybackActivity.scanPhotos(context, dir);

        assertTrue("里面有照片", scan.hasPhotos);
        assertEquals("两天的照片分两段", 2, scan.sections.size());
        assertEquals("新的那天排在前面", "20260112_090000",
                scan.sections.get(0).getItems().get(0).getTimestampPrefix());
        assertEquals(1, scan.sections.get(0).getItemCount());
        assertEquals("同一秒的几路算一组", 1, scan.sections.get(1).getItemCount());
        assertEquals(2, scan.sections.get(1).getItems().get(0).getPhotoCount());
    }

    /** 修改时间在扫描时就记下来：绑定缩略图时查表，不再一个文件问盘一次。 */
    @Test
    public void stampsComeFromTheScan() throws IOException {
        File dir = folder.newFolder("photos");
        File scanned = write(dir, "20260112_090000_surround.jpg", 50);
        assertTrue("改不动修改时间就测不下去", scanned.setLastModified(1_700_000_000_000L));

        PhotoPlaybackActivity.Scan scan = PhotoPlaybackActivity.scanPhotos(context, dir);

        assertEquals(Long.valueOf(1_700_000_000_000L),
                scan.stamps.get("20260112_090000_surround.jpg"));
        assertEquals("表里有就用表里的", Long.valueOf(1_700_000_000_000L),
                ExpandablePhotoGroupAdapter.signatureFor(scanned, scan.stamps));

        File gone = new File(dir, "20260112_090100_surround.jpg");     // 扫描之后才没的
        assertFalse(scan.stamps.containsKey(gone.getName()));
        assertEquals("表里没有才回盘上问（文件没了 → 0）", Long.valueOf(0L),
                ExpandablePhotoGroupAdapter.signatureFor(gone, scan.stamps));
        assertNull(ExpandablePhotoGroupAdapter.signatureFor(null, scan.stamps));
    }

    /** 开关开着：清单里的名字要带回来（这一步以前在主线程上，现在在扫描里）。 */
    @Test
    public void theLockedListComesBackFromTheScan() throws IOException {
        File dir = folder.newFolder("photos");
        File photo = write(dir, "20260112_090000_surround.jpg", 50);
        writeText(dir, "locked.txt", "# header\n20260112_090000_surround.jpg\n");

        new AppConfig(context).setFootageLockEnabled(true);
        try {
            PhotoPlaybackActivity.Scan scan = PhotoPlaybackActivity.scanPhotos(context, dir);

            assertTrue(scan.lockEnabled);
            assertTrue("清单里那个名字要带回来", scan.locked.contains(photo.getName()));
        } finally {
            new AppConfig(context).setFootageLockEnabled(false);
        }

        PhotoPlaybackActivity.Scan off = PhotoPlaybackActivity.scanPhotos(context, dir);
        assertFalse(off.lockEnabled);
        assertTrue("开关关着就是没锁（清单还在，但不算）", off.locked.isEmpty());
    }

    /** 没有地方存照片（没 U 盘、开发者选项没开）：扫描照旧给「没有照片」。 */
    @Test
    public void nothingToScanWhenThereIsNoDirectory() {
        PhotoPlaybackActivity.Scan scan = PhotoPlaybackActivity.scanPhotos(context, null);

        assertFalse(scan.hasPhotos);
        assertTrue(scan.sections.isEmpty());
        assertTrue(scan.stamps.isEmpty());
    }

    private static File write(File dir, String name, int bytes) throws IOException {
        File file = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[bytes]);
        }
        return file;
    }

    private static File writeText(File dir, String name, String text) throws IOException {
        File file = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes("UTF-8"));
        }
        return file;
    }
}

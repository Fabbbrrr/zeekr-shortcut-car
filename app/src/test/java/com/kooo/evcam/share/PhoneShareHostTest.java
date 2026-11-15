/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.share;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Dialog;
import android.os.Looper;

import com.kooo.evcam.screens.ShotActivity;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 分享服务跟着宿主走（F-07.03），重活不在点下去那一下做（F-07.04）。
 *
 * <p>dismiss 只在用户自己关掉对话框时才响：界面被关掉、被系统销毁，对话框跟着没了，
 * 监听口却留在原地。现在 {@link PhoneShare#bindToHost} 盯着宿主的 onDestroy，
 * dismiss 照旧。两条路都通向 {@link PhoneShare#stopOnce}，只认第一次。</p>
 *
 * <p>F-07.04 钉的是形状：{@link PhoneShare#show} 把问网卡交给后台，扫完才回主线程搭对话框。
 * 到底省了多少毫秒要在车机上量（StrictMode 或 Choreographer）—— 这里只钉住「不再当场搭」。</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
public class PhoneShareHostTest {

    /** 宿主毁了 → 服务要收回来。递进去的是真服务（端口 0 真起来），不是替身。 */
    @Test
    public void destroyingTheHostStopsTheServer() throws Exception {
        ActivityController<ShotActivity> controller =
                Robolectric.buildActivity(ShotActivity.class).setup();
        FileShareServer server = startServer();

        PhoneShare.bindToHost(controller.get(), PhoneShare.stopOnce(server));
        assertTrue("还没毁，服务要在", server.isAlive());

        controller.destroy();
        assertFalse("毁了就要停：这是 F-07.03 要修的那一件事", server.isAlive());
    }

    /** 认不出生命周期的宿主：这里不动手（也不许抛），那条路只剩 dismiss。 */
    @Test
    public void aHostWithoutALifecycleOwnerIsLeftToTheDismissPath() throws Exception {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        FileShareServer server = startServer();
        AtomicBoolean stopped = new AtomicBoolean();

        PhoneShare.bindToHost(controller.get(), () -> stopped.set(true));
        controller.destroy();

        assertFalse("认不出生命周期就什么都不做", stopped.get());
        assertTrue("服务还在，等 dismiss 或下一个接手的人", server.isAlive());
        server.stop();
    }

    /** 重复停只算第一次：dismiss 和 onDestroy 可能都会来。 */
    @Test
    public void stoppingTwiceStopsOnceAndDoesNotThrow() throws Exception {
        FileShareServer server = startServer();
        Runnable stop = PhoneShare.stopOnce(server);

        stop.run();
        assertFalse("第一次就停了", server.isAlive());

        stop.run();          // 第二条路再来一次：不该抛，也不该有事
        assertFalse("还停着", server.isAlive());
    }

    /**
     * 点下去那一下不搭对话框（F-07.04）：问网卡在后台，扫完交回主线程才搭。
     * 慢了多少毫秒要到车机上量，这里只钉「什么时候搭」。
     */
    @Test
    public void showDoesNotBuildTheDialogOnTheCallingThread() throws Exception {
        ActivityController<ShotActivity> controller =
                Robolectric.buildActivity(ShotActivity.class).setup();
        ShotActivity activity = controller.get();
        File file = File.createTempFile("phone_share_test", ".jpg");
        file.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[]{1, 2, 3});
        }

        PhoneShare.show(activity, file);
        assertNull("点下去那一下不该已经把对话框搭好", ShadowDialog.getLatestDialog());

        drainBack();                                   // 同一条队列：走到这里，show 的那一段已经跑完
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertNotNull("扫完还是要搭出来 —— 这是原来的行为，不能丢", dialog);

        dialog.dismiss();
        controller.destroy();
    }

    /** 后台那一段跑完（单线程队列，排在 show 的任务后面）。 */
    private static void drainBack() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        PhoneShare.BACK.execute(done::countDown);
        assertTrue("后台那一段没跑完", done.await(10, TimeUnit.SECONDS));
    }

    private static FileShareServer startServer() throws Exception {
        File file = File.createTempFile("phone_share_test", ".jpg");
        file.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[]{1, 2, 3});
        }
        FileShareServer server = new FileShareServer(RuntimeEnvironment.getApplication());
        server.start();
        server.share(file);
        assertTrue("端口没起来，这个测试就没在测东西", server.getListeningPort() > 0);
        return server;
    }

    /** 主线程那一段拿到地址就搭对话框（{@link PhoneShare#open}）；测试自己递地址，不碰真网卡。 */
    @Test
    public void theDialogOpensOnMainWithTheInjectedAddress() throws Exception {
        ActivityController<ShotActivity> controller =
                Robolectric.buildActivity(ShotActivity.class).setup();
        ShotActivity activity = controller.get();
        File file = File.createTempFile("phone_share_test", ".jpg");
        file.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[]{1, 2, 3});
        }

        FileShareServer server = PhoneShare.open(activity, file, null,
                Collections.singletonList(
                        new LocalNetwork.Endpoint("wlan0", "127.0.0.1", LocalNetwork.Kind.WIFI)));

        assertNotNull("服务要真的起来", server);
        drainBack();                                     // 二维码在后台画完
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNotNull("对话框要搭得出来", ShadowDialog.getLatestDialog());

        controller.destroy();                            // 没有 dismiss，靠的是 onDestroy
        assertFalse("宿主毁了，服务要收回来", server.isAlive());
    }
}

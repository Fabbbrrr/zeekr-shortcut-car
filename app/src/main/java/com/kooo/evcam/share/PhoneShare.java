/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.share;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 「发送到手机」：起一个临时的局域网服务，把二维码摆出来。
 *
 * <h3>为什么是扫码而不是配套 App</h3>
 *
 * <p>手机上不用装任何东西：扫码 → 浏览器打开 → 长按保存。
 * 代价是保存这一步要用户自己做，换来的是零安装。</p>
 *
 * <h3>服务活多久</h3>
 *
 * <p>只在这个对话框开着的时候。关掉就停 —— 一个能被局域网访问的文件服务，
 * 没有理由在用户已经不看它的时候继续开着。所以对话框里写明了
 * 保存完成前不要关闭（{@code share_phone_keep_open}）。</p>
 *
 * <p>但对话框是<b>被系统关掉</b>的场合不会有「用户关掉」那一下：界面被销毁、对话框跟着没了，
 * 服务却还开着监听口。所以除了 dismiss，这里还盯着宿主 Activity 的销毁（{@link #bindToHost}）。
 * 两条路都通向同一次停机，重复的调用只认第一次（{@link #stopOnce}）。</p>
 *
 * <h3>哪几件事不在主线程上</h3>
 *
 * <p>问网卡和画二维码在 {@link #BACK} 上（前者是 netlink 查询，后者要铺 420×420 的像素）；
 * 点下去到对话框出现这一段就是这两件事最慢。对话框本身照旧在主线程上搭。</p>
 */
public final class PhoneShare {

    private static final String TAG = "PhoneShare";
    private static final int QR_SIZE_PX = 420;

    /** 问网卡、画二维码都在这里；一条线程就够（同一时刻只可能有一个分享对话框）。测试也认它。 */
    static final ExecutorService BACK = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "PhoneShare");
        thread.setDaemon(true);
        return thread;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private PhoneShare() {
    }

    /**
     * 弹出发送对话框。
     *
     * @param file 要发送的那一个文件；为 null 或不存在时只提示，不弹窗
     */
    public static void show(Activity activity, File file) {
        show(activity, file, null);
    }

    /**
     * @param extraNote 额外的一句说明，显示在操作步骤下面；不需要时传 null。
     *                  视频用它讲清楚「只发当前这一段」。
     */
    public static void show(Activity activity, File file, String extraNote) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        if (file == null || !file.isFile() || file.length() <= 0) {
            toast(activity, activity.getString(R.string.share_phone_no_file));
            return;
        }

        // 问网卡不在主线程上（F-07.04）：扫完把结果交回主线程搭对话框。
        // 测试可以直接把地址递进 open()，不必真有一块网卡。
        BACK.execute(() -> {
            List<LocalNetwork.Endpoint> endpoints = LocalNetwork.enumerate();
            MAIN.post(() -> open(activity, file, extraNote, endpoints));
        });
    }

    /**
     * {@link #show} 扫完网卡之后的那一段，在主线程上跑。
     *
     * @return 建好的服务（起来过就算）；没地址、起来失败时是 null。界面用不上，测试用。
     */
    static FileShareServer open(Activity activity, File file, String extraNote,
                                List<LocalNetwork.Endpoint> endpoints) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            // 扫的时候还在，回来时界面可能已经关了
            return null;
        }
        if (endpoints.isEmpty()) {
            // 没有可用地址就不是「失败」，是前提没满足 —— 说清楚该做什么
            com.kooo.evcam.ui.CamDialogs.show(new MaterialAlertDialogBuilder(activity, R.style.Theme_Cam_MaterialAlertDialog)
                    .setTitle(R.string.share_phone_need_hotspot_title)
                    .setMessage(activity.getString(R.string.share_phone_need_hotspot_msg)
                            + "\n\n" + activity.getString(R.string.share_phone_network_hint))
                    .setPositiveButton(R.string.action_got_it, null));
            return null;
        }

        FileShareServer server = new FileShareServer(activity);
        try {
            server.start();
            server.share(file);
        } catch (Exception e) {
            // 异常原文（可能是 null，也可能是英文）只进日志，界面上只说没起来、请重试
            AppLog.e(TAG, "起不来分享服务", e);
            toast(activity, activity.getString(R.string.share_phone_failed));
            return null;
        }
        AppLog.i(TAG, "分享 " + file.getName() + "，端口 " + server.getListeningPort()
                + "，可用地址 " + endpoints.size() + " 个：" + endpoints);
        Runnable stop = stopOnce(server);
        Presenter presenter = new Presenter(activity, server, endpoints, file, extraNote, stop);
        bindToHost(activity, stop);
        presenter.show();
        return server;
    }

    /**
     * 宿主 Activity 销毁时把服务收回来（F-07.03）。
     *
     * <p>对话框的 dismiss 只在用户自己关掉时才响。界面被关掉、被系统销毁时对话框跟着没了，
     * 监听口却留在原地 —— 直到进程结束。三个调用者的对话框都是应用内的对话框，
     * 宿主都是 AppCompatActivity，所以能挂到它的生命周期上，不必各写一份 onDestroy。</p>
     *
     * <p>拿不到生命周期宿主时这里什么都不做，dialog dismiss 那条路照旧在。
     * {@code onStop} 由 {@link #stopOnce} 包过，重复调用不会重复停。</p>
     */
    static void bindToHost(Activity host, Runnable onStop) {
        if (!(host instanceof LifecycleOwner)) {
            return;
        }
        ((LifecycleOwner) host).getLifecycle().addObserver(new StopOnDestroy(onStop));
    }

    /** 只认 onDestroy：界面没了就停，别等 dismiss。 */
    private static final class StopOnDestroy implements LifecycleEventObserver {
        private final Runnable onStop;

        StopOnDestroy(Runnable onStop) {
            this.onStop = onStop;
        }

        @Override
        public void onStateChanged(@NonNull LifecycleOwner source, @NonNull Lifecycle.Event event) {
            if (event == Lifecycle.Event.ON_DESTROY) {
                source.getLifecycle().removeObserver(this);
                onStop.run();
            }
        }
    }

    /** 停一次就够：dismiss 和宿主销毁可能都会来停（NanoHTTPD 也不会因为多停一次而坏，但日志会重复）。 */
    static Runnable stopOnce(FileShareServer server) {
        AtomicBoolean stopped = new AtomicBoolean();
        return () -> {
            if (!stopped.compareAndSet(false, true)) {
                return;
            }
            try {
                server.stop();
                AppLog.d(TAG, "分享服务已停止");
            } catch (Exception e) {
                AppLog.w(TAG, "停止分享服务时出错: " + e);
            }
        };
    }

    private static void toast(Activity activity, String text) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
    }

    /** 对话框本身。拆出来是因为「切换地址」要改二维码，得留着几个引用。 */
    private static final class Presenter {
        private final Activity activity;
        private final FileShareServer server;
        private final List<LocalNetwork.Endpoint> endpoints;
        private final File file;
        private final String extraNote;
        private final Runnable stop;

        private int index;
        private ImageView qrImage;
        private TextView addressText;
        private TextView qrFailed;
        /** 换地址要重画，后到的旧结果不许贴上去。 */
        private int renderSeq;

        Presenter(Activity activity, FileShareServer server,
                  List<LocalNetwork.Endpoint> endpoints, File file, String extraNote, Runnable stop) {
            this.activity = activity;
            this.server = server;
            this.endpoints = endpoints;
            this.file = file;
            this.extraNote = extraNote;
            this.stop = stop;
        }

        void show() {
            int pad = dp(20);
            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(pad, pad, pad, pad);
            root.setGravity(Gravity.CENTER_HORIZONTAL);

            qrImage = new ImageView(activity);
            LinearLayout.LayoutParams qrParams =
                    new LinearLayout.LayoutParams(QR_SIZE_PX, QR_SIZE_PX);
            qrParams.gravity = Gravity.CENTER_HORIZONTAL;
            qrImage.setLayoutParams(qrParams);
            root.addView(qrImage);

            qrFailed = text(activity.getString(R.string.share_phone_qr_failed), R.dimen.text_caption, false);
            qrFailed.setVisibility(android.view.View.GONE);
            root.addView(qrFailed);

            root.addView(text(activity.getString(FileShareServer.isMedia(file.getName())
                    ? R.string.share_phone_steps : R.string.share_phone_steps_file), R.dimen.text_body, false));
            if (extraNote != null && !extraNote.isEmpty()) {
                root.addView(text(extraNote, R.dimen.text_caption, false));
            }

            addressText = text("", R.dimen.text_caption, true);
            root.addView(addressText);

            root.addView(text(activity.getString(R.string.share_phone_network_hint), R.dimen.text_caption, true));
            root.addView(text(activity.getString(R.string.share_phone_keep_open), R.dimen.text_caption, true));

            MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
                    activity, R.style.Theme_Cam_MaterialAlertDialog)
                    .setTitle(R.string.share_phone_title)
                    .setView(root)
                    .setPositiveButton(R.string.action_close, null)
                    .setOnDismissListener(dialog -> stop.run());

            if (endpoints.size() > 1) {
                // 两块网卡同时有地址是常事，默认那个未必是手机连得上的那个。
                // 与其让人对着一个扫不通的码发呆，不如给一个「换一个」。
                builder.setNeutralButton(R.string.share_phone_switch_address, null);
            }

            AlertDialog dialog = builder.create();
            dialog.setOnShowListener(d -> {
                com.kooo.evcam.ui.CamDialogs.paintNow(dialog);
                if (endpoints.size() > 1) {
                    // 直接拿按钮改点击：默认行为会关掉对话框，而换地址不该关
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                        index = (index + 1) % endpoints.size();
                        render();
                    });
                }
            });
            dialog.show();
            render();
        }

        private void render() {
            final int seq = ++renderSeq;
            LocalNetwork.Endpoint endpoint = endpoints.get(index);
            String url = server.urlFor(endpoint.address);
            addressText.setText(activity.getString(R.string.share_phone_address,
                    url == null ? "" : url, file.getName(),
                    FileShareServer.readableSize(file.length())));
            AppLog.i(TAG, "二维码地址: " + url + "（网卡 " + endpoint.interfaceName + "）");
            // 画二维码不在主线程上（F-07.04）：420×420 个像素，还要新建一张 Bitmap。
            // 画完回主线程贴上去 —— ImageView 只能在它出生的那条线程上改。
            BACK.execute(() -> {
                Bitmap qr = QrCode.encode(url, QR_SIZE_PX);
                MAIN.post(() -> {
                    if (seq != renderSeq) {
                        return;      // 已经换过地址了
                    }
                    qrImage.setImageBitmap(qr);
                    // 画不出来时那一格是白的，看上去和「界面坏了」没区别 —— 说出来
                    qrFailed.setVisibility(qr == null ? android.view.View.VISIBLE
                            : android.view.View.GONE);
                    if (qr == null) {
                        AppLog.i(TAG, "二维码没画出来: " + url);
                    }
                });
            });
        }

    /** @param sizeDimen 字号体系里的一档（{@code R.dimen.text_*}），不要传字面 sp */
    private TextView text(String content, int sizeDimen, boolean secondary) {
            TextView view = new TextView(activity);
            view.setText(content);
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    activity.getResources().getDimension(sizeDimen));
            view.setGravity(Gravity.CENTER_HORIZONTAL);
            view.setPadding(0, dp(10), 0, 0);
            if (secondary) {
                view.setAlpha(0.7f);
            }
            view.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return view;
        }

        private int dp(int value) {
            return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                    activity.getResources().getDisplayMetrics());
        }
    }
}

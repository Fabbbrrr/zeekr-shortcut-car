/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.zeekr;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;


import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;

/**
 * 「关于与致谢」页面。
 *
 * <p>这一页存在的意义就是把来源讲清楚：本应用的两个能力来源分别是哪个开源项目、
 * 各自是什么许可证、我们用了什么、没用什么。信息全部硬编码在这里，不联网。</p>
 */
import androidx.appcompat.app.AppCompatActivity;

public class AboutActivity extends AppCompatActivity {

    private static final String TAG = "AboutActivity";

    public static final String EVCAM_URL = "https://github.com/suyunkai/EVCam";
    public static final String GPL_URL = "https://www.gnu.org/licenses/gpl-3.0.html";
    public static final String PROJECT_URL = "https://github.com/dts88/zeekr-shortcut-car";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        TextView version = findViewById(R.id.about_version);
        if (version != null) {
            version.setText(buildVersionLine());
        }

        TextView body = findViewById(R.id.about_body);
        if (body != null) {
            body.setMovementMethod(LinkMovementMethod.getInstance());
        }

        bindLink(R.id.about_link_project, PROJECT_URL);
        bindLink(R.id.about_link_evcam, EVCAM_URL);
        bindLink(R.id.about_link_gpl, GPL_URL);

        setUpDeveloperUnlock();
        setUpDeveloperRow();

        View close = findViewById(R.id.about_close);
        if (close != null) {
            close.setOnClickListener(v -> finish());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 开发者选项可能是在别处（密码框、设置页）改的：回到这一页时按存着的值重写那两行
        refreshDeveloperRow();
    }

    private String buildVersionLine() {
        try {
            String name = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return getString(R.string.app_name_full) + "  v" + name;
        } catch (Exception e) {
            return getString(R.string.app_name_full);
        }
    }

    private void bindLink(int viewId, final String url) {
        View view = findViewById(viewId);
        if (view == null) {
            return;
        }
        view.setOnClickListener(v -> openUrl(url));
    }

    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            // 车机上常常没有浏览器，退而把链接显示出来让用户自己抄
            AppLog.w(TAG, "无法打开链接: " + url, e);
            Toast.makeText(this, url, Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ 开发者选项


    /**
     * 点「安全须知」标题：没打开时输密码打开开发者选项，打开了就在这里关掉。
     *
     * <p>藏在这里而不是给个显眼的入口：后面那些要么没做完、要么是排查用的，
     * 平时不该出现在设置里让人以为是正常功能。</p>
     *
     * <p>点一下就弹密码框。连点二十次那套是从安卓「关于本机」抄来的，
     * 但那是<b>没有密码</b>的场景才需要的门槛 —— 这里既然要输密码，
     * 密码本身就是门槛，再让人数二十下只是折磨自己。</p>
     */
    private void setUpDeveloperUnlock() {
        View heading = findViewById(R.id.about_safety_heading);
        if (heading == null) {
            return;
        }
        heading.setOnClickListener(v -> {
            if (com.kooo.evcam.settings.DeveloperMode.isUnlocked()) {
                promptToLockDeveloperMode();
            } else {
                promptForDeveloperPassword();
            }
        });
    }

    /**
     * 「打开开发者选项」那一行（4.19）：写着现在开着还是关着，点了按状态做那件事。
     *
     * <p>没开时点它先弹一个<b>红色</b>的警告框（和警报那些「不可逆」的框同一个样式）：开发者选项里有
     * 试验性的、不受支持的设置，改坏了没有人负责。看过那句话点确认才真的解锁（
     * {@link com.kooo.evcam.settings.DeveloperMode#enableFromWarning}）—— 不输密码，
     * 但那句警告不是装饰。开着时这一行只说「已经开着」，关掉用旁边那颗「关掉」（原来的 lock）。</p>
     *
     * <p>密码那条路照旧：点「安全须知」标题还是弹密码框，两条路都通。</p>
     */
    private void setUpDeveloperRow() {
        View row = findViewById(R.id.about_developer_row);
        View off = findViewById(R.id.about_developer_off);
        if (row == null || off == null) {
            return;
        }
        row.setOnClickListener(v -> {
            if (com.kooo.evcam.settings.DeveloperMode.isUnlocked()) {
                // 开着时这一行是状态，不是开关：关掉是旁边那颗，免得手滑一下就把界面弄没了
                return;
            }
            confirmEnableDeveloperOptions();
        });
        off.setOnClickListener(v -> promptToLockDeveloperMode());
        refreshDeveloperRow();
    }

    /** 按开发者选项此刻的状态改那两行的字与可见性。 */
    private void refreshDeveloperRow() {
        View row = findViewById(R.id.about_developer_row);
        View off = findViewById(R.id.about_developer_off);
        if (row == null || off == null) {
            return;
        }
        boolean unlocked = com.kooo.evcam.settings.DeveloperMode.isUnlocked();
        if (row instanceof android.widget.TextView) {
            ((android.widget.TextView) row).setText(unlocked
                    ? R.string.dev_enable_on_row : R.string.dev_enable_action);
        }
        row.setEnabled(!unlocked);
        off.setVisibility(unlocked ? View.VISIBLE : View.GONE);
    }

    /** 打开开发者选项之前的那句话：试验性的、不受支持的、风险自负。确认键是红的。 */
    private void confirmEnableDeveloperOptions() {
        com.kooo.evcam.ui.CamDialogs.showDestructive(
                new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                        .setTitle(R.string.dev_enable_warn_title)
                        .setMessage(R.string.dev_enable_warn_msg)
                        .setPositiveButton(R.string.dev_enable_confirm, (dialog, which) -> {
                            com.kooo.evcam.settings.DeveloperMode.enableFromWarning(this);
                            refreshDeveloperRow();
                            Toast.makeText(this, R.string.dev_unlocked, Toast.LENGTH_LONG).show();
                        })
                        .setNegativeButton(R.string.action_cancel, null));
    }

    private void promptForDeveloperPassword() {
        final EditText input = com.kooo.evcam.ui.CamDialogs.input(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint(R.string.dev_unlock_hint);
        // 说明写在输入框下面（ui-spec §三：框里的说明是次要的）
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        android.widget.TextView note = com.kooo.evcam.ui.CamDialogs.note(this, getString(R.string.dev_unlock_msg));
        note.setPadding(0, pad / 2, 0, 0);
        box.addView(note);

        com.kooo.evcam.ui.CamDialogs.show(new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.dev_unlock_title)
                .setView(box)
                .setPositiveButton(R.string.action_ok, (dialog, which) -> {
                    if (com.kooo.evcam.settings.DeveloperMode.unlock(this, input.getText().toString())) {
                        refreshDeveloperRow();
                        Toast.makeText(this, R.string.dev_unlocked,
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, R.string.dev_wrong_password, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    /** 开发者选项打开之后一直开着，关只在这里关（见 DeveloperMode）。 */
    private void promptToLockDeveloperMode() {
        com.kooo.evcam.ui.CamDialogs.show(new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.dev_lock_title)
                .setMessage(R.string.dev_lock_msg)
                .setPositiveButton(R.string.dev_lock_confirm, (dialog, which) -> {
                    com.kooo.evcam.settings.DeveloperMode.lock(this);
                    refreshDeveloperRow();
                    Toast.makeText(this, R.string.dev_locked, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.action_cancel, null));
    }
}

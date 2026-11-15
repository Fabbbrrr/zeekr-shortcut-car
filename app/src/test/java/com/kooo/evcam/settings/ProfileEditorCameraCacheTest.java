/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Looper;
import android.util.Size;
import android.widget.TextView;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.profile.CameraProfile;
import com.kooo.evcam.profile.Profile;
import com.kooo.evcam.profile.ProfileStore;
import com.kooo.evcam.screens.ShotActivity;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowCameraCharacteristics;
import org.robolectric.shadows.StreamConfigurationMapBuilder;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 编辑器那一页把相机能力缓存下来（审查 F-03.01）：渲染路径以前每重搭一次就问一遍相机服务。
 *
 * <p>冷缓存那一次先按「不知道」画，后台问到以后走原来的 {@code refresh()} 重搭 ——
 * 所以搭两页只该有一次真查询，而且第一次那一眼不能等在主线程上。</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class,
        shadows = {ProfileEditorCameraCacheTest.StubCameraManager.class})
public class ProfileEditorCameraCacheTest {

    private static final String CAMERA_ID = "9";
    /** 这一路声明的尺寸 —— 故意和配置里选的那个不一样，缓存到位以后问题行才亮得起来。 */
    private static final Size DECLARED = new Size(1920, 1080);
    private static final String PICKED = "1280x720";
    /**
     * JPEG 在能力表里的内部值：AOSP {@code StreamConfigurationMap.imageFormatToInternal} 把
     * {@link ImageFormat#JPEG}（0x100）映射成 {@code HAL_PIXEL_FORMAT_BLOB}（0x21），而 Robolectric 的
     * builder 把格式原样交给 {@code StreamConfiguration} 构造函数，那里只认内部值（AOSP 注释举的正是 JPEG
     * 这一例）。所以夹具按内部值登记，读取那一侧照旧用公开常量。
     */
    private static final int HAL_INTERNAL_JPEG = 0x21;

    /** 假相机被问了几次能力。整个测试类共用，每个测试开头清零。 */
    private static final AtomicInteger QUERIES = new AtomicInteger();
    private static volatile CameraCharacteristics characteristics;

    /** 假相机：只有这一台，回答里带上声明的尺寸，并数着被问的次数。 */
    @Implements(CameraManager.class)
    public static class StubCameraManager {

        @Implementation
        public String[] getCameraIdList() {
            return new String[]{CAMERA_ID};
        }

        @Implementation
        public CameraCharacteristics getCameraCharacteristics(String cameraId) {
            QUERIES.incrementAndGet();
            return characteristics;
        }
    }

    @Before
    public void stubCamera() {
        QUERIES.set(0);
        characteristics = null;
        CameraCharacteristics chars = ShadowCameraCharacteristics.newCameraCharacteristics();
        StreamConfigurationMap map = StreamConfigurationMapBuilder.newBuilder()
                .addOutputSize(HAL_INTERNAL_JPEG, DECLARED)
                .build();
        // 先量一下夹具真的答得上来：公开常量得读得出这条尺寸，否则测试是空过的。
        assertArrayEquals(new Size[]{DECLARED}, map.getOutputSizes(ImageFormat.JPEG));
        Shadows.shadowOf(chars).set(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP, map);
        characteristics = chars;
    }

    /** 搭两次页：问相机只有一次，而且发生在后台；缓存落地以后这一页自己重搭了一次。 */
    @Test
    public void characteristicsAreAskedOnceAcrossTwoRenders() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        // 拍照那一路走预览那份声明，免得它在测试里多问一次相机
        new AppConfig(app).setPhotoViaJpegEnabled(false);
        Profile profile = new ProfileStore(app).current();
        for (CameraProfile camera : profile.cameras) {
            camera.record.resolution = PICKED;   // 写死的尺寸：解析它不用问相机
            camera.enabled = CameraProfile.ROLE_COMPOSITE.equals(camera.role);
        }
        new ProfileStore(app).saveNow(profile);

        ShotActivity activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        ProfileEditorFragment pane = new ProfileEditorFragment();
        activity.getSupportFragmentManager().beginTransaction()
                .replace(activity.container, pane)
                .commitNow();
        TextView issues = activity.findViewById(R.id.editor_issues);
        assertTrue("缓存还冷，这一眼先不下结论：" + issues.getText(), issues.getText().length() == 0);

        // 后台线程是真的线程：等它问到、存进缓存、post 回主线程把这一页重搭
        for (int i = 0; i < 200 && QUERIES.get() == 0; i++) {
            Thread.sleep(25);
        }
        for (int i = 0; i < 40 && issues.getText().length() == 0; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(25);
        }
        assertEquals("问相机只有一次", 1, QUERIES.get());
        assertTrue("缓存落地后该重搭一次，把对不上的尺寸写出来：" + issues.getText(),
                issues.getText().length() > 0);

        pane.refresh();
        assertEquals("第二次渲染读缓存，不再问相机", 1, QUERIES.get());
    }
}

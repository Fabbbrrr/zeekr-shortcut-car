/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.profile;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.kooo.evcam.camera.CameraSlots;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * {@link RecordSpecs#anyGridEnabled} 问的是「这次录制能不能用 MediaRecorder」（{@code AppConfig.shouldUseCodecRecording}）。
 *
 * <p>以前它看每一路的 {@code grid}，而座舱那几路的 {@code grid} 是没有意义的默认值 ——
 * 于是只录座舱的录像报上「开了四宫格」，把 MediaRecorder 的选择顶掉。现在它和
 * {@link RecordSpecs#storedAsGrid} 认同一条规则：只数摆得进 2×2 的那一路。</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class RecordSpecsGridTest {

    private Context context;
    private Profile profile;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        profile = ProfileMigration.migrate(new ProfileMigration.Snapshot());
        new ProfileStore(context).select(profile.id);
    }

    /** 只录座舱：那一路的 grid=true 是默认值，不能让它把录制路径改成 MediaCodec。 */
    @Test
    public void cabinOnlyRecordingDoesNotReportGrid() {
        profile.camera(CameraProfile.ROLE_COMPOSITE).enabled = false;
        profile.camera(CameraProfile.ROLE_CABIN_2).enabled = true;
        new ProfileStore(context).save(profile);

        assertFalse(RecordSpecs.anyGridEnabled(context));
        assertFalse("同一件事，回看界面问的那句也说没拼",
                RecordSpecs.storedAsGrid(context, CameraSlots.KEY_CABIN_REAR));
    }

    /** 摆得进 2×2 的那一路开着且 grid 开：照旧走 MediaCodec。 */
    @Test
    public void thePlaceableLaneWithGridOnStillReportsGrid() {
        profile.camera(CameraProfile.ROLE_COMPOSITE).enabled = true;
        profile.camera(CameraProfile.ROLE_CABIN_2).enabled = true;
        new ProfileStore(context).save(profile);

        assertTrue(RecordSpecs.anyGridEnabled(context));
        assertTrue("同一件事，回看界面问的那句也说拼",
                RecordSpecs.storedAsGrid(context, CameraSlots.KEY_SURROUND));
    }
}

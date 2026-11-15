/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * {@link AppLog#init(Context)} 一个进程只真跑一次（F-06.03）。
 *
 * <p>装配现在跟着进程（{@link ZeekrShortcutApp#onCreate()}），主界面那一次调用保留但必须空转：
 * 否则每开一次主界面都会把这一趟的会话日志改名成 previous，并往进程上多装一个崩溃处理器。</p>
 *
 * <p>Robolectric 在一个沙箱里为每个用例新建 Application，静态状态却是用例共享的，
 * 所以每个用例自己把 {@code sInitialized} 清成 false 再装回去。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class AppLogInitTest {

    private static final String CURRENT = "current_session.log";
    private static final String PREVIOUS = "previous_session.log";

    private boolean savedInitialized;
    private Context savedContext;

    private Context app;
    private File logDir;
    private File currentLog;
    private File previousLog;

    @Before
    public void setUp() throws Exception {
        savedInitialized = readBoolean("sInitialized");
        savedContext = readContext();
        write("sInitialized", false);
        write("sAppContext", null);

        app = RuntimeEnvironment.getApplication();
        logDir = new File(app.getFilesDir(), "logs");
        logDir.mkdirs();
        currentLog = new File(logDir, CURRENT);
        previousLog = new File(logDir, PREVIOUS);
    }

    @After
    public void tearDown() throws Exception {
        write("sInitialized", savedInitialized);
        write("sAppContext", savedContext);
    }

    @Test
    public void theFirstCallRotatesTheSessionLogAndArmsTheHandler() throws Exception {
        Files.write(currentLog.toPath(), "marker-current\n".getBytes(StandardCharsets.UTF_8));

        AppLog.init(app);

        assertTrue("第一次 init 要把当前日志备份成上次日志", previousLog.isFile());
        assertEquals("marker-current", readLine(previousLog));
        assertFalse("备份走的是改名，当前日志不该还留在原地", currentLog.isFile());
        assertNotNull(Thread.getDefaultUncaughtExceptionHandler());
    }

    @Test
    public void aSecondCallLeavesLogsAndHandlerAlone() throws Exception {
        Files.write(currentLog.toPath(), "marker-first\n".getBytes(StandardCharsets.UTF_8));
        AppLog.init(app);
        assertEquals("marker-first", readLine(previousLog));

        Files.write(currentLog.toPath(), "marker-second\n".getBytes(StandardCharsets.UTF_8));
        Thread.UncaughtExceptionHandler handler = Thread.getDefaultUncaughtExceptionHandler();
        assertNotNull(handler);

        AppLog.init(app);

        assertSame("第二次 init 不该再装一个崩溃处理器", handler,
                Thread.getDefaultUncaughtExceptionHandler());
        assertTrue("第二次 init 不该再轮换日志", currentLog.isFile());
        assertEquals("marker-second", readLine(currentLog));
        assertEquals("marker-first", readLine(previousLog));
    }

    private static String readLine(File file) throws Exception {
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        int newline = text.indexOf('\n');
        return newline >= 0 ? text.substring(0, newline) : text;
    }

    private static boolean readBoolean(String name) throws Exception {
        return field(name).getBoolean(null);
    }

    private static Context readContext() throws Exception {
        return (Context) field("sAppContext").get(null);
    }

    private static void write(String name, Object value) throws Exception {
        field(name).set(null, value);
    }

    private static Field field(String name) throws Exception {
        Field f = AppLog.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}

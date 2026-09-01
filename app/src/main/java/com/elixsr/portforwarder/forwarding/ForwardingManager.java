package com.elixsr.portforwarder.forwarding;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The {@link ForwardingManager} class encapsulates all meta data related to the status of
 * forwarding throughout the application.
 *
 * 使用静态变量保存全局转发状态，视图重新进入时能正确读取
 * 使用 SharedPreferences 持久化状态，进程重启后能恢复
 */
public class ForwardingManager {

    private static ForwardingManager instance = null;

    private static final String PREFS_NAME = "forwarding_prefs";
    private static final String KEY_IS_FORWARDING_ENABLED = "is_forwarding_enabled";

    // 全局静态状态变量
    private static boolean isForwardingEnabled = false;

    private SharedPreferences prefs;

    private ForwardingManager() {
    }

    public static ForwardingManager getInstance() {
        if (instance == null) {
            instance = new ForwardingManager();
        }
        return instance;
    }

    /**
     * 初始化 SharedPreferences，从持久化存储恢复状态
     *
     * @param context 应用上下文
     */
    public void init(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        isForwardingEnabled = prefs.getBoolean(KEY_IS_FORWARDING_ENABLED, false);
    }

    public boolean isEnabled() {
        return isForwardingEnabled;
    }

    protected void enableForwarding() {
        isForwardingEnabled = true;
        if (prefs != null) {
            prefs.edit().putBoolean(KEY_IS_FORWARDING_ENABLED, true).apply();
        }
    }

    protected void disableForwarding() {
        isForwardingEnabled = false;
        if (prefs != null) {
            prefs.edit().putBoolean(KEY_IS_FORWARDING_ENABLED, false).apply();
        }
    }
}

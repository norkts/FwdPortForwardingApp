package com.elixsr.portforwarder.forwarding;

/**
 * The {@link ForwardingManager} class encapsulates all meta data related to the status of
 * forwarding throughout the application.
 *
 * 使用静态变量保存全局转发状态，视图重新进入时能正确读取
 * 状态仅在内存中，进程重启后丢失（符合需求：系统重启后端口转发规则默认不生效）
 */
public class ForwardingManager {

    private static ForwardingManager instance = null;

    // 全局静态状态变量
    private static boolean isForwardingEnabled = false;

    private ForwardingManager() {
    }

    public static ForwardingManager getInstance() {
        if (instance == null) {
            instance = new ForwardingManager();
        }
        return instance;
    }

    public boolean isEnabled() {
        return isForwardingEnabled;
    }

    protected void enableForwarding() {
        isForwardingEnabled = true;
    }

    protected void disableForwarding() {
        isForwardingEnabled = false;
    }
}

package com.elixsr.portforwarder.util;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/**
 * 日志缓冲区管理器
 * 在应用内部维护日志，供日志查看器使用
 */
public class LogBuffer {

    private static final int MAX_LOG_SIZE = 1000; // 最大保存日志条数

    private static LogBuffer instance;
    private LinkedList<LogEntry> logs;
    private SimpleDateFormat dateFormat;

    private LogBuffer() {
        logs = new LinkedList<>();
        dateFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());
    }

    public static synchronized LogBuffer getInstance() {
        if (instance == null) {
            instance = new LogBuffer();
        }
        return instance;
    }

    /**
     * 添加日志
     */
    public void addLog(String tag, String level, String message) {
        LogEntry entry = new LogEntry();
        entry.timestamp = dateFormat.format(new Date());
        entry.tag = tag;
        entry.level = level;
        entry.message = message;

        synchronized (logs) {
            logs.addLast(entry);
            while (logs.size() > MAX_LOG_SIZE) {
                logs.removeFirst();
            }
        }
    }

    /**
     * 获取所有日志
     */
    public String getAllLogs() {
        StringBuilder sb = new StringBuilder();
        synchronized (logs) {
            for (LogEntry entry : logs) {
                sb.append(entry.toString()).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 获取最新的 N 条日志
     */
    public String getRecentLogs(int count) {
        StringBuilder sb = new StringBuilder();
        synchronized (logs) {
            int start = Math.max(0, logs.size() - count);
            for (int i = start; i < logs.size(); i++) {
                sb.append(logs.get(i).toString()).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 清空日志
     */
    public void clear() {
        synchronized (logs) {
            logs.clear();
        }
    }

    /**
     * 获取日志条数
     */
    public int size() {
        synchronized (logs) {
            return logs.size();
        }
    }

    /**
     * 日志条目
     */
    private static class LogEntry {
        String timestamp;
        String tag;
        String level;
        String message;

        @Override
        public String toString() {
            return String.format("%s %s/%s: %s", timestamp, level, tag, message);
        }
    }

    // 便捷方法
    public void d(String tag, String message) {
        addLog(tag, "D", message);
    }

    public void i(String tag, String message) {
        addLog(tag, "I", message);
    }

    public void w(String tag, String message) {
        addLog(tag, "W", message);
    }

    public void e(String tag, String message) {
        addLog(tag, "E", message);
    }

    public void e(String tag, String message, Throwable throwable) {
        addLog(tag, "E", message + "\n" + throwable.toString());
    }
}

package com.elixsr.portforwarder.util;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import com.elixsr.portforwarder.R;

/**
 * 日志查看器管理器
 * 用于在应用内查看实时日志
 */
public class LogViewerManager {

    private static final long REFRESH_INTERVAL = 1000; // 1秒刷新一次

    private Context context;
    private Dialog dialog;
    private TextView logTextView;
    private ScrollView scrollView;
    private Button autoScrollButton;
    private Handler handler;
    private boolean isAutoScroll = true;
    private boolean isRunning = false;
    private int lastLineCount = 0;
    private LogBuffer logBuffer;

    public LogViewerManager(Context context) {
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());
        this.logBuffer = LogBuffer.getInstance();
    }

    /**
     * 显示日志查看对话框
     */
    public void showLogDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        View view = View.inflate(context, R.layout.dialog_log_viewer, null);

        logTextView = view.findViewById(R.id.logTextView);
        scrollView = view.findViewById(R.id.logScrollView);
        autoScrollButton = view.findViewById(R.id.btnAutoScroll);
        Button refreshButton = view.findViewById(R.id.btnRefresh);
        Button clearButton = view.findViewById(R.id.btnClear);

        // 刷新按钮
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshLogs();
            }
        });

        // 清空按钮
        clearButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                clearLogs();
            }
        });

        // 自动滚动按钮
        autoScrollButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isAutoScroll = !isAutoScroll;
                autoScrollButton.setText("自动滚动: " + (isAutoScroll ? "开" : "关"));
                if (isAutoScroll) {
                    scrollToBottom();
                }
            }
        });

        builder.setView(view);
        builder.setTitle("实时日志");
        builder.setPositiveButton("关闭", null);

        dialog = builder.create();
        dialog.show();

        // 开始自动刷新日志
        startAutoRefresh();
    }

    /**
     * 开始自动刷新日志
     */
    private void startAutoRefresh() {
        isRunning = true;
        refreshLogs(); // 立即刷新一次

        // 定期刷新
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isRunning && dialog != null && dialog.isShowing()) {
                    refreshLogs();
                    handler.postDelayed(this, REFRESH_INTERVAL);
                }
            }
        }, REFRESH_INTERVAL);
    }

    /**
     * 停止自动刷新
     */
    public void stopAutoRefresh() {
        isRunning = false;
        handler.removeCallbacksAndMessages(null);
    }

    /**
     * 刷新日志 - 从 LogBuffer 读取
     */
    private void refreshLogs() {
        final String logText = logBuffer.getAllLogs();

        handler.post(new Runnable() {
            @Override
            public void run() {
                if (logTextView != null) {
                    if (logText.isEmpty()) {
                        logTextView.setText("暂无日志\n\n提示：\n1. 启动转发服务会产生日志\n2. 日志会自动更新\n3. 可点击'刷新'手动更新");
                    } else {
                        logTextView.setText(logText);
                    }

                    // 如果有新日志且开启自动滚动，则滚动到底部
                    int lineCount = logText.isEmpty() ? 0 : logText.split("\n").length;
                    if (isAutoScroll && lineCount > lastLineCount) {
                        scrollToBottom();
                    }
                    lastLineCount = lineCount;
                }
            }
        });
    }

    /**
     * 清空日志显示
     */
    private void clearLogs() {
        if (logTextView != null) {
            logTextView.setText("");
            lastLineCount = 0;
            logBuffer.clear();
        }
    }

    /**
     * 滚动到底部
     */
    private void scrollToBottom() {
        if (scrollView != null) {
            scrollView.post(new Runnable() {
                @Override
                public void run() {
                    scrollView.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    }

    /**
     * 关闭对话框
     */
    public void dismiss() {
        stopAutoRefresh();
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }
}

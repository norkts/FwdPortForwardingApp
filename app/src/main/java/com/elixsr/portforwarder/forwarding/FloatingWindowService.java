package com.elixsr.portforwarder.forwarding;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;import android.provider.Settings;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import com.elixsr.portforwarder.R;
import com.elixsr.portforwarder.ui.MainActivity;

public class FloatingWindowService extends Service {

    private static final String CHANNEL_ID = "floating_window_channel";
    private static final int NOTIFICATION_ID = 2;
    private static final String TAG = "FloatingWindowService";    private static final String BROADCAST_ACTION = "com.elixsr.portforwarder.forwarding.ForwardingService.BROADCAST";

    private WindowManager windowManager;
    private View floatingView;
    private WindowManager.LayoutParams params;
    private BroadcastReceiver stateReceiver;
    private boolean isFloatingViewAdded = false;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        addFloatingBall();
        registerStateReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        removeFloatingBall();
        unregisterStateReceiver();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void addFloatingBall() {
        // 检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "悬浮窗权限未授予，跳过添加悬浮球");
            return;
        }

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_window, null);

        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        params = new WindowManager.LayoutParams(
                150,
                150,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 100;

        setupTouchListener();
        windowManager.addView(floatingView, params);
        isFloatingViewAdded = true;

        boolean isEnabled = ForwardingManager.getInstance().isEnabled();
        updateFloatingBallState(isEnabled);
    }

    private void setupTouchListener() {
        floatingView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private boolean isDragging = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isDragging = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float deltaX = event.getRawX() - initialTouchX;
                        float deltaY = event.getRawY() - initialTouchY;

                        if (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10) {
                            isDragging = true;
                        }

                        params.x = initialX + (int) deltaX;
                        params.y = initialY + (int) deltaY;
                        windowManager.updateViewLayout(floatingView, params);
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (!isDragging) {
                            onFloatingBallClick();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void onFloatingBallClick() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void removeFloatingBall() {
        if (floatingView != null && isFloatingViewAdded) {
            windowManager.removeView(floatingView);
            isFloatingViewAdded = false;
        }
    }

    private void registerStateReceiver() {
        stateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean isEnabled = intent.getBooleanExtra("PORT_FORWARD_SERVICE_STATE", false);
                updateFloatingBallState(isEnabled);
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(stateReceiver, new IntentFilter(BROADCAST_ACTION));
    }

    private void unregisterStateReceiver() {
        if (stateReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver);
            stateReceiver = null;
        }
    }

    private void updateFloatingBallState(boolean isEnabled) {
        if (floatingView == null) return;

        ImageView icon = floatingView.findViewById(R.id.floating_icon);
        GradientDrawable background = (GradientDrawable) floatingView.getBackground();

        if (isEnabled) {
            background.setColor(0xCC2196F3);  // 蓝色
            icon.setImageResource(R.drawable.ic_fwd_24dp);
        } else {
            background.setColor(0xCC9E9E9E);  // 灰色
            icon.setImageResource(R.drawable.ic_fwd_24dp);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "悬浮窗服务",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("显示转发服务状态");
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("端口转发服务")
                .setContentText("悬浮窗已启用")
                .setSmallIcon(R.drawable.ic_fwd_24dp)
                .setContentIntent(pendingIntent)
                .build();
    }
}

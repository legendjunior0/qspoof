package com.antutu.ABenchMark;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;
import android.content.pm.ServiceInfo;
import android.os.Build;

import java.lang.ref.WeakReference;
import java.util.Objects;

public final class QEnginePerformanceService extends Service {

    public static final int $stable = 8;

    public static final String ACTION_START_SERVICE =
            "com.antutu.ABenchMark.ACTION_START";
    public static final String ACTION_STOP_SERVICE =
            "com.antutu.ABenchMark.ACTION_STOP";
    public static final String CHANNEL_ID = "iqoo_performance_service_channel";
    public static final int NOTIFICATION_ID = 1001;
    public static final Companion Companion = new Companion();

    private static WeakReference<QEnginePerformanceService> instance;
    private static boolean isRunning;

    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private final heartbeatRunnable$1 heartbeatRunnable = new heartbeatRunnable$1();

    private WindowManager.LayoutParams overlayParams;
    private View overlayView;
    private WindowManager windowManager;

    public QEnginePerformanceService() {
        super();
    }

    @Override
    public void onCreate() {
        super.onCreate();

        instance = new WeakReference<>(this);

        createNotificationChannel();
        attachHardwareAcceleratedOverlay();
        startHeartbeatPulse();

        isRunning = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP_SERVICE.equals(action)) {
            stopForegroundService();
            return START_NOT_STICKY;
        }

        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );
            return START_STICKY;
        }
        startForeground(NOTIFICATION_ID, notification);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        detachOverlay();
        instance = null;
        isRunning = false;
        super.onDestroy();
    }

    private void attachHardwareAcceleratedOverlay() {
        try {
            if (overlayView != null) {
                return;
            }

            windowManager = (WindowManager) getSystemService("window");

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    2,
                    2,
                    2038,
                    16777496,
                    PixelFormat.TRANSLUCENT
            );

            params.gravity = 8388659;
            params.x = 0;
            params.y = 0;
            params.alpha = 0.02f;
            overlayParams = params;

            View view = new View(this);
            view.setAlpha(0.02f);
            view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            overlayView = view;

            if (windowManager != null) {
                windowManager.addView(view, params);
            }
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }

    private void detachOverlay() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable);

        try {
            if (overlayView != null && windowManager != null) {
                windowManager.removeView(overlayView);
            }
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }

        overlayView = null;
    }

    private void startHeartbeatPulse() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable);
        heartbeatHandler.postDelayed(heartbeatRunnable, 3000L);
    }

    private void stopForegroundService() {
        detachOverlay();
        stopForeground(1);
        stopSelf();

        instance = null;
        isRunning = false;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.qengine_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Notification channel for Performance Service");
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, QEnginePerformanceService.class);
        stopIntent.setAction(ACTION_STOP_SERVICE);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                0,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent contentIntent = new Intent(this, QEngineLauncherActivity.class);
        PendingIntent contentPendingIntent = PendingIntent.getActivity(
                this,
                0,
                contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setWhen(System.currentTimeMillis())
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.qengine_notification_title))
                .setContentText(getString(R.string.qengine_notification_text))
                .setContentIntent(contentPendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(
                        R.mipmap.ic_launcher,
                        getString(R.string.qengine_stop_action),
                        stopPendingIntent
                )
                .build();
    }

    private final class heartbeatRunnable$1 implements Runnable {
        @Override
        public void run() {
            if (isRunning) {
                if (overlayView != null) {
                    overlayView.invalidate();
                }

                heartbeatHandler.postDelayed(this, 3000L);
            }
        }
    }

    public static final class Companion {

        private Companion() {
        }

        public final boolean isRunning() {
            return QEnginePerformanceService.isRunning;
        }

        public final void startService(Context context) {
            Objects.requireNonNull(context, "context");

            Intent intent = new Intent(context, QEnginePerformanceService.class);
            intent.setAction(ACTION_START_SERVICE);
            context.startForegroundService(intent);
        }

        public final void stopService(Context context) {
            Objects.requireNonNull(context, "context");

            Intent intent = new Intent(context, QEnginePerformanceService.class);
            intent.setAction(ACTION_STOP_SERVICE);
            context.startService(intent);
        }
    }
}

/*
 * Fwd: the port forwarding app
 * Copyright (C) 2016  Elixsr Ltd
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.elixsr.portforwarder.forwarding;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.TaskStackBuilder;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import com.elixsr.portforwarder.util.LogBuffer;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.elixsr.portforwarder.FwdApplication;
import com.elixsr.portforwarder.ui.MainActivity;
import com.elixsr.portforwarder.R;
import com.elixsr.portforwarder.dao.RuleDao;
import com.elixsr.portforwarder.db.RuleDbHelper;
import com.elixsr.portforwarder.exceptions.ObjectNotFoundException;
import com.elixsr.portforwarder.models.RuleModel;

/**
 * The {@link ForwardingService} class acts as a controller of all all forwarding.
 * <p>
 * The class is responsible for starting forwarding for all rules found within the SQLite database.
 * <p>
 * The class creates a new thread for each Forwarding rule.
 */
public class ForwardingService extends Service {

    // Defines a custom Intent action
    public static final String BROADCAST_ACTION =
            "com.elixsr.portforwarder.forwarding.ForwardingService.BROADCAST";

    // Defines the key for the status "extra" in an Intent
    public static final String EXTENDED_DATA_STATUS =
            "com.elixsr.portforwarder.forwarding.ForwardingService.STATUS";

    public static final String PORT_FORWARD_SERVICE_STATE =
            "com.elixsr.portforwarder.forwarding.ForwardingService.PORT_FORWARD_STATE";

    public static final String PORT_FORWARD_SERVICE_ERROR_MESSAGE =
            "com.elixsr.portforwarder.forwarding.ForwardingService.PORT_FORWARD_ERROR_MESSAGE";

    private static final String PORT_FORWARD_SERVICE_WAKE_LOCK_TAG = "PortForwardServiceWakeLockTag";

    private static final String TAG = "ForwardingService";

    private static final String CATEGORY_FORWARDING = "Forwarding";

    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "forwarding_channel";
    private static final String ACTION_START_FORWARDING = "Start - Java NIO";
    private static final String LABEL_FORWARDING_TYPE = "";
    private static final String ACTION_STOP_FORWARDING = "Stop - Java NIO";

    private String status = "Test";

    private boolean runService = false;

    //change the magic number
    private ExecutorService executorService;

    //wake lock
    private PowerManager.WakeLock wakeLock;
    
    /**
     * Default constructor for {@link ForwardingService}.#
     * <p>
     * Creates a new instance of ForwardingService and initialises an {@link ExecutorService}
     * with a fixed thread pool of 30 threads.
     */
    public ForwardingService() {
        executorService = Executors.newFixedThreadPool(30);
    }

    public ForwardingService(ExecutorService executorService) {
        this.executorService = executorService;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                PORT_FORWARD_SERVICE_WAKE_LOCK_TAG);
        wakeLock.acquire();

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());

        startFloatingWindow();
    }

    /**
     * Starts forwarding based on rules found in database.
     *
     * @param intent
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        LogBuffer.getInstance().i(TAG, "========== FORWARDING SERVICE START ==========");
        LogBuffer.getInstance().i(TAG, "Ran the service");

        ForwardingManager.getInstance().enableForwarding();

        runService = true;

        Intent localIntent =
                new Intent(BROADCAST_ACTION)
                        .putExtra(PORT_FORWARD_SERVICE_STATE, ForwardingManager.getInstance().isEnabled());
        LocalBroadcastManager.getInstance(this).sendBroadcast(localIntent);

        showForwardingEnabledNotification();

        // 在后台线程执行阻塞操作，避免 ANR
        new Thread(new Runnable() {
            @Override
            public void run() {
                startForwardingRules();
            }
        }).start();

        return START_STICKY;
    }

    private void startForwardingRules() {
        RuleDao ruleDao = new RuleDao(new RuleDbHelper(this));
        List<RuleModel> ruleModels = ruleDao.getAllEnabledRuleModels();
        LogBuffer.getInstance().i(TAG, "Loaded " + ruleModels.size() + " forwarding rules");

        List<Forwarder> ruleModelForwarders = new ArrayList<>();

        InetSocketAddress from;

        for (RuleModel ruleModel : ruleModels) {
            if (!runService) {
                break;
            }

            try {
                from = generateFromIpUsingInterface(ruleModel.getFromInterfaceName(), ruleModel.getFromPort());
                LogBuffer.getInstance().i(TAG, "Rule '" + ruleModel.getName() + "': from " + from.getPort() + " to " + ruleModel.getTarget().getPort());

                if (ruleModel.isTcp() && runService) {
                    LogBuffer.getInstance().i(TAG, "  -> TCP forwarder");
                    ruleModelForwarders.add(new TcpForwarder(from, ruleModel.getTarget(), ruleModel.getName()));
                }

                if (ruleModel.isUdp() && runService) {
                    LogBuffer.getInstance().i(TAG, "  -> UDP forwarder");
                    ruleModelForwarders.add(new UdpForwarder(from, ruleModel.getTarget(), ruleModel.getName()));
                }

            } catch (SocketException | ObjectNotFoundException e) {
                LogBuffer.getInstance().e(TAG, "Error generating IP Address for FROM interface with rule '" + ruleModel.getName() + "'", e);

                Intent localIntent =
                        new Intent(BROADCAST_ACTION)
                                .putExtra(PORT_FORWARD_SERVICE_ERROR_MESSAGE, getString(R.string.start_rule_error_message) + " '" + ruleModel.getName() + "'");
                LocalBroadcastManager.getInstance(this).sendBroadcast(localIntent);
            }
        }

        executorService = Executors.newFixedThreadPool(ruleModelForwarders.size());

        CompletionService<Void> completionService =
                new ExecutorCompletionService<>(executorService);

        for (Forwarder ruleForwarder: ruleModelForwarders) {
            completionService.submit(ruleForwarder);
        }

        int remainingFutures = ruleModelForwarders.size();
        Future<?> completedFuture;

        while (remainingFutures > 0 && runService) {
            try {
                completedFuture = completionService.take();
                remainingFutures--;

                completedFuture.get();
            } catch (ExecutionException e) {
                LogBuffer.getInstance().e(TAG, "Error when forwarding port.", e);
                Intent localIntent =
                        new Intent(BROADCAST_ACTION)
                                .putExtra(PORT_FORWARD_SERVICE_ERROR_MESSAGE, e.getCause().getMessage());
                LocalBroadcastManager.getInstance(this).sendBroadcast(localIntent);

                break;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private void startFloatingWindow() {
        Intent intent = new Intent(this, FloatingWindowService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void stopFloatingWindow() {
        Intent intent = new Intent(this, FloatingWindowService.class);
        stopService(intent);
    }

    private InetSocketAddress generateFromIpUsingInterface(String interfaceName, int port) throws SocketException, ObjectNotFoundException {

        String address = null;
        InetSocketAddress inetSocketAddress;

        for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
            NetworkInterface intf = en.nextElement();

            LogBuffer.getInstance().d(TAG, intf.getDisplayName() + " vs " + interfaceName);
            if (intf.getDisplayName().equals(interfaceName)) {

                LogBuffer.getInstance().i(TAG, "Found the relevant Interface. Will attempt to fetch IP Address");

                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {

                    InetAddress inetAddress = enumIpAddr.nextElement();

                    address = new String(inetAddress.getHostAddress().toString());

                    if (address != null & address.length() > 0 && inetAddress instanceof Inet4Address) {

                        inetSocketAddress = new InetSocketAddress(address, port);
                        return inetSocketAddress;
                    }
                }
            }
        }

        //Failed to find the relevant interface
        //TODO: complete
//        Toast.makeText(this, "Could not find relevant network interface.",
//                Toast.LENGTH_LONG).show();
        throw new ObjectNotFoundException("Could not find IP Address for Interface " + interfaceName);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        LogBuffer.getInstance().i(TAG, "onTaskRemoved: called");

        // Build and send an Event.
        

        this.onDestroy();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        runService = false;

        stopFloatingWindow();

        executorService.shutdown();

        try {
            // Shutdown any existing tasks
            executorService.shutdownNow();
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                LogBuffer.getInstance().e(TAG, "onDestroy: Pool did not terminate");
            }
        } catch (InterruptedException ie) {
            // (Re-)Cancel if current thread also interrupted
            executorService.shutdownNow();
            // Preserve interrupt status
            Thread.currentThread().interrupt();
        }

        // 关闭所有打开的端口
        Forwarder.closeAllResources();        ForwardingManager.getInstance().disableForwarding();

        hideForwardingEnabledNotification();

        LogBuffer.getInstance().i(TAG, "========== FORWARDING SERVICE STOP ==========");
        
        //update the main activity
        Intent localIntent =
                new Intent(BROADCAST_ACTION)
                        // Puts the status into the Intent
                        .putExtra(PORT_FORWARD_SERVICE_STATE, ForwardingManager.getInstance().isEnabled());
        // Broadcasts the Intent to receivers in this app.
        LocalBroadcastManager.getInstance(this).sendBroadcast(localIntent);

        wakeLock.release();

        // Build and send an Event.
        
        LogBuffer.getInstance().i(TAG, "Ended the ForwardingService. Cleanup finished.");
    }

    private void hideForwardingEnabledNotification() {

        NotificationManager mNotificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        mNotificationManager.cancel(NOTIFICATION_ID);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Forwarding Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows when port forwarding is active");

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        NotificationCompat.Builder mBuilder =
                new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_fwd_24dp)
                        .setContentTitle(getString(R.string.notification_forwarding_active_title))
                        .setContentText(getString(R.string.notification_forwarding_touch_disable_text));

        mBuilder.setColor(ContextCompat.getColor(this, R.color.colorPrimaryDark));

        Intent resultIntent = new Intent(this, MainActivity.class);

        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
        stackBuilder.addParentStack(MainActivity.class);
        stackBuilder.addNextIntent(resultIntent);
        PendingIntent resultPendingIntent =
                stackBuilder.getPendingIntent(
                        0,
                        PendingIntent.FLAG_UPDATE_CURRENT
                );
        mBuilder.setContentIntent(resultPendingIntent);

        Notification notification = mBuilder.build();
        notification.flags = Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT | Notification.DEFAULT_LIGHTS;

        return notification;
    }

    private void showForwardingEnabledNotification() {
        createNotificationChannel();
        Notification notification = createNotification();

        NotificationManager mNotificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.notify(NOTIFICATION_ID, notification);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

package ru.ryadom.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Build
import androidx.core.app.NotificationCompat

class CallService : Service() {
    private val controller get() = (application as RyadomApplication).controller
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == STOP_SCREEN) {
            controller.stopSharing()
            if (!controller.state.value.connected) stopSelf()
            return START_NOT_STICKY
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(101, notification(this, false), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(101, notification(this, false))
        }
        if (intent?.action == CONNECT) controller.connect(
            intent.getStringExtra("link").orEmpty(), intent.getStringExtra("name").orEmpty(), intent.getBooleanExtra("create", false))
        if (!controller.state.value.connected && !controller.state.value.connecting) stopSelf()
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        if (controller.room != null || controller.state.value.connected || controller.state.value.connecting) controller.disconnect()
        super.onDestroy()
    }
    override fun onTaskRemoved(rootIntent: Intent?) { stopSelf() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val CONNECT = "ru.ryadom.CONNECT"
        const val STOP = "ru.ryadom.STOP"
        const val STOP_SCREEN = "ru.ryadom.STOP_SCREEN"
        fun notification(context: Context, screen: Boolean): Notification {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("call", "Совместный просмотр", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(context, if (screen) 2 else 1,
                Intent(context, CallService::class.java).setAction(if (screen) STOP_SCREEN else STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return NotificationCompat.Builder(context, "call").setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(if (screen) "Передаём экран" else "Вы рядом")
                .setContentText(if (screen) "Экран и звук передаются партнёру" else "Голосовой сеанс активен")
                .setContentIntent(open).setOngoing(true).setSilent(true)
                .addAction(0, if (screen) "Остановить показ" else "Завершить", stop).build()
        }
    }
}

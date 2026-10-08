package ru.ryadom.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private val controller get() = (application as RyadomApplication).controller
    private var incoming by mutableStateOf("")
    private var captureRequest: Long? = null
    private var pendingConnect: (() -> Unit)? = null
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) pendingConnect?.invoke()
        else controller.error("Без разрешения микрофона сеанс не начнётся")
        pendingConnect = null
    }
    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        controller.screenPermissionResult(if (it.resultCode == Activity.RESULT_OK) it.data else null, captureRequest)
        captureRequest = null
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        incoming = intent?.dataString.orEmpty()
        captureRequest = savedInstanceState?.takeIf { it.containsKey("captureRequest") }?.getLong("captureRequest")
        setContent { RyadomUi(controller, incoming, ::connect, ::shareScreen, ::shareInvite, ::leave) }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); incoming = intent.dataString.orEmpty() }
    override fun onSaveInstanceState(outState: Bundle) {
        captureRequest?.let { outState.putLong("captureRequest", it) }
        super.onSaveInstanceState(outState)
    }
    private fun connect(link: String, name: String, create: Boolean) {
        pendingConnect = {
            try {
                startForegroundService(Intent(this, CallService::class.java).setAction(CallService.CONNECT)
                    .putExtra("link", link).putExtra("name", name).putExtra("create", create))
            } catch (_: Exception) { controller.error("Не удалось начать сеанс. Открой приложение и попробуй ещё раз.") }
        }
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 31) permissions += Manifest.permission.BLUETOOTH_CONNECT
        permission.launch(permissions.toTypedArray())
    }
    private fun shareScreen() {
        val requestId = controller.requestScreenPermission()
        if (requestId != null) {
            captureRequest = requestId
            try { projection.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()) }
            catch (_: Exception) { controller.screenPermissionResult(null, requestId); captureRequest = null; controller.error("Не удалось открыть разрешение на показ экрана") }
        }
    }
    private fun shareInvite() {
        val invite = controller.state.value.invite ?: return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TITLE, "Давай посмотрим вместе 💜")
            .putExtra(Intent.EXTRA_TEXT, invite.toLink()), "Пригласить"))
    }
    private fun leave() { controller.disconnect(); stopService(Intent(this, CallService::class.java)) }
}

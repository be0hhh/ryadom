package ru.ryadom.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import livekit.org.webrtc.RendererCommon

private val Lavender = Color(0xFFC8B6FF)
private val Rose = Color(0xFFF3B5CB)
private val Ink = Color(0xFF121119)
private val Card = Color(0xFF211F2D)

@Composable
fun RyadomUi(controller: CallController, incoming: String,
    onConnect: (String, String, Boolean) -> Unit, onScreen: () -> Unit,
    onInvite: () -> Unit, onLeave: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = darkColorScheme(primary = Lavender, secondary = Rose,
        background = Ink, surface = Card, onPrimary = Ink, onSurface = Color(0xFFF7F3FF))) {
        Surface(modifier = Modifier.fillMaxSize(), color = Ink) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                state.error?.let { message ->
                    Surface(color = Color(0xFF4B2636), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(message, modifier = Modifier.weight(1f), fontSize = 13.sp)
                            IconButton(onClick = { controller.error(null) }) { Icon(Icons.Default.Close, "Закрыть сообщение") }
                        }
                    }
                }
                if (!state.connected && !state.connecting) Home(controller, incoming, onConnect)
                else Session(controller, state, onScreen, onInvite, onLeave)
            }
        }
    }
}

@Composable
private fun Home(controller: CallController, incoming: String, onConnect: (String, String, Boolean) -> Unit) {
    var name by rememberSaveable { mutableStateOf(controller.savedName) }
    var link by rememberSaveable { mutableStateOf(controller.savedSetup) }
    var showLink by remember { mutableStateOf(false) }
    LaunchedEffect(incoming) { if (incoming.isNotEmpty()) link = incoming }
    val invitation = remember(link) { Invitation.parse(link) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Spacer(Modifier.height(40.dp)) }
        item {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(25.dp)).background(Brush.linearGradient(listOf(Lavender, Rose))), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Favorite, null, tint = Ink, modifier = Modifier.size(36.dp))
            }
        }
        item {
            Text("Рядом", fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Твои видео. Ваш вечер.", fontSize = 20.sp, color = Lavender)
            Spacer(Modifier.height(10.dp))
            Text("Листайте TikTok, разговаривайте и делитесь тем, что хочется посмотреть вместе.", color = Color(0xFFB7B0C6), lineHeight = 23.sp)
        }
        item { OutlinedTextField(name, { if (it.length <= 40) name = it }, label = { Text("Твоё имя") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item {
            OutlinedTextField(link, { if (it.length <= 8192) link = it.trim() }, label = { Text("Приглашение или настройка сервера") },
                visualTransformation = if (showLink) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { IconButton(onClick = { showLink = !showLink }) { Icon(if (showLink) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Показать или скрыть ссылку") } },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(when { invitation?.isSetup == true -> "Сервер настроен — можно создать комнату"
                invitation != null -> "Приглашение готово — осталось войти"
                link.isEmpty() -> "Вставь ссылку от партнёра или из настройки твоего ПК"
                else -> "Ссылка должна начинаться с ryadom://" }, fontSize = 12.sp, color = Color(0xFFB7B0C6))
        }
        item {
            Button(onClick = { onConnect(link, name, true) }, enabled = invitation?.isSetup == true && name.isNotBlank(), modifier = Modifier.fillMaxWidth().height(54.dp)) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Создать комнату")
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { onConnect(link, name, false) }, enabled = invitation?.isSetup == false && name.isNotBlank(), modifier = Modifier.fillMaxWidth().height(54.dp)) {
                Text("Войти по приглашению")
            }
        }
        item { Text("Перед входом включи Tailscale.\nВаш сервер на ПК должен быть запущен.", fontSize = 12.sp, lineHeight = 19.sp, color = Color(0xFF898198)); Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Session(controller: CallController, state: CallUiState,
    onScreen: () -> Unit, onInvite: () -> Unit, onLeave: () -> Unit) {
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    if (fullscreen && state.videoTrack != null && controller.room != null) {
        Box(Modifier.fillMaxSize()) {
            RemoteVideo(controller.room!!, state.videoTrack, Modifier.fillMaxSize())
            FilledIconButton(onClick = { fullscreen = false }, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                Icon(Icons.Default.FullscreenExit, "Вернуться к чату")
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Рядом", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(when { state.connecting -> "Подключаемся…"; state.reconnecting -> "Восстанавливаем связь…"
                    state.partner != null -> "С тобой ${state.partner}"; else -> "Ждём второго участника" }, fontSize = 12.sp, color = Rose)
            }
            IconButton(onClick = onInvite, enabled = state.connected) { Icon(Icons.Default.PersonAdd, "Пригласить партнёра") }
            IconButton(onClick = onLeave) { Icon(Icons.Default.CallEnd, "Завершить сеанс", tint = Rose) }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Card), contentAlignment = Alignment.Center) {
            if (state.videoTrack != null && controller.room != null) {
                RemoteVideo(controller.room!!, state.videoTrack, Modifier.fillMaxSize())
                IconButton(onClick = { fullscreen = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                    Icon(Icons.Default.Fullscreen, "На весь экран")
                }
            } else Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (state.connecting) CircularProgressIndicator(Modifier.size(36.dp))
                else Icon(if (state.sharing) Icons.Default.ScreenShare else Icons.Default.FavoriteBorder, null, tint = Lavender, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(16.dp))
                Text(if (state.sharing) "Теперь открой TikTok" else "Посмотрим вместе?", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                Spacer(Modifier.height(8.dp))
                Text(if (state.sharing) "Партнёр видит твой экран.\nГолос продолжит работать." else "Нажми «Показать экран»\nили дождись показа партнёра.", color = Color(0xFFB7B0C6), fontSize = 14.sp)
            }
        }
        if (state.sharing) {
            Text(if (state.playbackSignal) "● Сигнал звука: есть" else "○ Сигнал звука: пока нет — включи ролик",
                fontSize = 12.sp, color = if (state.playbackSignal) Color(0xFFA2DDB6) else Rose, modifier = Modifier.padding(top = 8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = controller::toggleMicrophone, enabled = state.connected, modifier = Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) {
                Icon(if (state.microphone) Icons.Default.Mic else Icons.Default.MicOff, "Микрофон")
            }
            Button(onClick = { if (state.sharing) controller.stopSharing() else onScreen() }, enabled = state.connected && !state.reconnecting && !state.busySharing,
                modifier = Modifier.weight(2.6f), contentPadding = PaddingValues(8.dp)) {
                Icon(if (state.sharing) Icons.Default.StopScreenShare else Icons.Default.ScreenShare, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text(if (state.busySharing) "Подготовка…" else if (state.sharing) "Остановить" else "Показать экран", fontSize = 13.sp)
            }
            OutlinedButton(onClick = controller::togglePlayback, enabled = state.sharing, modifier = Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) {
                Icon(if (state.playback) Icons.Default.VolumeUp else Icons.Default.VolumeOff, "Звук роликов")
            }
        }
        Chat(state, controller::sendMessage)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Chat(state: CallUiState, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size) { if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex) }
    LazyColumn(Modifier.fillMaxWidth().height(130.dp), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.messages.isEmpty()) item { Text("Здесь можно шепнуть что-нибудь милое 💜", fontSize = 13.sp, color = Color(0xFF898198), modifier = Modifier.padding(vertical = 12.dp)) }
        items(state.messages, key = { it.id }) { message ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.mine) Arrangement.End else Arrangement.Start) {
                Surface(color = if (message.mine) Color(0xFF3B3155) else Card, shape = RoundedCornerShape(14.dp), modifier = Modifier.widthIn(max = 280.dp)) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(if (message.mine) "Ты" else message.name, fontSize = 10.sp, color = Rose)
                        Text(message.text, fontSize = 14.sp)
                    }
                }
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(text, { if (it.length <= 2000) text = it }, placeholder = { Text("Сообщение…", fontSize = 14.sp) }, maxLines = 3, modifier = Modifier.weight(1f))
        FilledIconButton(onClick = { onSend(text); text = "" }, enabled = state.connected && text.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, "Отправить") }
    }
}

@Composable
private fun RemoteVideo(room: Room, track: VideoTrack, modifier: Modifier) {
    val context = LocalContext.current
    val renderer = remember(room) { TextureViewRenderer(context).also {
        room.initVideoRenderer(it)
        it.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
    } }
    DisposableEffect(renderer) { onDispose { renderer.release() } }
    DisposableEffect(track, renderer) { track.addRenderer(renderer); onDispose { track.removeRenderer(renderer) } }
    AndroidView(factory = { renderer }, modifier = modifier)
}

package dev.kunnect

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val Leaf = Color(0xFFD6F4A9)
private val Ink = Color(0xFF243A15)
private val Green = Color(0xFF44651D)
private val shapes = Shapes(small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(36.dp))

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var showBatteryPrompt by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TeatherApp() }
    }
    fun connect() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        startForegroundService(Intent(this, ConnectionService::class.java))
        val prefs = Preferences(this)
        if (!prefs.batteryPromptSeen && !getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) showBatteryPrompt = true
    }
    fun requestBatteryExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }.onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

private object Glyphs {
    private fun vector(name: String, data: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(PathParser().parsePathString(data).toNodes(), fill = androidx.compose.ui.graphics.SolidColor(Color.Black)).build()
    val Link = vector("Connection", "M3.9,12A4.1,4.1 0,0 1,8 7.9H11V6H8A6,6 0,0 0,8 18H11V16.1H8A4.1,4.1 0,0 1,3.9 12M8,13H16V11H8ZM16,6H13V7.9H16A4.1,4.1 0,0 1,16 16.1H13V18H16A6,6 0,0 0,16 6")
    val Folder = vector("Folder", "M10,4H2V20H22V6H12ZM4,8H20V18H4Z")
    val Apps = vector("Apps", "M3,3H10V10H3ZM14,3H21V10H14ZM3,14H10V21H3ZM14,14H21V21H14Z")
    val Tune = vector("Settings", "M4,5H20V7H4ZM4,11H20V13H4ZM4,17H20V19H4ZM7,3H10V9H7ZM14,9H17V15H14ZM8,15H11V21H8Z")
    val Check = vector("Check", "M9,16.2L4.8,12L3.4,13.4L9,19L21,7L19.6,5.6Z")
    val Arrow = vector("Arrow", "M12,4L10.6,5.4L16.2,11H4V13H16.2L10.6,18.6L12,20L20,12Z")
    val Bell = vector("Notifications", "M12,22A2,2 0,0 0,14 20H10A2,2 0,0 0,12 22M18,16V10A6,6 0,0 0,13 4.1V2H11V4.1A6,6 0,0 0,6 10V16L4,18V19H20V18ZM8,17V10A4,4 0,0 1,16 10V17Z")
    val Shield = vector("Private", "M12,2L3,6V12C3,17 7,21 12,23C17,21 21,17 21,12V6ZM12,4.2L19,7.3V12C19,15.8 16,19 12,20.8C8,19 5,15.8 5,12V7.3Z")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TeatherApp() {
    val context = LocalActivity.current as MainActivity
    val prefs = remember { Preferences(context) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var pairing by rememberSaveable { mutableStateOf(false) }
    var settingsVersion by remember { mutableIntStateOf(0) }
    var dynamic by remember { mutableStateOf(prefs.dynamicColor) }
    var access by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) { access = context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(context, NotificationBridge::class.java)); if (access && BridgeState.listener == null) android.service.notification.NotificationListenerService.requestRebind(ComponentName(context, NotificationBridge::class.java)); settingsVersion++ } }
        context.lifecycle.addObserver(observer)
        onDispose { context.lifecycle.removeObserver(observer) }
    }
    val dark = isSystemInDarkTheme()
    val colors = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Leaf, onPrimary = Ink, primaryContainer = Color(0xFF344D1E), secondaryContainer = Color(0xFF303C28), background = Color(0xFF12150F), surface = Color(0xFF12150F), surfaceContainer = Color(0xFF20251B), surfaceContainerLow = Color(0xFF1B2017))
        else -> lightColorScheme(primary = Green, onPrimary = Color.White, primaryContainer = Leaf, onPrimaryContainer = Ink, secondaryContainer = Color(0xFFE4EACF), background = Color(0xFFF6F8EF), surface = Color(0xFFF6F8EF), surfaceContainer = Color(0xFFEAEFE1), surfaceContainerLow = Color(0xFFF0F3E8))
    }
    MaterialExpressiveTheme(colorScheme = colors, shapes = shapes) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                listOf("Connect" to Glyphs.Link, "Apps" to Glyphs.Apps, "Files" to Glyphs.Folder, "Settings" to Glyphs.Tune).forEachIndexed { index, (label, icon) ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(icon, null) }, label = { Text(label) })
                }
            }
        }) { padding ->
            val changed = { settingsVersion++; BridgeState.changed() }
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    0 -> Home(prefs, access, settingsVersion, { pairing = true }, { tab = 1 }, { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
                    1 -> AppsScreen(prefs, settingsVersion, changed)
                    2 -> FilesScreen(prefs, settingsVersion, changed)
                    else -> SettingsScreen(prefs, access, settingsVersion, dynamic, { dynamic = it; prefs.dynamicColor = it }, changed)
                }
            }
            if (context.showBatteryPrompt) AlertDialog(
                onDismissRequest = { context.showBatteryPrompt = false; prefs.batteryPromptSeen = true },
                title = { Text("Keep Teather reachable?") },
                text = { Text("Android may delay the connection while your phone sleeps. You can optionally allow Teather to run without battery optimization. This improves availability; it does not reduce battery use. Teather does not hold a wake lock or scan your files in the background.") },
                confirmButton = { TextButton(onClick = { context.showBatteryPrompt = false; prefs.batteryPromptSeen = true; context.requestBatteryExemption() }) { Text("Allow background access") } },
                dismissButton = { TextButton(onClick = { context.showBatteryPrompt = false; prefs.batteryPromptSeen = true }) { Text("Keep system default") } }
            )
            if (pairing) PairSheet(prefs, { pairing = false }, { settingsVersion++; pairing = false; context.stopService(Intent(context, ConnectionService::class.java)); context.connect() })
        }
    }
}

@Composable
private fun PageHeading(eyebrow: String, title: String, subtitle: String? = null) {
    Column(Modifier.padding(top = 24.dp, bottom = 22.dp)) {
        Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp)
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold, lineHeight = 40.sp, letterSpacing = (-1).sp)
        if (subtitle != null) { Spacer(Modifier.height(8.dp)); Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun Home(prefs: Preferences, access: Boolean, version: Int, pair: () -> Unit, apps: () -> Unit, grant: () -> Unit) {
    val context = LocalActivity.current as MainActivity
    val status by BridgeState.status.collectAsState()
    val running by BridgeState.running.collectAsState()
    val count by BridgeState.count.collectAsState()
    val lastSync by BridgeState.lastSync.collectAsState()
    val connected = status == "Connected"
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeading("Teather", "Your devices.\nBetter together.") }
        item {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(36.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(8.dp).background(if (connected) Green else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .4f), RoundedCornerShape(4.dp)))
                        Text(if (connected) "CONNECTED TO YOUR MAC" else if (running) "FINDING YOUR MAC" else "A LITTLE CLOSER", style = MaterialTheme.typography.labelMedium, letterSpacing = 1.sp)
                    }
                    DeviceArt(connected)
                    Text(if (connected) "One less screen\nto check." else "Phone here.\nNotifications there.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold, letterSpacing = (-.8).sp)
                    Spacer(Modifier.height(12.dp))
                    Text(if (connected) "$count active notifications mirrored" else "A private bridge from Android to Mac.\nPowered by your Tailscale network.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = { if (prefs.host.isBlank()) pair() else if (running) context.stopService(Intent(context, ConnectionService::class.java)) else context.connect() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Text(if (prefs.host.isBlank()) "Connect your Mac" else if (running) "Disconnect" else "Start syncing", fontSize = 16.sp); Spacer(Modifier.width(12.dp)); Icon(Glyphs.Arrow, null, Modifier.size(20.dp))
                    }
                }
            }
        }
        if (!access) item { InfoCard(Glyphs.Bell, "Let notifications through", "Choose Teather in Android’s notification access settings.", "Allow access", grant) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("YOUR APPS", "${prefs.enabledApps.size}", "Only what you choose", Modifier.weight(1f).clickable(onClick = apps))
                StatCard("CONNECTION", "Tailscale", "Encrypted in transit", Modifier.weight(1f))
            }
        }
        if (prefs.host.isNotBlank()) item { InfoCard(Glyphs.Link, "Your Mac", prefs.host + "\n" + status, "Edit pairing", pair) }
        if (lastSync > 0) item { Text("Last synced ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(lastSync))}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
        item { Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { Icon(Glyphs.Shield, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary); Text("No cloud account. Your devices, your network.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun DeviceArt(connected: Boolean) {
    val line = MaterialTheme.colorScheme.onPrimaryContainer
    val phoneFill = MaterialTheme.colorScheme.secondaryContainer
    val progress by animateFloatAsState(if (connected) 1f else .35f, label = "connection")
    Canvas(Modifier.fillMaxWidth().height(148.dp).semantics { contentDescription = "An Android phone connected to a Mac" }) {
        val unit = size.width / 300f
        fun p(x: Float, y: Float) = Offset(x * unit, y * unit)
        val stroke = Stroke(2.5f * unit)
        drawRoundRect(line.copy(alpha = .07f), p(113f, 16f), Size(150 * unit, 96 * unit), CornerRadius(12 * unit))
        drawRoundRect(line, p(113f, 16f), Size(150 * unit, 96 * unit), CornerRadius(12 * unit), style = stroke)
        drawLine(line, p(101f, 122f), p(275f, 122f), 3 * unit, StrokeCap.Round)
        drawRoundRect(phoneFill, p(31f, 37f), Size(59 * unit, 96 * unit), CornerRadius(13 * unit))
        drawRoundRect(line, p(31f, 37f), Size(59 * unit, 96 * unit), CornerRadius(13 * unit), style = stroke)
        drawLine(line, p(51f, 46f), p(70f, 46f), 3 * unit, StrokeCap.Round)
        drawRoundRect(line.copy(alpha = .12f), p(130f, 36f), Size(115 * unit, 24 * unit), CornerRadius(8 * unit))
        drawCircle(line.copy(alpha = .4f), 5 * unit, p(142f, 48f))
        drawLine(line.copy(alpha = .5f), p(155f, 48f), p(228f, 48f), 3 * unit, StrokeCap.Round)
        drawRoundRect(line.copy(alpha = .07f), p(130f, 69f), Size(115 * unit, 24 * unit), CornerRadius(8 * unit))
        repeat(3) { i -> drawCircle(line.copy(alpha = progress), 3 * unit, p(96f + i * 10, 78f)) }
        drawRoundRect(line.copy(alpha = .14f), p(41f, 64f), Size(39 * unit, 30 * unit), CornerRadius(7 * unit))
        drawLine(line, p(50f, 78f), p(59f, 85f), 2.5f * unit, StrokeCap.Round)
        drawLine(line, p(59f, 85f), p(72f, 72f), 2.5f * unit, StrokeCap.Round)
    }
}

@Composable
private fun StatCard(label: String, value: String, caption: String, modifier: Modifier) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
            Spacer(Modifier.height(12.dp)); Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp)); Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InfoCard(icon: ImageVector, title: String, body: String, action: String, click: () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(10.dp)); Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = click, contentPadding = PaddingValues(top = 12.dp, end = 12.dp)) { Text(action); Spacer(Modifier.width(8.dp)); Icon(Glyphs.Arrow, null, Modifier.size(16.dp)) }
        }
    }
}

private data class PhoneApp(val id: String, val name: String, val icon: androidx.compose.ui.graphics.ImageBitmap?)

@Composable
private fun AppsScreen(prefs: Preferences, version: Int, changed: () -> Unit) {
    val context = LocalContext.current
    var applications by remember { mutableStateOf<List<PhoneApp>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) {
        applications = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val ids = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { it.activityInfo.packageName }.toSet() + prefs.enabledApps
            ids.filter { it != context.packageName }.mapNotNull { id -> runCatching {
                val info = pm.getApplicationInfo(id, 0)
                val drawable = pm.getApplicationIcon(info)
                val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, 96, 96); drawable.draw(android.graphics.Canvas(bitmap))
                PhoneApp(id, pm.getApplicationLabel(info).toString(), bitmap.asImageBitmap())
            }.getOrNull() }.sortedBy { it.name.lowercase() }
        }
        loaded = true
    }
    val enabled = remember(version) { prefs.enabledApps }
    val local = remember(version) { prefs.localOnlyApps }
    val filtered = applications.filter { it.name.contains(query, true) || it.id.contains(query, true) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { PageHeading("Make it yours", "Less noise.\nMore you.", "Choose the apps that get a place on your Mac.") }
        item { OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), placeholder = { Text("Find an app") }, singleLine = true, shape = RoundedCornerShape(28.dp)) }
        item { Text("${enabled.size} SELECTED · ${applications.size} AVAILABLE", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 12.dp)) }
        if (!loaded) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (loaded && filtered.isEmpty()) item { Text("No matching apps", Modifier.padding(24.dp)) }
        items(filtered, key = { it.id }) { app ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (app.icon != null) Image(app.icon, null, Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
                        Column(Modifier.weight(1f)) { Text(app.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(if (app.id in enabled) "Mirroring to Mac" else "Stays on your phone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Switch(checked = app.id in enabled, onCheckedChange = { prefs.enabledApps = if (it) prefs.enabledApps + app.id else prefs.enabledApps - app.id; changed() }, modifier = Modifier.semantics { contentDescription = "Mirror ${app.name}" })
                    }
                    if (app.id in enabled) TextButton(onClick = { prefs.localOnlyApps = if (app.id in local) prefs.localOnlyApps - app.id else prefs.localOnlyApps + app.id; changed() }) { Text(if (app.id in local) "Mac dismissals stay on Mac" else "Dismiss on both devices", style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
        item { Text("System alerts without an app launcher may not appear here. Protected Android notifications cannot be dismissed remotely.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp)) }
    }
}

@Composable
private fun FilesScreen(prefs: Preferences, version: Int, changed: () -> Unit) {
    val context = LocalActivity.current as MainActivity
    val status by ShareState.status.collectAsState()
    val running by BridgeState.running.collectAsState()
    var enabled by remember(version) { mutableStateOf(prefs.shareEnabled) }
    var writable by remember(version) { mutableStateOf(prefs.shareWrite) }
    var confirmWrite by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val tree = prefs.sharedTree
    val name by produceState("", tree) { value = if (tree.isEmpty()) "" else withContext(Dispatchers.IO) { runCatching { SharedFolder(context, tree).root().name }.getOrDefault("Choose the folder again") } }
    fun refresh() {
        BridgeState.shareDirty.set(true); BridgeState.changed(); changed()
        if (BridgeState.running.value) context.startService(Intent(context, ConnectionService::class.java).setAction("refreshShare"))
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                val resolver = context.contentResolver
                runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                    .getOrElse { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                // Revoke the old tree grant, so only the selected folder remains exposed.
                if (prefs.sharedTree.isNotEmpty() && prefs.sharedTree != uri.toString()) {
                    val old = Uri.parse(prefs.sharedTree)
                    resolver.persistedUriPermissions.firstOrNull { it.uri == old }?.let { permission ->
                        resolver.releasePersistableUriPermission(old, (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0))
                    }
                }
                prefs.sharedTree = uri.toString(); prefs.shareWrite = false; prefs.shareEnabled = false
                error = ""; refresh()
            }.onFailure { error = "Couldn’t keep access to this folder. Please choose it again." }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageHeading("A little more connected", "Your files.\nWithin reach.", "One folder from your phone, as a disk in Finder.") }
        item { InfoCard(Glyphs.Folder, name.ifEmpty { "Choose a folder to share" }, "Only this folder and its contents are exposed. Android’s system picker controls access; no all-files permission is needed.", if (tree.isEmpty()) "Choose folder" else "Change folder") { picker.launch(null) } }
        if (tree.isNotEmpty()) {
            item { SettingToggle("Share with your Mac", "${if (running) status else "Start sharing to keep the connection available"}. No background file scans or copies.", enabled) {
                enabled = it; prefs.shareEnabled = it
                if (it && !running && prefs.host.isNotEmpty()) context.connect()
                refresh()
            } }
            item { SettingToggle("Allow changes from Mac", "Off by default. Allows creating, replacing, moving and deleting files in this folder. Remount on Mac after changing access.", writable) {
                if (it) confirmWrite = true else { writable = false; prefs.shareWrite = false; refresh() }
            } }
            item { TextButton(onClick = {
                prefs.shareEnabled = false; prefs.shareWrite = false
                val old = Uri.parse(prefs.sharedTree)
                prefs.sharedTree = ""; refresh()
                runCatching { context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == old }?.let { permission -> context.contentResolver.releasePersistableUriPermission(old, (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)) } }
            }) { Text("Stop sharing & forget folder") } }
        }
        if (prefs.host.isEmpty()) item { Text("Pair your Mac in the Connect tab first.", color = MaterialTheme.colorScheme.primary) }
        if (error.isNotEmpty()) item { Text(error, color = MaterialTheme.colorScheme.error) }
        item { InfoCard(Glyphs.Link, "Mount it in Finder", "On your Mac, open Teather → Mount Android folder. Files stream only when requested. Eject the disk when you’re finished to avoid Finder preview and metadata requests.", "Open Tailscale") {
            context.packageManager.getLaunchIntentForPackage("com.tailscale.ipn")?.let { context.startActivity(it) }
        } }
        item { Text("Use a local device folder for predictable performance. Cloud document providers, Finder previews and large transfers can use additional battery. The folder is available while Teather and Tailscale are running.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (confirmWrite) AlertDialog(onDismissRequest = { confirmWrite = false }, title = { Text("Allow your Mac to change files?") }, text = { Text("Your paired Mac will be able to create, replace, rename and delete files inside this folder. Read-only access is safer when you only need to browse or copy files to your Mac.") }, confirmButton = { TextButton(onClick = { confirmWrite = false; writable = true; prefs.shareWrite = true; refresh() }) { Text("Allow changes") } }, dismissButton = { TextButton(onClick = { confirmWrite = false }) { Text("Keep read-only") } })
}

@Composable
private fun SettingsScreen(prefs: Preferences, access: Boolean, version: Int, dynamic: Boolean, setDynamic: (Boolean) -> Unit, changed: () -> Unit) {
    val context = LocalActivity.current as MainActivity
    var hide by remember { mutableStateOf(prefs.hideContent) }
    var sound by remember { mutableStateOf(prefs.sound) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { PageHeading("The little details", "Quietly\nin control.") }
        item { SectionLabel("NOTIFICATIONS") }
        item { SettingToggle("Hide message content", "Show only the app name on your Mac, without the sender or message.", hide) { hide = it; prefs.hideContent = it; changed() } }
        item { SettingToggle("Play sounds on Mac", "Off by default. Reconnection updates stay silent.", sound) { sound = it; prefs.sound = it; changed() } }
        item { Spacer(Modifier.height(14.dp)); SectionLabel("APPEARANCE") }
        item { SettingToggle("Wallpaper colors", "Use your phone’s palette. Light and dark follow your system.", dynamic, setDynamic) }
        item { Spacer(Modifier.height(14.dp)); SectionLabel("CONNECTION HEALTH") }
        item { InfoCard(Glyphs.Bell, if (access) "Notification access is on" else "Notification access is off", "Teather needs access to mirror and dismiss your selected notifications.", "Open Android settings") { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } }
        item {
            val exempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
            InfoCard(Glyphs.Link, if (exempt) "Background access allowed" else "Keep the connection available", "Optional: exempt Teather from battery optimization for more reliable background access. This does not reduce battery use. Keep Tailscale connected too.", if (exempt) "App battery settings" else "Allow background access") {
                if (exempt) context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) else context.requestBatteryExemption()
            }
        }
        item { InfoCard(Glyphs.Shield, "Private by design", "Your pairing key is encrypted on this phone. Message content is not saved by the Android app. Your Mac’s Notification Center keeps its own history.", "Open Tailscale") {
            val intent = context.packageManager.getLaunchIntentForPackage("com.tailscale.ipn")
            if (intent != null) context.startActivity(intent) else context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        } }
        item { Text("Teather 0.2.1\nMade for fewer interruptions.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp, 24.dp)) }
    }
}

@Composable private fun SectionLabel(text: String) { Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.5.sp, modifier = Modifier.padding(8.dp, 10.dp)) }
@Composable private fun SettingToggle(title: String, caption: String, checked: Boolean, change: (Boolean) -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(4.dp)); Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Switch(checked, change, modifier = Modifier.semantics { contentDescription = title })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairSheet(prefs: Preferences, dismiss: () -> Unit, connect: () -> Unit) {
    var host by rememberSaveable { mutableStateOf(prefs.host) }
    var token by remember { mutableStateOf(prefs.token) }
    var visible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Meet your Mac.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Text("Open Teather in your Mac’s menu bar. Copy its Tailscale IPv4 address and pairing key here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(host, { host = it.trim(); error = null }, label = { Text("Mac’s Tailscale IPv4") }, placeholder = { Text("100.x.x.x") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it.trim().lowercase(); error = null }, label = { Text("Pairing key") }, singleLine = true, visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "Hide" else "Show") } }, modifier = Modifier.fillMaxWidth())
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                error = when { !(TailnetAddress.valid(host) || (BuildConfig.DEBUG && host == "127.0.0.1")) -> "Use the 100.64–100.127 IPv4 address shown in Tailscale."; !token.matches(Regex("[a-f0-9]{64}")) -> "The pairing key should contain 64 letters and numbers."; else -> null }
                if (error == null) runCatching { prefs.host = host; prefs.token = token; connect() }.onFailure { error = "Couldn’t securely save the key. Please try again." }
            }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Icon(Glyphs.Link, null); Spacer(Modifier.width(10.dp)); Text("Pair & start syncing", fontSize = 16.sp) }
            Text("Both devices must be connected to your tailnet. No public server needed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

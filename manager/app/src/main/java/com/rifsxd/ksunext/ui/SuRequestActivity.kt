package com.rifsxd.ksunext.ui

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.rifsxd.ksunext.Natives
import com.rifsxd.ksunext.ui.theme.KernelSUExpressiveDarkTheme
import com.rifsxd.ksunext.ui.util.execKsud

private const val TAG = "SuRequest"
private const val TIMEOUT_SECONDS = 30

class SuRequestActivity : ComponentActivity() {

    private var reqId: Long = -1
    private var uid: Int = -1
    private var decided = false
    private var timer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        reqId = intent.getLongExtra("ksu_reqid", -1)
        uid = intent.getIntExtra("ksu_uid", -1)
        val pid = intent.getIntExtra("ksu_pid", -1)

        if (reqId < 0 || uid < 0) {
            Log.w(TAG, "invalid su request intent: reqid=$reqId uid=$uid")
            finish()
            return
        }

        val (appLabel, appIcon, pkg) = resolveApp(uid)
        val appKey = pkg ?: "uid:$uid"

        setContent {
            KernelSUExpressiveDarkTheme(
                amoledMode = getSharedPreferences("settings", MODE_PRIVATE)
                    .getBoolean("enable_amoled", false),
            ) {
                SuRequestScreen(
                    appLabel = appLabel,
                    packageName = appKey,
                    appIcon = appIcon,
                    onGrant = { decide(true, appKey) },
                    onDeny = { decide(false, appKey) },
                )
            }
        }
    }

    private data class AppInfo(val label: String, val icon: Drawable?, val packageName: String?)

    private fun resolveApp(uid: Int): AppInfo {
        // Try getNameForUid first — works on Samsung even when getPackagesForUid doesn't
        val nameForUid = packageManager.getNameForUid(uid)
        val pkg = nameForUid
            ?: packageManager.getPackagesForUid(uid)?.firstOrNull()

        if (pkg != null) {
            try {
                val ai = packageManager.getApplicationInfo(pkg, 0)
                return AppInfo(
                    label = packageManager.getApplicationLabel(ai).toString(),
                    icon = packageManager.getApplicationIcon(ai),
                    packageName = pkg,
                )
            } catch (_: PackageManager.NameNotFoundException) {
                return AppInfo(label = pkg, icon = null, packageName = pkg)
            }
        }

        // Last resort: scan installed packages for matching UID
        for (pi in packageManager.getInstalledPackages(0)) {
            if (pi.applicationInfo?.uid == uid) {
                val ai = pi.applicationInfo!!
                return AppInfo(
                    label = packageManager.getApplicationLabel(ai).toString(),
                    icon = packageManager.getApplicationIcon(ai),
                    packageName = pi.packageName,
                )
            }
        }

        return AppInfo(label = "UID $uid", icon = null, packageName = null)
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }

    @Composable
    private fun SuRequestScreen(
        appLabel: String,
        packageName: String,
        appIcon: Drawable?,
        onGrant: () -> Unit,
        onDeny: () -> Unit,
    ) {
        var countdown by remember { mutableIntStateOf(TIMEOUT_SECONDS) }
        val progress by animateFloatAsState(
            targetValue = countdown.toFloat() / TIMEOUT_SECONDS,
            animationSpec = tween(900),
            label = "countdown",
        )

        DisposableEffect(Unit) {
            timer?.cancel()
            timer = object : CountDownTimer(TIMEOUT_SECONDS * 1000L, 1000) {
                override fun onTick(remaining: Long) {
                    countdown = (remaining / 1000).toInt()
                }
                override fun onFinish() {
                    onDeny()
                }
            }.start()
            onDispose { timer?.cancel() }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { /* consume taps on scrim */ },
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                tonalElevation = 2.dp,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(12.dp))

                    // Drag handle
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )

                    Spacer(Modifier.height(24.dp))

                    // Shield icon in a colored circle
                    Surface(
                        modifier = Modifier.size(56.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.Shield,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = "Superuser Request",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Spacer(Modifier.height(20.dp))

                    // App info card
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (appIcon != null) {
                                val bitmap = remember {
                                    val bmp = Bitmap.createBitmap(
                                        appIcon.intrinsicWidth.coerceAtLeast(1),
                                        appIcon.intrinsicHeight.coerceAtLeast(1),
                                        Bitmap.Config.ARGB_8888
                                    )
                                    val canvas = Canvas(bmp)
                                    appIcon.setBounds(0, 0, canvas.width, canvas.height)
                                    appIcon.draw(canvas)
                                    bmp.asImageBitmap()
                                }
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = appLabel,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                )
                                Spacer(Modifier.width(14.dp))
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = appLabel,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = "is requesting superuser access",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(8.dp))

                    // Countdown progress
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = "${countdown}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(20.dp))

                    // Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        FilledTonalButton(
                            onClick = onDeny,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) {
                            Text("Deny", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }

                        Button(
                            onClick = onGrant,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text("Grant", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    private fun decide(allow: Boolean, appKey: String) {
        if (decided) return
        decided = true
        timer?.cancel()

        CoroutineScope(Dispatchers.IO).launch {
            if (allow) {
                val granted = runCatching {
                    Natives.setAppProfile(
                        Natives.Profile(
                            name = appKey,
                            currentUid = uid,
                            allowSu = true,
                        )
                    )
                }.getOrDefault(false)
                if (!granted) Log.e(TAG, "failed to set app profile for uid=$uid")
            }

            val decision = if (allow) "allow" else "deny"
            val ok = runCatching { execKsud("su-reply --id $reqId --$decision") }
                .getOrDefault(false)
            if (!ok) Log.e(TAG, "failed to deliver su decision ($decision) for #$reqId")

            runOnUiThread { finish() }
        }
    }
}

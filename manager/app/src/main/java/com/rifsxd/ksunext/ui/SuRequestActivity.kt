package com.rifsxd.ksunext.ui

import android.content.Context
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.rifsxd.ksunext.Natives
import com.rifsxd.ksunext.R
import com.rifsxd.ksunext.ui.theme.KernelSUExpressiveDarkTheme
import com.rifsxd.ksunext.ui.util.execKsud

private const val TAG = "SuRequest"
private const val TIMEOUT_SECONDS = 30

/**
 * The decision the user picks on the SU-request bottom sheet.
 *
 * - [ALLOW_ONCE]: reply "allow" to this specific request; do NOT persist the
 *   decision. The next su invocation will prompt again.
 * - [ALLOW_ALWAYS]: reply "allow" and persist `allowSu = true` on the uid's
 *   profile so future su calls hit the kernel allowlist directly.
 * - [DENY_ONCE]: reply "deny" without persisting; next call prompts again.
 * - [DENY_ALWAYS]: reply "deny" and persist `allowSu = false` so future su
 *   calls are silently rejected.
 */
enum class SuDecision { ALLOW_ONCE, ALLOW_ALWAYS, DENY_ONCE, DENY_ALWAYS }

class SuRequestActivity : ComponentActivity() {

    private var reqId: Long = -1
    private var uid: Int = -1
    private var decided = false
    private var timer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        reqId = intent.getLongExtra("ksu_reqid", -1)
        uid = intent.getIntExtra("ksu_uid", -1)

        if (reqId < 0 || uid < 0) {
            Log.w(TAG, "invalid su request intent: reqid=$reqId uid=$uid")
            finish()
            return
        }

        val (appLabel, appIcon, pkg) = resolveApp(uid)
        val appKey = pkg ?: "uid:$uid"

        // Mirror MainActivity's amoled-pref read so the popup honors the same
        // user preference as the main UI. We never let the activity follow the
        // system dark-mode flag — the SU-request sheet is always dark.
        val amoled = try {
            getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("enable_amoled", false)
        } catch (_: Exception) {
            false
        }

        setContent {
            KernelSUExpressiveDarkTheme(amoledMode = amoled) {
                SuRequestScreen(
                    appLabel = appLabel,
                    packageName = appKey,
                    appIcon = appIcon,
                    uid = uid,
                    onDecision = { d -> decide(d, appKey) },
                )
            }
        }
    }

    private data class AppInfo(val label: String, val icon: Drawable?, val packageName: String?)

    private fun resolveApp(uid: Int): AppInfo {
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
        uid: Int,
        onDecision: (SuDecision) -> Unit,
    ) {
        val cs = MaterialTheme.colorScheme
        val typo = MaterialTheme.typography

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
                    onDecision(SuDecision.DENY_ONCE)
                }
            }.start()
            onDispose { timer?.cancel() }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                // Deeper scrim than before - part of the "no longer too bright"
                // feel.
                .background(cs.scrim.copy(alpha = 0.60f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { /* swallow scrim taps so an outside tap cannot auto-deny */ },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                tonalElevation = 3.dp,
                color = cs.surfaceContainer,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Drag handle
                    Box(
                        modifier = Modifier
                            .size(width = 32.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(cs.outlineVariant)
                    )

                    Spacer(Modifier.height(12.dp))

                    // Compact header: shield icon inline with title
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Surface(
                            modifier = Modifier.size(36.dp),
                            shape = CircleShape,
                            color = cs.tertiaryContainer,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Shield,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = cs.onTertiaryContainer,
                                )
                            }
                        }
                        Column {
                            Text(
                                text = stringResource(R.string.su_request_title),
                                style = typo.titleMedium,
                                color = cs.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.su_request_action_full_root),
                                style = typo.labelSmall,
                                color = cs.tertiary,
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // App card
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = cs.surfaceContainerHigh,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (appIcon != null) {
                                val bitmap = remember {
                                    val bmp = Bitmap.createBitmap(
                                        appIcon.intrinsicWidth.coerceAtLeast(1),
                                        appIcon.intrinsicHeight.coerceAtLeast(1),
                                        Bitmap.Config.ARGB_8888,
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
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp)),
                                )
                                Spacer(Modifier.width(12.dp))
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = appLabel,
                                    style = typo.titleSmall,
                                    color = cs.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = packageName,
                                    style = typo.bodySmall,
                                    color = cs.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            Text(
                                text = stringResource(R.string.su_request_uid_format, uid),
                                style = typo.labelSmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = cs.tertiary,
                        trackColor = cs.surfaceContainerHighest,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.su_request_auto_deny_format, countdown),
                        style = typo.labelSmall,
                        color = cs.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(14.dp))

                    // Simple Allow / Deny - both persist a profile. "Once"
                    // variants are architecturally broken on KernelSU-Next:
                    // without a persistent profile the kernel re-routes the
                    // next su exec through the request path, so Allow Once
                    // loops the popup forever instead of granting root.
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { onDecision(SuDecision.DENY_ALWAYS) },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = cs.error,
                            ),
                        ) {
                            Text(text = stringResource(R.string.su_request_deny))
                        }
                        Button(
                            onClick = { onDecision(SuDecision.ALLOW_ALWAYS) },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.su_request_allow),
                                style = typo.labelLarge,
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    /**
     * Maps the user's chosen [SuDecision] to the (allow, persist) tuple and
     * dispatches both the kernel allowlist write (when persisting) and the
     * daemon reply.
     *
     * Ordering is important: `Natives.setAppProfile` is called BEFORE
     * `execKsud("su-reply ...")` so that by the time the daemon tells the
     * blocked request-client to re-exec `/system/bin/su`, the uid is already on
     * the kernel allowlist and the normal (non-request) sucompat path grants
     * root on the second exec. See the task's end-to-end blocker notes - this
     * manager-side ordering is the piece that stays correct even once the ksud
     * side of the chain lands.
     */
    private fun decide(decision: SuDecision, appKey: String) {
        if (decided) return
        decided = true
        timer?.cancel()

        val allow = decision == SuDecision.ALLOW_ONCE || decision == SuDecision.ALLOW_ALWAYS
        val persist = decision == SuDecision.ALLOW_ALWAYS || decision == SuDecision.DENY_ALWAYS

        CoroutineScope(Dispatchers.IO).launch {
            if (persist) {
                val ok = runCatching {
                    Natives.setAppProfile(
                        Natives.Profile(
                            name = appKey,
                            currentUid = uid,
                            allowSu = allow,
                        )
                    )
                }.getOrDefault(false)
                if (!ok) Log.e(TAG, "failed to set app profile for uid=$uid allow=$allow")
            }

            val reply = if (allow) "allow" else "deny"
            val delivered = runCatching { execKsud("su-reply --id $reqId --$reply") }
                .getOrDefault(false)
            if (!delivered) Log.e(TAG, "failed to deliver su decision ($reply) for #$reqId")

            runOnUiThread { finish() }
        }
    }
}

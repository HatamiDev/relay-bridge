package com.relay.client.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.relay.client.BuildConfig
import com.relay.client.data.RelayRepository
import com.relay.client.service.ClientRelayService
import com.relay.client.ui.components.GlassSurface
import com.relay.client.ui.components.glow
import com.relay.client.ui.theme.Glass
import com.relay.core.net.PairingCoordinator
import com.relay.core.net.PairingPayload
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * The Receiver's pairing screen.
 *
 * Typing an 8-character code is the primary path because that is what the user
 * asked for, and it works when the two phones are not in the same room. The QR
 * scanner is offered alongside as the *safer* option, and the copy says why
 * rather than leaving the user to guess: the QR carries the sender's key, so a
 * hostile relay server cannot get between the two devices. The typed code
 * closes that gap with the verification number instead.
 *
 * The code field is segmented into eight boxes. That is not decoration — it
 * makes a transcription error visible at the character that went wrong, instead
 * of only at "pairing failed" three seconds later.
 */
@Composable
fun PairingScreen(
    repository: RelayRepository,
    onPaired: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Glass.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }

    val coordinator = remember {
        PairingCoordinator(context, repository.secureStore, BuildConfig.RELAY_SERVER_URL)
    }

    var code by rememberSaveable { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var sas by remember { mutableStateOf("") }

    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCamera = granted
        scanning = granted
        if (!granted) {
            status = "Camera access is needed to scan. You can still type the code."
            isError = true
        }
    }

    /** Shared completion path for both the typed code and the scanned QR. */
    fun redeem(pairCode: String, expectedKey: String? = null) {
        if (busy) return
        busy = true
        scanning = false
        isError = false
        status = "Connecting to the sender…"
        keyboard?.hide()

        scope.launch {
            coordinator.joinWithCode(pairCode, expectedKey)
                .onSuccess { peer ->
                    sas = peer.sas
                    status = "Paired with ${peer.label.ifEmpty { peer.model.ifEmpty { "sender" } }}."
                    ClientRelayService.start(context)
                    repository.connect()
                    onPaired()
                }
                .onFailure {
                    isError = true
                    status = friendlyError(it)
                    code = ""
                }
            busy = false
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))

        Text(
            "Enter the pairing code",
            color = colors.textPrimary,
            fontSize = 27.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Open the app on the phone with the SIM, tap “Create pairing code”, " +
                "and type what it shows here.",
            color = colors.textSecondary,
            fontSize = 13.5.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(30.dp))

        if (scanning && hasCamera) {
            GlassSurface(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                shape = RoundedCornerShape(30.dp),
                strong = true,
                glowColor = colors.accent,
            ) {
                QrScanner(
                    onResult = { raw ->
                        val payload = PairingPayload.parse(raw)
                        if (payload == null) {
                            status = "That QR is not a Relay pairing code."
                            isError = true
                        } else {
                            redeem(payload.pairCode, expectedKey = payload.gatewayPubKey)
                        }
                    },
                    modifier = Modifier.matchParentSize().clip(RoundedCornerShape(30.dp)),
                )
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxSize(0.68f)
                        .border(2.dp, colors.accent.copy(alpha = 0.85f), RoundedCornerShape(24.dp)),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "Cancel",
                color = colors.textSecondary,
                fontSize = 13.sp,
                modifier = Modifier.clickable { scanning = false },
            )
        } else {
            // ── Segmented code field ─────────────────────────────────────────
            CodeField(
                value = code,
                isError = isError,
                onValueChange = { raw ->
                    val cleaned = normalize(raw).take(CODE_LENGTH)
                    code = cleaned
                    isError = false
                    if (cleaned.length == CODE_LENGTH) redeem(cleaned)
                },
                focusRequester = focus,
            )

            LaunchedEffect(Unit) { if (sas.isEmpty()) focus.requestFocus() }

            Spacer(Modifier.height(18.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (code.length == CODE_LENGTH && !busy) {
                            Modifier.glow(colors.accent, RoundedCornerShape(50), 14.dp)
                        } else {
                            Modifier
                        },
                    )
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (code.length == CODE_LENGTH && !busy) colors.accent else colors.glassDark,
                    )
                    .clickable(enabled = code.length == CODE_LENGTH && !busy) { redeem(code) }
                    .padding(vertical = 15.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        color = colors.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Text(
                        "Connect",
                        color = if (code.length == CODE_LENGTH) {
                            Color(0xFF04121C)
                        } else {
                            colors.textTertiary
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).height(1.dp).background(colors.glassBorder))
                Text(
                    "  or  ",
                    color = colors.textTertiary,
                    fontSize = 11.sp,
                )
                Box(Modifier.weight(1f).height(1.dp).background(colors.glassBorder))
            }

            Spacer(Modifier.height(20.dp))

            GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                Column(
                    Modifier
                        .clickable {
                            if (hasCamera) scanning = true
                            else cameraPermission.launch(Manifest.permission.CAMERA)
                        }
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Rounded.QrCodeScanner,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(30.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Scan the QR instead",
                        color = colors.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "Safer: the QR carries the encryption key itself, so the " +
                            "relay server never has a chance to sit in the middle.",
                        color = colors.textTertiary,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // ── Verification number ──────────────────────────────────────────────
        AnimatedVisibility(sas.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(22.dp))
                GlassSurface(Modifier.fillMaxWidth(), glowColor = colors.success) {
                    Column(
                        Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = colors.success,
                            modifier = Modifier.size(26.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Verification number",
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            sas.chunked(3).joinToString(" "),
                            color = colors.accent,
                            fontSize = 36.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 4.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Check the sender shows the same six digits. If it does " +
                                "not, unpair now — someone is intercepting the pairing.",
                            color = colors.warning,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        if (status.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                status,
                color = if (isError) colors.danger else colors.accent,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.weight(1f))

        Text(
            "Relay server: ${BuildConfig.RELAY_SERVER_URL.substringAfter("//")}",
            color = colors.textTertiary,
            fontSize = 10.5.sp,
        )
        Spacer(Modifier.height(20.dp))
    }
}

// ── Segmented code field ─────────────────────────────────────────────────────

private const val CODE_LENGTH = 8

/**
 * Eight boxes over one invisible text field.
 *
 * A single wide input would let a mistyped character hide in the middle of a
 * string; boxes make position obvious and give the eye somewhere to land. The
 * real `BasicTextField` is transparent and full-bleed so the system keyboard,
 * autofill and text selection all keep working normally.
 */
@Composable
private fun CodeField(
    value: String,
    isError: Boolean,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
) {
    val colors = Glass.colors

    // No wrapper Box here, deliberately. The field used to sit inside one with
    // `matchParentSize()`, but matchParentSize contributes nothing to the
    // parent's measurement — and with no other child, the Box measured zero
    // high. The eight code boxes were then drawn outside their own layout
    // bounds, which is why the Connect button landed on top of them.
    // Sizing the field itself from its decoration box fixes it.
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        decorationBox = { inner ->
            // Keep the real field in the tree so the IME stays attached.
            Box(Modifier.size(0.dp)) { inner() }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(CODE_LENGTH) { index ->
                    val char = value.getOrNull(index)
                    val active = index == value.length

                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(0.78f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                when {
                                    isError -> colors.danger.copy(alpha = 0.12f)
                                    char != null -> colors.accentSoft
                                    else -> colors.glassDark
                                },
                            )
                            .border(
                                width = if (active) 1.5.dp else 1.dp,
                                color = when {
                                    isError -> colors.danger
                                    active -> colors.accent
                                    else -> colors.glassBorder
                                },
                                shape = RoundedCornerShape(12.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            char?.toString() ?: "",
                            color = if (isError) colors.danger else colors.textPrimary,
                            fontSize = 20.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    // Visual break where the sender renders its hyphen.
                    if (index == 3) Spacer(Modifier.width(4.dp))
                }
            }
        },
    )
}

// ── QR scanner ───────────────────────────────────────────────────────────────

/**
 * CameraX + ML Kit.
 *
 * `STRATEGY_KEEP_ONLY_LATEST` matters: a QR filling the frame produces analysis
 * frames faster than ML Kit consumes them, and a backpressure queue would leave
 * the preview several hundred milliseconds behind reality.
 */
@Composable
private fun QrScanner(onResult: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient() }
    var handled by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            scanner.close()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }

            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(executor) { proxy ->
                    val mediaImage = proxy.image
                    if (mediaImage == null || handled) { proxy.close(); return@setAnalyzer }

                    scanner.process(
                        InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees),
                    )
                        .addOnSuccessListener { barcodes ->
                            barcodes.firstNotNullOfOrNull { it.rawValue }?.let { value ->
                                if (!handled) {
                                    handled = true
                                    onResult(value)
                                }
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                }

                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
    )
}

// ── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Match the sender's alphabet exactly.
 *
 * Crockford Base32 omits I, L, O and U precisely so they can be folded into the
 * characters people mistake them for — which means the user can type a lowercase
 * l for a 1 and still get in.
 */
private fun normalize(raw: String): String = raw
    .uppercase()
    .replace(Regex("[^A-Z0-9]"), "")
    .replace('O', '0')
    .replace('I', '1')
    .replace('L', '1')
    .replace('U', 'V')

/** Turn a transport error into something a person can act on. */
private fun friendlyError(t: Throwable): String {
    val message = t.message.orEmpty()
    return when {
        t is SecurityException ->
            "Security check failed — the sender's key did not match the QR. " +
                "Do not continue; try again in person."
        message.contains("unknown_code") -> "That code is not recognised. Check each character."
        message.contains("expired") -> "That code has expired. Ask the sender for a new one."
        message.contains("room_full") -> "The sender has reached its receiver limit."
        message.contains("rate_limited") -> "Too many attempts. Wait a minute and retry."
        message.contains("cannot_join_own_room") -> "This is the sender's own code."
        message.contains("HTTP 4") || message.contains("HTTP 5") ->
            "The relay server rejected the request ($message)."
        else -> "Could not connect: $message"
    }
}

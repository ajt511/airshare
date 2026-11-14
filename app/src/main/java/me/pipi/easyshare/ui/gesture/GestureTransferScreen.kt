package me.pipi.easyshare.ui.gesture

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.pipi.easyshare.GestureCameraPreview
import me.pipi.easyshare.R
import androidx.camera.core.Preview
import me.pipi.easyshare.gesture.HandGesture
import me.pipi.easyshare.utils.DeviceUtils

/**
 * 手势传输主界面：背景前摄画面，中央为设备雷达，底部为阶段指引。
 * 状态驱动一切 — Idle(选内容) → PalmReady(已伸掌) → Grabbed(已抓住+选设备)
 * → Sending → Done。
 */
@Composable
fun GestureTransferScreen(
    state: GestureUiState,
    onPickFiles: () -> Unit,
    onClose: () -> Unit,
    onCancelSend: () -> Unit,
    onRetryCamera: () -> Unit,
    onStepSelection: (Int) -> Unit,
    onCameraSurfaceReady: (Preview.SurfaceProvider) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 前摄预览（暗化，作为取景参照）
        GestureCameraPreview(
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.35f),
            onSurfaceProviderReady = onCameraSurfaceReady,
        )

        IconButton(onClick = onClose, modifier = Modifier.padding(12.dp)) {
            Icon(
                painter = painterResource(me.pipi.easyshare.R.drawable.ic_close),
                contentDescription = stringResource(R.string.close),
                tint = Color.White,
            )
        }

        if (state.cameraError) {
            CameraErrorCard(
                onRetry = onRetryCamera,
                onPickFiles = onPickFiles,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(64.dp))
                GesturePhaseCard(state)
                Spacer(Modifier.height(8.dp))
                DeviceRadar(
                    state = state,
                    onStepSelection = onStepSelection,
                )
                Spacer(Modifier.height(8.dp))
                BottomContentRow(state, onPickFiles, onCancelSend)
            }
        }
    }
}

@Composable
private fun GesturePhaseCard(state: GestureUiState) {
    val (labelRes, highlight) = when (state.phase) {
        GesturePhase.Idle -> R.string.gesture_hint_idle to false
        GesturePhase.PalmReady -> R.string.gesture_hint_palm to true
        GesturePhase.Grabbed -> R.string.gesture_hint_grabbed to true
        is GesturePhase.Sending -> R.string.gesture_hint_sending to true
        is GesturePhase.Done -> if (state.phase.success) {
            R.string.gesture_hint_done_ok to false
        } else {
            R.string.gesture_hint_done_fail to false
        }
    }
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    val borderColor by animateColorAsState(
        targetValue = if (highlight) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = tween(200),
        label = "phaseBorder",
    )
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.Black.copy(alpha = 0.55f),
        modifier = Modifier.border(
            width = if (highlight) 2.dp else 0.dp,
            color = borderColor.copy(alpha = if (highlight) pulse else 0f),
            shape = RoundedCornerShape(20.dp),
        ),
    ) {
        Text(
            text = stringResource(labelRes),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}

/** 雷达式设备选择：横向滚动卡片，选中项放大高亮，左右按钮兼容无侧倾场景。 */
@Composable
private fun DeviceRadar(
    state: GestureUiState,
    onStepSelection: (Int) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.targets.isEmpty()) {
            Text(
                text = stringResource(R.string.no_nearby_devices),
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                itemsIndexed(state.targets, key = { _, item -> item.id }) { index, target ->
                    DeviceRadarItem(
                        target = target,
                        selected = target.id == state.selectedId,
                        onClick = { onStepSelection(index - state.selectedIndex) },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                OutlinedButton(onClick = { onStepSelection(-1) }) {
                    Text(stringResource(R.string.gesture_prev_device))
                }
                OutlinedButton(onClick = { onStepSelection(1) }) {
                    Text(stringResource(R.string.gesture_next_device))
                }
            }
        }
    }
}

@Composable
private fun DeviceRadarItem(
    target: GestureTarget,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.12f else 0.92f,
        animationSpec = tween(150),
        label = "radarScale",
    )
    val glow by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.25f),
        animationSpec = tween(150),
        label = "radarGlow",
    )
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.5f),
        modifier = Modifier
            .size(88.dp)
            .graphicsLayerScale(scale)
            .border(2.dp, glow, CircleShape),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(DeviceUtils.deviceIconById(target.brandId)),
                contentDescription = null,
                modifier = Modifier.size(34.dp),
            )
            Text(
                text = target.name,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(
                    if (target.isLan) R.string.channel_lan else R.string.channel_alliance,
                ),
                color = if (target.isLan) Color(0xFF7FD4FF) else Color(0xFFB69CFF),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun BottomContentRow(
    state: GestureUiState,
    onPickFiles: () -> Unit,
    onCancelSend: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        when (val phase = state.phase) {
            is GesturePhase.Sending -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(
                                R.string.gesture_sending_to, phase.targetName, phase.percent,
                            ),
                            color = Color.White,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onCancelSend) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
            is GesturePhase.Done -> {
                Text(
                    text = phase.message ?: stringResource(
                        if (phase.success) R.string.send_ok else R.string.send_fail,
                    ),
                    color = if (phase.success) Color(0xFF7CE38B) else Color(0xFFFF8A80),
                )
            }
            else -> {
                if (state.hasContent) {
                    ContentChips(state)
                } else {
                    Button(onClick = onPickFiles) {
                        Text(stringResource(R.string.gesture_pick_files))
                    }
                }
            }
        }
    }
}

@Composable
private fun ContentChips(state: GestureUiState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.gesture_holding),
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.height(6.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            itemsIndexed(state.contentNames.take(6)) { index, name ->
                AssistChip(
                    onClick = {},
                    label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = if (index == 0) {
                        {
                            Icon(
                                painter = painterResource(R.drawable.ic_hand),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun CameraErrorCard(
    onRetry: () -> Unit,
    onPickFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(32.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color.Black.copy(alpha = 0.7f),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                text = stringResource(R.string.gesture_camera_unavailable),
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                OutlinedButton(onClick = onPickFiles) {
                    Text(stringResource(R.string.gesture_pick_files))
                }
            }
        }
    }
}

private fun Modifier.graphicsLayerScale(scale: Float): Modifier =
    this.then(
        Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    )

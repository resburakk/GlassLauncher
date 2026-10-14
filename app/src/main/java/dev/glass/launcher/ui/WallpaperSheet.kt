package dev.glass.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.glass.launcher.data.WallpaperTarget

/**
 * Ana ekranda boşluğa basılı tutunca (düzenleme modu) "Duvar Kâğıdı" ile alttan açılan cam panel:
 * nereye uygulanacağı (ana ekran / kilit / ikisi), galeriden seçme ve sistem ayarları.
 */
@Composable
fun WallpaperSheet(
    visible: Boolean,
    target: WallpaperTarget,
    strength: Float,
    hasLauncherWallpaper: Boolean,
    onTarget: (WallpaperTarget) -> Unit,
    onPick: () -> Unit,
    onSystem: () -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit,
) {
    val motion = LocalMotion.current
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(motion.tween(180)), exit = fadeOut(motion.tween(160))) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)).clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose,
                )
            )
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(motion.bouncy()) { it } + fadeIn(motion.tween(160)),
            exit = slideOutVertically(motion.tween(200)) { it } + fadeOut(motion.tween(160)),
        ) {
            val shape = RoundedCornerShape(34.dp)
            Box(
                Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 10.dp, vertical = 10.dp)
                    .fillMaxWidth()
            ) {
                BlurBackdrop(34.dp, Modifier.matchParentSize())
                Column(
                    Modifier
                        .fillMaxWidth()
                        .liquidGlass(shape, strength, 18.dp, panel = true)
                        .background(Color(0xFF1C1C1E), shape)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.width(36.dp).height(5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.35f)))
                    Spacer(Modifier.height(12.dp))
                    Text("Duvar Kâğıdı", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Nereye uygulansın?", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.12f)).padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        WallpaperTarget.entries.forEach { t ->
                            val sel = t == target
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(if (sel) Color.White.copy(alpha = 0.92f) else Color.Transparent)
                                    .clickable { onTarget(t) }
                                    .padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    t.title, fontSize = 14.sp, textAlign = TextAlign.Center,
                                    color = if (sel) Color.Black else Color.White,
                                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    SheetButton("Galeriden Seç", primary = true, onClick = onPick)
                    Spacer(Modifier.height(8.dp))
                    SheetButton("Telefonun duvar kâğıdı ayarları", onClick = onSystem)
                    if (hasLauncherWallpaper) {
                        Spacer(Modifier.height(8.dp))
                        SheetButton("Launcher duvar kâğıdını kaldır", destructive = true, onClick = onRemove)
                    }
                    Spacer(Modifier.height(8.dp))
                    SheetButton("Vazgeç", onClick = onClose)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun SheetButton(text: String, primary: Boolean = false, destructive: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (primary) Color(0xFF0A84FF) else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, fontSize = 16.sp, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
            color = if (destructive) Color(0xFFFF453A) else Color.White,
        )
    }
}

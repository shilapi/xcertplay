package com.shilapi.xcertplay

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Landscape launch screen shown when the user opens the app from the launcher.
 *
 * It is locked to landscape (see `android:screenOrientation="landscape"` in the manifest) and
 * hands off to [CarPlayHostActivity] when the user taps "进入 CarPlay". Boot auto-start and USB
 * attach still launch [CarPlayHostActivity] directly so they skip this screen.
 *
 * Styled as a light "liquid glass" surface to match the host UI: a soft gradient background with
 * two colour glows, over which the branding and action panels float as translucent white cards.
 */
class LandscapeLaunchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The launch surface is light regardless of the system theme, so the system bars always
        // need dark icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        setContent {
            LandscapeLaunchScreen(onEnterCarPlay = ::enterCarPlay)
        }
    }

    private fun enterCarPlay() {
        startActivity(
            Intent(this, CarPlayHostActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            },
        )
        finish()
    }
}

@Composable
private fun LandscapeLaunchScreen(onEnterCarPlay: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(LaunchBackgroundTop, LaunchBackgroundBottom))),
    ) {
        // Soft colour glows behind the glass so the translucency has something to pick up.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = (-110).dp, y = (-150).dp)
                .size(520.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(LaunchGlowMint, Color.Transparent),
                    ),
                    CircleShape,
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 130.dp, y = 150.dp)
                .size(480.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(LaunchGlowBlue, Color.Transparent),
                    ),
                    CircleShape,
                ),
        )

        GlassCard(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 48.dp, vertical = 32.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "xcertplay",
                        color = LaunchAccent,
                        fontSize = 52.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Android 车机 CarPlay 接收端",
                        color = LaunchPrimary,
                        fontSize = 18.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "有线 / 无线连接 · CH341 或板载 I2C MFi 认证",
                        color = LaunchTertiary,
                        fontSize = 14.sp,
                    )
                }

                Spacer(Modifier.width(56.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Button(
                        onClick = onEnterCarPlay,
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LaunchAccent,
                            contentColor = LaunchButtonText,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp),
                    ) {
                        Text(
                            text = "进入 CarPlay",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = "点击左上角「设置」可调整配置，CarPlay 连接后自动隐藏",
                        color = LaunchSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Translucent white card with a bright hairline rim, mirroring the host UI's glass surfaces. */
@Composable
private fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier = modifier
            .shadow(elevation = 24.dp, shape = shape, clip = false)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(LaunchGlassTop, LaunchGlassBottom)), shape)
            .border(1.dp, LaunchGlassRim, shape)
            .padding(horizontal = 32.dp, vertical = 32.dp),
        content = content,
    )
}

// Mirrors GlassPalette (common/src/main/java/com/shilapi/xcertplay/GlassPalette.kt) as Compose
// colours. Keep both in sync when adjusting the palette.
private val LaunchBackgroundTop = Color(0xFFF2F5F9)
private val LaunchBackgroundBottom = Color(0xFFE2E8F1)
private val LaunchGlassTop = Color(0xF2FFFFFF)
private val LaunchGlassBottom = Color(0xD6FFFFFF)
private val LaunchGlassRim = Color(0xCCFFFFFF)
private val LaunchPrimary = Color(0xFF11181C)
private val LaunchSecondary = Color(0xFF5A6672)
private val LaunchTertiary = Color(0xFF8A949E)
private val LaunchAccent = Color(0xFF0A8F5F)
private val LaunchButtonText = Color(0xFFFFFFFF)
private val LaunchGlowMint = Color(0x668CDDB4)
private val LaunchGlowBlue = Color(0x667FB8E8)

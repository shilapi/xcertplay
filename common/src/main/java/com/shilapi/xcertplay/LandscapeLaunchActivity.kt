package com.shilapi.xcertplay

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 */
class LandscapeLaunchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
            .background(LaunchBackground)
            .safeDrawingPadding()
            .padding(horizontal = 56.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center,
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
                    color = LaunchSecondary,
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
                    shape = RoundedCornerShape(14.dp),
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

private val LaunchBackground = Color(0xFF161618)
private val LaunchAccent = Color(0xFF7FCD9A)
private val LaunchSecondary = Color(0xFFAAB4BE)
private val LaunchTertiary = Color(0xFF6E7A84)
private val LaunchButtonText = Color(0xFF08110B)

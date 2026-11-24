package io.github.conditionstech.sdm.ui

import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.conditionstech.sdm.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SDM's dark, artwork-led look (owner's choice, 2026-11-24): deep navy-black surfaces, the
 * logo's indigo-to-sky gradient as the accent, big bold titles, generous rounded corners.
 * Our own design; no other app's assets or layouts.
 */
object SdmColors {
    val Background = Color(0xFF0A0A12)
    val Surface = Color(0xFF13131D)
    val Raised = Color(0xFF1C1C29)
    val Line = Color(0xFF2A2A3A)
    val Indigo = Color(0xFF6366F1)
    val Sky = Color(0xFF38BDF8)
    val Text = Color(0xFFF4F4F8)
    val Muted = Color(0xFF9A9AB0)
    val Error = Color(0xFFFF6B7A)

    /** The accent: SDM's logo gradient, used for progress, the play button and highlights. */
    val Accent = Brush.linearGradient(listOf(Indigo, Sky))
}

private val Colors = darkColorScheme(
    primary = SdmColors.Sky,
    onPrimary = Color(0xFF041019),
    primaryContainer = SdmColors.Indigo,
    onPrimaryContainer = Color.White,
    secondary = SdmColors.Indigo,
    secondaryContainer = Color(0xFF26264A),
    onSecondaryContainer = SdmColors.Text,
    background = SdmColors.Background,
    onBackground = SdmColors.Text,
    surface = SdmColors.Background,
    onSurface = SdmColors.Text,
    surfaceVariant = SdmColors.Raised,
    onSurfaceVariant = SdmColors.Muted,
    surfaceContainer = SdmColors.Surface,
    surfaceContainerLow = SdmColors.Surface,
    surfaceContainerHigh = SdmColors.Raised,
    surfaceContainerHighest = SdmColors.Raised,
    outline = SdmColors.Line,
    outlineVariant = SdmColors.Line,
    error = SdmColors.Error,
)

private val Type = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val Shape = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun SdmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Type, shapes = Shape, content = content)
}

/** The big screen titles ("Music", "Videos", "Downloads"). */
val ScreenTitle = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp)

/**
 * A song's or video's picture, made by Android from the file itself (the embedded cover or a
 * video frame); a gradient tile with a note when there is none.
 */
@Composable
fun rememberArtwork(uri: String?, pixels: Int = 512): ImageBitmap? {
    val context = LocalContext.current
    val art by produceState<ImageBitmap?>(null, uri, pixels) {
        value = if (uri == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching<Bitmap> { context.contentResolver.loadThumbnail(Uri.parse(uri), Size(pixels, pixels), null) }
                    .getOrNull()?.asImageBitmap()
            }
        }
    }
    return art
}

@Composable
fun Artwork(uri: String?, size: Dp, modifier: Modifier = Modifier, corner: Dp = 12.dp, pixels: Int = 256) {
    val art = rememberArtwork(uri, pixels)
    Box(
        modifier.then(Modifier.clip(RoundedCornerShape(corner)).background(SdmColors.Accent)).let {
            if (size > 0.dp) it.then(Modifier.size(size)) else it
        },
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(painterResource(R.drawable.ic_tab_music), contentDescription = null, tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(if (size > 0.dp) size * 0.42f else 96.dp))
        }
    }
}


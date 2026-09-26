package com.duoopen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowMetricsCalculator
import com.duoopen.fold.FoldLine
import com.duoopen.settings.DuoSettings
import com.duoopen.ui.DuoApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Exact hinge placement from Jetpack WindowManager, when the window spans a fold. */
    private val foldLine = MutableStateFlow<FoldLine?>(null)
    private lateinit var dualScreen: DualScreen

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@MainActivity)
                    .windowLayoutInfo(this@MainActivity)
                    .collect { info ->
                        val feature = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                        foldLine.value = feature?.let(::learnFold)
                    }
            }
        }

        dualScreen = DualScreen(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                DuoApp(foldLine, dualScreen)
            }
        }
    }

    /**
     * Converts a FoldingFeature into shader geometry and records which side of
     * the display it splits, so the wallpaper (no WindowManager access) can
     * place its hinge correctly in any rotation.
     */
    private fun learnFold(feature: FoldingFeature): FoldLine {
        val bounds = feature.bounds
        val splitsX = feature.orientation == FoldingFeature.Orientation.VERTICAL
        val window = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(this).bounds
        val splitsLong = splitsX == (window.width() >= window.height())
        DuoSettings.update { it.copy(foldSplitsLong = splitsLong) }
        return FoldLine(
            splitsX = splitsX,
            position = if (splitsX) bounds.exactCenterX() else bounds.exactCenterY(),
        )
    }
}

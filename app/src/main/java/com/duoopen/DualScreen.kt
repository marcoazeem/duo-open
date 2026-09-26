package com.duoopen

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.area.WindowAreaCapability
import androidx.window.area.WindowAreaController
import androidx.window.area.WindowAreaInfo
import androidx.window.area.WindowAreaPresentationSessionCallback
import androidx.window.area.WindowAreaSessionPresenter
import androidx.window.core.ExperimentalWindowApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Lights the cover screen alongside the inner screen through the Window Area
 * API ("dual screen" / concurrent display — the same mode the camera's cover
 * preview and Interpreter use). Only some foldables offer it, and the OS
 * enters it only when an app asks, so this is the way to try the effect
 * with both panels on. The presented content is a placeholder; the point is
 * that the second panel is lit, which lets the overlay service run an engine
 * on it too.
 */
@OptIn(ExperimentalWindowApi::class)
class DualScreen(private val activity: ComponentActivity) {

    private val controller = WindowAreaController.getOrCreate()
    private val _status = MutableStateFlow("Checking…")
    val status: StateFlow<String> = _status
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active
    val supported: Boolean get() = rearInfo != null

    private var rearInfo: WindowAreaInfo? = null
    private var presenter: WindowAreaSessionPresenter? = null

    init {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.windowAreaInfos.collect { infos ->
                    Log.i(TAG, "window areas: " + infos.joinToString { info ->
                        "${info.type} ${info.metrics.bounds} present=${info.getCapability(WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA)?.status} " +
                            "transfer=${info.getCapability(WindowAreaCapability.Operation.OPERATION_TRANSFER_ACTIVITY_TO_AREA)?.status}"
                    }.ifEmpty { "(none)" })
                    val rear = infos.firstOrNull { it.type == WindowAreaInfo.Type.TYPE_REAR_FACING }
                    rearInfo = rear
                    val cap = rear?.getCapability(WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA)
                    _status.value = when {
                        rear == null -> "This phone doesn't offer its second screen to apps."
                        cap?.status == WindowAreaCapability.Status.WINDOW_AREA_STATUS_ACTIVE -> "Both screens are on."
                        cap?.status == WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE ->
                            "Available: the cover screen can be lit while the inner screen is in use."
                        cap?.status == WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNAVAILABLE ->
                            "Not available right now — open the phone fully and try again."
                        else -> "This phone doesn't support lighting both screens for apps."
                    }
                }
            }
        }
    }

    fun start() {
        val info = rearInfo ?: return
        if (presenter != null) return
        runCatching {
            controller.presentContentOnWindowArea(
                info.token,
                activity,
                ContextCompat.getMainExecutor(activity),
                object : WindowAreaPresentationSessionCallback {
                    override fun onSessionStarted(session: WindowAreaSessionPresenter) {
                        Log.i(TAG, "dual screen session started")
                        presenter = session
                        session.setContentView(placeholder(session.context))
                        _active.value = true
                    }

                    override fun onSessionEnded(t: Throwable?) {
                        Log.i(TAG, "dual screen session ended: ${t?.message}")
                        presenter = null
                        _active.value = false
                        if (t != null) _status.value = "Dual screen ended: ${t.message}"
                    }

                    override fun onContainerVisibilityChanged(isVisible: Boolean) = Unit
                },
            )
        }.onFailure {
            Log.w(TAG, "presentContentOnWindowArea failed", it)
            _status.value = "Couldn't start dual screen: ${it.message}"
        }
    }

    fun stop() {
        runCatching { presenter?.close() }
        presenter = null
        _active.value = false
    }

    private companion object {
        const val TAG = "DuoDual"
    }

    private fun placeholder(context: Context): View = FrameLayout(context).apply {
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF7B5CFF.toInt(), 0xFFC44EDD.toInt(), 0xFFF0564A.toInt()),
        )
        addView(
            TextView(context).apply {
                text = "Duo Open\n\nBoth screens are on.\nFold the phone."
                setTextColor(Color.WHITE)
                textSize = 26f
                gravity = Gravity.CENTER
            },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
    }
}

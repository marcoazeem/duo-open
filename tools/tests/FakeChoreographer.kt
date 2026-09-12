// Deterministic scheduler for the actual TiltFollower source; no Android runtime.
package android.view
class Choreographer {
    fun interface FrameCallback { fun doFrame(frameTimeNanos: Long) }
    val pending = mutableSetOf<FrameCallback>()
    fun postFrameCallback(f: FrameCallback) { pending.add(f) }
    fun removeFrameCallback(f: FrameCallback) { pending.remove(f) }
    fun tick(t: Long) { val ready = pending.toList(); pending.clear(); ready.forEach { it.doFrame(t) } }
    companion object { private val instance = Choreographer(); fun getInstance() = instance }
}

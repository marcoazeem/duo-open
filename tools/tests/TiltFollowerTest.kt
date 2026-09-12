import android.view.Choreographer
import com.duoopen.fold.TiltFollower
fun main() {
 val clock = Choreographer.getInstance()
 lateinit var follower: TiltFollower
 var calls = 0
 follower = TiltFollower { calls++; follower.cancel() }
 follower.setTarget(10f)
 clock.tick(1_000_000_000L)
 check(calls == 1 && clock.pending.isEmpty()) { "cancel in callback resurrected follower" }
 follower = TiltFollower { calls++; follower.snap(4f) }
 follower.setTarget(10f)
 clock.tick(2_000_000_000L)
 check(follower.current == 4f && clock.pending.isEmpty()) { "snap in callback resurrected follower" }
 var settled = 0f
 follower = TiltFollower { settled = it }
 follower.setTarget(10f)
 repeat(100) { clock.tick(3_000_000_000L + it * 8_333_333L) }
 check(settled == 10f && clock.pending.isEmpty()) { "ordinary animation did not settle" }
 follower.setTarget(20f)
 follower.cancel()
 check(clock.pending.isEmpty()) { "cancel did not remove scheduled callback" }
 follower.setTarget(30f)
 clock.tick(4_000_000_000L)
 check(follower.current > 10f && clock.pending.isNotEmpty()) { "reuse after cancellation failed" }
 follower.cancel()
 println("PASS: cancel/snap during callback, convergence, scheduled cancellation, reuse")
}

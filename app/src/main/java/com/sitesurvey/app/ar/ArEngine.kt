package com.sitesurvey.app.ar

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/** What the UI needs from each camera frame. */
class ArSnapshot(
    val tracking: Boolean,
    val message: String?,
    /** Room space to clip space, or null before the room is anchored. */
    val mvp: FloatArray?,
    val width: Int,
    val height: Int,
    /** A floor surface is under the crosshair (only checked before anchoring). */
    val floorUnderCrosshair: Boolean,
)

/**
 * Owns the ARCore session and the GL thread. The room lives in one anchor: room space is the
 * anchor's local frame (origin at corner 1, y up). The UI does its own geometry with the
 * matrix in each snapshot; only anchoring needs a real hit test, so that runs here.
 */
class ArEngine(private val context: Context) : GLSurfaceView.Renderer {
    @Volatile var session: Session? = null
        private set
    lateinit var view: GLSurfaceView
    var onSnapshot: ((ArSnapshot) -> Unit)? = null

    private val bg = BackgroundRenderer()
    private val main = Handler(Looper.getMainLooper())
    private val actions = ConcurrentLinkedQueue<(Frame) -> Unit>()
    private var anchor: Anchor? = null
    private var width = 1
    private var height = 1
    private var geometryDirty = true
    private var textureBound = false
    private val proj = FloatArray(16)
    private val viewM = FloatArray(16)
    private val model = FloatArray(16)
    private val vp = FloatArray(16)

    /** Returns null on success, or a message to show. */
    fun createSession(): String? = try {
        val s = Session(context)
        val cfg = Config(s).apply {
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            focusMode = Config.FocusMode.AUTO
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) depthMode = Config.DepthMode.AUTOMATIC
        }
        s.configure(cfg)
        session = s
        null
    } catch (e: Exception) {
        "AR couldn't start: ${e.javaClass.simpleName}"
    }

    fun resume(): String? = try {
        session?.resume(); view.onResume(); null
    } catch (e: Exception) {
        "Camera is busy. Close other camera apps and try again."
    }

    fun pause() {
        view.onPause()
        session?.pause()
    }

    fun close() {
        session?.close()
        session = null
    }

    /** Anchors the room at the floor under the crosshair, axes lined up with the world. */
    fun anchorAtCrosshair(done: (Boolean) -> Unit) {
        actions.add { frame ->
            val hit = floorHit(frame)
            val ok = if (hit != null) {
                val t = hit.hitPose.translation
                setAnchor(session!!.createAnchor(Pose(t, floatArrayOf(0f, 0f, 0f, 1f))))
                true
            } else false
            main.post { done(ok) }
        }
    }

    /** Re-anchors at the same spot, turned by [yaw] radians about the vertical. */
    fun rotateAnchor(yaw: Float, done: () -> Unit = {}) {
        actions.add {
            val a = anchor
            if (a != null) {
                val q = floatArrayOf(0f, sin(yaw / 2f), 0f, cos(yaw / 2f))
                setAnchor(session!!.createAnchor(Pose(a.pose.translation, q)))
            }
            main.post(done)
        }
    }

    fun clearAnchor() { actions.add { setAnchor(null) } }

    private fun setAnchor(a: Anchor?) {
        anchor?.detach()
        anchor = a
    }

    private fun floorHit(frame: Frame): HitResult? {
        val camY = frame.camera.pose.ty()
        for (hit in frame.hitTest(width / 2f, height / 2f)) {
            val t = hit.trackable
            val y = hit.hitPose.ty()
            if (hit.distance > 10f) continue
            if (t is Plane && t.type == Plane.Type.HORIZONTAL_UPWARD_FACING && y < camY - 0.3f) return hit
            if (t is DepthPoint && y < camY - 0.6f) return hit
        }
        return null
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        bg.createOnGlThread()
        textureBound = false
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        width = w; height = h
        geometryDirty = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (!textureBound) { s.setCameraTextureName(bg.textureId); textureBound = true }
        if (geometryDirty) {
            val rotation = view.display?.rotation ?: 0
            s.setDisplayGeometry(rotation, width, height)
            geometryDirty = false
        }
        val frame = try { s.update() } catch (e: Exception) { return }
        bg.draw(frame)

        val cam = frame.camera
        val tracking = cam.trackingState == TrackingState.TRACKING
        if (tracking) {
            while (true) {
                val a = actions.poll() ?: break
                try { a(frame) } catch (_: Exception) {}
            }
        }
        if (!tracking) {
            publish(ArSnapshot(false, failureText(cam.trackingFailureReason), null, width, height, false))
            return
        }
        cam.getProjectionMatrix(proj, 0, 0.05f, 60f)
        cam.getViewMatrix(viewM, 0)
        Matrix.multiplyMM(vp, 0, proj, 0, viewM, 0)
        val a = anchor
        val mvp = if (a != null && a.trackingState != TrackingState.STOPPED) {
            a.pose.toMatrix(model, 0)
            FloatArray(16).also { Matrix.multiplyMM(it, 0, vp, 0, model, 0) }
        } else null
        val floor = if (a == null) floorHit(frame) != null else false
        publish(ArSnapshot(true, null, mvp, width, height, floor))
    }

    private fun publish(s: ArSnapshot) { main.post { onSnapshot?.invoke(s) } }

    private fun failureText(r: TrackingFailureReason) = when (r) {
        TrackingFailureReason.INSUFFICIENT_LIGHT -> "Too dark. Turn on the lights."
        TrackingFailureReason.EXCESSIVE_MOTION -> "Moving too fast. Slow down."
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "Aim at something with more detail."
        TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera unavailable."
        else -> "Move the phone slowly to get started."
    }
}

package com.example.rootrecents

/**
 * Hands the finger of the bottom trigger (which owns the touch stream) over to the recents screen
 * while it is starting up and being shown. Everything runs on the main thread.
 */
object PeekBridge {
    interface Listener {
        fun onMove(fx: Float, fy: Float, dx: Float, dy: Float)
        fun onRelease()
    }

    private var listener: Listener? = null
    private var active = false
    private var released = false
    private var sx = 0f
    private var sy = 0f
    private var fx = 0f
    private var fy = 0f

    fun begin(x: Float, y: Float) {
        active = true
        released = false
        sx = x; sy = y; fx = x; fy = y
    }

    fun move(x: Float, y: Float) {
        if (!active) return
        fx = x; fy = y
        listener?.onMove(fx, fy, fx - sx, fy - sy)
    }

    fun release() {
        if (!active) return
        released = true
        listener?.onRelease()
    }

    /** True from the moment the peek begins until the finger is lifted. */
    val isActive: Boolean get() = active && !released

    fun attach(l: Listener) {
        listener = l
        if (active) {
            l.onMove(fx, fy, fx - sx, fy - sy)
            if (released) l.onRelease()
        }
    }

    fun detach(l: Listener) {
        if (listener === l) listener = null
        active = false
    }
}

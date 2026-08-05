package com.pixeltouchpad.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * TouchpadView - captures touch gestures and translates them to cursor/click/scroll events.
 *
 * Gestures:
 * - 1 finger drag: move cursor (smoothed to reduce jitter)
 * - 1 finger tap: left click
 * - 2 finger tap (no movement): right click
 * - 2 finger same direction: scroll (natural direction, with momentum after lift)
 * - 2 finger pinch (distance changes): zoom (Ctrl+scroll)
 * - double-tap then hold: drag. See [enableDragLock]. Replaces the old two-finger hold+add-
 *   finger drag: this is deliberately a *double*-tap (same spot, quick succession) rather than
 *   "any touch soon after a tap," since the latter misfired on ordinary click-then-move-then-
 *   click usage. By default the button releases as soon as you lift the finger (ordinary
 *   click-and-drag feel). With [endDragOnSingleTap] on, it instead persists across lift/re-
 *   touch - move by touching and moving again, no need to hold continuously - until ended by a
 *   single tap.
 * - 3 finger swipe L/R/U/D: back / recent / app drawer / notifications
 */
class TouchpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class SwipeDirection { LEFT, RIGHT, UP, DOWN }

    // --- Configuration ---
    var sensitivity = 1.5f
    var scrollSensitivity = 0.08f
    var enableDragLock = true
    var endDragOnSingleTap = false // false = release ends the drag; true = persist, end on a tap
    var naturalScrolling = true
    private val tapMaxDuration = 200L   // ms
    private val tapMaxDistance = 30f     // px
    private val moveDeadzone = 1.5f      // px, raw finger jitter filter before cursor moves
    private val dragHoldTime = 250L     // ms before a held finger counts as "hold"
    private val threeFingerSwipeThreshold = 100f // px minimum swipe
    private val pinchZoomThreshold = 30f // px change in finger distance

    // Cursor movement smoothing (exponential moving average) - damps sensor/touch noise
    // without adding much lag. Lower alpha = smoother but laggier, higher = snappier but noisier.
    private val smoothingAlpha = 0.4f
    private var smoothedDx = 0f
    private var smoothedDy = 0f

    // Scroll momentum (decays after the fingers lift, like trackpad/phone scrolling)
    private val momentumTickMs = 16L
    private val momentumDecay = 0.93f
    private val momentumMinVelocity = 0.3f // scroll units/tick below which momentum stops
    private val scrollVelocityWindowMs = 100L
    private data class ScrollSample(val time: Long, val vDelta: Float, val hDelta: Float)
    private val scrollSamples = ArrayDeque<ScrollSample>()
    private var pendingMomentum: Runnable? = null

    // --- Cursor state ---
    var cursorX = 0f
        private set
    var cursorY = 0f
        private set
    var displayWidth = 1920f
    var displayHeight = 1080f

    // --- Touch tracking ---
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchStartTime = 0L
    private var activePointerCount = 0
    private var maxPointerCountInGesture = 0

    // Two-finger state
    private var isScrolling = false
    private var lastScrollX = 0f
    private var lastScrollY = 0f
    private var twoFingerTapStartTime = 0L
    private var twoFingerMoved = false
    private var initialPinchDistance = 0f
    private var lastPinchDistance = 0f
    private var isPinching = false

    // Drag-lock state (persists across touch sessions once armed - see class doc)
    private var isDragMode = false
    private val doubleTapWindow = 300L // ms between two taps to count as a double-tap
    private var lastQuickTapTime = 0L
    private var lastQuickTapX = 0f
    private var lastQuickTapY = 0f
    private val holdHandler = Handler(Looper.getMainLooper())
    private var pendingHoldCheck: Runnable? = null

    // Three-finger state
    private var isThreeFingerGesture = false
    private var threeFingerStartX = 0f
    private var threeFingerStartY = 0f
    private var threeFingerLastX = 0f
    private var threeFingerLastY = 0f

    // --- Callbacks ---
    var onCursorMove: ((x: Float, y: Float) -> Unit)? = null
    var onClick: ((x: Float, y: Float) -> Unit)? = null
    var onRightClick: ((x: Float, y: Float) -> Unit)? = null
    var onScroll: ((x: Float, y: Float, vScroll: Float, hScroll: Float) -> Unit)? = null
    var onPinchZoom: ((zoomDelta: Float) -> Unit)? = null
    var onDragStart: (() -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null
    var onThreeFingerSwipe: ((direction: SwipeDirection) -> Unit)? = null

    // --- Drawing ---
    private val bgPaint = Paint().apply { color = Color.parseColor("#1a1a2e") }
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#16213e")
        strokeWidth = 1f
    }
    private val touchPaint = Paint().apply {
        color = Color.parseColor("#0f3460")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val statusPaint = Paint().apply {
        color = Color.parseColor("#e94560")
        textSize = 40f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    private val coordPaint = Paint().apply {
        color = Color.parseColor("#888888")
        textSize = 32f
        textAlign = Paint.Align.LEFT
        isAntiAlias = true
    }
    private var drawTouchX = -1f
    private var drawTouchY = -1f
    private var gestureLabel = ""

    init {
        isClickable = true
        isFocusable = true
    }

    fun resetCursor() {
        cursorX = displayWidth / 2
        cursorY = displayHeight / 2
        invalidate()
    }

    private fun cancelPendingHoldCheck() {
        pendingHoldCheck?.let { holdHandler.removeCallbacks(it) }
        pendingHoldCheck = null
    }

    private fun cancelMomentum() {
        pendingMomentum?.let { holdHandler.removeCallbacks(it) }
        pendingMomentum = null
    }

    private fun recordScrollVelocitySample(vDelta: Float, hDelta: Float) {
        val now = System.currentTimeMillis()
        scrollSamples.addLast(ScrollSample(now, vDelta, hDelta))
        while (scrollSamples.isNotEmpty() && now - scrollSamples.first().time > scrollVelocityWindowMs) {
            scrollSamples.removeFirst()
        }
    }

    private fun startScrollMomentumIfApplicable() {
        val samples = scrollSamples.toList()
        scrollSamples.clear()
        if (samples.size < 2) return

        val span = (samples.last().time - samples.first().time).coerceAtLeast(1L)
        var velocityV = samples.sumOf { it.vDelta.toDouble() }.toFloat() / span * momentumTickMs
        var velocityH = samples.sumOf { it.hDelta.toDouble() }.toFloat() / span * momentumTickMs

        if (abs(velocityV) < momentumMinVelocity && abs(velocityH) < momentumMinVelocity) return

        cancelMomentum()
        val runnable = object : Runnable {
            override fun run() {
                if (abs(velocityV) < momentumMinVelocity && abs(velocityH) < momentumMinVelocity) {
                    pendingMomentum = null
                    return
                }
                onScroll?.invoke(cursorX, cursorY, velocityV, velocityH)
                velocityV *= momentumDecay
                velocityH *= momentumDecay
                holdHandler.postDelayed(this, momentumTickMs)
            }
        }
        pendingMomentum = runnable
        holdHandler.postDelayed(runnable, momentumTickMs)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                cancelPendingHoldCheck()
                cancelMomentum()
                lastTouchX = event.x
                lastTouchY = event.y
                touchStartX = event.x
                touchStartY = event.y
                touchStartTime = System.currentTimeMillis()
                activePointerCount = 1
                maxPointerCountInGesture = 1
                isScrolling = false
                isThreeFingerGesture = false
                isPinching = false
                twoFingerMoved = false
                smoothedDx = 0f
                smoothedDy = 0f
                gestureLabel = if (isDragMode) "DRAG" else ""
                drawTouchX = event.x
                drawTouchY = event.y

                // isDragMode is NOT reset here - it persists across touch sessions while
                // drag-locked (see class doc). Only arm a new hold-check when not already locked.
                if (enableDragLock && !isDragMode) {
                    val dist = sqrt(
                        (event.x - lastQuickTapX).let { it * it } +
                        (event.y - lastQuickTapY).let { it * it }
                    )
                    if (touchStartTime - lastQuickTapTime < doubleTapWindow && dist < tapMaxDistance) {
                        // Second touch of a potential double-tap: arm a delayed check. If this
                        // same finger stays roughly still past dragHoldTime, engage drag lock.
                        val armX = event.x
                        val armY = event.y
                        val check = Runnable {
                            if (activePointerCount == 1 && !isDragMode) {
                                val d = sqrt(
                                    (lastTouchX - armX).let { it * it } +
                                    (lastTouchY - armY).let { it * it }
                                )
                                if (d < tapMaxDistance) {
                                    isDragMode = true
                                    lastQuickTapTime = 0L // consumed - don't also match as an end-tap
                                    gestureLabel = "DRAG"
                                    onDragStart?.invoke()
                                    invalidate()
                                }
                            }
                        }
                        pendingHoldCheck = check
                        holdHandler.postDelayed(check, dragHoldTime)
                    }
                }

                invalidate()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (isDragMode) return true // drag-locked: only single-finger taps matter

                cancelPendingHoldCheck()
                activePointerCount = event.pointerCount
                maxPointerCountInGesture = maxOf(maxPointerCountInGesture, event.pointerCount)

                if (activePointerCount == 2) {
                    isScrolling = true
                    lastScrollX = averageX(event)
                    lastScrollY = averageY(event)
                    twoFingerTapStartTime = System.currentTimeMillis()
                    twoFingerMoved = false
                    initialPinchDistance = fingerDistance(event)
                    lastPinchDistance = initialPinchDistance
                    isPinching = false
                }

                if (activePointerCount >= 3) {
                    isScrolling = false
                    isPinching = false
                    isThreeFingerGesture = true
                    threeFingerStartX = averageX(event)
                    threeFingerStartY = averageY(event)
                    threeFingerLastX = threeFingerStartX
                    threeFingerLastY = threeFingerStartY
                    gestureLabel = "3-FINGER"
                }
            }

            MotionEvent.ACTION_MOVE -> {
                when {
                    isThreeFingerGesture && event.pointerCount >= 3 -> {
                        // Track latest average position for swipe direction
                        threeFingerLastX = averageX(event)
                        threeFingerLastY = averageY(event)
                    }

                    isScrolling && event.pointerCount >= 2 -> {
                        // Check for pinch vs scroll
                        val currentDist = fingerDistance(event)
                        val distDelta = currentDist - initialPinchDistance
                        val currentX = averageX(event)
                        val currentY = averageY(event)
                        val scrollDeltaX = currentX - lastScrollX
                        val scrollDeltaY = currentY - lastScrollY

                        if (!isPinching && abs(distDelta) > pinchZoomThreshold) {
                            // Finger distance changed significantly → pinch zoom
                            isPinching = true
                            gestureLabel = "ZOOM"
                        }

                        if (isPinching) {
                            val pinchDelta = currentDist - lastPinchDistance
                            lastPinchDistance = currentDist
                            if (abs(pinchDelta) > 2f) {
                                twoFingerMoved = true
                                onPinchZoom?.invoke(pinchDelta)
                            }
                        } else {
                            // Normal scroll (both fingers move together)
                            lastScrollX = currentX
                            lastScrollY = currentY
                            if (abs(scrollDeltaX) > 1f || abs(scrollDeltaY) > 1f) {
                                twoFingerMoved = true
                                gestureLabel = "SCROLL"
                                val sign = if (naturalScrolling) 1f else -1f
                                val vDelta = sign * scrollDeltaY * scrollSensitivity
                                val hDelta = sign * scrollDeltaX * scrollSensitivity
                                onScroll?.invoke(cursorX, cursorY, vDelta, hDelta)
                                recordScrollVelocitySample(vDelta, hDelta)
                            }
                        }
                    }

                    !isScrolling && !isThreeFingerGesture && event.pointerCount == 1 -> {
                        // Single-finger cursor movement - same path whether or not drag-locked;
                        // button-hold is handled server-side based on drag state, orthogonal to
                        // how the cursor itself moves.
                        //
                        // The deadzone gates lastTouchX/Y itself (not just whether we apply the
                        // result), so a run of tiny samples - a slow, precise roll - keeps
                        // accumulating into the next event's rawDx/rawDy instead of being
                        // dropped. Smoothing only sees deltas that already cleared the deadzone;
                        // smoothing tiny per-sample noise directly made fine movement worse, since
                        // the exponential average converges to whatever small value keeps coming
                        // in and can sit below the deadzone indefinitely.
                        val rawDx = event.x - lastTouchX
                        val rawDy = event.y - lastTouchY

                        if (abs(rawDx) > moveDeadzone || abs(rawDy) > moveDeadzone) {
                            lastTouchX = event.x
                            lastTouchY = event.y

                            smoothedDx = smoothingAlpha * rawDx + (1 - smoothingAlpha) * smoothedDx
                            smoothedDy = smoothingAlpha * rawDy + (1 - smoothingAlpha) * smoothedDy

                            cursorX += smoothedDx * sensitivity
                            cursorY += smoothedDy * sensitivity
                            onCursorMove?.invoke(cursorX, cursorY)
                        }

                        drawTouchX = event.x
                        drawTouchY = event.y
                        invalidate()
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                activePointerCount = event.pointerCount - 1
                if (isScrolling && !isPinching && activePointerCount < 2) {
                    startScrollMomentumIfApplicable()
                    isScrolling = false
                }
            }

            MotionEvent.ACTION_UP -> {
                val elapsed = System.currentTimeMillis() - touchStartTime
                val distX = event.x - touchStartX
                val distY = event.y - touchStartY
                val dist = sqrt(distX * distX + distY * distY)
                val wasQuickTap = elapsed < tapMaxDuration && dist < tapMaxDistance

                when {
                    isThreeFingerGesture && maxPointerCountInGesture >= 3 -> {
                        // Evaluate three-finger swipe direction
                        val swipeDx = threeFingerLastX - threeFingerStartX
                        val swipeDy = threeFingerLastY - threeFingerStartY
                        val absDx = abs(swipeDx)
                        val absDy = abs(swipeDy)

                        if (absDx > threeFingerSwipeThreshold || absDy > threeFingerSwipeThreshold) {
                            if (absDx > absDy) {
                                if (swipeDx < 0) onThreeFingerSwipe?.invoke(SwipeDirection.LEFT)
                                else onThreeFingerSwipe?.invoke(SwipeDirection.RIGHT)
                            } else {
                                if (swipeDy < 0) onThreeFingerSwipe?.invoke(SwipeDirection.UP)
                                else onThreeFingerSwipe?.invoke(SwipeDirection.DOWN)
                            }
                        }
                    }

                    isDragMode -> {
                        if (endDragOnSingleTap) {
                            // Persists across lift/re-touch; a single quick tap ends it, any
                            // other release just pauses movement until the next touch.
                            if (wasQuickTap) {
                                isDragMode = false
                                onDragEnd?.invoke()
                            }
                        } else {
                            // Default: any release ends the drag immediately (ordinary
                            // click-and-drag feel), tap or not.
                            isDragMode = false
                            onDragEnd?.invoke()
                        }
                    }

                    maxPointerCountInGesture == 2 && !twoFingerMoved && !isPinching -> {
                        // Two-finger tap = right click
                        val twoFingerElapsed = System.currentTimeMillis() - twoFingerTapStartTime
                        if (twoFingerElapsed < tapMaxDuration) {
                            onRightClick?.invoke(cursorX, cursorY)
                        }
                    }

                    !isScrolling && maxPointerCountInGesture <= 1 -> {
                        // Single-finger tap = left click
                        if (wasQuickTap) {
                            onClick?.invoke(cursorX, cursorY)
                            lastQuickTapTime = System.currentTimeMillis()
                            lastQuickTapX = event.x
                            lastQuickTapY = event.y
                        }
                    }
                }

                if (isScrolling && !isPinching) startScrollMomentumIfApplicable()

                // Reset gesture state - isDragMode is intentionally left untouched (see above)
                cancelPendingHoldCheck()
                activePointerCount = 0
                maxPointerCountInGesture = 0
                isScrolling = false
                isThreeFingerGesture = false
                isPinching = false
                gestureLabel = if (isDragMode) "DRAG" else ""
                drawTouchX = -1f
                drawTouchY = -1f
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                // Unlike ACTION_UP, a cancel ends the drag lock defensively - the gesture was
                // aborted/taken over by the system rather than a normal user release, so there's
                // no guarantee a matching end-double-tap will ever arrive.
                cancelPendingHoldCheck()
                if (isDragMode) onDragEnd?.invoke()
                isDragMode = false
                activePointerCount = 0
                maxPointerCountInGesture = 0
                isScrolling = false
                isThreeFingerGesture = false
                isPinching = false
                gestureLabel = ""
                drawTouchX = -1f
                drawTouchY = -1f
                invalidate()
            }
        }
        return true
    }

    private fun averageX(event: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until event.pointerCount) sum += event.getX(i)
        return sum / event.pointerCount
    }

    private fun averageY(event: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until event.pointerCount) sum += event.getY(i)
        return sum / event.pointerCount
    }

    private fun fingerDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Grid
        val gridSpacing = 80f
        var gx = gridSpacing
        while (gx < width) { canvas.drawLine(gx, 0f, gx, height.toFloat(), gridPaint); gx += gridSpacing }
        var gy = gridSpacing
        while (gy < height) { canvas.drawLine(0f, gy, width.toFloat(), gy, gridPaint); gy += gridSpacing }

        // Touch indicator
        if (drawTouchX >= 0 && drawTouchY >= 0) {
            touchPaint.alpha = 100
            canvas.drawCircle(drawTouchX, drawTouchY, 60f, touchPaint)
            touchPaint.alpha = 200
            canvas.drawCircle(drawTouchX, drawTouchY, 20f, touchPaint)
        }

        // Gesture label
        if (gestureLabel.isNotEmpty()) {
            canvas.drawText(gestureLabel, width / 2f, 60f, statusPaint)
        }

        // Cursor coordinates (clamped here only for display; the real tracked
        // value is left unbounded so relative motion keeps flowing to the OS
        // even while the cursor is transiting a display we don't control)
        val coordText = "%.0f, %.0f".format(
            cursorX.coerceIn(0f, displayWidth - 1f),
            cursorY.coerceIn(0f, displayHeight - 1f)
        )
        canvas.drawText(coordText, 20f, height - 20f, coordPaint)
    }
}

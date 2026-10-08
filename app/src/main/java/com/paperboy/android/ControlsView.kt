package com.paperboy.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * On-screen gamepad: D-pad, A, B, Select, Start and a Menu button.
 *
 * Touches are re-evaluated from scratch on every event, so fingers can slide between
 * buttons, press several at once (A+B, or a diagonal) and lift one finger without
 * disturbing the others.
 *
 * Layout: portrait puts the pad below the picture in three bands (MENU; D-pad and A/B;
 * SELECT/START) so no two controls overlap. Landscape puts the D-pad and the A/B
 * buttons in the side columns when there is room for them (always the case with
 * [Viewport.compute]'s reserveSides), and otherwise floats them over the picture.
 *
 * This view takes all the touches, but each button group is drawn by its own small
 * child view ([Part]). A press then only redraws that group, so the screen area the
 * window reports as changed stays small instead of the whole screen; on e-ink that is
 * the area the panel has to refresh.
 *
 * Two looks: translucent white shapes for LCD/OLED screens, and in [einkStyle] solid
 * black outlines that fill black while pressed. Translucency renders as dithered
 * mush on e-ink, and outline-only keeps the number of changed pixels per press small.
 */
class ControlsView(context: Context) : ViewGroup(context) {
    /** Called with the new NativeCore.BTN_* mask whenever it changes. */
    var onButtons: ((Int) -> Unit)? = null

    /** Called when the Menu button is tapped. */
    var onMenu: (() -> Unit)? = null

    var einkStyle = false
        set(value) {
            field = value
            applyStyle()
            parts.forEach { it.invalidate() }
        }

    private val game = Rect()
    private val insets = Rect()

    private var dpadX = 0f
    private var dpadY = 0f
    private var dpadR = 0f
    private var aX = 0f
    private var aY = 0f
    private var bX = 0f
    private var bY = 0f
    private var btnR = 0f
    private val selectRect = RectF()
    private val startRect = RectF()
    private val menuRect = RectF()

    private var pressed = 0

    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillOff = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fillOn = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textOff = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val textOn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val plus = Path()

    /**
     * Draws one group of controls; [paint] works in this view's coordinates.
     * (Not called "draw": inside a View that name means View.draw(), which calls onDraw().)
     */
    private inner class Part(private val paint: (Canvas) -> Unit) : View(this@ControlsView.context) {
        val bounds = Rect()

        override fun onDraw(canvas: Canvas) {
            canvas.translate(-left.toFloat(), -top.toFloat())
            paint(canvas)
        }
    }

    private val dpadPart = Part { drawDpad(it) }
    private val abPart = Part {
        drawRound(it, aX, aY, "A", isDown(NativeCore.BTN_A))
        drawRound(it, bX, bY, "B", isDown(NativeCore.BTN_B))
    }
    private val selectPart = Part { drawPill(it, selectRect, "SELECT", isDown(NativeCore.BTN_SELECT)) }
    private val startPart = Part { drawPill(it, startRect, "START", isDown(NativeCore.BTN_START)) }
    private val menuPart = Part { drawPill(it, menuRect, "MENU", false) }
    private val parts = listOf(dpadPart, abPart, selectPart, startPart, menuPart)

    init {
        applyStyle()
        parts.forEach { addView(it) }
    }

    private fun applyStyle() {
        if (einkStyle) {
            outline.color = Color.BLACK
            fillOff.color = Color.TRANSPARENT
            fillOn.color = Color.BLACK
            textOff.color = Color.BLACK
            textOn.color = Color.WHITE
        } else {
            outline.color = Color.TRANSPARENT
            fillOff.color = Color.argb(70, 255, 255, 255)
            fillOn.color = Color.argb(160, 255, 255, 255)
            textOff.color = Color.argb(210, 255, 255, 255)
            textOn.color = Color.argb(210, 255, 255, 255)
        }
    }

    /** [gameRect] is where the picture is drawn; [insetRect] the safe-area insets. */
    fun update(gameRect: Rect, insetRect: Rect) {
        game.set(gameRect)
        insets.set(insetRect)
        placeParts()
    }

    /** Lifts every virtual button (used when the menu opens). */
    fun releaseAll() {
        if (pressed != 0) {
            pressed = 0
            onButtons?.invoke(0)
            parts.forEach { it.invalidate() }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            getDefaultSize(suggestedMinimumHeight, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = placeParts()

    /** Lays out the controls and moves each [Part] onto its group. */
    private fun placeParts() {
        if (width <= 0 || height <= 0) return
        layoutControls()
        val margin = outline.strokeWidth + 2f // room for the stroke and anti-aliasing
        setBounds(dpadPart, dpadX - dpadR, dpadY - dpadR, dpadX + dpadR, dpadY + dpadR, margin)
        setBounds(
            abPart, min(aX, bX) - btnR, min(aY, bY) - btnR, max(aX, bX) + btnR, max(aY, bY) + btnR, margin
        )
        setBounds(selectPart, selectRect, margin)
        setBounds(startPart, startRect, margin)
        setBounds(menuPart, menuRect, margin)
        for (p in parts) {
            val bounds = p.bounds
            p.measure(
                MeasureSpec.makeMeasureSpec(bounds.width(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(bounds.height(), MeasureSpec.EXACTLY),
            )
            p.layout(bounds.left, bounds.top, bounds.right, bounds.bottom)
            p.invalidate() // geometry may have changed without the size changing
        }
    }

    private fun setBounds(p: Part, r: RectF, margin: Float) = setBounds(p, r.left, r.top, r.right, r.bottom, margin)

    private fun setBounds(p: Part, l: Float, t: Float, r: Float, b: Float, margin: Float) {
        p.bounds.set(
            floor(l - margin).toInt(), floor(t - margin).toInt(),
            ceil(r + margin).toInt(), ceil(b + margin).toInt(),
        )
    }

    /** All touches go to [onTouchEvent]; the parts only draw. */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

    private fun layoutControls() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val portrait = h >= w
        val left = insets.left.toFloat()
        val right = w - insets.right
        val top = insets.top.toFloat()
        val bottom = h - insets.bottom
        val aw = right - left
        val ah = bottom - top

        val leftCol = game.left - left
        val rightCol = right - game.right
        val columns = !portrait && min(leftCol, rightCol) >= ah * 0.28f

        if (portrait) {
            layoutPortrait(left, right, max(game.bottom.toFloat(), top), bottom)
            return
        }

        if (columns) {
            dpadR = min(leftCol * 0.42f, ah * 0.22f)
            btnR = min(rightCol * 0.20f, dpadR * 0.55f)
            dpadX = left + leftCol / 2f
            dpadY = top + ah * 0.55f
            placeAB(right - rightCol / 2f, top + ah * 0.55f)
        } else {
            // Overlay: pad floats over the sides of the picture.
            dpadR = min(ah * 0.22f, aw * 0.14f)
            btnR = dpadR * 0.52f
            dpadX = left + dpadR * 1.4f
            dpadY = bottom - dpadR * 1.35f
            placeAB(right - btnR * 3.4f, bottom - dpadR * 1.35f)
        }

        val pillW = max(min(aw, ah) * 0.20f, btnR * 2.4f)
        val pillH = btnR * 0.8f
        // Select/Start: centred under the picture when there is room, else along the bottom edge.
        val under = game.bottom + (bottom - game.bottom) * 0.5f
        val pillY = if (bottom - game.bottom >= pillH * 3f) under else bottom - pillH * 1.8f
        placeSelectStart((left + right) / 2f, pillY, pillW, pillH)

        val pad = pillH * 0.5f
        menuRect.set(left + pad, top + pad, left + pad + pillW * 0.9f, top + pad + pillH)

        applySizes(pillH)
    }

    /**
     * Portrait: the area under the picture is split into three bands, top to bottom:
     * MENU, then the D-pad and A/B side by side, then SELECT/START. Sizes come from
     * both the width and the band heights, so a short control area shrinks the buttons
     * instead of piling them on top of each other.
     */
    private fun layoutPortrait(left: Float, right: Float, areaTop: Float, bottom: Float) {
        val aw = right - left
        val areaH = bottom - areaTop
        val cx = (left + right) / 2f

        val pillH = min(aw * 0.065f, areaH * 0.11f)
        val pillW = pillH * 3.2f
        val menuBand = pillH * 1.7f
        val pillBand = pillH * 2.2f
        val mainTop = areaTop + menuBand
        val mainH = (bottom - pillBand) - mainTop

        // The D-pad sits at 1/4 of the width, the A/B pair at 3/4. A/B reaches 1.12 dpadR
        // left of its centre and 0.91 dpadR above/below it, so these limits keep a gap
        // between the two and keep both inside the middle band.
        dpadR = min(aw * 0.2f, mainH * 0.45f)
        btnR = dpadR * 0.52f
        dpadX = left + aw * 0.25f
        dpadY = mainTop + mainH / 2f
        placeAB(right - aw * 0.25f, dpadY)

        val menuY = areaTop + menuBand / 2f
        menuRect.set(cx - pillW * 0.45f, menuY - pillH / 2f, cx + pillW * 0.45f, menuY + pillH / 2f)
        placeSelectStart(cx, bottom - pillBand / 2f, pillW, pillH)

        applySizes(pillH)
    }

    private fun placeSelectStart(cx: Float, y: Float, pillW: Float, pillH: Float) {
        selectRect.set(cx - pillW * 1.1f, y - pillH / 2f, cx - pillW * 0.1f, y + pillH / 2f)
        startRect.set(cx + pillW * 0.1f, y - pillH / 2f, cx + pillW * 1.1f, y + pillH / 2f)
    }

    private fun applySizes(pillH: Float) {
        outline.strokeWidth = max(3f, btnR * 0.06f)
        textOff.textSize = btnR * 0.9f
        textOn.textSize = textOff.textSize
        smallSize = pillH * 0.42f
    }

    private var smallSize = 12f

    private fun placeAB(abX: Float, abY: Float) {
        aX = abX + btnR * 1.15f
        aY = abY - btnR * 0.75f
        bX = abX - btnR * 1.15f
        bY = abY + btnR * 0.75f
    }

    // ---- drawing ----------------------------------------------------------------------------

    private fun isDown(bit: Int) = (pressed and bit) != 0

    private fun drawDpad(c: Canvas) {
        val r = dpadR
        val arm = r * 0.36f
        val corner = arm * 0.45f
        // The cross as a single outline, so there are no lines through its centre.
        plus.reset()
        plus.moveTo(dpadX - arm, dpadY - r)
        plus.lineTo(dpadX + arm, dpadY - r)
        plus.lineTo(dpadX + arm, dpadY - arm)
        plus.lineTo(dpadX + r, dpadY - arm)
        plus.lineTo(dpadX + r, dpadY + arm)
        plus.lineTo(dpadX + arm, dpadY + arm)
        plus.lineTo(dpadX + arm, dpadY + r)
        plus.lineTo(dpadX - arm, dpadY + r)
        plus.lineTo(dpadX - arm, dpadY + arm)
        plus.lineTo(dpadX - r, dpadY + arm)
        plus.lineTo(dpadX - r, dpadY - arm)
        plus.lineTo(dpadX - arm, dpadY - arm)
        plus.close()
        c.drawPath(plus, fillOff)
        if (einkStyle) c.drawPath(plus, outline)

        if (isDown(NativeCore.BTN_LEFT)) c.drawRoundRect(dpadX - r, dpadY - arm, dpadX, dpadY + arm, corner, corner, fillOn)
        if (isDown(NativeCore.BTN_RIGHT)) c.drawRoundRect(dpadX, dpadY - arm, dpadX + r, dpadY + arm, corner, corner, fillOn)
        if (isDown(NativeCore.BTN_UP)) c.drawRoundRect(dpadX - arm, dpadY - r, dpadX + arm, dpadY, corner, corner, fillOn)
        if (isDown(NativeCore.BTN_DOWN)) c.drawRoundRect(dpadX - arm, dpadY, dpadX + arm, dpadY + r, corner, corner, fillOn)
    }

    private fun drawRound(c: Canvas, x: Float, y: Float, label: String, down: Boolean) {
        c.drawCircle(x, y, btnR, if (down) fillOn else fillOff)
        if (einkStyle) c.drawCircle(x, y, btnR, outline)
        val p = if (down) textOn else textOff
        p.textSize = btnR * 0.9f
        c.drawText(label, x, y - (p.descent() + p.ascent()) / 2f, p)
    }

    private fun drawPill(c: Canvas, r: RectF, label: String, down: Boolean) {
        val radius = r.height() / 2f
        c.drawRoundRect(r, radius, radius, if (down) fillOn else fillOff)
        if (einkStyle) c.drawRoundRect(r, radius, radius, outline)
        val p = if (down) textOn else textOff
        p.textSize = smallSize
        c.drawText(label, r.centerX(), r.centerY() - (p.descent() + p.ascent()) / 2f, p)
    }

    // ---- touch ------------------------------------------------------------------------------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val action = e.actionMasked

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            val i = e.actionIndex
            if (inflated(menuRect).contains(e.getX(i), e.getY(i))) {
                onMenu?.invoke()
                return true
            }
        }

        var mask = 0
        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) {
            // On POINTER_UP the lifted finger is still listed; ignore it.
            val lifted = if (action == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
            for (i in 0 until e.pointerCount) {
                if (i != lifted) mask = mask or hit(e.getX(i), e.getY(i))
            }
        }
        applyMask(mask)
        return true
    }

    private fun hit(x: Float, y: Float): Int {
        var m = 0

        val dx = x - dpadX
        val dy = y - dpadY
        if (hypot(dx, dy) <= dpadR * 1.35f) {
            val dead = dpadR * 0.28f
            if (dx > dead) m = m or NativeCore.BTN_RIGHT
            if (dx < -dead) m = m or NativeCore.BTN_LEFT
            if (dy > dead) m = m or NativeCore.BTN_DOWN
            if (dy < -dead) m = m or NativeCore.BTN_UP
        }

        val reach = btnR * 1.3f
        if (hypot(x - aX, y - aY) <= reach) m = m or NativeCore.BTN_A
        if (hypot(x - bX, y - bY) <= reach) m = m or NativeCore.BTN_B

        if (inflated(selectRect).contains(x, y)) m = m or NativeCore.BTN_SELECT
        if (inflated(startRect).contains(x, y)) m = m or NativeCore.BTN_START
        return m
    }

    private val scratch = RectF()

    /** Slightly larger hit area than the drawn pill; thumbs are not precise. */
    private fun inflated(r: RectF): RectF {
        val grow = r.height() * 0.35f
        scratch.set(r.left - grow, r.top - grow, r.right + grow, r.bottom + grow)
        return scratch
    }

    private fun applyMask(mask: Int) {
        if (mask == pressed) return
        if ((mask and pressed.inv()) != 0) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val changed = mask xor pressed
        pressed = mask
        onButtons?.invoke(mask)
        // Redraw only the groups whose buttons changed.
        if ((changed and DPAD) != 0) dpadPart.invalidate()
        if ((changed and (NativeCore.BTN_A or NativeCore.BTN_B)) != 0) abPart.invalidate()
        if ((changed and NativeCore.BTN_SELECT) != 0) selectPart.invalidate()
        if ((changed and NativeCore.BTN_START) != 0) startPart.invalidate()
    }

    private companion object {
        const val DPAD = NativeCore.BTN_UP or NativeCore.BTN_DOWN or NativeCore.BTN_LEFT or NativeCore.BTN_RIGHT
    }
}

package com.example.island

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class Kind { IDLE, REC, TIMER, MUSIC }

class IslandModel(
    val kind: Kind,
    val leftIcon: Drawable? = null,
    val leftText: String? = null,
    val timerIcon: Boolean = false,
    val eq: Boolean = false,
    val rowText: String? = null,
    val color: Int = Color.WHITE
)

/**
 * Pulau hitam gaya iOS:
 * - Ngepas ke lubang kamera asli (DisplayCutout), bisa dikalibrasi manual.
 * - Ukuran dianimasikan pakai pegas (spring), jadi terasa "hidup" dan real-time.
 * - Isi dibagi kiri / kanan kamera, teks panjang (lirik, detik) di baris bawah.
 */
class IslandView(ctx: Context) : FrameLayout(ctx) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    // Geometri kamera (px)
    private var haveCamera = false
    private var camCx = 0f
    private var camTop = 0f
    private var camW = 0f
    private var camH = 0f

    // Kalibrasi manual (px)
    private var offX = 0f
    private var offY = 0f
    private var extra = 0f

    // Bentuk yang dianimasikan (px)
    private var sw = 0f
    private var sh = 0f
    private val rect = RectF()
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

    // Target tata letak
    private var tgtL = 0f
    private var tgtT = 0f
    private var tgtW = 0f
    private var tgtH = 0f
    private var topH = 0f

    private var kind = Kind.IDLE
    private var lastRow: String? = null
    private var fade: ValueAnimator? = null

    private fun label(sp: Float) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTypeface(typeface, Typeface.BOLD)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        visibility = View.GONE
    }

    private val iconLeft = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(7f))
            }
        }
        clipToOutline = true
        visibility = View.GONE
    }
    private val textLeft = label(13f)
    private val iconRight = ImageView(context).apply {
        setImageResource(R.drawable.ic_timer)
        visibility = View.GONE
    }
    private val eq = EqView(context).apply { visibility = View.GONE }
    private val rowText = label(14f).apply { gravity = Gravity.CENTER }

    private val kids: List<View> by lazy { listOf(iconLeft, textLeft, iconRight, eq, rowText) }

    // Pegas untuk lebar & tinggi
    private val widthProp = object : FloatPropertyCompat<IslandView>("islandW") {
        override fun getValue(o: IslandView): Float = o.sw
        override fun setValue(o: IslandView, value: Float) {
            o.sw = value
            o.invalidate()
        }
    }
    private val heightProp = object : FloatPropertyCompat<IslandView>("islandH") {
        override fun getValue(o: IslandView): Float = o.sh
        override fun setValue(o: IslandView, value: Float) {
            o.sh = value
            o.invalidate()
        }
    }

    private fun newSpring(p: FloatPropertyCompat<IslandView>): SpringAnimation =
        SpringAnimation(this, p).setSpring(
            SpringForce().setDampingRatio(0.72f).setStiffness(380f)
        )

    private val springW = newSpring(widthProp)
    private val springH = newSpring(heightProp)

    init {
        setWillNotDraw(false)
        clipChildren = false
        alpha = 0f
        addView(iconLeft)
        addView(textLeft)
        addView(iconRight)
        addView(eq)
        addView(rowText)
    }

    // ---------- API publik ----------

    fun setCalibration(oxDp: Float, oyDp: Float, extraDp: Float) {
        offX = dp(oxDp)
        offY = dp(oyDp)
        extra = dp(extraDp)
        if (!haveCamera) return
        if (kind == Kind.IDLE) snapToIdle() else retarget(true)
        invalidate()
    }

    fun render(m: IslandModel) {
        val prev = kind
        kind = m.kind
        if (m.kind == Kind.IDLE) {
            collapse()
            return
        }

        iconLeft.visibility = if (m.leftIcon != null) View.VISIBLE else View.GONE
        if (m.leftIcon != null && iconLeft.drawable !== m.leftIcon) iconLeft.setImageDrawable(m.leftIcon)

        textLeft.visibility = if (m.leftText != null) View.VISIBLE else View.GONE
        if (m.leftText != null && textLeft.text.toString() != m.leftText) textLeft.text = m.leftText

        iconRight.visibility = if (m.timerIcon) View.VISIBLE else View.GONE
        eq.visibility = if (m.eq) View.VISIBLE else View.GONE
        eq.setColor(m.color)

        rowText.visibility = if (m.rowText != null) View.VISIBLE else View.GONE
        textLeft.setTextColor(m.color)
        rowText.setTextColor(m.color)
        val rowChanged = m.rowText != lastRow
        lastRow = m.rowText
        if (rowChanged) rowText.text = m.rowText

        animate().cancel()
        alpha = 1f
        retarget(true)

        if (prev != m.kind) {
            fadeContent()
        } else if (rowChanged && m.kind == Kind.MUSIC) {
            rowText.alpha = 0f
            rowText.animate().alpha(1f).setDuration(160).start()
        }
    }

    // ---------- Kamera / cutout ----------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
        postDelayed({ useFallbackCamera() }, 600)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= 28) {
            val screenW = resources.displayMetrics.widthPixels
            val rects = insets.displayCutout?.boundingRects.orEmpty()
            val r = rects
                .filter { it.top < dp(120f) && it.width() > 0 && it.height() > 0 }
                .minByOrNull { abs(it.exactCenterX() - screenW / 2f) }
            if (r != null) {
                setCamera(r.exactCenterX(), r.top.toFloat(), r.width().toFloat(), r.height().toFloat())
            }
        }
        return super.onApplyWindowInsets(insets)
    }

    private fun useFallbackCamera() {
        if (haveCamera) return
        val w = resources.displayMetrics.widthPixels
        setCamera(w / 2f, dp(10f), dp(22f), dp(22f))
    }

    private fun setCamera(cx: Float, top: Float, w: Float, h: Float) {
        val first = !haveCamera
        camCx = cx
        camTop = top
        camW = w
        camH = h
        haveCamera = true
        if (kind == Kind.IDLE) snapToIdle() else retarget(!first)
    }

    // ---------- Animasi ----------

    private fun snapToIdle() {
        springW.cancel()
        springH.cancel()
        sw = camW + extra + dp(8f)
        sh = camH + extra + dp(8f)
        invalidate()
    }

    private fun collapse() {
        if (!haveCamera) return
        lastRow = null
        fade?.cancel()
        fade = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 110
            addUpdateListener { setContentAlpha(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (kind == Kind.IDLE) kids.forEach { it.visibility = View.GONE }
                }
            })
            start()
        }
        springW.animateToFinalPosition(camW + extra + dp(8f))
        springH.animateToFinalPosition(camH + extra + dp(8f))
        animate().cancel()
        animate().setStartDelay(260).setDuration(160).alpha(0f).start()
    }

    private fun fadeContent() {
        fade?.cancel()
        setContentAlpha(0f)
        fade = ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = 90
            duration = 200
            addUpdateListener { setContentAlpha(it.animatedValue as Float) }
            start()
        }
    }

    private fun setContentAlpha(a: Float) {
        kids.forEach { it.alpha = a }
    }

    // ---------- Tata letak ----------

    private fun retarget(animate: Boolean) {
        if (!haveCamera) return
        val screenW = resources.displayMetrics.widthPixels.toFloat()
        val cw = camW + extra
        val ch = camH + extra
        val cx = camCx + offX
        val top = camTop + offY - dp(4f)
        val edge = dp(14f)
        val gap = dp(8f)

        val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        fun exact(px: Float) = View.MeasureSpec.makeMeasureSpec(px.toInt(), View.MeasureSpec.EXACTLY)

        iconLeft.measure(exact(dp(26f)), exact(dp(26f)))
        iconRight.measure(exact(dp(22f)), exact(dp(22f)))
        eq.measure(exact(dp(22f)), exact(dp(16f)))
        textLeft.measure(View.MeasureSpec.makeMeasureSpec(dp(90f).toInt(), View.MeasureSpec.AT_MOST), unspec)
        val maxRow = min(dp(280f), screenW - dp(56f))
        val rowMode = if (kind == Kind.MUSIC) View.MeasureSpec.EXACTLY else View.MeasureSpec.AT_MOST
        rowText.measure(View.MeasureSpec.makeMeasureSpec(maxRow.toInt(), rowMode), unspec)

        val leftW = when {
            iconLeft.visibility == View.VISIBLE -> dp(26f)
            textLeft.visibility == View.VISIBLE -> textLeft.measuredWidth.toFloat()
            else -> 0f
        }
        val rightW = when {
            iconRight.visibility == View.VISIBLE -> dp(22f)
            eq.visibility == View.VISIBLE -> dp(22f)
            else -> 0f
        }
        val slot = max(leftW, rightW)
        val hasRow = rowText.visibility == View.VISIBLE

        topH = max(ch + dp(8f), dp(34f))
        val rowH = if (hasRow) rowText.measuredHeight + dp(10f) else 0f
        val widthTop = if (slot > 0f) cw + 2f * (gap + slot + edge) else cw + dp(8f)
        val widthRow = if (hasRow) rowText.measuredWidth + dp(36f) else 0f

        tgtW = max(widthTop, widthRow)
        tgtH = topH + rowH
        tgtL = cx - tgtW / 2f
        tgtT = top

        layoutKids(cx, edge)

        if (animate) {
            springW.animateToFinalPosition(tgtW)
            springH.animateToFinalPosition(tgtH)
        } else {
            springW.cancel()
            springH.cancel()
            sw = tgtW
            sh = tgtH
            invalidate()
        }
    }

    private fun layoutKids(cx: Float, edge: Float) {
        val cy = tgtT + topH / 2f
        fun place(v: View, left: Float) {
            val w = v.measuredWidth
            val h = v.measuredHeight
            val l = left.toInt()
            val t = (cy - h / 2f).toInt()
            v.layout(l, t, l + w, t + h)
        }
        place(iconLeft, tgtL + edge)
        place(textLeft, tgtL + edge)
        place(iconRight, tgtL + tgtW - edge - iconRight.measuredWidth)
        place(eq, tgtL + tgtW - edge - eq.measuredWidth)
        val rl = (cx - rowText.measuredWidth / 2f).toInt()
        val rt = (tgtT + topH - dp(2f)).toInt()
        rowText.layout(rl, rt, rl + rowText.measuredWidth, rt + rowText.measuredHeight)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            View.MeasureSpec.getSize(widthMeasureSpec),
            View.MeasureSpec.getSize(heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (haveCamera) layoutKids(camCx + offX, dp(14f))
    }

    // ---------- Gambar ----------

    override fun dispatchDraw(canvas: Canvas) {
        if (!haveCamera || sw <= 0f || sh <= 0f) return
        val left = camCx + offX - sw / 2f
        val top = camTop + offY - dp(4f)
        rect.set(left, top, left + sw, top + sh)
        val radius = min(sh / 2f, dp(30f))
        canvas.drawRoundRect(rect, radius, radius, paint)

        val save = canvas.save()
        path.rewind()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.clipPath(path)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }
}

/** Batang equalizer kecil yang bergerak (gaya visualizer iOS). */
class EqView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
        }
    }

    fun setColor(c: Int) {
        p.color = c
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) {
            if (!anim.isStarted) anim.start()
        } else {
            anim.cancel()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE && !anim.isStarted) anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val n = 4
        val gap = width * 0.12f
        val bw = (width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val f = 0.3f + 0.7f * abs(sin(Math.PI * (t * 2.0 + i * 0.37))).toFloat()
            val h = height * f
            val x = i * (bw + gap)
            canvas.drawRoundRect(x, (height - h) / 2f, x + bw, (height + h) / 2f, bw / 2f, bw / 2f, p)
        }
    }
}

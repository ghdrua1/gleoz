package com.example.hyundai_test

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

internal class GleozArt(private val resources: Resources) {
    private val bitmaps = mutableMapOf<Int, Bitmap>()
    val workshopWalk by lazy { GleozWorkshopTimeline.read(resources, R.raw.gleoz_workshop_walk_timeline) }
    val workshopMagic by lazy { GleozWorkshopTimeline.read(resources, R.raw.gleoz_workshop_magic_timeline) }
    val camp by lazy { GleozWorkshopTimeline.read(resources, R.raw.gleoz_camp_timeline) }

    fun bitmap(id: Int): Bitmap = bitmaps.getOrPut(id) {
        BitmapFactory.decodeResource(resources, id)
    }

    fun oz(hat: Boolean): Bitmap {
        val id = if (hat) R.drawable.gleoz_workshop_oz_moonlight else R.drawable.gleoz_workshop_oz
        return bitmaps.getOrPut(id) {
            val source = BitmapFactory.decodeResource(resources, id)
            // Both outfits use the same crop so the staff and boots stay aligned.
            Bitmap.createBitmap(source, 404, 48, 652, 896)
        }
    }
}

internal class GleozPreviewScene(
    context: Context,
    private val art: GleozArt,
    private val camp: Boolean,
    private val hat: Boolean,
    private val night: Boolean,
    private val separateOz: Boolean,
) : View(context) {
    private val backdropPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val wallpaper = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val colors = if (night) intArrayOf(0xFF9691BC.toInt(), 0xFF7E7BA9.toInt(), 0xFF666596.toInt())
            else intArrayOf(0xFFF5E8F5.toInt(), 0xFFEAD6EE.toInt(), 0xFFDDC3E9.toInt())
        shader = RadialGradient(0f, 0f, 1f, colors, floatArrayOf(0f, if (night) 0.62f else 0.6f, 1f), Shader.TileMode.CLAMP)
    }
    private val backdropBounds = RectF(
        0f, GleozWorkshopPlacement.BACKGROUND_TOP,
        GleozWorkshopPlacement.WIDTH,
        GleozWorkshopPlacement.BACKGROUND_TOP + GleozWorkshopPlacement.BACKGROUND_HEIGHT,
    )
    private val backdropId = if (night) R.drawable.gleoz_camp_night_background else R.drawable.gleoz_camp_day_background
    private val starsId = if (night) R.drawable.gleoz_night_stars else R.drawable.gleoz_day_stars
    private var placement: GleozWorkshopPlacement? = null
    private var campTime = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) placement = GleozWorkshopPlacement.fit(w.toFloat(), h.toFloat(), camp)
    }

    fun campAnimator() = sceneTimelineAnimator(art.camp.durationMs, repeat = true) { frame ->
        campTime = frame
        invalidate()
    }

    fun resetCamp() {
        campTime = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scenePlacement = placement ?: return
        canvas.save()
        scenePlacement.applyTo(canvas)
        if (camp) {
            canvas.drawBitmap(art.bitmap(backdropId), null, backdropBounds, backdropPaint)
        } else {
            canvas.save()
            canvas.translate(520f, 325f)
            canvas.scale(520f, 325f)
            canvas.drawRect(-1f, -1f, 1f, 1f, wallpaper)
            canvas.restore()
            canvas.drawBitmap(art.bitmap(starsId), null, RectF(0f, 0f, 1040f, 650f), backdropPaint)
        }
        if (!separateOz) {
            val timeline = if (camp) art.camp else art.workshopMagic
            if (!camp) canvas.translate(-157f, -50f)
            timeline.draw(canvas, art, hat, night, campTime)
        }
        canvas.restore()
    }
}


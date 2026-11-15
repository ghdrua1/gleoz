package com.example.hyundai_test

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

internal const val WORKSHOP_NIGHT_TINT = 0x4016172B

internal class GleozWorkshopPlacement private constructor(
    private val left: Float,
    private val top: Float,
    private val scale: Float,
) {
    fun applyTo(canvas: Canvas) {
        canvas.translate(left, top)
        canvas.scale(scale, scale)
    }

    companion object {
        const val WIDTH = 1040f
        const val BACKGROUND_TOP = -8.67f
        const val BACKGROUND_HEIGHT = 693.333f

        fun fit(width: Float, height: Float, camp: Boolean = false): GleozWorkshopPlacement {
            val scale = maxOf(width / WIDTH, height / BACKGROUND_HEIGHT)
            val availableWidth = width / scale
            // Keep the fire and seated Oz in view when the window narrows.
            val center = if (width / height >= 1.25f) WIDTH / 2f else if (camp) 582f else 520f
            val viewLeft = (center - availableWidth / 2f).coerceIn(0f, (WIDTH - availableWidth).coerceAtLeast(0f))
            val imageTop = (height - BACKGROUND_HEIGHT * scale) * 0.2f
            return GleozWorkshopPlacement(-viewLeft * scale, imageTop - BACKGROUND_TOP * scale, scale)
        }
    }
}

internal fun sceneTimelineAnimator(durationMs: Long, repeat: Boolean, onFrame: (Float) -> Unit) =
    ValueAnimator.ofFloat(0f, durationMs.toFloat()).apply {
        duration = durationMs
        if (repeat) repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { onFrame(it.animatedValue as Float) }
    }

internal class GleozWorkshopMotion(
    context: Context,
    private val art: GleozArt,
    private val hat: Boolean,
    private val night: Boolean,
    private val placement: GleozWorkshopPlacement,
) : View(context) {
    private var timeline = art.workshopMagic
    private var time = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun animator() = play(art.workshopMagic, repeat = true)

    fun returnAnimator() = play(art.workshopWalk, repeat = false)

    fun reset() {
        timeline = art.workshopMagic
        time = 0f
        invalidate()
    }

    private fun play(track: GleozWorkshopTimeline, repeat: Boolean) =
        sceneTimelineAnimator(track.durationMs, repeat) { frame ->
            timeline = track
            time = frame
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        placement.applyTo(canvas)
        canvas.translate(-157f, -50f)
        timeline.draw(canvas, art, hat, night, time)
        canvas.restore()
    }
}

internal class GleozWorkshopTimeline private constructor(
    val durationMs: Long,
    private val layers: List<Layer>,
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val source = Rect()
    private val nightDoor = PorterDuffColorFilter(WORKSHOP_NIGHT_TINT, PorterDuff.Mode.SRC_ATOP)
    private val screen = PorterDuffXfermode(PorterDuff.Mode.SCREEN)

    fun draw(canvas: Canvas, art: GleozArt, hat: Boolean, night: Boolean, time: Float) {
        for (layer in layers) {
            if (layer.asset == Asset.DOOR) continue
            val opacity = layer.opacity.at(time)
            if (opacity <= 0f) continue

            val bitmap = if (hat && layer.asset == Asset.OZ) art.oz(true) else art.bitmap(layer.asset.drawable)
            val crop = layer.crop
            val sourceRect = if (crop == null) null else source.apply {
                set(
                    (crop.left * bitmap.width).roundToInt(), (crop.top * bitmap.height).roundToInt(),
                    (crop.right * bitmap.width).roundToInt(), (crop.bottom * bitmap.height).roundToInt(),
                )
            }
            val bounds = layer.bounds
            paint.alpha = (opacity * 255).roundToInt()
            paint.colorFilter = if (night && layer.asset == Asset.DOOR) nightDoor else null
            paint.xfermode = if (layer.asset == Asset.CAMP_GLOW) screen else null
            canvas.save()
            canvas.translate(layer.x.at(time), layer.y.at(time))
            canvas.rotate(layer.rotation.at(time), layer.pivotX, layer.pivotY)
            canvas.scale(layer.scaleX.at(time), layer.scaleY.at(time), layer.pivotX, layer.pivotY)
            canvas.drawBitmap(bitmap, sourceRect, bounds, paint)
            canvas.restore()
        }
    }

    private enum class Asset(val drawable: Int) {
        DOOR(R.drawable.gleoz_village_door),
        OZ(R.drawable.gleoz_village_oz),
        WALK_SHADOW(R.drawable.gleoz_village_walk_shadow),
        WALK(R.drawable.gleoz_village_walk),
        TURN(R.drawable.gleoz_village_turn),
        REAR(R.drawable.gleoz_village_rear),
        MAGIC_SHADOW(R.drawable.gleoz_village_magic_shadow),
        GLOW(R.drawable.gleoz_village_glow),
        SWEEP(R.drawable.gleoz_village_sweep),
        STAR(R.drawable.gleoz_village_star),
        FLOAT(R.drawable.gleoz_village_float),
        CAMP_OZ(R.drawable.gleoz_camp_oz),
        CAMP_SHADOW(R.drawable.gleoz_camp_shadow),
        CAMP_GLOW(R.drawable.gleoz_camp_glow),
        CAMP_FLAME(R.drawable.gleoz_camp_flame),
        CAMP_EMBER_1(R.drawable.gleoz_camp_ember_1),
        CAMP_EMBER_2(R.drawable.gleoz_camp_ember_2),
        CAMP_EMBER_3(R.drawable.gleoz_camp_ember_3),
        CAMP_EMBER_4(R.drawable.gleoz_camp_ember_4),
        CAMP_EMBER_5(R.drawable.gleoz_camp_ember_5),
    }

    private class Layer(data: JSONObject) {
        val asset = Asset.valueOf(data.getString("asset").uppercase())
        val bounds = data.getJSONArray("bounds").let { rect ->
            val left = rect.getDouble(0).toFloat()
            val top = rect.getDouble(1).toFloat()
            RectF(left, top, left + rect.getDouble(2).toFloat(), top + rect.getDouble(3).toFloat())
        }
        val crop: RectF? = data.optJSONArray("crop")?.let {
            RectF(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat(), it.getDouble(3).toFloat())
        }
        private val pivot = data.optJSONArray("pivot")
        val pivotX = pivot?.getDouble(0)?.toFloat() ?: bounds.centerX()
        val pivotY = pivot?.getDouble(1)?.toFloat() ?: bounds.centerY()
        val opacity = Track(data.optJSONArray("opacity"), 1f)
        val x = Track(data.optJSONArray("translate-x"), 0f)
        val y = Track(data.optJSONArray("translate-y"), 0f)
        val rotation = Track(data.optJSONArray("rotate"), 0f)
        val scaleX = Track(data.optJSONArray("scale-x"), 1f)
        val scaleY = Track(data.optJSONArray("scale-y"), 1f)
    }

    // The exported CSS keyframes store time in ms, value, and optional outgoing easing.
    private class Track(data: JSONArray?, private val defaultValue: Float) {
        private val keys = List(data?.length() ?: 0) { index ->
            val key = requireNotNull(data).getJSONArray(index)
            Key(key.getDouble(0).toFloat(), key.getDouble(1).toFloat(), key.optInt(2))
        }

        fun at(time: Float): Float {
            if (keys.isEmpty()) return defaultValue
            val end = keys.indexOfFirst { it.time >= time }
            if (end == 0) return keys.first().value
            if (end < 0) return keys.last().value

            val a = keys[end - 1]
            val b = keys[end]
            if (a.value == b.value) return a.value
            val fraction = (time - a.time) / (b.time - a.time)
            val progress = when (a.ease) {
                1 -> easing.getInterpolation(fraction)
                2 -> cssEaseInOut.getInterpolation(fraction)
                else -> fraction
            }
            return a.value + (b.value - a.value) * progress
        }

        private data class Key(val time: Float, val value: Float, val ease: Int)
    }

    companion object {
        private val easing = PathInterpolator(0.5f, 0f, 0.5f, 1f)
        private val cssEaseInOut = PathInterpolator(0.42f, 0f, 0.58f, 1f)

        fun read(resources: Resources, id: Int): GleozWorkshopTimeline {
            val json = resources.openRawResource(id).bufferedReader().use { JSONObject(it.readText()) }
            val layers = json.getJSONArray("layers")
            return GleozWorkshopTimeline(json.getLong("duration"), List(layers.length()) { Layer(layers.getJSONObject(it)) })
        }
    }
}


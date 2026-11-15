package com.example.hyundai_test

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.AbsoluteSizeSpan
import android.text.style.TypefaceSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import kotlin.math.roundToInt

private const val MOTION_TAG = "GleozWorkshopMotion"

class GleozPreviewActivity : Activity() {
    private enum class Page { HOME, MENU, JOURNEY, COMPANION, ITEMS, SCENE }

    private lateinit var root: FrameLayout
    private lateinit var art: GleozArt
    private lateinit var regular: Typeface
    private lateinit var bold: Typeface
    private val preferences by lazy { getSharedPreferences("gleoz_preview", MODE_PRIVATE) }

    private var page = Page.HOME
    private var camp = false
    private var hat = false
    private var growthConfirmed = false
    private var draftCamp = false
    private var draftHat = false

    private var car: Car? = null
    private var restrictions: CarUxRestrictionsManager? = null
    private var connectionSession = 0
    private var started = false
    private var resumed = false
    private val returnTrigger = GleozReturnTrigger()
    private var renderedPreviewAllowed: Boolean? = null
    private var gearSubscription: Pair<CarPropertyManager, CarPropertyManager.CarPropertyEventCallback>? = null
    private var campScene: GleozPreviewScene? = null
    private var workshopMotion: GleozWorkshopMotion? = null
    private var idleAnimator: ValueAnimator? = null
    private var playingReturn = false
    private var backCallback: OnBackInvokedCallback? = null

    private val night get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES
    private val backgroundColor get() = if (night) 0xFF1D1E21.toInt() else 0xFFF7F8FA.toInt()
    private val surfaceColor get() = if (night) 0xFF313236.toInt() else Color.WHITE
    private val inkColor get() = if (night) Color.WHITE else 0xFF131417.toInt()
    private val softColor get() = if (night) 0x0DFFFFFF else 0x0D131417
    private val lineColor get() = if (night) 0x1AFFFFFF else 0x1A131417

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val font = resources.getFont(R.font.gleoz_asta_sans)
        regular = Typeface.create(font, 400, false)
        bold = Typeface.create(font, 700, false)
        art = GleozArt(resources)
        camp = preferences.getBoolean("camp", false)
        hat = preferences.getBoolean("hat", false)
        growthConfirmed = preferences.getBoolean("growth_confirmed", false)
        discardDraft()
        root = FrameLayout(this).apply { setBackgroundColor(backgroundColor) }
        root.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) root.post { render() }
        }
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        started = true
        val session = ++connectionSession
        car = runCatching {
            Car.createCar(this, Handler(Looper.getMainLooper()), Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connection, ready ->
                if (session != connectionSession) return@createCar
                clearRestrictionListener()
                clearGearListener()
                if (ready) {
                    restrictions = runCatching {
                        val manager = connection.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                        manager?.registerListener {
                            if (session == connectionSession) updateVehiclePresentation()
                        }
                        manager
                    }.getOrNull()
                    listenForGear(connection)
                }
                render()
            }
        }.getOrNull()
        render()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        updateIdleMotion()
    }

    override fun onPause() {
        resumed = false
        stopIdleMotion()
        super.onPause()
    }

    override fun onStop() {
        started = false
        ++connectionSession
        clearRestrictionListener()
        clearGearListener()
        render()
        car?.disconnect()
        car = null
        super.onStop()
    }

    private fun clearRestrictionListener() {
        val manager = restrictions
        restrictions = null
        runCatching { manager?.unregisterListener() }
    }

    @Suppress("DEPRECATION") // registerCallback supports the API 34 Pleos image.
    private fun listenForGear(connection: Car) {
        runCatching {
            val manager = connection.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: return
            val gear = VehiclePropertyIds.GEAR_SELECTION
            val listener = object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: CarPropertyValue<*>) {
                    if (gearSubscription?.second !== this || value.propertyId != gear) return
                    val next = if (value.status == CarPropertyValue.STATUS_AVAILABLE) value.value as? Int else null
                    returnTrigger.onGear(next)
                    updateVehiclePresentation()
                }

                override fun onErrorEvent(propertyId: Int, areaId: Int) {
                    if (gearSubscription?.second !== this || propertyId != gear) return
                    returnTrigger.onGear(null)
                    updateVehiclePresentation()
                }
            }
            if (manager.registerCallback(listener, gear, CarPropertyManager.SENSOR_RATE_ONCHANGE)) {
                gearSubscription = manager to listener
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun clearGearListener() {
        val subscription = gearSubscription
        gearSubscription = null
        returnTrigger.onGear(null)
        if (subscription != null) {
            val (manager, listener) = subscription
            runCatching { manager.unregisterCallback(listener, VehiclePropertyIds.GEAR_SELECTION) }
        }
    }

    private fun updateVehiclePresentation() {
        val restricted = currentRestriction()
        if (restricted == null) returnTrigger.onGear(null)
        val allowed = started && restricted == false
        if (allowed != renderedPreviewAllowed) render() else updateIdleMotion(restricted)
    }

    private fun updateIdleMotion(restricted: Boolean? = currentRestriction()) {
        if (restricted == null) returnTrigger.onGear(null)
        if (!started || !resumed || !returnTrigger.parked || restricted != false) {
            stopIdleMotion()
            return
        }
        if (page != Page.HOME || camp) returnTrigger.discardPending()
        val scene = campScene
        val motion = workshopMotion
        if (scene == null && motion == null) return
        if (!ValueAnimator.areAnimatorsEnabled()) {
            returnTrigger.discardPending()
            stopIdleMotion()
            return
        }
        if (returnTrigger.takeIfAllowed(motion != null) && motion != null) {
            startReturnMotion(motion)
            return
        }
        if (idleAnimator != null) return

        idleAnimator = (motion?.animator() ?: scene?.campAnimator())?.also { it.start() }
    }

    private fun startReturnMotion(motion: GleozWorkshopMotion) {
        stopIdleMotion()
        val animator = motion.returnAnimator()
        playingReturn = true
        idleAnimator = animator
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (idleAnimator !== animation) return
                playingReturn = false
                idleAnimator = null
                motion.reset()
                Log.d(MOTION_TAG, "Return completed")
                updateIdleMotion()
            }
        })
        Log.d(MOTION_TAG, "Return started")
        animator.start()
    }

    private fun stopIdleMotion() {
        val previous = idleAnimator
        idleAnimator = null
        if (playingReturn) Log.d(MOTION_TAG, "Return canceled")
        playingReturn = false
        previous?.cancel()
        workshopMotion?.reset()
        campScene?.resetCamp()
    }

    private fun currentRestriction(): Boolean? =
        runCatching { restrictions?.currentCarUxRestrictions?.isRequiresDistractionOptimization }.getOrNull()

    private fun canShowPreview(): Boolean = started && currentRestriction() == false

    private fun discardDraft() {
        draftCamp = camp
        draftHat = hat
    }

    private fun open(destination: Page) {
        page = destination
        discardDraft()
    }

    private fun act(action: () -> Unit) {
        if (canShowPreview()) {
            returnTrigger.discardPending()
            action()
        }
        render()
    }

    private fun goBack() = act { open(if (page == Page.MENU) Page.HOME else Page.MENU) }

    private fun render() {
        if (!::root.isInitialized || root.width <= 0 || root.height <= 0) return
        val restricted = currentRestriction()
        if (restricted == null) returnTrigger.onGear(null)
        val allowed = started && restricted == false
        renderedPreviewAllowed = allowed
        if (!allowed) open(Page.HOME)
        updateBackCallback(allowed && page != Page.HOME)
        stopIdleMotion()
        workshopMotion = null
        campScene = null
        root.removeAllViews()
        root.setBackgroundColor(backgroundColor)
        val layout = PreviewLayout()
        if (!allowed) {
            layout.add(layout.label(getString(R.string.gleoz_preview_restricted), 32, strong = true, centered = true),
                0, 0, layout.width, layout.height)
            return
        }
        when (page) {
            Page.HOME -> layout.home()
            Page.MENU -> layout.menu()
            Page.JOURNEY -> layout.journey()
            Page.COMPANION -> layout.customize(items = false)
            Page.ITEMS -> layout.customize(items = true)
            Page.SCENE -> layout.options(items = false)
        }
    }

    private fun updateBackCallback(needed: Boolean) {
        if (Build.VERSION.SDK_INT < 33) return
        if (needed && backCallback == null) {
            backCallback = OnBackInvokedCallback { goBack() }.also {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, it)
            }
        } else if (!needed) {
            backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            backCallback = null
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (page == Page.HOME) super.onBackPressed() else goBack()
    }

    private inner class PreviewLayout {
        val width = root.width
        val height = root.height
        private val density = resources.displayMetrics.density
        private val wide = width >= 720f * density && width / height.toFloat() >= 1.25f
        private val scale = minOf(width / (if (wide) 1040f else 560f), height / 650f)
        private val margin = size(40)
        private val contentWidth = width - margin * 2
        private val target = maxOf(size(64), (64f * density).roundToInt())
        private val footerTop = height - size(32) - target

        private fun size(design: Int) = (design * scale).roundToInt()

        fun add(view: View, x: Int, y: Int, w: Int, h: Int, parent: FrameLayout = root) {
            parent.addView(view, FrameLayout.LayoutParams(w.coerceAtLeast(1), h.coerceAtLeast(1)).apply {
                leftMargin = x
                topMargin = y
            })
        }

        fun label(value: CharSequence, designSize: Int, strong: Boolean = false, centered: Boolean = false) =
            TextView(this@GleozPreviewActivity).apply {
                text = value
                setTextSize(TypedValue.COMPLEX_UNIT_PX, designSize * scale)
                typeface = if (strong) bold else regular
                setTextColor(inkColor)
                includeFontPadding = false
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = if (centered) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
            }

        fun home() {
            val scene = sceneArt(camp, hat, separateOz = !camp)
            add(scene, 0, 0, width, height)
            if (camp) {
                campScene = scene
            } else {
                val placement = GleozWorkshopPlacement.fit(width.toFloat(), height.toFloat())
                val motion = GleozWorkshopMotion(this@GleozPreviewActivity, art, hat, night, placement)
                add(motion, 0, 0, width, height)
                workshopMotion = motion
            }
            updateIdleMotion()
            add(icon(R.string.gleoz_menu, menu = true, fill = surfaceColor) { open(Page.MENU) },
                width - size(24) - target, size(20), target, target)
        }


        fun menu() {
            val drawerWidth = if (wide) size(360) else width
            if (wide) {
                add(sceneArt(camp, hat), 0, 0, width, height)
                add(View(this@GleozPreviewActivity).apply {
                    setBackgroundColor(0x29131417)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    setOnClickListener { act { open(Page.HOME) } }
                }, 0, 0, width, height)
            }
            val panel = FrameLayout(this@GleozPreviewActivity).apply {
                setBackgroundColor(if (wide) surfaceColor else backgroundColor)
                isClickable = true
            }
            add(panel, width - drawerWidth, 0, drawerWidth, height)
            val inset = size(if (wide) 24 else 40)
            add(label(getString(R.string.gleoz_menu), 36, strong = true), inset, size(16),
                drawerWidth - inset * 2 - target, size(80), panel)
            add(icon(R.string.gleoz_close, drawable = R.drawable.oz_close, fill = softColor) { open(Page.HOME) },
                drawerWidth - size(24) - target, size(24), target, target, panel)
            listOf(
                R.string.gleoz_journey to Page.JOURNEY,
                R.string.gleoz_customize to Page.COMPANION,
                R.string.gleoz_scenes to Page.SCENE,
            ).forEachIndexed { i, (title, destination) ->
                val row = button(title, fill = softColor) { open(destination) }.apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setPadding(size(24), 0, size(16), 0)
                }
                add(row, inset, size(120) + i * (target + size(16)), drawerWidth - inset * 2, target, panel)
            }
        }

        fun journey() {
            header(R.string.gleoz_journey)
            val gap = size(if (wide) 48 else 32)
            val column = (contentWidth - gap) / 2
            val right = margin + column + gap
            val top = (height - size(180)) / 2 + size(4)
            val distance = getString(R.string.gleoz_distance_demo)
            val styled = SpannableString(distance)
            val numberEnd = distance.indexOf(' ')
            styled.setSpan(AbsoluteSizeSpan(size(40)), 0, numberEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            styled.setSpan(TypefaceSpan(bold), 0, numberEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            metric(R.string.gleoz_distance, styled, 24, margin, top, column)
            metric(R.string.gleoz_growth_stage, getString(if (growthConfirmed) R.string.gleoz_stage_two else R.string.gleoz_stage_one),
                40, right, top, column)

            val status = FrameLayout(this@GleozPreviewActivity).apply { background = rounded(softColor) }
            val statusWidth = if (wide) column else contentWidth
            add(status, if (wide) right else margin, top + size(124), statusWidth, size(56))
            val copy = if (growthConfirmed) R.string.gleoz_next_growth else R.string.gleoz_growth_reached
            val reserved = if (growthConfirmed) size(96) else 0
            add(label(getString(copy), 24), size(16), 0, statusWidth - size(32) - reserved, size(56), status)
            if (growthConfirmed) {
                val remaining = label(getString(R.string.gleoz_remaining_demo), 24, strong = true).apply {
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                }
                add(remaining, statusWidth - size(16) - reserved, 0, reserved, size(56), status)
            } else {
                add(button(R.string.gleoz_confirm_growth, fill = inkColor, primary = true) {
                    growthConfirmed = true
                    preferences.edit().putBoolean("growth_confirmed", true).apply()
                }, width - margin - size(180), footerTop, size(180), target)
            }
        }

        fun customize(items: Boolean) {
            header(R.string.gleoz_customize)
            val tabsWidth = if (wide) size(368) else contentWidth
            val tabWidth = tabsWidth / 2
            listOf(R.string.gleoz_companion to Page.COMPANION, R.string.gleoz_items to Page.ITEMS)
                .forEachIndexed { i, (title, destination) ->
                    val active = page == destination
                    val tab = button(title, strong = active, enabled = !active) { open(destination) }.apply {
                        isSelected = active
                        setTextColor(inkColor)
                    }
                    add(tab, margin + i * tabWidth, size(104), tabWidth, target)
                    rule(margin + i * tabWidth, size(104) + target - size(if (active) 4 else 1),
                        tabWidth, size(if (active) 4 else 1), if (active) inkColor else lineColor)
                }
            if (items) {
                options(items = true)
                return
            }
            val card = FrameLayout(this@GleozPreviewActivity).apply { background = rounded(surfaceColor, 20) }
            val cardTop = size(104) + target + size(20)
            add(card, margin, cardTop, contentWidth, size(112))
            add(label(getString(R.string.gleoz_oz), 30, strong = true), size(24), 0,
                contentWidth - size(152), size(112), card)
            val avatar = ImageView(this@GleozPreviewActivity).apply {
                setImageBitmap(art.oz(hat = false))
                drawable.setFilterBitmap(true)
                background = rounded(softColor)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(size(8), size(4), size(8), size(4))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            add(avatar, contentWidth - size(112), size(16), size(88), size(80), card)
        }

        fun options(items: Boolean) {
            if (!items) header(R.string.gleoz_scenes)
            val top = if (items) size(104) + target + size(if (wide) 20 else 16) else size(112)
            val columnGap = size(24)
            val previewWidth = if (wide) ((contentWidth - columnGap) * (568f / 936f)).roundToInt() else contentWidth
            val previewHeight = if (wide) height - size(32) - top else {
                val room = footerTop - top - size(16) - target * 2 - size(14)
                minOf(size(if (items) 212 else 288), room).coerceAtLeast(size(50))
            }
            val preview = sceneArt(if (items) camp else draftCamp, if (items) draftHat else hat)
            preview.background = rounded(backgroundColor)
            preview.clipToOutline = true
            add(preview, margin, top, previewWidth, previewHeight)
            val listX = if (wide) margin + previewWidth + columnGap else margin
            val listY = if (wide) top else top + previewHeight + size(16)
            val listWidth = width - margin - listX
            val applied = if (items) hat else camp
            val draft = if (items) draftHat else draftCamp
            val titles = if (items) listOf(R.string.gleoz_default_item, R.string.gleoz_moon_hat)
                else listOf(R.string.gleoz_default_scene, R.string.gleoz_camp)
            val rowGap = if (wide && items) size(16) else 0
            titles.forEachIndexed { i, title ->
                val option = i == 1
                val status = when {
                    option == applied -> R.string.gleoz_applied
                    option == draft -> R.string.gleoz_selecting
                    else -> null
                }
                val row = FrameLayout(this@GleozPreviewActivity).apply {
                    isSelected = option == draft
                    background = ripple(Color.TRANSPARENT, 12)
                    setOnClickListener { act { if (items) draftHat = option else draftCamp = option } }
                }
                val rowY = listY + i * (target + rowGap)
                add(row, listX, rowY, listWidth, target)
                val rowInset = size(16)
                add(label(getString(title), 26, strong = true), rowInset, 0, listWidth - rowInset * 2 - size(88), target, row)
                status?.let {
                    val statusLabel = label(getString(it), 20).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
                    add(statusLabel, listWidth - rowInset - size(84), 0, size(84), target, row)
                }
                if (i == 0) rule(listX, rowY + target - size(1), listWidth, size(1))
            }
            val applyWidth = size(144)
            val cancelWidth = size(104)
            val right = width - margin
            val changed = applied != draft
            add(button(R.string.gleoz_cancel, enabled = changed) { discardDraft() },
                right - applyWidth - size(12) - cancelWidth, footerTop, cancelWidth, target)
            add(button(R.string.gleoz_apply, fill = if (changed) inkColor else lineColor, primary = changed, enabled = changed) {
                if (items) {
                    hat = draftHat
                    preferences.edit().putBoolean("hat", hat).apply()
                } else {
                    camp = draftCamp
                    preferences.edit().putBoolean("camp", camp).apply()
                }
                open(Page.HOME)
            }, right - applyWidth, footerTop, applyWidth, target)
        }

        private fun metric(title: Int, value: CharSequence, textSize: Int, x: Int, y: Int, w: Int) {
            add(label(getString(title), 26), x, y, w, size(34))
            add(label(value, textSize, strong = textSize == 40), x, y + size(42), w, size(52))
            rule(x, y + size(103), w, size(1))
        }

        private fun header(title: Int) {
            add(icon(R.string.gleoz_back, drawable = R.drawable.oz_back) { open(Page.MENU) },
                margin, size(24), target, target)
            val titleX = margin + target + size(24)
            add(label(getString(title), 40, strong = true), titleX, size(16), width - titleX - margin, size(80))
        }

        private fun rule(x: Int, y: Int, w: Int, h: Int, color: Int = lineColor) {
            add(View(this@GleozPreviewActivity).apply {
                setBackgroundColor(color)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, x, y, w, h)
        }

        private fun sceneArt(camp: Boolean, hat: Boolean, separateOz: Boolean = false) =
            GleozPreviewScene(this@GleozPreviewActivity, art, camp, hat, night, separateOz)

        private fun button(
            title: Int,
            fill: Int = Color.TRANSPARENT,
            primary: Boolean = false,
            enabled: Boolean = true,
            strong: Boolean = true,
            click: () -> Unit,
        ) = Button(this@GleozPreviewActivity).apply {
            setText(title)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 26f * scale)
            typeface = if (strong) bold else regular
            setTextColor(if (primary) backgroundColor else inkColor)
            setAllCaps(false)
            includeFontPadding = false
            maxLines = 1
            gravity = Gravity.CENTER
            minimumWidth = 0
            minimumHeight = 0
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            stateListAnimator = null
            background = ripple(fill)
            isEnabled = enabled
            if (enabled) setOnClickListener { act(click) }
            else setTextColor((inkColor and 0x00FFFFFF) or 0x8C000000.toInt())
        }

        private fun icon(
            description: Int,
            drawable: Int? = null,
            menu: Boolean = false,
            fill: Int = Color.TRANSPARENT,
            click: () -> Unit,
        ): View {
            val view = if (menu) MenuControl(this@GleozPreviewActivity, inkColor) else ImageButton(this@GleozPreviewActivity).apply {
                setImageResource(requireNotNull(drawable))
                setColorFilter(inkColor)
                scaleType = ImageView.ScaleType.FIT_CENTER
                val padding = size(if (description == R.string.gleoz_close) 8 else 4)
                setPadding(padding, padding, padding, padding)
            }
            return view.apply {
                contentDescription = getString(description)
                background = ripple(fill)
                setOnClickListener { act(click) }
            }
        }

        private fun rounded(color: Int, radius: Int = 16) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = size(radius).toFloat()
        }

        private fun ripple(color: Int, radius: Int = 16) =
            RippleDrawable(ColorStateList.valueOf(lineColor), rounded(color, radius), rounded(Color.WHITE, radius))
    }

    private class MenuControl(context: Context, color: Int) : ImageButton(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val unit = minOf(width, height) / 64f
            val x = width / 2f
            val y = height / 2f
            for (offset in -1..1) {
                val line = y + offset * 9f * unit
                canvas.drawRoundRect(x - 14f * unit, line - 1.5f * unit, x + 14f * unit,
                    line + 1.5f * unit, unit, unit, paint)
            }
        }
    }
}

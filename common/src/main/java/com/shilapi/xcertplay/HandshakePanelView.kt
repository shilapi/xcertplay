package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.HandshakeTimeline.Channel
import com.shilapi.xcertplay.HandshakeTimeline.ChipState
import com.shilapi.xcertplay.HandshakeTimeline.Direction
import com.shilapi.xcertplay.HandshakeTimeline.Group
import com.shilapi.xcertplay.HandshakeTimeline.LinkKind
import com.shilapi.xcertplay.HandshakeTimeline.LinkState
import com.shilapi.xcertplay.HandshakeTimeline.Phase
import com.shilapi.xcertplay.HandshakeTimeline.PhaseKind
import com.shilapi.xcertplay.HandshakeTimeline.Row
import com.shilapi.xcertplay.HandshakeTimeline.State
import com.shilapi.xcertplay.host.R
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * Main-screen connection panel: a progress card (phase track and link icons) and a log
 * card (the exchange, grouped by step). The cards sit side by side on wide areas and stack on tall
 * ones. Must be used on the main thread.
 */
class HandshakePanelView(context: Context) : ViewGroup(context) {
    private var timeline: HandshakeTimeline? = null
    private var status = "Preparing CarPlay"
    private var renderedRevision = -1
    private var renderScheduled = false
    private var followLatest = true
    private var lastShownRowId = -1
    private var tallWidth = 0
    private var tallHeight = 0
    private val expandedGroups = mutableSetOf<Group>()
    private val collapsedGroups = mutableSetOf<Group>()
    private val expandedRows = mutableSetOf<Int>()

    private val progressCard = LinearLayout(context)
    private val logCard = FrameLayout(context)
    private val statusGlyph = StateGlyphView(context)
    private val phaseTitle = text(17f, LABEL, medium = true)
    private val subtitle = text(13f, SECONDARY)
    private val previousRow = LinearLayout(context)
    private val previousText = text(12f, RED)
    private val track = PhaseTrackView(context)
    private val linkLine = LinkLineView(context)
    private val list = LinearLayout(context)
    private val scroll = ScrollView(context)

    private val render = Runnable {
        renderScheduled = false
        renderNow()
    }
    private val tick = object : Runnable {
        override fun run() {
            track.invalidate()
            linkLine.invalidate()
            if (isAnimating()) postDelayed(this, TICK_MILLIS)
        }
    }

    init {
        progressCard.orientation = LinearLayout.VERTICAL
        progressCard.background = cardBackground()
        progressCard.setPadding(dp(18), dp(16), dp(18), dp(16))
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(statusGlyph, LinearLayout.LayoutParams(dp(26), dp(26)))
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        phaseTitle.maxLines = 2
        phaseTitle.ellipsize = TextUtils.TruncateAt.END
        titles.addView(phaseTitle)
        subtitle.maxLines = 2
        subtitle.ellipsize = TextUtils.TruncateAt.END
        titles.addView(subtitle, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(3) })
        header.addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
            marginStart = dp(12)
        })
        progressCard.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        previousRow.orientation = LinearLayout.HORIZONTAL
        previousRow.gravity = Gravity.CENTER_VERTICAL
        previousRow.addView(icon(R.drawable.ic_hs_replay, RED), LinearLayout.LayoutParams(dp(14), dp(14)))
        previousText.isSingleLine = true
        previousText.ellipsize = TextUtils.TruncateAt.END
        previousRow.addView(previousText, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(6) })
        progressCard.addView(previousRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })

        // The spacer takes any spare height, so the track and link line keep a fixed distance
        // from the card's bottom edge however many lines the header needs.
        progressCard.addView(View(context), LinearLayout.LayoutParams(MATCH, 0, 1f))
        progressCard.addView(track, LinearLayout.LayoutParams(MATCH, dp(34)).apply { topMargin = dp(18) })
        progressCard.addView(linkLine, LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(16) })
        addView(progressCard)

        logCard.background = cardBackground()
        logCard.clipToOutline = true
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(0, dp(6), 0, dp(10))
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(list, LayoutParams(MATCH, WRAP))
        scroll.setOnScrollChangeListener { view, _, scrollY, _, _ ->
            val content = (view as ScrollView).getChildAt(0)?.height ?: 0
            followLatest = scrollY + view.height >= content - dp(24)
        }
        logCard.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(logCard)
    }

    /** Shows [next] from scratch; null hides the panel content. */
    fun bind(next: HandshakeTimeline?) {
        timeline = next
        renderedRevision = -1
        tallHeight = 0
        followLatest = true
        lastShownRowId = next?.groups?.flatMap { it.rows }?.maxOfOrNull { it.id } ?: -1
        expandedGroups.clear()
        collapsedGroups.clear()
        expandedRows.clear()
        renderNow()
    }

    /** Host progress also covers permission prompts before the controller starts. */
    fun setStatus(message: String) {
        status = message
        updateSubtitle()
    }

    /** Schedules a redraw after the bound timeline changed. */
    fun notifyChanged() {
        if (renderScheduled) return
        renderScheduled = true
        postDelayed(render, RENDER_INTERVAL_MILLIS)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val gap = dp(GAP_DP)
        if (isWide(width, height)) {
            val total = minOf(width, MAX_WIDE_PX)
            val left = (total * 0.4f).toInt().coerceIn(dp(300), dp(420)).coerceAtMost(total / 2)
            progressCard.measure(exactly(left), exactly(height))
            logCard.measure(exactly(total - left - gap), exactly(height))
        } else {
            val total = minOf(width, dp(MAX_TALL_DP))
            // Unspecified height leaves the centring spacers empty, so the card wraps its content.
            progressCard.measure(exactly(total), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (total != tallWidth) tallHeight = 0
            tallWidth = total
            tallHeight = maxOf(tallHeight, progressCard.measuredHeight)
            progressCard.measure(exactly(total), exactly(minOf(tallHeight, height)))
            logCard.measure(exactly(total), exactly((height - progressCard.measuredHeight - gap).coerceAtLeast(0)))
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val gap = dp(GAP_DP)
        val pw = progressCard.measuredWidth
        val ph = progressCard.measuredHeight
        if (isWide(width, b - t)) {
            val x = (width - pw - gap - logCard.measuredWidth) / 2
            progressCard.layout(x, 0, x + pw, ph)
            logCard.layout(x + pw + gap, 0, x + pw + gap + logCard.measuredWidth, logCard.measuredHeight)
        } else {
            val x = (width - pw) / 2
            progressCard.layout(x, 0, x + pw, ph)
            logCard.layout(x, ph + gap, x + logCard.measuredWidth, ph + gap + logCard.measuredHeight)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restartTicker()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        removeCallbacks(render)
        renderScheduled = false
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        restartTicker()
        if (visibility == VISIBLE) renderNow()
    }

    private fun isWide(width: Int, height: Int): Boolean = width > height * WIDE_RATIO

    private fun restartTicker() {
        removeCallbacks(tick)
        if (isAnimating()) post(tick)
    }

    private fun isAnimating(): Boolean {
        val model = timeline ?: return false
        return isAttachedToWindow && isShown && model.failure == null && model.completedAtMillis == 0L
    }

    private fun renderNow() {
        val model = timeline ?: return
        if (!isShown) return
        restartTicker()
        if (model.revision == renderedRevision) return
        renderedRevision = model.revision
        val failure = model.failure
        val done = model.completedAtMillis > 0
        val phase = currentPhase(model)
        statusGlyph.state = when {
            failure != null -> State.FAILED
            done -> State.DONE
            else -> State.ACTIVE
        }
        phaseTitle.text = when {
            done -> "Connected"
            phase != null -> phase.title
            else -> "Starting"
        }
        phaseTitle.setTextColor(if (failure != null) RED else LABEL)
        val previous = model.previousFailure
        previousText.text = previous?.substringBefore(": ")
        previousRow.visibility = if (previous == null) GONE else VISIBLE
        track.phases = model.phases
        track.invalidate()
        linkLine.model = model
        linkLine.invalidate()
        renderGroups(model)
        updateSubtitle()
        if (followLatest) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** The failed phase, else the phase of the latest exchange while it runs, else the last running one. */
    private fun currentPhase(model: HandshakeTimeline): Phase? {
        val latest = model.latestGroup?.phase
        if (model.failure != null) return model.phases.firstOrNull { it.state == State.FAILED } ?: latest
        if (latest?.state == State.ACTIVE) return latest
        return model.phases.lastOrNull { it.state == State.ACTIVE } ?: latest
    }

    private fun updateSubtitle() {
        val model = timeline ?: return
        val failure = model.failure
        val waiting = model.currentAwaiting()?.second
        val line = when {
            failure != null -> failure.detail
            model.completedAtMillis > 0 -> null
            waiting != null -> waiting.text
            else -> status
        }
        setIfChanged(subtitle, line.orEmpty())
        subtitle.setTextColor(if (failure != null) RED_SOFT else SECONDARY)
        val visibility = if (line.isNullOrEmpty()) GONE else VISIBLE
        if (subtitle.visibility != visibility) subtitle.visibility = visibility
    }

    private fun renderGroups(model: HandshakeTimeline) {
        list.removeAllViews()
        if (model.groups.isEmpty()) {
            list.addView(StateGlyphView(context).apply { state = State.ACTIVE },
                LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(28)
                })
            return
        }
        val shownBefore = lastShownRowId
        model.groups.forEachIndexed { index, group ->
            if (index > 0) list.addView(separator())
            val expanded = group !in collapsedGroups &&
                (group.state != State.DONE || group in expandedGroups || group === model.latestGroup)
            list.addView(groupHeader(model, group, expanded))
            if (expanded) group.rows.forEach { row ->
                list.addView(rowView(row).also { if (row.id > shownBefore) animateIn(it) })
            }
            group.rows.lastOrNull()?.let { lastShownRowId = maxOf(lastShownRowId, it.id) }
        }
    }

    private fun groupHeader(model: HandshakeTimeline, group: Group, expanded: Boolean): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(14), dp(8))
        }
        row.addView(StateGlyphView(context).apply {
            state = if (model.failure != null && group.state == State.ACTIVE) State.SKIPPED else group.state
        }, LinearLayout.LayoutParams(dp(GLYPH_DP), dp(GLYPH_DP)))
        row.addView(text(15f, LABEL, medium = true).apply {
            text = group.title
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(TITLE_GAP_DP) })
        tagIcon(group.tag)?.let {
            row.addView(icon(it, TERTIARY), LinearLayout.LayoutParams(dp(15), dp(15)).apply { marginStart = dp(8) })
        }
        if (group.state == State.DONE && group.endedAtMillis > 0) {
            row.addView(text(13f, TERTIARY).apply {
                text = seconds(group.endedAtMillis - group.startedAtMillis)
                fontFeatureSettings = "tnum"
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        }
        val chevron = icon(R.drawable.ic_hs_chevron, TERTIARY).apply { rotation = if (expanded) 90f else 0f }
        row.addView(chevron, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(4) })
        row.setOnClickListener {
            if (expanded) {
                collapsedGroups += group
                expandedGroups -= group
            } else {
                collapsedGroups -= group
                expandedGroups += group
            }
            chevron.animate().rotation(if (expanded) 0f else 90f).setDuration(CHEVRON_MILLIS).withEndAction {
                renderedRevision = -1
                renderNow()
            }
        }
        return row
    }

    private fun rowView(row: Row): View {
        val color = channelColor(row.channel)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(5), dp(14), dp(7))
        }
        val arrow = when (row.direction) {
            Direction.ACCESSORY_TO_PHONE -> R.drawable.ic_hs_arrow_forward
            Direction.PHONE_TO_ACCESSORY -> R.drawable.ic_hs_arrow_back
            Direction.LOCAL -> R.drawable.ic_hs_dot
        }
        container.addView(icon(arrow, if (row.direction == Direction.LOCAL) TERTIARY else color),
            LinearLayout.LayoutParams(dp(GLYPH_DP), dp(14)).apply { topMargin = dp(2) })
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        container.addView(column, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(TITLE_GAP_DP) })

        val nameLine = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val nameColor = when {
            row.failed -> RED
            row.warning -> CUE
            row.direction == Direction.LOCAL -> SECONDARY
            else -> LABEL
        }
        nameLine.addView(text(14f, nameColor).apply {
            text = row.name
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        val detail = row.detail
        val open = detail != null && row.id in expandedRows
        if (detail != null) {
            nameLine.addView(icon(R.drawable.ic_hs_chevron, TERTIARY).apply { rotation = if (open) 90f else 0f },
                LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(6) })
        }
        column.addView(nameLine)

        if (row.direction != Direction.LOCAL && row.meaning != row.name) {
            column.addView(text(12f, if (row.failed) RED_SOFT else SECONDARY).apply { text = row.meaning },
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
        }
        row.reply?.let { reply ->
            val ok = reply.status == "200"
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            line.addView(icon(if (ok) R.drawable.ic_hs_check else R.drawable.ic_hs_close, if (ok) ACCENT else RED),
                LinearLayout.LayoutParams(dp(13), dp(13)))
            line.addView(text(12f, if (ok) SECONDARY else RED_SOFT).apply {
                text = reply.meaning ?: if (ok) "" else reply.status
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(6) })
            line.addView(text(11f, TERTIARY).apply {
                text = "${reply.latencyMillis}ms"
                fontFeatureSettings = "tnum"
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) })
            column.addView(line, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(3) })
        }
        if (row.chips.isNotEmpty()) {
            column.addView(FlowLayout(context, dp(5)).apply {
                row.chips.forEach { chip ->
                    val chipColor = when (chip.state) {
                        ChipState.OK -> ACCENT
                        ChipState.PARTIAL -> CUE
                        ChipState.MISSING -> SECONDARY
                    }
                    addView(pill(chip.label, chipColor, strike = chip.state == ChipState.MISSING))
                }
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        }
        if (detail != null) {
            container.setOnClickListener {
                if (open) expandedRows -= row.id else expandedRows += row.id
                renderedRevision = -1
                renderNow()
            }
            if (open) {
                column.addView(text(11f, SECONDARY).apply {
                    text = detail
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(FILL_DARK)
                    }
                }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
            }
        }
        return container
    }

    private fun animateIn(view: View) {
        view.alpha = 0f
        view.translationY = dp(6).toFloat()
        view.animate().alpha(1f).translationY(0f).setDuration(ROW_IN_MILLIS).start()
    }

    private fun separator(): View = View(context).apply {
        setBackgroundColor(SEPARATOR)
        layoutParams = LinearLayout.LayoutParams(MATCH, maxOf(1, dp(1) / 2)).apply { marginStart = dp(16 + GLYPH_DP + TITLE_GAP_DP) }
    }

    private fun cardBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(CORNER_DP).toFloat()
        setColor(CARD)
    }

    private fun setIfChanged(view: TextView, value: String) {
        if (view.text.toString() != value) view.text = value
    }

    private fun exactly(size: Int): Int = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)

    private fun icon(res: Int, color: Int): ImageView = ImageView(context).apply {
        setImageResource(res)
        setColorFilter(color)
    }

    private fun text(size: Float, color: Int, medium: Boolean = false): TextView =
        TextView(context).apply {
            textSize = size
            setTextColor(color)
            includeFontPadding = false
            typeface = if (medium) MEDIUM else Typeface.DEFAULT
        }

    private fun pill(label: String, color: Int, strike: Boolean): TextView =
        text(11f, color).apply {
            text = label
            isSingleLine = true
            if (strike) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(withAlpha(color, 36))
            }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Phase icons packed in a row; the running phase pulses. */
    private class PhaseTrackView(context: Context) : View(context) {
        var phases: List<Phase> = emptyList()
        private val density = resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }
        private val dashed = DashPathEffect(floatArrayOf(3 * density, 3 * density), 0f)
        private val icons = mutableMapOf<Int, Drawable>()

        override fun onDraw(canvas: Canvas) {
            if (phases.isEmpty()) return
            val count = phases.size
            val gap = TRACK_GAP_DP * density
            val cy = height / 2f
            val radius = minOf(13 * density, (width - (count - 1) * gap) / count / 2, height / 2f - 2 * density)
            // Nodes sit side by side with a fixed gap, centred as a group.
            val left = (width - count * 2 * radius - (count - 1) * gap) / 2
            val pulse = (SystemClock.uptimeMillis() % PULSE_MILLIS) / PULSE_MILLIS.toFloat()
            phases.forEachIndexed { i, phase ->
                val cx = left + radius + i * (2 * radius + gap)
                val iconColor: Int
                when (phase.state) {
                    State.DONE -> {
                        fill.color = ACCENT
                        canvas.drawCircle(cx, cy, radius, fill)
                        iconColor = LABEL
                    }
                    State.ACTIVE -> {
                        fill.color = withAlpha(CUE, (90 * (1 - pulse)).toInt())
                        canvas.drawCircle(cx, cy, radius * (1 + 0.45f * pulse), fill)
                        fill.color = withAlpha(ACCENT, 90 + (40 * sin(pulse * 2 * PI)).toInt())
                        canvas.drawCircle(cx, cy, radius, fill)
                        iconColor = CUE
                    }
                    State.FAILED -> {
                        fill.color = RED
                        canvas.drawCircle(cx, cy, radius, fill)
                        iconColor = LABEL
                    }
                    State.SKIPPED -> {
                        ring.color = FILL
                        ring.pathEffect = dashed
                        canvas.drawCircle(cx, cy, radius, ring)
                        iconColor = withAlpha(TERTIARY, 90)
                    }
                    State.IDLE -> {
                        fill.color = FILL_DARK
                        canvas.drawCircle(cx, cy, radius, fill)
                        iconColor = TERTIARY
                    }
                }
                val size = radius * 1.1f
                val drawable = icons.getOrPut(phaseIcon(phase.kind)) { context.getDrawable(phaseIcon(phase.kind))!!.mutate() }
                drawable.setBounds((cx - size / 2).toInt(), (cy - size / 2).toInt(), (cx + size / 2).toInt(), (cy + size / 2).toInt())
                drawable.setTint(iconColor)
                drawable.draw(canvas)
            }
        }
    }

    /**
     * One icon per link (Wi-Fi, Bluetooth, AirPlay, tunnel). Connecting links breathe, and the
     * AirPlay icon carries the stream count.
     */
    private class LinkLineView(context: Context) : View(context) {
        var model: HandshakeTimeline? = null
        private val density = resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10 * density
            textAlign = Paint.Align.CENTER
            typeface = MEDIUM
            color = LABEL
        }
        private val icons = mutableMapOf<Int, Drawable>()
        private val badge = RectF()

        override fun onDraw(canvas: Canvas) {
            val model = model ?: return
            val cy = height / 2f
            val links = model.links
            val slot = width / links.size.toFloat()
            val radius = minOf(15 * density, slot / 2 - NODE_GAP_DP / 2 * density, height / 2f - 6 * density)
            val breath = (sin(SystemClock.uptimeMillis() % PULSE_MILLIS / PULSE_MILLIS.toFloat() * 2 * PI) + 1) / 2
            links.forEachIndexed { i, link ->
                val cx = slot * (i + 0.5f)
                val tone = when (link.state) {
                    LinkState.OFF -> TERTIARY
                    LinkState.LISTENING, LinkState.CONNECTING -> CUE
                    LinkState.UP, LinkState.ENCRYPTED -> ACCENT
                    LinkState.RELEASED -> SECONDARY
                    LinkState.FAILED -> RED
                }
                val breathing = link.state == LinkState.LISTENING || link.state == LinkState.CONNECTING
                fill.color = when {
                    link.state == LinkState.OFF -> FILL_DARK
                    breathing -> withAlpha(tone, 40 + (60 * breath).toInt())
                    else -> withAlpha(tone, 56)
                }
                canvas.drawCircle(cx, cy, radius, fill)
                drawIcon(canvas, linkIcon(link.kind), cx, cy, radius * 1.05f, tone)

                if (link.state == LinkState.ENCRYPTED || link.state == LinkState.RELEASED) {
                    val bx = cx + radius * 0.72f
                    val by = cy + radius * 0.72f
                    val br = 6.5f * density
                    fill.color = CARD
                    canvas.drawCircle(bx, by, br + 1.5f * density, fill)
                    fill.color = if (link.state == LinkState.ENCRYPTED) ACCENT else FILL
                    canvas.drawCircle(bx, by, br, fill)
                    val mark = if (link.state == LinkState.ENCRYPTED) R.drawable.ic_hs_lock else R.drawable.ic_hs_check
                    drawIcon(canvas, mark, bx, by, br * 1.3f, LABEL)
                }
                if (link.kind == LinkKind.AIRPLAY && model.streamCount > 0) {
                    val label = model.streamCount.toString()
                    val h = 15 * density
                    val w = maxOf(h, badgeText.measureText(label) + 8 * density)
                    val bx = cx + radius * 0.7f
                    val by = cy - radius * 0.75f
                    badge.set(bx - w / 2, by - h / 2, bx + w / 2, by + h / 2)
                    fill.color = CARD
                    canvas.drawRoundRect(badge.left - 1.5f * density, badge.top - 1.5f * density,
                        badge.right + 1.5f * density, badge.bottom + 1.5f * density, h, h, fill)
                    fill.color = ACCENT
                    canvas.drawRoundRect(badge, h / 2, h / 2, fill)
                    canvas.drawText(label, bx, by - (badgeText.descent() + badgeText.ascent()) / 2, badgeText)
                }
            }
        }

        private fun drawIcon(canvas: Canvas, res: Int, cx: Float, cy: Float, size: Float, color: Int) {
            val drawable = icons.getOrPut(res) { context.getDrawable(res)!!.mutate() }
            drawable.setBounds((cx - size / 2).toInt(), (cy - size / 2).toInt(), (cx + size / 2).toInt(), (cy + size / 2).toInt())
            drawable.setTint(color)
            drawable.draw(canvas)
        }
    }

    /** Status mark: spinner while running, check when done, cross when failed, ring when idle. */
    private class StateGlyphView(context: Context) : View(context) {
        var state: State = State.IDLE
            set(value) {
                field = value
                invalidate()
            }
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
        private val oval = RectF()
        private val check = context.getDrawable(R.drawable.ic_hs_check)!!.mutate()
        private val cross = context.getDrawable(R.drawable.ic_hs_close)!!.mutate()

        override fun onDraw(canvas: Canvas) {
            val r = minOf(width, height) / 2f - density
            val cx = width / 2f
            val cy = height / 2f
            when (state) {
                State.ACTIVE -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = maxOf(2 * density, r * 0.18f)
                    paint.color = FILL
                    canvas.drawCircle(cx, cy, r * 0.85f, paint)
                    paint.color = CUE
                    oval.set(cx - r * 0.85f, cy - r * 0.85f, cx + r * 0.85f, cy + r * 0.85f)
                    val start = (SystemClock.uptimeMillis() % SPIN_MILLIS) * 360f / SPIN_MILLIS
                    canvas.drawArc(oval, start, 100f, false, paint)
                    postInvalidateDelayed(TICK_MILLIS)
                }
                State.DONE -> filled(canvas, cx, cy, r, ACCENT, check)
                State.FAILED -> filled(canvas, cx, cy, r, RED, cross)
                State.IDLE, State.SKIPPED -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1.5f * density
                    paint.color = TERTIARY
                    canvas.drawCircle(cx, cy, r * 0.7f, paint)
                }
            }
        }

        private fun filled(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int, mark: Drawable) {
            paint.style = Paint.Style.FILL
            paint.color = color
            canvas.drawCircle(cx, cy, r, paint)
            val s = r * 0.62f
            mark.setBounds((cx - s).toInt(), (cy - s).toInt(), (cx + s).toInt(), (cy + s).toInt())
            mark.setTint(LABEL)
            mark.draw(canvas)
        }
    }

    /** Wraps chips onto as many lines as needed. */
    private class FlowLayout(context: Context, private val gap: Int) : ViewGroup(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val maxWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            var x = 0
            var y = 0
            var lineHeight = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                child.measure(MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
                if (x > 0 && x + child.measuredWidth > maxWidth) {
                    x = 0
                    y += lineHeight + gap
                    lineHeight = 0
                }
                x += child.measuredWidth + gap
                lineHeight = maxOf(lineHeight, child.measuredHeight)
            }
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + lineHeight + paddingTop + paddingBottom)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val maxWidth = r - l - paddingLeft - paddingRight
            var x = 0
            var y = 0
            var lineHeight = 0
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (x > 0 && x + child.measuredWidth > maxWidth) {
                    x = 0
                    y += lineHeight + gap
                    lineHeight = 0
                }
                child.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + child.measuredWidth, paddingTop + y + child.measuredHeight)
                x += child.measuredWidth + gap
                lineHeight = maxOf(lineHeight, child.measuredHeight)
            }
        }
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val RENDER_INTERVAL_MILLIS = 120L
        private const val TICK_MILLIS = 33L
        private const val PULSE_MILLIS = 1400L
        private const val SPIN_MILLIS = 900L
        private const val ROW_IN_MILLIS = 220L
        private const val CHEVRON_MILLIS = 150L
        private const val GAP_DP = 12
        private const val GLYPH_DP = 18
        private const val TITLE_GAP_DP = 12
        private const val NODE_GAP_DP = 10f
        private const val TRACK_GAP_DP = 6f
        private const val CORNER_DP = 22
        private const val MAX_TALL_DP = 720
        private const val MAX_WIDE_PX = 1200
        private const val WIDE_RATIO = 1.2f

        internal val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

        // One muted violet accent, neutral grey surfaces, near-white text, a muted cyan only for
        // in-progress cues and a muted rose for errors. No gradients. The settings menu shares it.
        internal val ACCENT = Color.rgb(0x92, 0x75, 0xD7)
        internal val LABEL = Color.rgb(0xF7, 0xF8, 0xFF)
        private val CUE = Color.rgb(0x75, 0xAC, 0xB8)
        internal val RED = Color.rgb(0xFF, 0x9E, 0xB0)
        private val RED_SOFT = Color.rgb(0xCB, 0xAE, 0xB3)
        internal val CARD = Color.rgb(0x0F, 0x0F, 0x0F)
        internal val FILL = Color.rgb(0x40, 0x3A, 0x59)
        internal val FILL_DARK = Color.rgb(0x33, 0x33, 0x33)
        internal val SEPARATOR = withAlpha(LABEL, 34)
        internal val SECONDARY = withAlpha(LABEL, 137)
        private val TERTIARY = withAlpha(LABEL, 120)

        internal fun withAlpha(color: Int, alpha: Int): Int =
            Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

        private fun channelColor(channel: Channel): Int = when (channel) {
            Channel.LOCAL, Channel.USB, Channel.NETWORK, Channel.STREAM -> SECONDARY
            Channel.BLUETOOTH, Channel.LOCKDOWN, Channel.IAP2, Channel.RTSP -> ACCENT
        }

        private fun phaseIcon(kind: PhaseKind): Int = when (kind) {
            PhaseKind.MFI -> R.drawable.ic_hs_verified
            PhaseKind.HOTSPOT -> R.drawable.ic_hs_wifi
            PhaseKind.ADVERTISE -> R.drawable.ic_hs_broadcast
            PhaseKind.BLUETOOTH -> R.drawable.ic_hs_bluetooth
            PhaseKind.USB -> R.drawable.ic_hs_usb
            PhaseKind.USB_DATA -> R.drawable.ic_hs_layers
            PhaseKind.LOCKDOWN -> R.drawable.ic_hs_lock
            PhaseKind.NETWORK -> R.drawable.ic_hs_ethernet
            PhaseKind.IAP2 -> R.drawable.ic_hs_link
            PhaseKind.PAIR -> R.drawable.ic_hs_key
            PhaseKind.SESSION -> R.drawable.ic_hs_airplay
            PhaseKind.STREAMS -> R.drawable.ic_hs_play
            PhaseKind.HANDOFF -> R.drawable.ic_hs_sync_alt
        }

        private fun linkIcon(kind: LinkKind): Int = when (kind) {
            LinkKind.WIFI -> R.drawable.ic_hs_wifi
            LinkKind.RFCOMM -> R.drawable.ic_hs_bluetooth
            LinkKind.USB -> R.drawable.ic_hs_usb
            LinkKind.NCM -> R.drawable.ic_hs_ethernet
            LinkKind.AIRPLAY -> R.drawable.ic_hs_airplay
            LinkKind.TUNNEL -> R.drawable.ic_hs_sync_alt
        }

        private fun tagIcon(tag: String?): Int? = when (tag) {
            "Bluetooth" -> R.drawable.ic_hs_bluetooth
            "Tunnel" -> R.drawable.ic_hs_sync_alt
            "CarKit" -> R.drawable.ic_hs_usb
            "USB network" -> R.drawable.ic_hs_ethernet
            "Encrypted" -> R.drawable.ic_hs_lock
            "TCP 7000" -> R.drawable.ic_hs_wifi
            else -> null
        }

        private fun seconds(millis: Long): String =
            if (millis < 1000) "${millis.coerceAtLeast(0)}ms" else String.format(Locale.US, "%.1fs", millis / 1000.0)
    }
}

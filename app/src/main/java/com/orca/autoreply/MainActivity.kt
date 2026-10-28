package com.orca.autoreply

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.SpannableString
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File

class MainActivity : ComponentActivity() {

    private enum class Screen { LIST, PICKER, EDITOR, DND }

    private lateinit var settings: ReplySettings
    private lateinit var content: FrameLayout
    private var screen = Screen.LIST

    // Editor draft: nothing is persisted until the user taps 저장.
    private var editStatus: CallStatus? = null
    private var editIsNew = false
    private var editMessage = ""
    private var editEnabled = true
    private var dirty = false
    private var refreshImageUi: (() -> Unit)? = null
    private val draftFile get() = File(cacheDir, "draft_image.jpg")

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { !it }.keys
            toast(if (denied.isEmpty()) "권한이 모두 허용되었습니다" else "허용되지 않은 권한: ${denied.size}개")
            if (screen == Screen.LIST) showList()
        }

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null && screen == Screen.EDITOR) saveDraftImage(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = ReplySettings(this)
        content = FrameLayout(this).apply { setBackgroundColor(Colors.BG) }
        setContentView(content)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })
    }

    override fun onResume() {
        super.onResume()
        // Refresh readiness tiles after returning from system permission/battery screens.
        if (screen == Screen.LIST) showList()
    }

    private fun handleBack() {
        when (screen) {
            Screen.LIST -> finish()
            Screen.PICKER, Screen.DND -> showList()
            Screen.EDITOR -> if (dirty) confirmDiscard() else showList()
        }
    }

    private fun show(view: View) {
        content.removeAllViews()
        content.addView(view, FrameLayout.LayoutParams(-1, -1))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun chevron() = ImageView(this).apply {
        setImageResource(R.drawable.ic_chevron_right)
        setColorFilter(Colors.SUBTLE)
    }

    // ───────────────────────── 1. 목록 ─────────────────────────

    private fun showList() {
        screen = Screen.LIST
        val on = settings.masterEnabled
        val col = column().apply { setPadding(dp(16), dp(24), dp(16), dp(112)) }

        val bar = row()
        bar.addView(label("통화 후 자동 문자", 22f, Colors.TEXT, true), lp(w = 0, weight = 1f))
        if (SHOW_LOG_EXPORT) bar.addView(iconButton(R.drawable.ic_log, "로그 파일 내보내기") { exportLog() })
        col.addView(bar, lp())

        // Power button: tap to turn auto-send on/off.
        val halo = FrameLayout(this).apply { background = ovalBg(if (on) Colors.HALO_ON else Colors.HALO_OFF) }
        val power = FrameLayout(this).apply {
            background = ovalBg(if (on) Colors.ACCENT else Colors.OFF_FILL)
            elevation = if (on) dp(6).toFloat() else 0f
            isClickable = true
            isFocusable = true
            contentDescription = if (on) "자동 전송 끄기" else "자동 전송 켜기"
            addView(
                ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_power)
                    setColorFilter(if (on) Colors.ON_ACCENT else Colors.OFF_ICON)
                },
                FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER),
            )
            setOnClickListener { toggleMaster() }
        }
        halo.addView(power, FrameLayout.LayoutParams(dp(140), dp(140), Gravity.CENTER))

        val hero = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        hero.addView(halo, LinearLayout.LayoutParams(dp(160), dp(160)))
        val stateText = if (on) "켜짐" else "꺼짐"
        val title = SpannableString("자동 전송 $stateText").apply {
            setSpan(ForegroundColorSpan(if (on) Colors.ACCENT else Colors.OFF_ICON), length - 2, length, 0)
        }
        hero.addView(label(title, 26f, Colors.TEXT, true).apply { gravity = Gravity.CENTER }, lp(top = 20))
        hero.addView(
            label(
                if (on) "통화가 끝나면 상태에 맞는 문자를 보냅니다" else "버튼을 눌러 자동 전송을 켜세요",
                14f, Colors.MUTED,
            ).apply { gravity = Gravity.CENTER },
            lp(top = 4),
        )
        col.addView(hero, lp(top = 24))

        // Readiness tiles (tap to fix).
        val tiles = row().apply { gravity = Gravity.NO_GRAVITY }
        val permOk = allPermissionsGranted()
        val battOk = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        tiles.addView(tile(permOk, "권한", if (permOk) "허용됨" else "눌러서 허용") { requestPermissions() }, tileLp(false))
        tiles.addView(
            tile(battOk, "배터리", if (battOk) "제외됨" else "눌러서 설정") { requestIgnoreBatteryOptimizations() },
            tileLp(false),
        )
        val dndOn = settings.dndEnabled
        tiles.addView(
            tile(
                R.drawable.ic_moon,
                if (dndOn) Colors.ACCENT else Colors.NEUTRAL_BG,
                if (dndOn) Colors.ON_ACCENT else Colors.NEUTRAL_FG,
                "방해금지", dndTileText(),
            ) { showDnd() },
            tileLp(true),
        )
        col.addView(tiles, lp(top = 24))

        // Registered messages.
        val registered = CallStatus.entries.filter { settings.registered(it) }
        val head = row()
        head.addView(label("등록된 문자", 15f, Colors.TEXT, true), lp(w = 0, weight = 1f))
        head.addView(label("${registered.size} / ${CallStatus.entries.size}", 13f, Colors.MUTED))
        col.addView(head, lp(top = 28))

        if (registered.isEmpty()) {
            val empty = column().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(20), dp(24), dp(20), dp(24))
                background = GradientDrawable().apply {
                    setColor(Colors.SURFACE)
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), Colors.DASH, dp(6).toFloat(), dp(4).toFloat())
                }
            }
            empty.addView(label("아직 등록된 문자가 없습니다", 15f, Colors.TEXT, true))
            empty.addView(label("오른쪽 아래 + 버튼을 눌러 템플릿으로 시작하세요", 13f, Colors.MUTED), lp(top = 6))
            col.addView(empty, lp(top = 10))
        }
        registered.forEach { col.addView(listRow(it), lp(top = 10)) }

        val fab = iconButton(R.drawable.ic_plus, "문자 등록", Colors.ON_ACCENT, ovalBg(Colors.ACCENT)) { showPicker() }
            .apply { elevation = dp(8).toFloat() }
        val frame = FrameLayout(this)
        frame.addView(ScrollView(this).apply { addView(col) }, FrameLayout.LayoutParams(-1, -1))
        frame.addView(fab, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(20), dp(24))
        })
        show(frame)
    }

    private fun tileLp(last: Boolean) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
            if (!last) marginEnd = dp(8)
        }

    /** Readiness tile: green check when ok, amber alert otherwise. */
    private fun tile(ok: Boolean, title: String, sub: String, onClick: () -> Unit): LinearLayout =
        if (ok) tile(R.drawable.ic_check, Colors.GREEN_BG, Colors.GREEN_FG, title, sub, onClick)
        else tile(R.drawable.ic_alert, Colors.AMBER_BG, Colors.AMBER_FG, title, sub, onClick)

    private fun tile(icon: Int, bg: Int, fg: Int, title: String, sub: String, onClick: () -> Unit): LinearLayout =
        card(12).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            isFocusable = true
            addView(
                FrameLayout(this@MainActivity).apply {
                    background = ovalBg(bg)
                    addView(
                        ImageView(this@MainActivity).apply {
                            setImageResource(icon)
                            setColorFilter(fg)
                        },
                        FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER),
                    )
                },
                LinearLayout.LayoutParams(dp(32), dp(32)),
            )
            addView(label(title, 13f, Colors.TEXT, true).apply { gravity = Gravity.CENTER }, lp(top = 6))
            addView(label(sub, 12f, Colors.MUTED).apply { gravity = Gravity.CENTER }, lp(top = 2))
            setOnClickListener { onClick() }
        }

    private fun listRow(s: CallStatus): LinearLayout {
        val r = row().apply {
            background = roundBg(Colors.CARD, 16, Colors.BORDER)
            elevation = dp(1).toFloat()
            setPadding(dp(12), dp(12), dp(8), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { showEditor(s, false) }
        }
        // A switched-off message is dimmed (switch stays bright so it can be turned back on).
        val dim = { on: Boolean -> if (on) 1f else 0.42f }
        val left = row().apply { alpha = dim(settings.enabled(s)) }
        left.addView(statusBadge(s, 40), LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
        val texts = column()
        texts.addView(label(s.label, 15f, Colors.TEXT, true))
        val preview = settings.message(s).ifBlank { "(문구 없음)" } + if (settings.imageFile(s).exists()) "  · 이미지" else ""
        texts.addView(label(preview, 13f, Colors.MUTED).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, lp(top = 2))
        left.addView(texts, lp(w = 0, weight = 1f))
        r.addView(left, lp(w = 0, weight = 1f))
        r.addView(appSwitch(settings.enabled(s), "${s.label} 사용") {
            settings.setEnabled(s, it)
            left.alpha = dim(it)
        })
        r.addView(chevron(), LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(4) })
        return r
    }

    private fun toggleMaster() {
        val turningOn = !settings.masterEnabled
        settings.masterEnabled = turningOn
        if (turningOn && !allPermissionsGranted()) requestPermissions()
        showList()
    }

    // ───────────────────────── 방해금지 ─────────────────────────

    private fun fmtTime(min: Int) = "%02d:%02d".format(min / 60, min % 60)

    private fun dndTileText() = when {
        !settings.dndEnabled -> "꺼짐"
        settings.isDndActive() -> "지금 적용 중"
        settings.dndAllDay -> "종일"
        else -> "${fmtTime(settings.dndStart)}~${fmtTime(settings.dndEnd)}"
    }

    /** Changes here are saved immediately (no save button). */
    private fun showDnd() {
        screen = Screen.DND
        val col = column().apply { setPadding(dp(16), dp(24), dp(16), dp(32)) }
        val head = row()
        head.addView(iconButton(R.drawable.ic_back, "뒤로") { showList() })
        head.addView(label("방해금지", 20f, Colors.TEXT, true), lp(w = 0, weight = 1f).apply { marginStart = dp(12) })
        col.addView(head, lp())
        col.addView(
            label("정한 요일과 시간에는 자동 전송이 켜져 있어도 문자를 보내지 않습니다. 변경 사항은 바로 저장됩니다.", 14f, Colors.MUTED),
            lp(top = 12),
        )

        val body = column()
        val statusText = label("", 13f, Colors.MUTED)
        fun refreshStatus() {
            statusText.text = if (!settings.dndEnabled) {
                "방해금지가 꺼져 있습니다"
            } else {
                settings.dndSummary() + " · " + if (settings.isDndActive()) "지금 적용 중" else "지금은 적용되지 않음"
            }
        }

        val enableCard = row().apply {
            background = roundBg(Colors.SURFACE, 16, Colors.BORDER)
            setPadding(dp(16), dp(8), dp(12), dp(8))
        }
        enableCard.addView(label("방해금지 사용", 15f, Colors.TEXT, true), lp(w = 0, weight = 1f))
        enableCard.addView(appSwitch(settings.dndEnabled, "방해금지 사용") {
            settings.dndEnabled = it
            body.alpha = if (it) 1f else 0.4f
            refreshStatus()
        })
        col.addView(enableCard, lp(top = 16))
        col.addView(statusText, lp(top = 10))

        // Weekdays (Mon … Sun).
        body.addView(label("요일", 14f, Colors.TEXT, true))
        val chips = row()
        listOf("월", "화", "수", "목", "금", "토", "일").forEachIndexed { i, name ->
            val chip = label(name, 14f, Colors.BODY, true).apply {
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = "${name}요일"
            }
            fun paint() {
                val selected = (settings.dndDays and (1 shl i)) != 0
                chip.background = roundBg(if (selected) Colors.ACCENT else Colors.CHIP, 12)
                chip.setTextColor(if (selected) Colors.ON_ACCENT else Colors.BODY)
            }
            paint()
            chip.setOnClickListener {
                settings.dndDays = settings.dndDays xor (1 shl i)
                paint()
                refreshStatus()
            }
            chips.addView(chip, lp(w = 0, h = dp(44), weight = 1f).apply { if (i > 0) marginStart = dp(6) })
        }
        body.addView(chips, lp(top = 8))

        // Time window (hidden when "하루 종일" is on).
        val timeBox = row().apply { gravity = Gravity.NO_GRAVITY }
        fun timeCard(title: String, get: () -> Int, set: (Int) -> Unit): LinearLayout {
            val value = label(fmtTime(get()), 22f, Colors.TEXT, true)
            return card(14).apply {
                isClickable = true
                isFocusable = true
                addView(label(title, 12f, Colors.MUTED, true))
                addView(value, lp(top = 4))
                setOnClickListener {
                    android.app.TimePickerDialog(
                        this@MainActivity,
                        { _, h, m ->
                            set(h * 60 + m)
                            value.text = fmtTime(get())
                            refreshStatus()
                        },
                        get() / 60, get() % 60, true,
                    ).show()
                }
            }
        }
        timeBox.addView(
            timeCard("시작", { settings.dndStart }, { settings.dndStart = it }),
            lp(w = 0, weight = 1f).apply { marginEnd = dp(8) },
        )
        timeBox.addView(timeCard("종료", { settings.dndEnd }, { settings.dndEnd = it }), lp(w = 0, weight = 1f))
        val timeHint = label("종료 시각이 시작보다 빠르면 다음 날까지 이어집니다. 예: 22:00 ~ 08:00", 12f, Colors.MUTED)
        fun showTime(allDay: Boolean) {
            timeBox.visibility = if (allDay) View.GONE else View.VISIBLE
            timeHint.visibility = if (allDay) View.GONE else View.VISIBLE
        }

        val allDayCard = row().apply {
            background = roundBg(Colors.SURFACE, 16, Colors.BORDER)
            setPadding(dp(16), dp(8), dp(12), dp(8))
        }
        allDayCard.addView(label("하루 종일", 15f, Colors.TEXT, true), lp(w = 0, weight = 1f))
        allDayCard.addView(appSwitch(settings.dndAllDay, "하루 종일") {
            settings.dndAllDay = it
            showTime(it)
            refreshStatus()
        })
        body.addView(allDayCard, lp(top = 16))
        body.addView(timeBox, lp(top = 12))
        body.addView(timeHint, lp(top = 8))
        showTime(settings.dndAllDay)

        body.alpha = if (settings.dndEnabled) 1f else 0.4f
        col.addView(body, lp(top = 20))
        refreshStatus()
        show(ScrollView(this).apply { addView(col) })
    }

    // ───────────────────────── 2. 템플릿 선택 ─────────────────────────

    private fun showPicker() {
        screen = Screen.PICKER
        val col = column().apply { setPadding(dp(16), dp(24), dp(16), dp(32)) }
        val head = row()
        head.addView(iconButton(R.drawable.ic_back, "뒤로") { showList() })
        head.addView(label("문자 등록", 20f, Colors.TEXT, true), lp(w = 0, weight = 1f).apply { marginStart = dp(12) })
        col.addView(head, lp())
        col.addView(
            label("통화 상태에 맞는 템플릿을 고르면 기본 문구가 채워집니다. 상태마다 문자 하나를 등록할 수 있습니다.", 14f, Colors.MUTED),
            lp(top = 12),
        )

        CallStatus.entries.forEach { s ->
            val taken = settings.registered(s)
            val c = row().apply {
                gravity = Gravity.TOP
                background = roundBg(if (taken) Colors.SURFACE else Colors.CARD, 16, Colors.BORDER)
                setPadding(dp(14), dp(14), dp(12), dp(14))
                if (!taken) {
                    elevation = dp(1).toFloat()
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { showEditor(s, true) }
                }
            }
            val left = row().apply {
                gravity = Gravity.TOP
                alpha = if (taken) 0.5f else 1f
            }
            left.addView(statusBadge(s, 40), LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
            val t = column()
            t.addView(label(s.label, 15f, Colors.TEXT, true))
            t.addView(label(s.description, 13f, Colors.MUTED), lp(top = 2))
            t.addView(label(s.defaultMessage, 13f, Colors.BODY).apply {
                background = roundBg(Colors.CHIP, 10)
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }, lp(top = 8))
            left.addView(t, lp(w = 0, weight = 1f))
            c.addView(left, lp(w = 0, weight = 1f))
            if (taken) {
                c.addView(label("등록됨", 12f, Colors.MUTED, true).apply { setPadding(dp(8), dp(2), 0, 0) })
            } else {
                c.addView(chevron().apply { setColorFilter(Colors.ACCENT) },
                    LinearLayout.LayoutParams(dp(24), dp(24)).apply { topMargin = dp(8) })
            }
            col.addView(c, lp(top = 12))
        }
        show(ScrollView(this).apply { addView(col) })
    }

    // ───────────────────────── 3. 설정(편집) ─────────────────────────

    private fun showEditor(status: CallStatus, isNew: Boolean) {
        screen = Screen.EDITOR
        editStatus = status
        editIsNew = isNew
        dirty = false
        editMessage = if (isNew) status.defaultMessage else settings.message(status)
        editEnabled = if (isNew) true else settings.enabled(status)
        val saved = settings.imageFile(status)
        if (!isNew && saved.exists()) saved.copyTo(draftFile, overwrite = true) else draftFile.delete()

        val col = column().apply { setPadding(dp(16), dp(24), dp(16), dp(32)) }
        val head = row()
        head.addView(iconButton(R.drawable.ic_back, "뒤로") { handleBack() })
        head.addView(statusBadge(status, 32), LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(12) })
        head.addView(label(status.label, 20f, Colors.TEXT, true), lp(w = 0, weight = 1f).apply { marginStart = dp(10) })
        col.addView(head, lp())
        col.addView(label(status.description, 13f, Colors.MUTED), lp(top = 8))

        val enableCard = row().apply {
            background = roundBg(Colors.SURFACE, 16, Colors.BORDER)
            setPadding(dp(16), dp(8), dp(12), dp(8))
        }
        enableCard.addView(label("이 문자 사용", 15f, Colors.TEXT, true), lp(w = 0, weight = 1f))
        enableCard.addView(appSwitch(editEnabled, "이 문자 사용") { editEnabled = it; dirty = true })
        col.addView(enableCard, lp(top = 16))

        // Image first: thumbnail + info + change button, then rotate/rotate/delete as labelled buttons.
        col.addView(label("첨부 이미지 (MMS)", 14f, Colors.TEXT, true), lp(top = 24))
        val imgCard = card()
        val thumb = ImageView(this).apply { background = roundBg(Colors.SURFACE, 12, Colors.FIELD_BORDER) }
        val imgTitle = label("", 15f, Colors.TEXT, true)
        val imgSub = label("", 13f, Colors.BODY)
        val pick = pill("이미지 선택", false, Colors.ACCENT) { imagePicker.launch("image/*") }
        val info = column()
        info.addView(imgTitle)
        info.addView(imgSub, lp(top = 4))
        info.addView(label("JPEG ${MAX_IMAGE_BYTES / 1024}KB 이하로 자동 변환됩니다.", 12f, Colors.MUTED), lp(top = 4))
        info.addView(pick, lp(top = 10))
        val top = row().apply { gravity = Gravity.TOP }
        top.addView(thumb, LinearLayout.LayoutParams(dp(120), dp(160)))
        top.addView(info, lp(w = 0, weight = 1f).apply { marginStart = dp(16) })
        imgCard.addView(top, lp())

        val imgActions = row()
        imgActions.addView(
            iconLabelButton(R.drawable.ic_rotate_left, "왼쪽 회전", Colors.BODY, Colors.CHIP) { rotateDraft(-90) },
            lp(w = 0, h = dp(44), weight = 1f),
        )
        imgActions.addView(
            iconLabelButton(R.drawable.ic_rotate_right, "오른쪽 회전", Colors.BODY, Colors.CHIP) { rotateDraft(90) },
            lp(w = 0, h = dp(44), weight = 1f).apply { marginStart = dp(8) },
        )
        imgActions.addView(
            iconLabelButton(R.drawable.ic_trash, "삭제", Colors.DANGER, Colors.DANGER_BG) {
                draftFile.delete()
                dirty = true
                refreshImageUi?.invoke()
            },
            lp(w = 0, h = dp(44), weight = 1f).apply { marginStart = dp(8) },
        )
        imgCard.addView(imgActions, lp(top = 14))

        refreshImageUi = {
            val has = draftFile.exists()
            if (has) {
                thumb.clearColorFilter()
                thumb.scaleType = ImageView.ScaleType.FIT_CENTER
                thumb.setImageBitmap(BitmapFactory.decodeFile(draftFile.path, BitmapFactory.Options().apply { inSampleSize = 2 }))
            } else {
                thumb.setImageResource(R.drawable.ic_image)
                thumb.setColorFilter(Colors.SUBTLE)
                thumb.scaleType = ImageView.ScaleType.CENTER
            }
            imgTitle.text = if (has) "이미지 첨부됨" else "첨부된 이미지 없음"
            imgSub.text = if (has) "${draftFile.length() / 1024}KB · MMS로 전송" else "문자만 전송됩니다"
            pick.text = if (has) "이미지 변경" else "이미지 선택"
            imgActions.visibility = if (has) View.VISIBLE else View.GONE
        }
        refreshImageUi?.invoke()
        col.addView(imgCard, lp(top = 8))

        col.addView(label("문구", 14f, Colors.TEXT, true), lp(top = 24))
        val msg = field("보낼 문구", editMessage, 5)
        msg.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                editMessage = s.toString()
                dirty = true
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        col.addView(msg, lp(top = 8))

        col.addView(label("추천 문구 (눌러서 적용)", 12f, Colors.MUTED, true), lp(top = 14))
        status.templates.forEach { t ->
            val chip = label(t, 13f, Colors.BODY).apply {
                background = roundBg(Colors.CARD, 12, Colors.FIELD_BORDER)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    msg.setText(t)
                    msg.setSelection(t.length)
                }
            }
            col.addView(chip, lp(top = 8))
        }

        val actions = row()
        actions.addView(pill("저장", true) { saveEditor() }, lp(w = 0, weight = 1f).apply { marginEnd = dp(12) })
        actions.addView(
            pill(if (isNew) "취소" else "삭제", false, Colors.DANGER, Colors.DANGER_BORDER) { onDeleteClicked() },
            lp(w = 0, weight = 1f),
        )
        col.addView(actions, lp(top = 28))

        show(ScrollView(this).apply { addView(col) })
    }

    private fun saveEditor() {
        val s = editStatus ?: return
        val hasImage = draftFile.exists()
        if (editMessage.isBlank() && !hasImage) {
            toast("문구나 이미지 중 하나는 있어야 합니다")
            return
        }
        settings.setMessage(s, editMessage)
        settings.setEnabled(s, editEnabled)
        settings.setRegistered(s, true)
        val real = settings.imageFile(s)
        if (hasImage) {
            real.parentFile?.mkdirs()
            draftFile.copyTo(real, overwrite = true)
        } else {
            real.delete()
        }
        AppLog.d(this, "saved: status=${s.key} enabled=$editEnabled image=$hasImage len=${editMessage.length}")
        toast("저장되었습니다")
        showList()
    }

    private fun onDeleteClicked() {
        val s = editStatus ?: return
        if (editIsNew) {
            showList()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("삭제")
            .setMessage("'${s.label}' 문자 설정을 삭제할까요? 문구와 이미지가 함께 지워집니다.")
            .setPositiveButton("삭제") { _, _ ->
                settings.unregister(s)
                AppLog.d(this, "deleted: status=${s.key}")
                toast("삭제되었습니다")
                showList()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun confirmDiscard() {
        AlertDialog.Builder(this)
            .setMessage("저장하지 않은 변경 사항이 있습니다. 나갈까요?")
            .setPositiveButton("나가기") { _, _ -> showList() }
            .setNegativeButton("계속 편집", null)
            .show()
    }

    // ───────────────────────── 이미지 처리 ─────────────────────────

    /** Decodes the picked image upright, then stores it in the draft as a JPEG that fits the MMS limit. */
    private fun saveDraftImage(uri: Uri) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        var bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        if (bmp == null) {
            toast("이미지를 읽을 수 없습니다")
            return
        }
        // Camera photos store their orientation in EXIF; bake it into the pixels so it isn't lost.
        val exifDegrees = contentResolver.openInputStream(uri)?.use { readExifDegrees(it) } ?: 0
        if (exifDegrees != 0) bmp = rotateBitmap(bmp, exifDegrees)
        val originalBytes = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L

        val data = fitJpeg(bmp)
        if (data == null) {
            AppLog.w(this, "image rejected: could not fit ${MAX_IMAGE_BYTES / 1024}KB (original ${originalBytes / 1024}KB)")
            Toast.makeText(this, "이미지가 너무 복잡해서 ${MAX_IMAGE_BYTES / 1024}KB 이하로 줄일 수 없습니다. 다른 이미지를 선택하세요", Toast.LENGTH_LONG).show()
            return
        }
        draftFile.writeBytes(data)
        dirty = true
        refreshImageUi?.invoke()
        AppLog.d(this, "image picked: original=${originalBytes / 1024}KB -> ${data.size / 1024}KB exifRotation=$exifDegrees")
        if (originalBytes > data.size) {
            Toast.makeText(
                this, "MMS 용량 제한에 맞춰 ${originalBytes / 1024}KB → ${data.size / 1024}KB로 자동 압축했습니다", Toast.LENGTH_LONG,
            ).show()
        }
    }

    /** Try progressively smaller sizes/qualities until the JPEG fits the MMS limit; null if none does. */
    private fun fitJpeg(bmp: Bitmap): ByteArray? {
        for (maxSide in intArrayOf(1024, 800, 640, 480)) {
            val scale = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
            val scaled = Bitmap.createScaledBitmap(
                bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true,
            )
            var quality = 85
            while (quality >= 35) {
                val bos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, bos)
                if (bos.size() <= MAX_IMAGE_BYTES) return bos.toByteArray()
                quality -= 10
            }
        }
        return null
    }

    private fun readExifDegrees(input: java.io.InputStream): Int =
        when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

    private fun rotateBitmap(bmp: Bitmap, degrees: Int): Bitmap =
        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

    /** Rotates the draft image by 90° steps. */
    private fun rotateDraft(degrees: Int) {
        val bmp = if (draftFile.exists()) BitmapFactory.decodeFile(draftFile.path) else null
        if (bmp == null) {
            toast("회전할 이미지가 없습니다")
            return
        }
        val data = fitJpeg(rotateBitmap(bmp, degrees))
        if (data == null) {
            toast("회전 후 용량을 줄일 수 없습니다")
            return
        }
        draftFile.writeBytes(data)
        dirty = true
        refreshImageUi?.invoke()
        AppLog.d(this, "image rotated: degrees=$degrees -> ${data.size / 1024}KB")
    }

    // ───────────────────────── 권한 · 배터리 · 로그 ─────────────────────────

    private fun requiredPermissions() = listOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.SEND_SMS,
    )

    private fun allPermissionsGranted() = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        val perms = requiredPermissions().toMutableList()
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(perms.toTypedArray())
    }

    /** Shows the system "allow background activity" dialog; falls back to the app info page. */
    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("이미 배터리 최적화에서 제외되어 있습니다")
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
    }

    private fun exportLog() {
        val file = AppLog.file(this)
        if (!file.exists() || file.length() == 0L) {
            toast("기록된 로그가 없습니다")
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "AutoReply 로그")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "로그 파일 보내기"))
    }

    private companion object {
        // Log export buttons are hidden in release-style builds; set to true for test builds.
        const val SHOW_LOG_EXPORT = false

        // KT/SKT MMS size limit is ~300KB per message; leave room for text and headers.
        const val MAX_IMAGE_BYTES = 250 * 1024
    }
}

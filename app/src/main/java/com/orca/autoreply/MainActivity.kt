package com.orca.autoreply

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream

class MainActivity : ComponentActivity() {

    private lateinit var settings: ReplySettings
    private val imageLabels = HashMap<CallStatus, TextView>()
    private val imagePreviews = HashMap<CallStatus, ImageView>()
    private val rotateRows = HashMap<CallStatus, LinearLayout>()
    private var pickingFor: CallStatus? = null
    private lateinit var testNumber: EditText

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { !it }.keys
            Toast.makeText(
                this,
                if (denied.isEmpty()) "권한이 모두 허용되었습니다" else "허용되지 않은 권한: ${denied.size}개",
                Toast.LENGTH_SHORT,
            ).show()
        }

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val status = pickingFor ?: return@registerForActivityResult
            if (uri != null) saveImage(status, uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = ReplySettings(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        root.addView(TextView(this).apply { text = "통화 종료 후 자동 문자 전송"; textSize = 20f })

        root.addView(Switch(this).apply {
            text = "자동 전송 사용"
            isChecked = settings.masterEnabled
            setOnCheckedChangeListener { _, on -> settings.masterEnabled = on }
        }, matchWrap(top = 12))

        root.addView(Button(this).apply {
            text = "1. 권한 허용"
            setOnClickListener { requestPermissions() }
        }, matchWrap(top = 8))

        root.addView(Button(this).apply {
            text = "2. 배터리 최적화 제외 요청"
            setOnClickListener { requestIgnoreBatteryOptimizations() }
        }, matchWrap())

        root.addView(Button(this).apply {
            text = "3. 로그 파일 내보내기"
            setOnClickListener { exportLog() }
        }, matchWrap())

        testNumber = EditText(this).apply {
            hint = "테스트 수신 번호 (예: 01012345678)"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setText(settings.testNumber)
        }
        root.addView(testNumber, matchWrap(top = 12))

        CallStatus.entries.forEach { root.addView(statusCard(it), matchWrap(top = 20)) }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun statusCard(status: CallStatus): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
        }
        card.addView(Switch(this).apply {
            text = status.label
            isChecked = settings.enabled(status)
            setOnCheckedChangeListener { _, on -> settings.setEnabled(status, on) }
        })
        card.addView(EditText(this).apply {
            hint = "보낼 메시지"
            setText(settings.message(status))
            minLines = 2
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = settings.setMessage(status, s.toString())
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }, matchWrap(top = 8))

        val label = TextView(this)
        imageLabels[status] = label
        card.addView(label, matchWrap(top = 8))

        // Thumbnail of the image that will actually be sent, plus 90° rotation controls.
        val preview = ImageView(this).apply {
            adjustViewBounds = true
            maxHeight = dp(180)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        imagePreviews[status] = preview
        card.addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })

        val rotateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rotateRow.addView(Button(this).apply {
            text = "⟲ 왼쪽 90°"
            setOnClickListener { rotateSavedImage(status, -90) }
        })
        rotateRow.addView(Button(this).apply {
            text = "⟳ 오른쪽 90°"
            setOnClickListener { rotateSavedImage(status, 90) }
        })
        rotateRows[status] = rotateRow
        card.addView(rotateRow)
        refreshImageLabel(status)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "이미지 선택"
            setOnClickListener { pickingFor = status; imagePicker.launch("image/*") }
        })
        row.addView(Button(this).apply {
            text = "이미지 삭제"
            setOnClickListener { settings.imageFile(status).delete(); refreshImageLabel(status) }
        })
        row.addView(Button(this).apply {
            text = "테스트 전송"
            setOnClickListener { sendTest(status) }
        })
        card.addView(row)
        return card
    }

    /** Shows the system "allow background activity" dialog; falls back to the app info page. */
    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "이미 배터리 최적화에서 제외되어 있습니다", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "기록된 로그가 없습니다", Toast.LENGTH_SHORT).show()
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

    /** Sends the status's template to the test number, ignoring the enabled switches. */
    private fun sendTest(status: CallStatus) {
        val number = testNumber.text.toString().trim()
        if (number.isEmpty()) {
            Toast.makeText(this, "테스트 수신 번호를 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }
        settings.testNumber = number
        Toast.makeText(this, "전송 요청: $number", Toast.LENGTH_SHORT).show()
        Thread { Sender.send(applicationContext, number, status) }.start()
    }

    private fun refreshImageLabel(status: CallStatus) {
        val f = settings.imageFile(status)
        imagePreviews[status]?.apply {
            val thumb = if (f.exists()) {
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 2 })
            } else null
            setImageBitmap(thumb)
            visibility = if (thumb != null) View.VISIBLE else View.GONE
        }
        rotateRows[status]?.visibility = if (f.exists()) View.VISIBLE else View.GONE
        imageLabels[status]?.text =
            if (f.exists()) "첨부 이미지: ${f.length() / 1024}KB (MMS로 전송)"
            else "첨부 이미지 없음 (문자만 전송) · 이미지는 JPEG ${MAX_IMAGE_BYTES / 1024}KB 이하로 자동 변환됩니다"
    }

    /** Downscale + recompress so the MMS stays under typical carrier limits (~300KB). */
    private fun saveImage(status: CallStatus, uri: Uri) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        var bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        if (bmp == null) {
            Toast.makeText(this, "이미지를 읽을 수 없습니다", Toast.LENGTH_SHORT).show()
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

        writeImage(status, data)
        AppLog.d(this, "image saved: status=${status.key} original=${originalBytes / 1024}KB -> ${data.size / 1024}KB exifRotation=$exifDegrees")
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

    private fun writeImage(status: CallStatus, data: ByteArray) {
        val file = settings.imageFile(status)
        file.parentFile?.mkdirs()
        file.writeBytes(data)
        refreshImageLabel(status)
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

    /** Rotates the stored image by 90° steps and re-saves it. */
    private fun rotateSavedImage(status: CallStatus, degrees: Int) {
        val file = settings.imageFile(status)
        val bmp = if (file.exists()) BitmapFactory.decodeFile(file.path) else null
        if (bmp == null) {
            Toast.makeText(this, "회전할 이미지가 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        val data = fitJpeg(rotateBitmap(bmp, degrees))
        if (data == null) {
            Toast.makeText(this, "회전 후 용량을 줄일 수 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        writeImage(status, data)
        AppLog.d(this, "image rotated: status=${status.key} degrees=$degrees -> ${data.size / 1024}KB")
    }

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.SEND_SMS,
        )
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(perms.toTypedArray())
    }

    private companion object {
        // KT/SKT MMS size limit is ~300KB per message; leave room for text and headers.
        const val MAX_IMAGE_BYTES = 250 * 1024
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }
}

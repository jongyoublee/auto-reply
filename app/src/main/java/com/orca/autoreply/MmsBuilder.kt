package com.orca.autoreply

import java.io.ByteArrayOutputStream

/**
 * Minimal m-send-req encoder (OMA MMS 1.x / WSP) for SmsManager.sendMultimediaMessage.
 *
 * Korean carriers (KT, SKT) and the stock Samsung/Google messaging apps all use
 * multipart/related with a SMIL layout part, so that is the default. Set USE_SMIL to false
 * to fall back to a plain multipart/mixed body if a carrier rejects the SMIL variant.
 */
object MmsBuilder {
    const val USE_SMIL = true

    private const val SMIL_ID = "<smil>"
    private const val IMAGE_NAME = "image.jpg"
    private const val TEXT_NAME = "text.txt"

    fun build(number: String, text: String, jpeg: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x8C); out.write(0x80)                            // X-Mms-Message-Type: m-send-req
        out.write(0x98); str(out, "T${System.currentTimeMillis()}") // X-Mms-Transaction-Id
        out.write(0x8D); out.write(0x90)                            // X-Mms-MMS-Version: 1.0
        out.write(0x89); out.write(0x01); out.write(0x81)           // From: insert-address-token
        out.write(0x97); str(out, "${number.filter { it.isDigit() || it == '+' }}/TYPE=PLMN") // To

        // Content-Type: multipart/related; type="application/smil"; start="<smil>"  (or multipart/mixed)
        out.write(0x84)
        if (USE_SMIL) {
            val ct = ByteArrayOutputStream().apply {
                write(0xB3)                                          // application/vnd.wap.multipart.related
                write(0x89); str(this, "application/smil")           // type
                write(0x99); str(this, SMIL_ID)                      // start
            }.toByteArray()
            out.write(ct.size); out.write(ct)
        } else {
            out.write(0xA3)                                          // application/vnd.wap.multipart.mixed
        }

        val hasText = text.isNotBlank()
        val parts = ArrayList<Pair<ByteArray, ByteArray>>()

        if (USE_SMIL) {
            val headers = ByteArrayOutputStream().apply {
                str(this, "application/smil")
                write(0xC0); write(0x22); str(this, SMIL_ID)         // Content-ID
                write(0x8E); str(this, "smil.xml")                   // Content-Location
            }.toByteArray()
            parts += headers to smil(hasText).toByteArray(Charsets.UTF_8)
        }
        parts += imagePart(jpeg)
        if (hasText) parts += textPart(text)

        uintvar(out, parts.size)
        for ((h, d) in parts) {
            uintvar(out, h.size)
            uintvar(out, d.size)
            out.write(h)
            out.write(d)
        }
        return out.toByteArray()
    }

    private fun smil(hasText: Boolean): String {
        val textRegion = if (hasText) """<region id="Text" left="0" top="70%" width="100%" height="30%" fit="meet"/>""" else ""
        val textElem = if (hasText) """<text src="$TEXT_NAME" region="Text"/>""" else ""
        val imageHeight = if (hasText) "70%" else "100%"
        return """<smil><head><layout><root-layout width="100%" height="100%"/>""" +
            """<region id="Image" left="0" top="0" width="100%" height="$imageHeight" fit="meet"/>$textRegion""" +
            """</layout></head><body><par dur="5000ms"><img src="$IMAGE_NAME" region="Image"/>$textElem</par></body></smil>"""
    }

    private fun imagePart(jpeg: ByteArray): Pair<ByteArray, ByteArray> {
        val headers = ByteArrayOutputStream().apply {
            str(this, "image/jpeg")
            write(0xC0); write(0x22); str(this, "<image>")
            write(0x8E); str(this, IMAGE_NAME)
        }.toByteArray()
        return headers to jpeg
    }

    private fun textPart(text: String): Pair<ByteArray, ByteArray> {
        val ct = ByteArrayOutputStream().apply {
            str(this, "text/plain"); write(0x81); write(0xEA)        // charset=UTF-8 (MIBenum 106)
        }.toByteArray()
        val headers = ByteArrayOutputStream().apply {
            write(ct.size); write(ct)
            write(0xC0); write(0x22); str(this, "<text>")
            write(0x8E); str(this, TEXT_NAME)
        }.toByteArray()
        return headers to text.toByteArray(Charsets.UTF_8)
    }

    private fun str(out: ByteArrayOutputStream, s: String) {
        out.write(s.toByteArray(Charsets.UTF_8)); out.write(0)
    }

    private fun uintvar(out: ByteArrayOutputStream, value: Int) {
        val bytes = ArrayList<Int>()
        var v = value
        bytes.add(v and 0x7F)
        v = v ushr 7
        while (v > 0) {
            bytes.add(0, (v and 0x7F) or 0x80)
            v = v ushr 7
        }
        bytes.forEach { out.write(it) }
    }
}

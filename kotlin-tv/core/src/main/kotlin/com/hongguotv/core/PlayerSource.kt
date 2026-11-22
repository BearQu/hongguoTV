// SPDX-License-Identifier: GPL-3.0-only
// Player endpoint selection ported from fqapp (github.com/ch6vip/fqapp)
// rust/src/endpoints/video.rs and rust/src/upstream/client.rs; GPL-3.0-only.
package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Holds one registered upstream device, mirroring fqapp's pool rotation with a
 * single device plus re-registration whenever upstream rejects the signature. */
internal class PlayerDeviceSource(
    private val http: OkHttpClient,
    private val registrar: DeviceRegistrar = DeviceRegistrar(),
    private val store: () -> PlayerDeviceStore? = { ContentRepository.deviceStore },
) {
    @Volatile private var cached: PlayerDevice? = null

    @Synchronized
    fun obtain(): PlayerDevice {
        cached?.let { return it }
        store()?.load()?.let { stored -> PlayerDevice.fromJson(stored)?.let { cached = it; return it } }
        val fresh = registrar.register(http)
        cached = fresh
        store()?.save(fresh.toJson())
        return fresh
    }

    @Synchronized
    fun invalidate() {
        cached = null
        runCatching { store()?.save(null) }
    }

    companion object {
        // The shortplay video_model URL from fetchShortplayVideoModel.
        internal const val SHORTPLAY_URL = "https://api3-normal-sinfonlinea.fqnovel.com/novel/player/video_model/v1/?iid={install_id}&device_id={device_id}&aid=1967&app_name=novelapp&version_code=72132&version_name=7.2.1.32&device_platform=android&device_brand=Xiaomi&os_version=13&cdid={cdid}"
        // The phoenix multi_video_model URL from phoenix_player_url.
        internal const val PHOENIX_URL = "https://api5-normal-lf.fqnovel.com/novel/player/multi_video_model/v1/?iid={install_id}&device_id={device_id}&ac=wifi&channel=update_64&aid=8662&app_name=novelread&version_code=72932&version_name=7.2.9.32&device_platform=android&os=android&ssmix=a&device_type=25053RT47C&device_brand=Redmi&language=zh&os_api=36&os_version=16&manifest_version_code=72932&resolution=1280*2772&dpi=520&update_version_code=72932&host_abi=arm64-v8a&dragon_device_type=phone&pv_player=72932&compliance_status=0&need_personal_recommend=1&player_so_load=1&is_android_pad_screen=0"
        // Official-app variant: red-novel single-video endpoint (aid 8662, com.phoenix.read).
        internal const val PHOENIX_SINGLE_URL = "https://api5-normal-lf.fqnovel.com/novel/player/video_model/v1/?iid={install_id}&device_id={device_id}&ac=wifi&channel=update_64&aid=8662&app_name=novelread&version_code=72932&version_name=7.2.9.32&device_platform=android&os=android&ssmix=a&device_type=25053RT47C&device_brand=Redmi&language=zh&os_api=36&os_version=16&manifest_version_code=72932&resolution=1280*2772&dpi=520&update_version_code=72932&host_abi=arm64-v8a&dragon_device_type=phone&pv_player=72932&compliance_status=0&need_personal_recommend=1&player_so_load=1&is_android_pad_screen=0"
        // Official-app variant: POST /novel/player/multi_video_model/:dr_scene/v1/ with dr_scene=default.
        internal val PHOENIX_DEFAULT_URL: String = PHOENIX_URL.replace("/multi_video_model/v1/", "/multi_video_model/default/v1/")
        internal const val FALLBACK_USER_AGENT = "com.xs.fm/632 (Linux; U; Android 15; zh_CN; 23049RAD8C; Build/AQ3A.250226.002; Cronet/TTNetVersion:fc4cebd3 2024-12-10 QuicVersion:d9628e3d 2024-10-11)"

        fun substituteDevice(url: String, device: PlayerDevice): String = url
            .replace("{device_id}", device.deviceId)
            .replace("{install_id}", device.installId)
            .replace("{secret_key}", device.secretKey)
            .replace("{cdid}", device.cdid)
            .replace("{openudid}", device.openudid.ifEmpty { device.deviceId })

        fun shortplayBody(videoId: String): ByteArray = JSONObject()
            .put("video_id", videoId).put("content_type", 1004)
            .put("biz_param", JSONObject()
                .put("detail_page_version", 0).put("device_level", 3)
                .put("disable_digg_stat", false).put("disable_video_relate_book", false)
                .put("from_video_id", "").put("need_all_video_definition", true)
                .put("need_mp4_align", false).put("source", 4)
                .put("use_os_player", false).put("use_server_dns", false)
                .put("video_platform", 3)).toString().toByteArray()

        fun phoenixBody(videoId: String): ByteArray = JSONObject()
            .put("dr_scene", "default")
            .put("biz_param", JSONObject()
                .put("caller_scene", "download")
                .put("detail_page_version", 0).put("device_level", 3)
                .put("disable_digg_stat", false).put("need_all_video_definition", true)
                .put("need_mp4_align", false).put("use_os_player", false)
                .put("use_server_dns", false).put("video_platform", 1024))
            .put("mixed_video_id_map", JSONObject().put("1004", JSONArray().put(videoId)))
            .toString().toByteArray()
    }
}

/** A resolved playable row: score decides the ranking, quality labels the row. */
internal data class PlayerVariant(val score: Long, val url: String, val key: ByteArray?, val quality: Int, val byteVc2: Boolean = false)

/** Model/variant extraction mirroring video.rs; every entry point is pure JSON. */
internal object StreamModels {
    /** Score variants: hard-decodable formats win; byteVC2 stays selectable (low priority). */
    fun qualityScore(info: JSONObject): Long? {
        val codec = buildString {
            append(info.str("codec_type").lowercase())
            append(' ').append(info.optJSONObject("video_meta")?.str("codec_type").orEmpty().lowercase())
            append(' ').append(info.str("gear_des_key").lowercase())
        }
        // ByteVC2 is a ByteDance-proprietary codec the platform MediaCodec cannot
        // decode. Keep it as a low-priority candidate instead of dropping it: when
        // it is the only available rendition, TranscodingVideoDataSource detects
        // bvc2 in the decrypted stream and transcodes it to H.264 locally.
        // Hard-decodable h264/hevc still win because they score higher.
        val w = int64FromAny(info, "vwidth")
        val h = int64FromAny(info, "vheight")
        var b = int64FromAny(info, "bitrate")
        if (b == 0L) b = int64FromAny(info, "real_bitrate")
        val bonus = if (codec.contains("h264") || codec.contains("avc1")) 1 else 0
        val base = w * 1_000_000_000 + h * 1_000_000 + b + bonus
        // byteVC2 needs a local transcode pass; rank it below any hard-decodable
        // rendition but keep it selectable when it is the only option.
        return if (isByteVc2(info)) base - 1_000_000_000_000_000L else base
    }

    /** True when this rendition carries the ByteVC2/bvc2 codec, which needs local transcoding. */
    fun isByteVc2(info: JSONObject): Boolean {
        val codec = buildString {
            append(info.str("codec_type").lowercase())
            append(' ').append(info.optJSONObject("video_meta")?.str("codec_type").orEmpty().lowercase())
            append(' ').append(info.str("gear_des_key").lowercase())
        }
        return codec.contains("bytevc2") || codec.contains("bvc2")
    }

    /** Short-edge naming, normalised through the same quality table as before. */
    fun displayName(w: Long, h: Long): Int {
        val edge = if (w > 0 && h > 0) minOf(w, h) else maxOf(w, h)
        return ContentRepository.qualityNumber(edge.toString())
    }

    fun int64FromAny(info: JSONObject, key: String): Long {
        when (val value = info.opt(key)) {
            is Number -> return value.toLong()
            is String -> return scanInt64(value)
            else -> return 0
        }
    }

    private fun scanInt64(s: String): Long {
        val t = s.trim()
        var index = 0
        var sign = 1L
        if (t.startsWith("-")) { sign = -1; index = 1 } else if (t.startsWith("+")) index = 1
        var digits = ""
        while (index < t.length && t[index] in '0'..'9') { digits += t[index]; index++ }
        return digits.toLongOrNull()?.times(sign) ?: 0
    }

    /** base64 decode that tolerates missing padding and newlines, like audio_b64_decode. */
    fun lenientB64(value: String): ByteArray? {
        val trimmed = value.trim().replace("\r", "").replace("\n", "")
        val padded = trimmed + "=".repeat((4 - trimmed.length % 4) % 4)
        return runCatching { decode64(padded) }.getOrNull()
    }

    /** One video_list entry into a playable (url, key) pair; empty url on failure. */
    fun decryptEntry(info: JSONObject, keySeed: ByteArray?): PlayerVariant? {
        val score = qualityScore(info) ?: return null
        var rawUrl = info.str("main_url").ifEmpty { info.str("backup_url_1") }
        if (rawUrl.isNotEmpty() && !rawUrl.startsWith("http")) {
            lenientB64(rawUrl)?.takeIf { it.size > 4 && it.startsWithHttp() }?.let { rawUrl = String(it, Charsets.UTF_8) }
        }
        val url = if (keySeed != null) spadeDecrypt(rawUrl, keySeed) ?: rawUrl else rawUrl
        if (!url.startsWith("http")) return null
        // Red-novel cenc streams carry the key material in encrypt_info.spade_a.
        // Older/newer shapes may put it at the top level; accept both.
        val spadeA = info.optJSONObject("encrypt_info")?.str("spade_a").orEmpty()
            .ifEmpty { info.str("spade_a") }
        val key = spadeA.takeIf { it.isNotEmpty() }?.let { runCatching { MediaCrypto.deriveKey(it) }.getOrNull() }
        return PlayerVariant(score, cleanUrl(url), key, displayName(int64FromAny(info, "vwidth"), int64FromAny(info, "vheight")), isByteVc2(info))
    }

    private fun ByteArray.startsWithHttp() = size >= 4 && String(this, 0, 4, Charsets.US_ASCII) == "http"

    /** Spade URL decryption; returns null when the blob is not spade-framed. */
    fun spadeDecrypt(value: String, keySeed: ByteArray): String? =
        runCatching { MediaCrypto.decryptUrl(value, keySeed) }.getOrNull()

    /** Every video_model map in a model response, object- or string-shaped. */
    fun models(data: JSONObject): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        decodeModel(data.opt("video_model"))?.let { out += it }
        data.keys().asSequence().forEach { key ->
            data.optJSONObject(key)?.let { decodeModel(it.opt("video_model"))?.let { model -> out += model } }
        }
        return out
    }
    private fun decodeModel(value: Any?): JSONObject? = when (value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
        else -> null
    }

    fun keySeed(model: JSONObject): ByteArray? =
        model.str("key_seed").takeIf { it.isNotEmpty() }?.let { lenientB64(it) }

    /** Rows from an object-shaped video_list (the shortplay/phoenix shape). */
    fun listRows(videoList: JSONObject?, keySeed: ByteArray?): List<PlayerVariant> =
        videoList?.keys()?.asSequence()?.mapNotNull { videoList.optJSONObject(it) }?.mapNotNull { decryptEntry(it, keySeed) }?.toList() ?: emptyList()

    /** Rows from an array-shaped video_list; cenc entries carry encrypt_info.spade_a. */
    fun arrayRows(videoList: JSONArray?): List<PlayerVariant> {
        if (videoList == null) return emptyList()
        return (0 until videoList.length()).mapNotNull { videoList.optJSONObject(it) }.mapNotNull { info ->
            val score = qualityScore(info) ?: return@mapNotNull null
            var url = cleanUrl(info.str("play_url").ifEmpty { info.str("main_url") })
            if (!url.startsWith("http")) return@mapNotNull null
            val spadeA = info.optJSONObject("encrypt_info")?.str("spade_a").orEmpty()
                .ifEmpty { info.str("spade_a") }
            val key = spadeA.takeIf { it.isNotEmpty() }?.let { runCatching { MediaCrypto.deriveKey(it) }.getOrNull() }
            PlayerVariant(score, url, key, displayName(int64FromAny(info, "vwidth"), int64FromAny(info, "vheight")), isByteVc2(info))
        }
    }

    /** The model's fallback_api URL, audio-encrypt stream parameters stripped. */
    fun fallbackApiUrl(model: JSONObject): String {
        var url = when (val raw = model.opt("fallback_api")) {
            is JSONObject -> raw.str("fallback_api")
            is String -> runCatching { JSONObject(raw).str("fallback_api") }.getOrDefault(raw)
            else -> ""
        }
        url = url.replace("&stream_type=audio_encrypt", "").replace("?stream_type=audio_encrypt&", "?")
        return url
    }

    fun pick(rows: List<PlayerVariant>, maxQuality: Int): PlayerVariant? =
        rows.sortedByDescending { it.score }.let { sorted -> sorted.firstOrNull { it.quality <= maxQuality } ?: sorted.lastOrNull() }
}

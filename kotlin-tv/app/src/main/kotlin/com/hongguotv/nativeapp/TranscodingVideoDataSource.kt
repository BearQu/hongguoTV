// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.hongguotv.core.RemoteVideo
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * 播放数据源：检测解密后视频是否为 byteVC2（bvc2），若是则在本地转码为 H.264 后播放；
 * 否则直接走原始解密字节流（带 512KB 缓冲，避免每次 read 都触发网络 Range 请求）。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TranscodingVideoDataSource(
    private val context: Context,
    private val video: RemoteVideo
) : BaseDataSource(true) {
    private var position = 0L
    private var remaining = 0L
    private var uri: Uri? = null
    private var opened = false
    private var stream: FileInputStream? = null
    private var local = false
    private var buffer = byteArrayOf()
    private var bufferOffset = 0

    companion object {
        // 探测结果按 video 实例缓存：Factory 每次 open 都 new 一个 DataSource，
        // 但同一个 RemoteVideo 的头 256KB 不应被反复读取。
        private val probeCache = java.util.WeakHashMap<RemoteVideo, Boolean>()
    }

    override fun open(spec: DataSpec): Long {
        transferInitializing(spec)
        uri = spec.uri
        position = spec.position
        buffer = byteArrayOf(); bufferOffset = 0

        // 探测 bvc2（结果按 video 实例缓存，避免每次 open 都读 256KB）
        local = probeCache.getOrPut(video) {
            val head = video.read(0, minOf(262144, video.total.toInt()))
            contains(head, "bvc2".toByteArray()) || contains(head, "bytevc2".toByteArray())
        }

        val total: Long
        if (local) {
            val key = video.info.url.hashCode().toString()
            val input = File(context.cacheDir, "bvc2-$key-input.mp4")
            val output = File(context.cacheDir, "bvc2-$key-h264.mp4")
            if (!output.exists() || output.length() == 0L) {
                if (!input.exists() || input.length() != video.total) {
                    input.outputStream().use { out ->
                        var pos = 0L
                        while (pos < video.total) {
                            val len = minOf(512 * 1024, (video.total - pos).toInt())
                            out.write(video.read(pos, len))
                            pos += len
                        }
                    }
                }
                val rc = Bvc2Transcoder.transcode(input.absolutePath, output.absolutePath)
                if (rc != 0 || !output.exists() || output.length() == 0L) {
                    throw IOException("bvc2 转码失败（code=$rc）")
                }
            }
            stream?.close()
            stream = FileInputStream(output)
            total = output.length()
        } else {
            total = video.total
        }
        if (position > total) throw IOException("读取位置超出视频长度")
        remaining = if (spec.length == C.LENGTH_UNSET.toLong()) total - position else minOf(spec.length, total - position)
        opened = true
        transferStarted(spec)
        return remaining
    }

    override fun read(target: ByteArray, targetOffset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val want = minOf(length.toLong(), remaining).toInt()
        val count = if (local) {
            stream?.read(target, targetOffset, want) ?: -1
        } else {
            if (bufferOffset >= buffer.size) {
                buffer = video.read(position, minOf(1024 * 1024L, remaining).toInt())
                bufferOffset = 0
            }
            if (buffer.isEmpty()) return C.RESULT_END_OF_INPUT
            val n = minOf(want, buffer.size - bufferOffset, remaining.toInt())
            buffer.copyInto(target, targetOffset, bufferOffset, bufferOffset + n)
            bufferOffset += n
            n
        }
        if (count <= 0) return C.RESULT_END_OF_INPUT
        position += count
        remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri() = uri
    override fun close() {
        stream?.close(); stream = null
        buffer = byteArrayOf(); bufferOffset = 0
        uri = null
        if (opened) { opened = false; transferEnded() }
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        for (i in 0..(haystack.size - needle.size)) {
            var match = true
            for (j in needle.indices) if (haystack[i + j] != needle[j]) { match = false; break }
            if (match) return true
        }
        return false
    }
}

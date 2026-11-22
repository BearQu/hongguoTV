// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/** JNI 转码器：把解密后的 bvc2 MP4 转成标准 H.264 MP4。 */
object Bvc2Transcoder {
    init {
        System.loadLibrary("bvc2transcoder")
    }

    /**
     * @return 0 成功，负数失败
     */
    external fun transcode(inputPath: String, outputPath: String): Int

    const val OK = 0
}

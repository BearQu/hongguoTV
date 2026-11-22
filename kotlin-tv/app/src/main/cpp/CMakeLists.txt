# SPDX-License-Identifier: GPL-3.0-only
cmake_minimum_required(VERSION 3.22.1)
project("bvc2transcoder")

# FFmpeg 静态库（NDK 交叉编译，含 bvc2 解码 + x264 编码）
set(FFMPEG_ROOT "/tmp/bvc2-android/dist")
set(X264_ROOT "/tmp/bvc2-android/x264-dist")

add_library(bvc2transcoder SHARED "Bvc2Transcoder.cpp")

target_include_directories(bvc2transcoder PRIVATE
    ${FFMPEG_ROOT}/include
    ${X264_ROOT}/include)

# 链接顺序：依赖者在前，被依赖者在后
target_link_libraries(bvc2transcoder
    ${FFMPEG_ROOT}/lib/libavformat.a
    ${FFMPEG_ROOT}/lib/libavcodec.a
    ${FFMPEG_ROOT}/lib/libswresample.a
    ${FFMPEG_ROOT}/lib/libavutil.a
    ${X264_ROOT}/lib/libx264.a
    android
    log
    z
    m
    dl)

# 预编译静态库的 NEON asm 含非 PIC 重定位；-Bsymbolic 让内部符号本地解析避免 TEXTREL
target_link_options(bvc2transcoder PRIVATE "-Wl,-Bsymbolic")

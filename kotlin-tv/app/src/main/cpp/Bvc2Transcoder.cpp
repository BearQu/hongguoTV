//
// Bvc2Transcoder.cpp
//
// JNI implementation: decode a CENC-decrypted MP4 whose video track is
// bvc2 (byteVC2 / VVC, AV_CODEC_ID_BVC2) and produce an H.264 + AAC MP4.
//
// Built against FFmpeg 8.x static libs in /tmp/bvc2-android/dist.
// NOTE: that FFmpeg build used --disable-swscale, so sws_scale is used only
// when its header is available; otherwise a built-in 10-bit -> 8-bit
// converter handles the bvc2 decoder's yuv420p10le output.
//

#include <jni.h>
#include <android/log.h>

#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/avutil.h>
#include <libavutil/channel_layout.h>
#include <libavutil/common.h>
#include <libavutil/error.h>
#include <libavutil/imgutils.h>
#include <libavutil/mathematics.h>
#include <libavutil/opt.h>
#include <libavutil/pixdesc.h>
#include <libavutil/rational.h>
#include <libavutil/samplefmt.h>
#include <libswresample/swresample.h>
}

#if defined(__has_include)
#  if __has_include(<libswscale/swscale.h>)
#    define BVC2_HAS_SWSCALE 1
extern "C" {
#    include <libswscale/swscale.h>
}
#  else
#    define BVC2_HAS_SWSCALE 0
#  endif
#else
#  define BVC2_HAS_SWSCALE 0
#endif

#define LOG_TAG "Bvc2Transcoder"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

// Return codes: 0 = success, negative = failure.
enum Bvc2Result {
    BVC2_OK             =    0,
    BVC2_ERR_ARG        =   -1,  // bad JNI argument / path
    BVC2_ERR_INPUT_OPEN =   -2,  // avformat_open_input failed
    BVC2_ERR_INPUT_INFO =   -3,  // avformat_find_stream_info failed
    BVC2_ERR_VIDEO      =   -4,  // no decodable video stream
    BVC2_ERR_DECODER    =   -5,  // decoder setup/decode failed
    BVC2_ERR_OUTPUT     =   -6,  // muxer setup failed
    BVC2_ERR_ENCODER    =   -7,  // encoder setup/encode failed
    BVC2_ERR_ALLOC      =   -8,  // allocation failed
    BVC2_ERR_FORMAT     =   -9,  // unsupported pixel format / size change
    BVC2_ERR_IO         =  -10,  // read/write failed
    BVC2_ERR_AUDIO      =  -11,  // audio resample/encode failed
};

namespace {

const char* errstr(int err, char* buf, size_t len) {
    buf[0] = '\0';
    av_strerror(err, buf, len);
    return buf;
}

bool check_err(int ret, const char* what) {
    if (ret >= 0) return false;
    char b[AV_ERROR_MAX_STRING_SIZE];
    LOGE("%s failed: %s (%d)", what, errstr(ret, b, sizeof(b)), ret);
    return true;
}

struct JStringGuard {
    JNIEnv* env;
    jstring str;
    const char* chars;

    JStringGuard(JNIEnv* e, jstring s) : env(e), str(s), chars(nullptr) {
        if (str) chars = env->GetStringUTFChars(str, nullptr);
    }
    ~JStringGuard() {
        if (chars) env->ReleaseStringUTFChars(str, chars);
    }
    bool ok() const { return chars != nullptr; }
    const char* get() const { return chars; }
};

int pick_aac_rate(int src) {
    static const int rates[] = {
        96000, 88200, 64000, 48000, 44100, 32000,
        24000, 22050, 16000, 12000, 11025, 8000, 7350
    };
    for (unsigned i = 0; i < sizeof(rates) / sizeof(rates[0]); ++i) {
        if (rates[i] == src) return src;
    }
    LOGW("sample rate %d not supported by AAC, using 48000", src);
    return 48000;
}

struct Transcoder {
    AVFormatContext* ifmt = nullptr;
    AVFormatContext* ofmt = nullptr;

    int vin = -1;
    const AVCodec* vdec = nullptr;
    AVCodecContext* vdec_ctx = nullptr;

    int ain = -1;
    const AVCodec* adec = nullptr;
    AVCodecContext* adec_ctx = nullptr;

    AVCodecContext* venc_ctx = nullptr;
    AVStream* vout = nullptr;

    AVCodecContext* aenc_ctx = nullptr;
    AVStream* aout = nullptr;
    SwrContext* swr = nullptr;

    AVPacket* pkt = nullptr;
    AVFrame* frame = nullptr;
    AVFrame* conv = nullptr;
#if BVC2_HAS_SWSCALE
    SwsContext* sws = nullptr;
    enum AVPixelFormat sws_fmt = AV_PIX_FMT_NONE;
    int sws_w = 0;
    int sws_h = 0;
#endif

    int64_t next_vpts = 0;
    int64_t next_apts = 0;
    bool header_written = false;
    bool trailer_written = false;
    bool io_opened = false;

    ~Transcoder() { cleanup(); }

    void cleanup() {
        if (header_written && !trailer_written && ofmt) {
            av_write_trailer(ofmt);
            trailer_written = true;
        }
#if BVC2_HAS_SWSCALE
        if (sws) { sws_freeContext(sws); sws = nullptr; }
#endif
        if (conv)  { av_frame_free(&conv); }
        if (frame) { av_frame_free(&frame); }
        if (pkt)   { av_packet_free(&pkt); }
        if (swr)   { swr_free(&swr); }
        if (aenc_ctx) { avcodec_free_context(&aenc_ctx); }
        if (venc_ctx) { avcodec_free_context(&venc_ctx); }
        if (adec_ctx) { avcodec_free_context(&adec_ctx); }
        if (vdec_ctx) { avcodec_free_context(&vdec_ctx); }
        if (ofmt) {
            if (io_opened && ofmt->pb) {
                avio_close(ofmt->pb);
                ofmt->pb = nullptr;
            }
            avformat_free_context(ofmt);
            ofmt = nullptr;
        }
        if (ifmt) { avformat_close_input(&ifmt); }
    }

    int run(const char* in, const char* out) {
        av_log_set_level(AV_LOG_ERROR);
        if (!in || !out) {
            LOGE("null input or output path");
            return BVC2_ERR_ARG;
        }

        int ret = openInput(in);
        if (ret < 0) return ret;

        ret = setupOutput(out);
        if (ret < 0) return ret;

        ret = setupVideo();
        if (ret < 0) return ret;

        // Audio is optional: a failure here only disables audio.
        ret = setupAudio();
        if (ret < 0) {
            LOGW("audio disabled (error %d); continuing video-only", ret);
            disableAudio();
        }

        ret = avformat_write_header(ofmt, nullptr);
        if (check_err(ret, "avformat_write_header")) return BVC2_ERR_OUTPUT;
        header_written = true;

        ret = transcodeLoop();
        if (ret < 0) return ret;

        ret = flushAll();
        if (ret < 0) return ret;

        ret = av_write_trailer(ofmt);
        if (check_err(ret, "av_write_trailer")) return BVC2_ERR_IO;
        trailer_written = true;

        LOGI("transcode succeeded: %s -> %s", in, out);
        return BVC2_OK;
    }

    int openInput(const char* path) {
        int ret = avformat_open_input(&ifmt, path, nullptr, nullptr);
        if (check_err(ret, "avformat_open_input")) return BVC2_ERR_INPUT_OPEN;

        ret = avformat_find_stream_info(ifmt, nullptr);
        if (check_err(ret, "avformat_find_stream_info")) return BVC2_ERR_INPUT_INFO;

        vin = av_find_best_stream(ifmt, AVMEDIA_TYPE_VIDEO, -1, -1, &vdec, 0);
        if (vin < 0 || !vdec) {
            // Fallback: some bvc2 files may not expose a usable codec_id from
            // find_stream_info; pick the first video stream and force BVC2.
            for (unsigned i = 0; i < ifmt->nb_streams; ++i) {
                if (ifmt->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
                    vin = static_cast<int>(i);
                    break;
                }
            }
            if (vin >= 0) {
                AVCodecParameters* cp = ifmt->streams[vin]->codecpar;
                if (cp->codec_id == AV_CODEC_ID_NONE ||
                    cp->codec_id == AV_CODEC_ID_BVC2 ||
                    cp->codec_tag == MKTAG('b', 'v', 'c', '2')) {
                    cp->codec_id = AV_CODEC_ID_BVC2;
                    vdec = avcodec_find_decoder(AV_CODEC_ID_BVC2);
                }
            }
        }
        if (vin < 0 || !vdec) {
            LOGE("no decodable video stream (bvc2 decoder unavailable)");
            return BVC2_ERR_VIDEO;
        }
        LOGI("video stream %d, codec=%s", vin, vdec->name);
        return BVC2_OK;
    }

    int setupOutput(const char* path) {
        int ret = avformat_alloc_output_context2(&ofmt, nullptr, "mp4", path);
        if (check_err(ret, "avformat_alloc_output_context2") || !ofmt) return BVC2_ERR_OUTPUT;
        if (!(ofmt->oformat->flags & AVFMT_NOFILE)) {
            ret = avio_open(&ofmt->pb, path, AVIO_FLAG_WRITE);
            if (check_err(ret, "avio_open")) return BVC2_ERR_IO;
            io_opened = true;
        }
        return BVC2_OK;
    }

    int setupVideo() {
        vdec_ctx = avcodec_alloc_context3(vdec);
        if (!vdec_ctx) return BVC2_ERR_ALLOC;
        int ret = avcodec_parameters_to_context(vdec_ctx, ifmt->streams[vin]->codecpar);
        if (check_err(ret, "avcodec_parameters_to_context(video)")) return BVC2_ERR_DECODER;
        vdec_ctx->pkt_timebase = ifmt->streams[vin]->time_base;
        ret = avcodec_open2(vdec_ctx, vdec, nullptr);
        if (check_err(ret, "avcodec_open2(video decoder)")) return BVC2_ERR_DECODER;

        const AVCodec* enc = avcodec_find_encoder(AV_CODEC_ID_H264);
        if (!enc) { LOGE("H.264 encoder (libx264) not found"); return BVC2_ERR_ENCODER; }
        venc_ctx = avcodec_alloc_context3(enc);
        if (!venc_ctx) return BVC2_ERR_ALLOC;

        AVRational fps = av_guess_frame_rate(ifmt, ifmt->streams[vin], nullptr);
        if (fps.num <= 0 || fps.den <= 0) fps = av_make_q(25, 1);

        venc_ctx->width  = vdec_ctx->width;
        venc_ctx->height = vdec_ctx->height;
        venc_ctx->pix_fmt = AV_PIX_FMT_YUV420P;
        venc_ctx->time_base = av_inv_q(fps);
        venc_ctx->framerate = fps;
        venc_ctx->sample_aspect_ratio = vdec_ctx->sample_aspect_ratio;
        if (ofmt->oformat->flags & AVFMT_GLOBALHEADER) {
            venc_ctx->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
        }
        av_opt_set(venc_ctx->priv_data, "preset", "veryfast", 0);
        av_opt_set(venc_ctx->priv_data, "crf", "23", 0);

        ret = avcodec_open2(venc_ctx, enc, nullptr);
        if (check_err(ret, "avcodec_open2(H.264)")) return BVC2_ERR_ENCODER;

        vout = avformat_new_stream(ofmt, nullptr);
        if (!vout) return BVC2_ERR_ALLOC;
        vout->time_base = venc_ctx->time_base;
        ret = avcodec_parameters_from_context(vout->codecpar, venc_ctx);
        if (check_err(ret, "avcodec_parameters_from_context(video)")) return BVC2_ERR_OUTPUT;
        return BVC2_OK;
    }

    int setupAudio() {
        ain = av_find_best_stream(ifmt, AVMEDIA_TYPE_AUDIO, -1, -1, &adec, 0);
        if (ain < 0 || !adec) {
            LOGW("no audio stream; output will be video-only");
            return BVC2_ERR_ARG;
        }

        adec_ctx = avcodec_alloc_context3(adec);
        if (!adec_ctx) return BVC2_ERR_ALLOC;
        int ret = avcodec_parameters_to_context(adec_ctx, ifmt->streams[ain]->codecpar);
        if (check_err(ret, "avcodec_parameters_to_context(audio)")) return BVC2_ERR_DECODER;
        adec_ctx->pkt_timebase = ifmt->streams[ain]->time_base;
        ret = avcodec_open2(adec_ctx, adec, nullptr);
        if (check_err(ret, "avcodec_open2(audio decoder)")) return BVC2_ERR_DECODER;

        const AVCodec* enc = avcodec_find_encoder(AV_CODEC_ID_AAC);
        if (!enc) { LOGW("AAC encoder not found"); return BVC2_ERR_ENCODER; }
        aenc_ctx = avcodec_alloc_context3(enc);
        if (!aenc_ctx) return BVC2_ERR_ALLOC;

        aenc_ctx->sample_rate = pick_aac_rate(adec_ctx->sample_rate);
        if (av_channel_layout_copy(&aenc_ctx->ch_layout, &adec_ctx->ch_layout) < 0 ||
            aenc_ctx->ch_layout.nb_channels <= 0) {
            av_channel_layout_default(&aenc_ctx->ch_layout, 2);
        }
        aenc_ctx->sample_fmt = enc->sample_fmts ? enc->sample_fmts[0] : AV_SAMPLE_FMT_FLTP;
        aenc_ctx->time_base = av_make_q(1, aenc_ctx->sample_rate);
        if (ofmt->oformat->flags & AVFMT_GLOBALHEADER) {
            aenc_ctx->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
        }

        ret = avcodec_open2(aenc_ctx, enc, nullptr);
        if (check_err(ret, "avcodec_open2(AAC)")) return BVC2_ERR_ENCODER;

        ret = swr_alloc_set_opts2(&swr,
                                  &aenc_ctx->ch_layout, aenc_ctx->sample_fmt, aenc_ctx->sample_rate,
                                  &adec_ctx->ch_layout, adec_ctx->sample_fmt, adec_ctx->sample_rate,
                                  0, nullptr);
        if (check_err(ret, "swr_alloc_set_opts2") || !swr) return BVC2_ERR_ALLOC;
        ret = swr_init(swr);
        if (check_err(ret, "swr_init")) return BVC2_ERR_ALLOC;

        aout = avformat_new_stream(ofmt, nullptr);
        if (!aout) return BVC2_ERR_ALLOC;
        aout->time_base = aenc_ctx->time_base;
        ret = avcodec_parameters_from_context(aout->codecpar, aenc_ctx);
        if (check_err(ret, "avcodec_parameters_from_context(audio)")) return BVC2_ERR_OUTPUT;
        return BVC2_OK;
    }

    void disableAudio() {
        if (swr) { swr_free(&swr); }
        if (aenc_ctx) { avcodec_free_context(&aenc_ctx); }
        if (adec_ctx) { avcodec_free_context(&adec_ctx); }
        aout = nullptr;
        ain = -1;
        adec = nullptr;
    }

    // 10-bit (yuv420p10le / yuv420p10be) -> 8-bit yuv420p, simple bit shift.
    static int convert10to8(const AVFrame* src, AVFrame* dst) {
        const bool big_endian = (src->format == AV_PIX_FMT_YUV420P10BE);
        for (int p = 0; p < 3; ++p) {
            const int w = (p == 0) ? src->width : (src->width + 1) / 2;
            const int h = (p == 0) ? src->height : (src->height + 1) / 2;
            const uint8_t* s = src->data[p];
            uint8_t* d = dst->data[p];
            const int sls = src->linesize[p];
            const int dls = dst->linesize[p];
            for (int y = 0; y < h; ++y) {
                const uint8_t* row = s + static_cast<ptrdiff_t>(y) * sls;
                uint8_t* out = d + static_cast<ptrdiff_t>(y) * dls;
                for (int x = 0; x < w; ++x) {
                    const uint16_t v = big_endian
                        ? static_cast<uint16_t>((static_cast<uint16_t>(row[x * 2]) << 8) | row[x * 2 + 1])
                        : static_cast<uint16_t>((static_cast<uint16_t>(row[x * 2 + 1]) << 8) | row[x * 2]);
                    out[x] = static_cast<uint8_t>(v >> 2);
                }
            }
        }
        return 0;
    }

    int processVideo(AVFrame* src) {
        if (src->width != venc_ctx->width || src->height != venc_ctx->height) {
            LOGE("video resolution changed: %dx%d -> %dx%d",
                 venc_ctx->width, venc_ctx->height, src->width, src->height);
            return BVC2_ERR_FORMAT;
        }

        const enum AVPixelFormat fmt = static_cast<enum AVPixelFormat>(src->format);
        AVFrame* send = src;

        if (fmt != AV_PIX_FMT_YUV420P) {
            if (!conv) {
                conv = av_frame_alloc();
                if (!conv) return BVC2_ERR_ALLOC;
            }
            av_frame_unref(conv);
            conv->format = AV_PIX_FMT_YUV420P;
            conv->width = venc_ctx->width;
            conv->height = venc_ctx->height;
            if (av_frame_get_buffer(conv, 32) < 0) return BVC2_ERR_ALLOC;

#if BVC2_HAS_SWSCALE
            if (!sws || sws_fmt != fmt || sws_w != src->width || sws_h != src->height) {
                if (sws) { sws_freeContext(sws); sws = nullptr; }
                sws = sws_getContext(src->width, src->height, fmt,
                                     conv->width, conv->height, AV_PIX_FMT_YUV420P,
                                     SWS_BILINEAR, nullptr, nullptr, nullptr);
                if (!sws) { LOGE("sws_getContext failed"); return BVC2_ERR_FORMAT; }
                sws_fmt = fmt;
                sws_w = src->width;
                sws_h = src->height;
            }
            const int rh = sws_scale(sws,
                                     reinterpret_cast<const uint8_t* const*>(src->data), src->linesize,
                                     0, src->height, conv->data, conv->linesize);
            if (rh != conv->height) { LOGE("sws_scale returned %d", rh); return BVC2_ERR_FORMAT; }
#else
            if (fmt != AV_PIX_FMT_YUV420P10LE && fmt != AV_PIX_FMT_YUV420P10BE) {
                LOGE("unsupported decoded pixel format: %s", av_get_pix_fmt_name(fmt));
                return BVC2_ERR_FORMAT;
            }
            if (convert10to8(src, conv) < 0) return BVC2_ERR_FORMAT;
#endif
            send = conv;
        }

        int64_t pts = (src->best_effort_timestamp != AV_NOPTS_VALUE)
                    ? src->best_effort_timestamp : src->pts;
        if (pts == AV_NOPTS_VALUE) {
            pts = next_vpts;
        } else {
            pts = av_rescale_q(pts, vdec_ctx->pkt_timebase, venc_ctx->time_base);
        }
        send->pts = pts;
        send->pict_type = AV_PICTURE_TYPE_NONE;
        next_vpts = pts + 1;

        return encodeVideo(send);
    }

    int encodeVideo(const AVFrame* frame) {
        int ret = avcodec_send_frame(venc_ctx, frame);
        if (ret == AVERROR(EAGAIN)) {
            int dr = drainVideoPackets();
            if (dr < 0) return dr;
            ret = avcodec_send_frame(venc_ctx, frame);
        }
        if (check_err(ret, "avcodec_send_frame(video)")) return BVC2_ERR_ENCODER;
        return drainVideoPackets();
    }

    int drainVideoPackets() {
        for (;;) {
            int ret = avcodec_receive_packet(venc_ctx, pkt);
            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) return BVC2_OK;
            if (check_err(ret, "avcodec_receive_packet(video)")) return BVC2_ERR_ENCODER;
            av_packet_rescale_ts(pkt, venc_ctx->time_base, vout->time_base);
            pkt->stream_index = vout->index;
            int wr = av_interleaved_write_frame(ofmt, pkt);
            av_packet_unref(pkt);
            if (check_err(wr, "av_interleaved_write_frame(video)")) return BVC2_ERR_IO;
        }
    }

    // 分配固定 frame_size 的输出缓冲，转换输入后继续 drain swr 内部缓冲。
    // swr_convert 返回的是输出采样数，不会自行分配输出缓冲。
    int resampleAndWrite(const uint8_t* const* in, int in_nb) {
        const int frameSize = aenc_ctx->frame_size > 0 ? aenc_ctx->frame_size : 1024;
        uint8_t** out = nullptr;
        int out_linesize = 0;
        int ret = av_samples_alloc_array_and_samples(&out, &out_linesize,
            aenc_ctx->ch_layout.nb_channels, frameSize, aenc_ctx->sample_fmt, 0);
        if (ret < 0 || !out) {
            if (out) av_freep(&out[0]);
            av_freep(&out);
            return BVC2_ERR_ALLOC;
        }
        int result = BVC2_OK;
        int nb = swr_convert(swr, out, frameSize, in, in_nb);
        if (check_err(nb, "swr_convert")) result = BVC2_ERR_AUDIO;
        else if (nb > 0) result = writeAudioSamples(nb, out);
        // 继续把 swr 内部缓冲的样本输出完（每次最多 frameSize）。
        while (result == BVC2_OK) {
            nb = swr_convert(swr, out, frameSize, nullptr, 0);
            if (nb < 0) { check_err(nb, "swr_convert(drain)"); result = BVC2_ERR_AUDIO; break; }
            if (nb <= 0) break;
            result = writeAudioSamples(nb, out);
        }
        av_freep(&out[0]);
        av_freep(&out);
        return result;
    }


    int writeAudioSamples(int nb, uint8_t* out[8]) {
        if (nb <= 0) return BVC2_OK;
        // AAC 编码器要求每帧恰好 frame_size 个采样；不足则补零。
        const int frameSize = aenc_ctx->frame_size > 0 ? aenc_ctx->frame_size : 1024;
        AVFrame* af = av_frame_alloc();
        if (!af) return BVC2_ERR_ALLOC;
        af->format = aenc_ctx->sample_fmt;
        af->sample_rate = aenc_ctx->sample_rate;
        if (av_channel_layout_copy(&af->ch_layout, &aenc_ctx->ch_layout) < 0) {
            av_frame_free(&af);
            return BVC2_ERR_ALLOC;
        }
        af->nb_samples = frameSize;
        if (av_frame_get_buffer(af, 0) < 0) {
            av_frame_free(&af);
            return BVC2_ERR_ALLOC;
        }

        const int ch = aenc_ctx->ch_layout.nb_channels;
        const int bps = av_get_bytes_per_sample(aenc_ctx->sample_fmt);
        const bool planar = av_sample_fmt_is_planar(aenc_ctx->sample_fmt);
        const int planes = planar ? ch : 1;
        // 先把整帧清零，再拷入 nb 个有效采样（nb <= frameSize）。
        const int frame_bytes = planar ? frameSize * bps : frameSize * ch * bps;
        const int valid_bytes = planar ? nb * bps : nb * ch * bps;
        for (int p = 0; p < planes; ++p) {
            memset(af->data[p], 0, static_cast<size_t>(frame_bytes));
            memcpy(af->data[p], out[p], static_cast<size_t>(valid_bytes));
        }

        int64_t pts = next_apts;
        af->pts = pts;
        next_apts = pts + frameSize;

        int ret = encodeAudio(af);
        av_frame_free(&af);
        return ret;
    }

    int processAudio(AVFrame* src) {
        if (!aenc_ctx || !swr || !aout) return BVC2_OK;
        if (src->nb_samples <= 0) return BVC2_OK;

        return resampleAndWrite(
            reinterpret_cast<const uint8_t* const*>(src->data), src->nb_samples);
    }

    int encodeAudio(const AVFrame* frame) {
        int ret = avcodec_send_frame(aenc_ctx, frame);
        if (ret == AVERROR(EAGAIN)) {
            int dr = drainAudioPackets();
            if (dr < 0) return dr;
            ret = avcodec_send_frame(aenc_ctx, frame);
        }
        if (check_err(ret, "avcodec_send_frame(audio)")) return BVC2_ERR_ENCODER;
        return drainAudioPackets();
    }

    int drainAudioPackets() {
        for (;;) {
            int ret = avcodec_receive_packet(aenc_ctx, pkt);
            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) return BVC2_OK;
            if (check_err(ret, "avcodec_receive_packet(audio)")) return BVC2_ERR_ENCODER;
            av_packet_rescale_ts(pkt, aenc_ctx->time_base, aout->time_base);
            pkt->stream_index = aout->index;
            int wr = av_interleaved_write_frame(ofmt, pkt);
            av_packet_unref(pkt);
            if (check_err(wr, "av_interleaved_write_frame(audio)")) return BVC2_ERR_IO;
        }
    }

    int transcodeLoop() {
        if (!pkt)   { pkt = av_packet_alloc(); if (!pkt) return BVC2_ERR_ALLOC; }
        if (!frame) { frame = av_frame_alloc(); if (!frame) return BVC2_ERR_ALLOC; }

        for (;;) {
            int ret = av_read_frame(ifmt, pkt);
            if (ret == AVERROR_EOF) break;
            if (check_err(ret, "av_read_frame")) return BVC2_ERR_IO;

            if (pkt->stream_index == vin) {
                ret = avcodec_send_packet(vdec_ctx, pkt);
                av_packet_unref(pkt);
                if (check_err(ret, "avcodec_send_packet(video)")) return BVC2_ERR_DECODER;
                for (;;) {
                    ret = avcodec_receive_frame(vdec_ctx, frame);
                    if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
                    if (check_err(ret, "avcodec_receive_frame(video)")) return BVC2_ERR_DECODER;
                    ret = processVideo(frame);
                    av_frame_unref(frame);
                    if (ret < 0) return ret;
                }
            } else if (ain >= 0 && pkt->stream_index == ain && aenc_ctx) {
                ret = avcodec_send_packet(adec_ctx, pkt);
                av_packet_unref(pkt);
                if (check_err(ret, "avcodec_send_packet(audio)")) return BVC2_ERR_DECODER;
                for (;;) {
                    ret = avcodec_receive_frame(adec_ctx, frame);
                    if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
                    if (check_err(ret, "avcodec_receive_frame(audio)")) return BVC2_ERR_DECODER;
                    ret = processAudio(frame);
                    av_frame_unref(frame);
                    if (ret < 0) return ret;
                }
            } else {
                av_packet_unref(pkt);
            }
        }
        return BVC2_OK;
    }

    int flushAll() {
        // Flush video decoder, then video encoder.
        int ret = avcodec_send_packet(vdec_ctx, nullptr);
        if (check_err(ret, "flush video decoder")) return BVC2_ERR_DECODER;
        for (;;) {
            ret = avcodec_receive_frame(vdec_ctx, frame);
            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
            if (check_err(ret, "avcodec_receive_frame(video flush)")) return BVC2_ERR_DECODER;
            ret = processVideo(frame);
            av_frame_unref(frame);
            if (ret < 0) return ret;
        }

        ret = avcodec_send_frame(venc_ctx, nullptr);
        if (check_err(ret, "flush video encoder")) return BVC2_ERR_ENCODER;
        ret = drainVideoPackets();
        if (ret < 0) return ret;

        if (aenc_ctx && adec_ctx && swr && aout) {
            ret = avcodec_send_packet(adec_ctx, nullptr);
            if (check_err(ret, "flush audio decoder")) return BVC2_ERR_DECODER;
            for (;;) {
                ret = avcodec_receive_frame(adec_ctx, frame);
                if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
                if (check_err(ret, "avcodec_receive_frame(audio flush)")) return BVC2_ERR_DECODER;
                ret = processAudio(frame);
                av_frame_unref(frame);
                if (ret < 0) return ret;
            }

            ret = resampleAndWrite(nullptr, 0);
            if (ret < 0) return ret;

            ret = avcodec_send_frame(aenc_ctx, nullptr);
            if (check_err(ret, "flush audio encoder")) return BVC2_ERR_ENCODER;
            ret = drainAudioPackets();
            if (ret < 0) return ret;
        }
        return BVC2_OK;
    }
};

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_hongguotv_nativeapp_Bvc2Transcoder_transcode(
        JNIEnv* env, jclass clazz, jstring inputPath, jstring outputPath) {
    (void)clazz;
    JStringGuard in(env, inputPath);
    if (!in.ok()) {
        LOGE("GetStringUTFChars(inputPath) failed");
        return BVC2_ERR_ARG;
    }
    JStringGuard out(env, outputPath);
    if (!out.ok()) {
        LOGE("GetStringUTFChars(outputPath) failed");
        return BVC2_ERR_ARG;
    }

    Transcoder t;
    return t.run(in.get(), out.get());
}

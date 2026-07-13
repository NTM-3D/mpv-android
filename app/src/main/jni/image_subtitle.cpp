#include <vector>
#include <string>
#include <mutex>
#include <algorithm>
#include <cmath>

#include <jni.h>

extern "C" {
    #include <libavcodec/avcodec.h>
    #include <libavformat/avformat.h>
}

#include "jni_utils.h"
#include "log.h"

extern "C" {
    jni_func(jboolean, initImageSubtitleDecoder, jstring path, jint ff_index, jint subtitle_order, jstring codec_hint);
    jni_func(void, releaseImageSubtitleDecoder);
    jni_func(jobject, renderImageSubtitleAt, jdouble time_pos_sec, jint out_width, jint out_height);
};

struct SubtitleEvent {
    int64_t start_ms;
    int64_t end_ms;
    int canvas_w;
    int canvas_h;
    int x;
    int y;
    int w;
    int h;
    std::vector<uint32_t> pixels;
};

static std::mutex g_sub_mutex;
static std::vector<SubtitleEvent> g_events;
static bool g_decoder_ready = false;
static int g_last_event_idx = -2;
static int g_last_out_w = 0;
static int g_last_out_h = 0;

static void clear_state_locked() {
    g_events.clear();
    g_decoder_ready = false;
    g_last_event_idx = -2;
    g_last_out_w = 0;
    g_last_out_h = 0;
}

static inline uint8_t a_from_argb(uint32_t c) { return (c >> 24) & 0xFF; }

static SubtitleEvent make_event(const AVSubtitle *sub, int canvas_w, int canvas_h, int64_t pts_ms)
{
    int min_x = INT32_MAX, min_y = INT32_MAX;
    int max_x = INT32_MIN, max_y = INT32_MIN;

    for (unsigned i = 0; i < sub->num_rects; ++i) {
        AVSubtitleRect *r = sub->rects[i];
        if (!r || r->type != SUBTITLE_BITMAP || r->w <= 0 || r->h <= 0)
            continue;
        min_x = std::min(min_x, r->x);
        min_y = std::min(min_y, r->y);
        max_x = std::max(max_x, r->x + r->w);
        max_y = std::max(max_y, r->y + r->h);
    }

    SubtitleEvent ev{};
    if (min_x == INT32_MAX || min_y == INT32_MAX || max_x <= min_x || max_y <= min_y)
        return ev;

    ev.x = min_x;
    ev.y = min_y;
    ev.w = max_x - min_x;
    ev.h = max_y - min_y;
    ev.canvas_w = canvas_w;
    ev.canvas_h = canvas_h;
    ev.pixels.assign(static_cast<size_t>(ev.w * ev.h), 0U);

    for (unsigned i = 0; i < sub->num_rects; ++i) {
        AVSubtitleRect *r = sub->rects[i];
        if (!r || r->type != SUBTITLE_BITMAP || r->w <= 0 || r->h <= 0 || !r->data[0] || !r->data[1])
            continue;

        const uint8_t *idx_plane = r->data[0];
        const uint32_t *pal = reinterpret_cast<const uint32_t*>(r->data[1]);
        const int line = r->linesize[0];
        const int base_x = r->x - min_x;
        const int base_y = r->y - min_y;

        for (int y = 0; y < r->h; ++y) {
            for (int x = 0; x < r->w; ++x) {
                uint8_t idx = idx_plane[y * line + x];
                uint32_t color = pal[idx];
                if (!a_from_argb(color))
                    continue;
                const int tx = base_x + x;
                const int ty = base_y + y;
                if (tx < 0 || ty < 0 || tx >= ev.w || ty >= ev.h)
                    continue;
                ev.pixels[ty * ev.w + tx] = color;
            }
        }
    }

    int64_t start = sub->start_display_time;
    int64_t end = sub->end_display_time;
    if (end <= start)
        end = start + 2000;
    if (pts_ms >= 0) {
        ev.start_ms = pts_ms + start;
        ev.end_ms = pts_ms + end;
    } else {
        ev.start_ms = start;
        ev.end_ms = end;
    }
    return ev;
}

static jobject make_bitmap(JNIEnv *env, const std::vector<uint32_t> &pixels, int width, int height)
{
    if (width <= 0 || height <= 0 || pixels.empty())
        return NULL;
    jintArray arr = env->NewIntArray(width * height);
    if (!arr)
        return NULL;
    env->SetIntArrayRegion(arr, 0, width * height, reinterpret_cast<const jint*>(pixels.data()));

    jobject bitmap_config =
        env->GetStaticObjectField(android_graphics_Bitmap_Config, android_graphics_Bitmap_Config_ARGB_8888);
    jobject bitmap =
        env->CallStaticObjectMethod(android_graphics_Bitmap, android_graphics_Bitmap_createBitmap,
                                    arr, width, height, bitmap_config);
    env->DeleteLocalRef(arr);
    env->DeleteLocalRef(bitmap_config);
    return bitmap;
}

jni_func(jboolean, initImageSubtitleDecoder, jstring jpath, jint ff_index, jint subtitle_order, jstring jcodec_hint) {
    if (!jpath)
        return JNI_FALSE;
    const char *path = env->GetStringUTFChars(jpath, NULL);
    if (!path)
        return JNI_FALSE;
    const char *codec_hint = NULL;
    if (jcodec_hint)
        codec_hint = env->GetStringUTFChars(jcodec_hint, NULL);

    AVFormatContext *fmt = NULL;
    AVCodecContext *codec = NULL;
    AVPacket *pkt = NULL;
    bool ok = false;
    std::vector<SubtitleEvent> events;

    do {
        if (avformat_open_input(&fmt, path, NULL, NULL) < 0)
            break;
        if (avformat_find_stream_info(fmt, NULL) < 0)
            break;

        int stream_idx = -1;
        std::vector<int> subtitle_streams;
        for (unsigned i = 0; i < fmt->nb_streams; ++i) {
            AVStream *st = fmt->streams[i];
            if (!st || st->codecpar->codec_type != AVMEDIA_TYPE_SUBTITLE)
                continue;
            subtitle_streams.push_back(static_cast<int>(i));
            if (static_cast<int>(i) == ff_index) {
                stream_idx = static_cast<int>(i);
            }
        }
        if (stream_idx < 0 && subtitle_order >= 0 &&
            subtitle_order < static_cast<jint>(subtitle_streams.size())) {
            stream_idx = subtitle_streams[subtitle_order];
        }
        if (stream_idx < 0 && codec_hint) {
            std::string hint(codec_hint);
            for (int idx : subtitle_streams) {
                AVCodecParameters *cp = fmt->streams[idx]->codecpar;
                const AVCodecDescriptor *desc = avcodec_descriptor_get(cp->codec_id);
                const char *name = desc ? desc->name : NULL;
                if (name && hint.find(name) != std::string::npos) {
                    stream_idx = idx;
                    break;
                }
            }
        }
        if (stream_idx < 0 && !subtitle_streams.empty())
            stream_idx = subtitle_streams.front();
        if (stream_idx < 0)
            break;

        AVStream *sub_stream = fmt->streams[stream_idx];
        int64_t stream_start_ms = 0;
        if (sub_stream->start_time != AV_NOPTS_VALUE) {
            stream_start_ms = av_rescale_q(sub_stream->start_time, sub_stream->time_base, AVRational{1, 1000});
        }
        const AVCodec *dec = avcodec_find_decoder(sub_stream->codecpar->codec_id);
        if (!dec)
            break;

        codec = avcodec_alloc_context3(dec);
        if (!codec)
            break;
        if (avcodec_parameters_to_context(codec, sub_stream->codecpar) < 0)
            break;
        if (avcodec_open2(codec, dec, NULL) < 0)
            break;

        pkt = av_packet_alloc();
        if (!pkt)
            break;

        const int canvas_w = codec->width > 0 ? codec->width : sub_stream->codecpar->width;
        const int canvas_h = codec->height > 0 ? codec->height : sub_stream->codecpar->height;

        while (av_read_frame(fmt, pkt) >= 0) {
            if (pkt->stream_index != stream_idx) {
                av_packet_unref(pkt);
                continue;
            }

            int64_t pkt_pts = pkt->pts;
            AVSubtitle sub{};
            int got = 0;
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wdeprecated-declarations"
            int ret = avcodec_decode_subtitle2(codec, &sub, &got, pkt);
#pragma GCC diagnostic pop
            av_packet_unref(pkt);
            if (ret < 0)
                continue;
            if (!got) {
                avsubtitle_free(&sub);
                continue;
            }

            int64_t pts_ms = -1;
            if (sub.pts != AV_NOPTS_VALUE)
                pts_ms = sub.pts / 1000;
            else if (pkt_pts != AV_NOPTS_VALUE)
                pts_ms = av_rescale_q(pkt_pts, sub_stream->time_base, AVRational{1, 1000});
            if (pts_ms != -1) {
                pts_ms -= stream_start_ms;
                if (pts_ms < 0)
                    pts_ms = 0;
            }

            SubtitleEvent ev = make_event(&sub, canvas_w, canvas_h, pts_ms);
            if (!ev.pixels.empty())
                events.push_back(std::move(ev));
            avsubtitle_free(&sub);
        }

        std::sort(events.begin(), events.end(), [](const SubtitleEvent &a, const SubtitleEvent &b) {
            return a.start_ms < b.start_ms;
        });
        ok = !events.empty();
        ALOGV("initImageSubtitleDecoder path=%s ff_index=%d subtitle_order=%d stream_idx=%d events=%zu",
              path, (int)ff_index, (int)subtitle_order, stream_idx, events.size());
    } while (0);

    if (pkt)
        av_packet_free(&pkt);
    if (codec)
        avcodec_free_context(&codec);
    if (fmt)
        avformat_close_input(&fmt);
    env->ReleaseStringUTFChars(jpath, path);
    if (codec_hint)
        env->ReleaseStringUTFChars(jcodec_hint, codec_hint);

    if (!ok) {
        ALOGE("initImageSubtitleDecoder failed: no usable subtitle events produced");
    }

    std::lock_guard<std::mutex> lock(g_sub_mutex);
    clear_state_locked();
    if (ok) {
        g_events = std::move(events);
        g_decoder_ready = true;
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

jni_func(void, releaseImageSubtitleDecoder) {
    std::lock_guard<std::mutex> lock(g_sub_mutex);
    clear_state_locked();
}

static int find_active_event(const std::vector<SubtitleEvent> &events, int64_t ms)
{
    int lo = 0;
    int hi = static_cast<int>(events.size()) - 1;
    while (lo <= hi) {
        int mid = lo + (hi - lo) / 2;
        if (ms < events[mid].start_ms) {
            hi = mid - 1;
        } else if (ms >= events[mid].end_ms) {
            lo = mid + 1;
        } else {
            return mid;
        }
    }
    return -1;
}

jni_func(jobject, renderImageSubtitleAt, jdouble time_pos_sec, jint out_width, jint out_height) {
    if (out_width <= 0 || out_height <= 0)
        return NULL;

    std::lock_guard<std::mutex> lock(g_sub_mutex);
    if (!g_decoder_ready)
        return NULL;

    int64_t ms = static_cast<int64_t>(std::llround(time_pos_sec * 1000.0));
    int event_idx = find_active_event(g_events, ms);

    if (event_idx == g_last_event_idx && out_width == g_last_out_w && out_height == g_last_out_h)
        return NULL;

    g_last_event_idx = event_idx;
    g_last_out_w = out_width;
    g_last_out_h = out_height;

    if (event_idx < 0) {
        std::vector<uint32_t> clear(1, 0);
        return make_bitmap(env, clear, 1, 1);
    }

    const SubtitleEvent &ev = g_events[event_idx];
    std::vector<uint32_t> frame(static_cast<size_t>(out_width * out_height), 0U);

    const float sx = ev.canvas_w > 0 ? static_cast<float>(out_width) / static_cast<float>(ev.canvas_w) : 1.0f;
    const float sy = ev.canvas_h > 0 ? static_cast<float>(out_height) / static_cast<float>(ev.canvas_h) : 1.0f;
    int dst_x = static_cast<int>(std::lround(ev.x * sx));
    int dst_y = static_cast<int>(std::lround(ev.y * sy));
    int dst_w = std::max(1, static_cast<int>(std::lround(ev.w * sx)));
    int dst_h = std::max(1, static_cast<int>(std::lround(ev.h * sy)));

    for (int y = 0; y < dst_h; ++y) {
        const int ty = dst_y + y;
        if (ty < 0 || ty >= out_height)
            continue;
        int src_y = (y * ev.h) / dst_h;
        if (src_y < 0 || src_y >= ev.h)
            continue;
        for (int x = 0; x < dst_w; ++x) {
            const int tx = dst_x + x;
            if (tx < 0 || tx >= out_width)
                continue;
            int src_x = (x * ev.w) / dst_w;
            if (src_x < 0 || src_x >= ev.w)
                continue;
            uint32_t c = ev.pixels[src_y * ev.w + src_x];
            if (!a_from_argb(c))
                continue;
            frame[ty * out_width + tx] = c;
        }
    }

    return make_bitmap(env, frame, out_width, out_height);
}

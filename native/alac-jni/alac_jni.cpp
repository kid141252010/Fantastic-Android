#include <jni.h>

#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <new>
#include <vector>

#include "ALACBitUtilities.h"
#include "ALACDecoder.h"

namespace {

constexpr uint32_t kBitBufferPadding = 3;

ALACDecoder* decoder_from_handle(jlong handle) {
    return reinterpret_cast<ALACDecoder*>(handle);
}

jbyteArray empty_array(JNIEnv* env) {
    return env->NewByteArray(0);
}

bool read_array(JNIEnv* env, jbyteArray array, std::vector<uint8_t>* output) {
    const jsize length = env->GetArrayLength(array);
    if (env->ExceptionCheck() || length < 0) {
        env->ExceptionClear();
        return false;
    }
    output->assign(static_cast<size_t>(length), 0);
    if (length != 0) {
        env->GetByteArrayRegion(
            array,
            0,
            length,
            reinterpret_cast<jbyte*>(output->data()));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
    }
    return true;
}

void write_float_le(uint8_t* destination, float value) {
    static_assert(sizeof(float) == sizeof(uint32_t));
    uint32_t bits = 0;
    std::memcpy(&bits, &value, sizeof(bits));
    destination[0] = static_cast<uint8_t>(bits);
    destination[1] = static_cast<uint8_t>(bits >> 8);
    destination[2] = static_cast<uint8_t>(bits >> 16);
    destination[3] = static_cast<uint8_t>(bits >> 24);
}

int32_t read_signed_sample(const uint8_t* source, uint8_t bit_depth) {
    if (bit_depth == 32) {
        uint32_t bits = static_cast<uint32_t>(source[0]) |
                        (static_cast<uint32_t>(source[1]) << 8) |
                        (static_cast<uint32_t>(source[2]) << 16) |
                        (static_cast<uint32_t>(source[3]) << 24);
        return static_cast<int32_t>(bits);
    }

    uint32_t bits = static_cast<uint32_t>(source[0]) |
                    (static_cast<uint32_t>(source[1]) << 8) |
                    (static_cast<uint32_t>(source[2]) << 16);
    if (bit_depth == 20) {
        bits >>= 4;
        if ((bits & (1u << 19)) != 0) {
            bits |= ~((1u << 20) - 1u);
        }
    } else if ((bits & (1u << 23)) != 0) {
        bits |= ~((1u << 24) - 1u);
    }
    return static_cast<int32_t>(bits);
}

jbyteArray decode_packet(JNIEnv* env, ALACDecoder* decoder, jbyteArray packet) {
    std::vector<uint8_t> encoded;
    if (!read_array(env, packet, &encoded) || encoded.empty()) {
        return empty_array(env);
    }

    const uint8_t bit_depth = decoder->mConfig.bitDepth;
    const uint32_t channels = decoder->mConfig.numChannels;
    const uint32_t frame_length = decoder->mConfig.frameLength;
    if (channels == 0 || frame_length == 0 ||
        (bit_depth != 16 && bit_depth != 20 && bit_depth != 24 && bit_depth != 32)) {
        return empty_array(env);
    }

    const size_t bytes_per_sample = bit_depth == 16 ? 2 : bit_depth == 32 ? 4 : 3;
    const size_t raw_capacity = static_cast<size_t>(frame_length) * channels * bytes_per_sample;
    std::vector<uint8_t> padded(encoded.size() + kBitBufferPadding, 0);
    std::memcpy(padded.data(), encoded.data(), encoded.size());
    std::vector<uint8_t> raw(raw_capacity, 0);

    BitBuffer bits;
    BitBufferInit(&bits, padded.data(), static_cast<uint32_t>(padded.size()));
    uint32_t decoded_samples = 0;
    const int32_t status = decoder->Decode(
        &bits,
        raw.data(),
        frame_length,
        channels,
        &decoded_samples);
    if (status != ALAC_noErr || decoded_samples == 0 || decoded_samples > frame_length) {
        return empty_array(env);
    }

    const size_t sample_count = static_cast<size_t>(decoded_samples) * channels;
    if (bit_depth == 16) {
        const size_t output_bytes = sample_count * sizeof(int16_t);
        jbyteArray output = env->NewByteArray(static_cast<jsize>(output_bytes));
        if (output == nullptr || env->ExceptionCheck()) {
            env->ExceptionClear();
            return empty_array(env);
        }
        env->SetByteArrayRegion(
            output,
            0,
            static_cast<jsize>(output_bytes),
            reinterpret_cast<const jbyte*>(raw.data()));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return empty_array(env);
        }
        return output;
    }

    const size_t raw_stride = bytes_per_sample;
    std::vector<uint8_t> output_bytes(sample_count * sizeof(float), 0);
    const float scale = std::ldexp(1.0f, -static_cast<int>(bit_depth - 1));
    for (size_t index = 0; index < sample_count; ++index) {
        const int32_t sample = read_signed_sample(raw.data() + index * raw_stride, bit_depth);
        write_float_le(
            output_bytes.data() + index * sizeof(float),
            static_cast<float>(sample) * scale);
    }

    jbyteArray output = env->NewByteArray(static_cast<jsize>(output_bytes.size()));
    if (output == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        return empty_array(env);
    }
    env->SetByteArrayRegion(
        output,
        0,
        static_cast<jsize>(output_bytes.size()),
        reinterpret_cast<const jbyte*>(output_bytes.data()));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return empty_array(env);
    }
    return output;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_kid141252010_fantastic_playback_AlacDecoder_open(
    JNIEnv* env,
    jclass,
    jbyteArray cookie) {
    std::vector<uint8_t> bytes;
    if (cookie == nullptr || !read_array(env, cookie, &bytes) || bytes.size() < 8) {
        return 0;
    }

    std::unique_ptr<ALACDecoder> decoder(new (std::nothrow) ALACDecoder());
    if (!decoder) {
        return 0;
    }
    if (decoder->Init(bytes.data(), static_cast<uint32_t>(bytes.size())) != ALAC_noErr) {
        return 0;
    }
    return reinterpret_cast<jlong>(decoder.release());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_kid141252010_fantastic_playback_AlacDecoder_decode(
    JNIEnv* env,
    jclass,
    jlong handle,
    jbyteArray packet) {
    ALACDecoder* decoder = decoder_from_handle(handle);
    if (decoder == nullptr || packet == nullptr) {
        return empty_array(env);
    }
    return decode_packet(env, decoder, packet);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_kid141252010_fantastic_playback_AlacDecoder_close(
    JNIEnv*,
    jclass,
    jlong handle) {
    delete decoder_from_handle(handle);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_kid141252010_fantastic_playback_AlacDecoder_channels(
    JNIEnv*,
    jclass,
    jlong handle) {
    ALACDecoder* decoder = decoder_from_handle(handle);
    return decoder == nullptr ? 0 : static_cast<jint>(decoder->mConfig.numChannels);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_kid141252010_fantastic_playback_AlacDecoder_sampleRate(
    JNIEnv*,
    jclass,
    jlong handle) {
    ALACDecoder* decoder = decoder_from_handle(handle);
    return decoder == nullptr ? 0 : static_cast<jint>(decoder->mConfig.sampleRate);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_kid141252010_fantastic_playback_AlacDecoder_bits(
    JNIEnv*,
    jclass,
    jlong handle) {
    ALACDecoder* decoder = decoder_from_handle(handle);
    return decoder == nullptr ? 0 : static_cast<jint>(decoder->mConfig.bitDepth);
}

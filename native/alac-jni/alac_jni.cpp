#include <jni.h>

#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <unordered_map>
#include <vector>

#include "ALACBitUtilities.h"
#include "ALACDecoder.h"

namespace {

struct DecoderContext {
    ALACDecoder decoder;
    uint32_t channels = 0;
    uint32_t sampleRate = 0;
    uint32_t bits = 0;
    uint32_t frameLength = 0;
    std::mutex mutex;
};

std::mutex gHandlesMutex;
std::unordered_map<jlong, std::shared_ptr<DecoderContext>> gHandles;
jlong gNextHandle = 1;

std::shared_ptr<DecoderContext> findHandle(jlong handle) {
    std::lock_guard<std::mutex> lock(gHandlesMutex);
    const auto it = gHandles.find(handle);
    return it == gHandles.end() ? nullptr : it->second;
}

jbyteArray emptyArray(JNIEnv* env) {
    return env->NewByteArray(0);
}

jlong open(JNIEnv* env, jobject, jbyteArray cookieArray) {
    if (cookieArray == nullptr) return 0;
    const jsize cookieLength = env->GetArrayLength(cookieArray);
    if (cookieLength < 24) return 0;

    std::vector<uint8_t> cookie(static_cast<size_t>(cookieLength));
    env->GetByteArrayRegion(cookieArray, 0, cookieLength,
                            reinterpret_cast<jbyte*>(cookie.data()));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return 0;
    }

    auto context = std::make_shared<DecoderContext>();
    if (context->decoder.Init(cookie.data(), static_cast<uint32_t>(cookie.size())) != ALAC_noErr) {
        return 0;
    }
    const ALACSpecificConfig& config = context->decoder.mConfig;
    if (config.frameLength == 0 || config.frameLength > (1u << 20) ||
        config.numChannels == 0 || config.numChannels > 8 ||
        (config.bitDepth != 16 && config.bitDepth != 20 &&
         config.bitDepth != 24 && config.bitDepth != 32) || config.sampleRate == 0) {
        return 0;
    }
    context->channels = config.numChannels;
    context->sampleRate = config.sampleRate;
    context->bits = config.bitDepth;
    context->frameLength = config.frameLength;

    std::lock_guard<std::mutex> lock(gHandlesMutex);
    const jlong handle = gNextHandle++;
    if (handle <= 0) return 0;
    gHandles.emplace(handle, std::move(context));
    return handle;
}

jbyteArray decode(JNIEnv* env, jobject, jlong handle, jbyteArray packetArray) {
    const auto context = findHandle(handle);
    if (context == nullptr || packetArray == nullptr) return emptyArray(env);
    const jsize packetLength = env->GetArrayLength(packetArray);
    if (packetLength <= 0) return emptyArray(env);

    std::vector<uint8_t> packet(static_cast<size_t>(packetLength));
    env->GetByteArrayRegion(packetArray, 0, packetLength,
                            reinterpret_cast<jbyte*>(packet.data()));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return emptyArray(env);
    }

    const uint32_t nativeBytesPerSample = context->bits == 16 ? 2u :
                                          (context->bits == 20 || context->bits == 24) ? 3u : 4u;
    const uint64_t nativeCapacity64 = static_cast<uint64_t>(context->frameLength) *
                                      context->channels * nativeBytesPerSample;
    if (nativeCapacity64 > static_cast<uint64_t>(std::numeric_limits<jsize>::max())) {
        return emptyArray(env);
    }
    std::vector<uint8_t> nativeOutput(static_cast<size_t>(nativeCapacity64), 0);

    std::lock_guard<std::mutex> lock(context->mutex);
    BitBuffer bits;
    BitBufferInit(&bits, packet.data(), static_cast<uint32_t>(packet.size()));
    uint32_t decodedSamples = context->frameLength;
    if (context->decoder.Decode(&bits, nativeOutput.data(), context->frameLength,
                                context->channels, &decodedSamples) != ALAC_noErr ||
        decodedSamples == 0 || decodedSamples > context->frameLength) {
        return emptyArray(env);
    }

    const uint64_t sampleCount64 = static_cast<uint64_t>(decodedSamples) * context->channels;
    const uint64_t outputBytes64 = sampleCount64 * (context->bits == 16 ? 2u : 4u);
    if (outputBytes64 > static_cast<uint64_t>(std::numeric_limits<jsize>::max())) {
        return emptyArray(env);
    }
    const jsize outputBytes = static_cast<jsize>(outputBytes64);
    jbyteArray result = env->NewByteArray(outputBytes);
    if (result == nullptr) return nullptr;

    jbyte* output = env->GetByteArrayElements(result, nullptr);
    if (output == nullptr) return nullptr;
    if (context->bits == 16) {
        std::memcpy(output, nativeOutput.data(), static_cast<size_t>(outputBytes));
    } else {
        for (size_t i = 0; i < static_cast<size_t>(sampleCount64); ++i) {
            int32_t value;
            if (context->bits == 20 || context->bits == 24) {
                const size_t offset = i * 3;
                uint32_t packed = static_cast<uint32_t>(nativeOutput[offset]) |
                                  (static_cast<uint32_t>(nativeOutput[offset + 1]) << 8) |
                                  (static_cast<uint32_t>(nativeOutput[offset + 2]) << 16);
                if ((packed & 0x800000u) != 0) packed |= 0xff000000u;
                value = static_cast<int32_t>(packed);
                if (context->bits == 20) value >>= 4;
            } else {
                std::memcpy(&value, nativeOutput.data() + i * 4, sizeof(value));
            }
            const float denominator = context->bits == 20 ? 524288.0f :
                                      context->bits == 24 ? 8388608.0f : 2147483648.0f;
            const float normalized = static_cast<float>(value) / denominator;
            std::memcpy(output + i * sizeof(float), &normalized, sizeof(float));
        }
    }
    env->ReleaseByteArrayElements(result, output, 0);
    return result;
}

void close(JNIEnv*, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(gHandlesMutex);
    gHandles.erase(handle);
}

jint channels(JNIEnv*, jobject, jlong handle) {
    const auto context = findHandle(handle);
    return context == nullptr ? 0 : static_cast<jint>(context->channels);
}

jint sampleRate(JNIEnv*, jobject, jlong handle) {
    const auto context = findHandle(handle);
    return context == nullptr ? 0 : static_cast<jint>(context->sampleRate);
}

jint bits(JNIEnv*, jobject, jlong handle) {
    const auto context = findHandle(handle);
    return context == nullptr ? 0 : static_cast<jint>(context->bits);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_kid141252010_fantastic_playback_AlacDecoder_open(
    JNIEnv* env, jobject receiver, jbyteArray cookie) {
    return open(env, receiver, cookie);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_github_kid141252010_fantastic_playback_AlacDecoder_decode(
    JNIEnv* env, jobject receiver, jlong handle, jbyteArray packet) {
    return decode(env, receiver, handle, packet);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_kid141252010_fantastic_playback_AlacDecoder_close(
    JNIEnv* env, jobject receiver, jlong handle) {
    close(env, receiver, handle);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_kid141252010_fantastic_playback_AlacDecoder_channels(
    JNIEnv* env, jobject receiver, jlong handle) {
    return channels(env, receiver, handle);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_kid141252010_fantastic_playback_AlacDecoder_sampleRate(
    JNIEnv* env, jobject receiver, jlong handle) {
    return sampleRate(env, receiver, handle);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_kid141252010_fantastic_playback_AlacDecoder_bits(
    JNIEnv* env, jobject receiver, jlong handle) {
    return bits(env, receiver, handle);
}

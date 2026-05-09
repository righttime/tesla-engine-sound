#include "aaudio_engine.h"
#include <android/log.h>
#include <cstring>

#define LOG_TAG "AAudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

AAudioEngine::AAudioEngine() {
    LOGI("AAudioEngine constructed");
}

AAudioEngine::~AAudioEngine() {
    shutdown();
}

bool AAudioEngine::initialize(int sampleRate, int channelCount, int bufferSizeFrames) {
    std::lock_guard<std::mutex> lock(m_mutex);

    if (m_stream != nullptr) {
        LOGW("Already initialized, shutting down first");
        shutdown();
    }

    m_sampleRate = sampleRate;
    m_channelCount = channelCount;
    m_bufferSizeFrames = bufferSizeFrames;

    m_ringBuffer.initialize(RING_BUFFER_SAMPLES);

    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) {
        LOGE("Failed to create stream builder: %s", AAudio_convertResultToText(result));
        return false;
    }

    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSampleRate(builder, sampleRate);
    AAudioStreamBuilder_setChannelCount(builder, channelCount);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setDataCallback(builder, dataCallback, this);
    AAudioStreamBuilder_setFramesPerDataCallback(builder, bufferSizeFrames);

    result = AAudioStreamBuilder_openStream(builder, &m_stream);
    AAudioStreamBuilder_delete(builder);

    if (result != AAUDIO_OK) {
        LOGE("Failed to open stream: %s", AAudio_convertResultToText(result));
        m_stream = nullptr;
        return false;
    }

    LOGI("Stream opened: rate=%d, channels=%d, buf=%d",
         AAudioStream_getSampleRate(m_stream),
         AAudioStream_getChannelCount(m_stream),
         AAudioStream_getFramesPerBurst(m_stream));

    result = AAudioStream_requestStart(m_stream);
    if (result != AAUDIO_OK) {
        LOGE("Failed to start stream: %s", AAudio_convertResultToText(result));
        AAudioStream_close(m_stream);
        m_stream = nullptr;
        return false;
    }

    m_isActive.store(true);
    LOGI("AAudio stream started");
    return true;
}

void AAudioEngine::writeAudio(const int16_t* data, int sampleCount) {
    if (!m_isActive.load()) return;

    std::lock_guard<std::mutex> lock(m_mutex);
    for (int i = 0; i < sampleCount; i++) {
        m_ringBuffer.write(data[i]);
    }
}

void AAudioEngine::setVolume(float volume) {
    m_volume.store(volume);
}

bool AAudioEngine::isActive() const {
    return m_isActive.load();
}

void AAudioEngine::shutdown() {
    std::lock_guard<std::mutex> lock(m_mutex);

    if (m_stream != nullptr) {
        AAudioStream_requestStop(m_stream);
        AAudioStream_close(m_stream);
        m_stream = nullptr;
    }

    m_ringBuffer.destroy();
    m_isActive.store(false);
    LOGI("AAudio engine shutdown");
}

aaudio_data_callback_result_t AAudioEngine::dataCallback(
    AAudioStream* stream, void* userData, void* audioData, int32_t numFrames) {
    if (!userData) return AAUDIO_CALLBACK_RESULT_STOP;
    return static_cast<AAudioEngine*>(userData)->onDataCallback(audioData, numFrames);
}

aaudio_data_callback_result_t AAudioEngine::onDataCallback(void* audioData, int32_t numFrames) {
    int16_t* output = static_cast<int16_t*>(audioData);
    size_t totalSamples = numFrames * m_channelCount;

    std::lock_guard<std::mutex> lock(m_mutex);
    size_t available = m_ringBuffer.size();
    size_t toRead = std::min(available, totalSamples);

    if (toRead > 0) {
        m_ringBuffer.readAndRemove(static_cast<int>(toRead), output);
    }

    // Fill remaining with silence
    if (toRead < totalSamples) {
        std::memset(output + toRead, 0, (totalSamples - toRead) * sizeof(int16_t));
    }

    // Apply volume
    float vol = m_volume.load();
    if (vol < 0.999f) {
        for (size_t i = 0; i < totalSamples; i++) {
            output[i] = static_cast<int16_t>(output[i] * vol);
        }
    }

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

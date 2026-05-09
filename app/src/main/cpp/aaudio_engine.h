#ifndef ANDROID_AAUDIO_ENGINE_H
#define ANDROID_AAUDIO_ENGINE_H

#include <aaudio/AAudio.h>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include "engine_sim/ring_buffer.h"

class AAudioEngine {
public:
    AAudioEngine();
    ~AAudioEngine();

    bool initialize(int sampleRate, int channelCount, int bufferSizeFrames);
    void writeAudio(const int16_t* data, int sampleCount);
    void setVolume(float volume);
    bool isActive() const;
    void shutdown();

    int getSampleRate() const { return m_sampleRate; }
    int getChannelCount() const { return m_channelCount; }

private:
    static aaudio_data_callback_result_t dataCallback(
        AAudioStream* stream, void* userData, void* audioData, int32_t numFrames);
    aaudio_data_callback_result_t onDataCallback(void* audioData, int32_t numFrames);

    AAudioStream* m_stream = nullptr;
    int m_sampleRate = 44100;
    int m_channelCount = 1;
    int m_bufferSizeFrames = 512;
    std::atomic<float> m_volume{1.0f};
    RingBuffer<int16_t> m_ringBuffer;
    static constexpr int RING_BUFFER_SAMPLES = 44100 * 2; // ~2 seconds
    std::mutex m_mutex;
    std::atomic<bool> m_isActive{false};
};

#endif // ANDROID_AAUDIO_ENGINE_H

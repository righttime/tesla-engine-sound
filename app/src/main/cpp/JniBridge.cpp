#include "JniBridge.h"

#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <chrono>
#include <cstring>
#include <memory>
#include <cmath>
#include <thread>
#include <vector>

#define LOG_TAG "JniBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#include "engine_sim/engine.h"
#include "engine_sim/vehicle.h"
#include "engine_sim/transmission.h"
#include "aaudio_engine.h"

struct ImpulseLayer {
    std::vector<float> data;
    float volume = 0.0f;
};

struct PopcornEvent {
    bool active = false;
    int position = 0;
    int length = 0;
    double amplitude = 0;
    double baseFreq = 0;
    double prevFiltered = 0;
};

struct EngineSimHandle {
    std::unique_ptr<Engine> engine;
    std::unique_ptr<Vehicle> vehicle;
    std::unique_ptr<Transmission> transmission;
    std::unique_ptr<AAudioEngine> audioEngine;

    float currentRpm = 800.0f;
    float currentTorque = 0.0f;
    float currentPower = 0.0f;
    float currentThrottle = 0.0f;
    float volume = 1.0f;
    bool isRunning = false;
    bool isStarting = false;     // 스타터 모터 크랭킹 중
    double starterStartTime = 0; // 크랭킹 시작 시각 (seconds)
    double starterDuration = 2.0;// 크랭킹 지속 시간
    int sampleRate = 44100;
    int cylinderCount = 4;

    // Audio pump thread
    std::thread* audioPumpThread = nullptr;
    std::atomic<bool> audioPumpRunning{false};

    // Multi-layer impulse responses (6 slots)
    static const int MAX_IR_LAYERS = 15;
    ImpulseLayer irLayers[MAX_IR_LAYERS];
    int activeIrCount = 0;

    // Preset name for IR selection
    std::string currentPreset;

    // Backfire / popcorn state
    PopcornEvent popcornEvents[4];
    double prevSmoothThrottle = 0.0;
};

// --- Load WAV file from Android assets ---
static bool loadWavFromAssets(AAssetManager* assetMgr, const char* path,
                               std::vector<float>& pcmFloat) {
    AAsset* asset = AAssetManager_open(assetMgr, path, AASSET_MODE_STREAMING);
    if (!asset) {
        LOGE("Failed to open asset: %s", path);
        return false;
    }

    off_t fileSize = AAsset_getLength(asset);
    if (fileSize < 44) {
        LOGE("WAV file too small: %s (%ld bytes)", path, (long)fileSize);
        AAsset_close(asset);
        return false;
    }

    std::vector<uint8_t> raw(fileSize);
    AAsset_read(asset, raw.data(), fileSize);
    AAsset_close(asset);

    if (raw[0] != 'R' || raw[1] != 'I' || raw[2] != 'F' || raw[3] != 'F') {
        LOGE("Not a RIFF file: %s", path);
        return false;
    }

    uint32_t dataOffset = 0;
    uint32_t dataSize = 0;
    for (uint32_t i = 12; i < fileSize - 8; i++) {
        if (raw[i] == 'd' && raw[i+1] == 'a' && raw[i+2] == 't' && raw[i+3] == 'a') {
            dataOffset = i + 8;
            dataSize = *(uint32_t*)&raw[i + 4];
            break;
        }
    }

    if (dataOffset == 0 || dataSize == 0) {
        LOGE("No data chunk found in: %s", path);
        return false;
    }

    uint32_t sampleCount = dataSize / sizeof(int16_t);
    int16_t* pcm16 = (int16_t*)(raw.data() + dataOffset);

    // Convert to float [-1, 1] and truncate to IR_MAX_LENGTH
    int irLen = std::min((int)sampleCount, 2048);
    pcmFloat.resize(irLen);
    float maxVal = 0.0f;
    for (int i = 0; i < irLen; i++) {
        pcmFloat[i] = pcm16[i] / 32768.0f;
        float abs = std::fabs(pcmFloat[i]);
        if (abs > maxVal) maxVal = abs;
    }
    // Normalize
    if (maxVal > 0.001f) {
        for (int i = 0; i < irLen; i++) {
            pcmFloat[i] /= maxVal;
        }
    }

    LOGI("Loaded IR WAV: %s — %d samples (truncated from %u)", path, irLen, sampleCount);
    return true;
}

// --- Audio pump: direct synthesis + multi-layer FIR convolution ---
static void audioPump(EngineSimHandle* h) {
    LOGI("Audio pump started (engine-sim pipeline, %d cylinders, %d layers)",
         h->cylinderCount, h->activeIrCount);

    const int chunkSize = 256;
    int16_t* audioBuffer = new int16_t[chunkSize];

    // Find max IR length across active layers
    int maxIrLen = 0;
    for (int l = 0; l < EngineSimHandle::MAX_IR_LAYERS; l++) {
        if (h->irLayers[l].volume > 0.0f && (int)h->irLayers[l].data.size() > maxIrLen)
            maxIrLen = (int)h->irLayers[l].data.size();
    }
    if (maxIrLen == 0) maxIrLen = 1;

    int historyLen = maxIrLen + chunkSize;
    std::vector<float> history(historyLen, 0.0f);
    int histWritePos = 0;

    // Per-cylinder filter state (engine-sim: each cylinder has independent filters)
    static const int MAX_CYL = 12;
    float cylPrevDc[MAX_CYL] = {0};
    float cylPrevSample[MAX_CYL] = {0};
    int cylJitterWritePos[MAX_CYL] = {0};
    std::vector<float> cylJitterHistory[MAX_CYL];
    for (int c = 0; c < MAX_CYL; c++) {
        cylJitterHistory[c].resize(64, 0.0f);
    }
    
    // Separate jitter history (exhaust flow timing variation)
    const int jitterHistLen = 64;
    std::vector<float> jitterHistory(jitterHistLen, 0.0f);
    int jitterWritePos = 0;
    int writeCount = 0;

    // Time tracking for starter
    auto startTime = std::chrono::steady_clock::now();

    double crankAngle = 0.0;
    const double cycleRad = 4.0 * M_PI;  // 720 degrees

    // Smoothing state
    double smoothRpm = 800.0;
    double smoothThrottle = 0.0;

    // Engine-sim pipeline state
    float prevSample = 0.0f;       // unused (per-cylinder now)
    float prevFilteredNoise = 0.0f;// air noise LPF state
    float prevAntiAlias = 0.0f;    // anti-aliasing LPF state
    float levelPeak = 0.001f;       // engine-sim leveling: peak tracker
    float levelAttenuation = 1.0f;  // engine-sim leveling: smoothed attenuation
    float levelTarget = 0.916f;     // engine-sim: 30000/32768 in float scale
    uint32_t rngState = 54321;

    // Backfire / popcorn
    uint32_t popcornSeed = 99999;

    // Cabin filter state
    float cabinLowShelf = 0.0f;   // ~200Hz low shelf
    float cabinHighShelf = 0.0f;  // ~2kHz high shelf
    static const int CABIN_REVERB_LEN = 256;  // ~5.8ms at 44.1kHz
    float cabinReverbBuf[CABIN_REVERB_LEN] = {0};
    int cabinReverbPos = 0;

    // Noise helper
    auto nextNoise = [&]() -> float {
        rngState = rngState * 1103515245u + 12345u;
        return ((rngState >> 16) & 0x7fff) / 16383.5f - 1.0f;
    };

    while (h->audioPumpRunning.load()) {
        // === Starter motor simulation ===
        if (h->isStarting && !h->isRunning) {
            auto now = std::chrono::steady_clock::now();
            double currentTime = std::chrono::duration<double>(now - startTime).count();
            
            // Record start time on first frame
            if (h->starterStartTime == 0) h->starterStartTime = currentTime;
            double elapsed = currentTime - h->starterStartTime;
            if (elapsed < 0) elapsed = 0;
            double progress = elapsed / h->starterDuration;
            
            if (progress >= 1.0) {
                // 시동 성공! 엔진 점화
                h->isStarting = false;
                h->isRunning = true;
                h->currentRpm = 1200.0f; // 높은 아이들로 점프
                LOGI("Engine started!");
            } else {
                // 크랭킹: 150~350 RPM 불규칙하게
                double baseCrankRpm = 150.0 + progress * 200.0;
                // 랜덤한 RPM 변동 (실린더 압축 저항)
                double crankVariation = 80.0 * std::sin(elapsed * 37.0) + 40.0 * std::sin(elapsed * 73.0);
                h->currentRpm = (float)(baseCrankRpm + crankVariation);
                if (h->currentRpm < 80.0f) h->currentRpm = 80.0f;
            }
        }
        
        if ((h->isRunning || h->isStarting) && h->audioEngine && h->audioEngine->isActive()) {
            double sr = h->sampleRate;

            for (int i = 0; i < chunkSize; i++) {
                // Smooth RPM and throttle
                double targetRpm = h->currentRpm;
                double targetThrottle = h->currentThrottle;
                smoothRpm += (targetRpm - smoothRpm) * 0.005;
                smoothThrottle += (targetThrottle - smoothThrottle) * 0.01;

                // RPM-based pitch adjustment (more realistic engine sound)
                const float rpm = smoothRpm;
                float pitchMultiplier = 1.0f;
                
                if (rpm < 2000.0f) {
                    // At low RPM: deeper, more realistic idle sound
                    pitchMultiplier = 0.8f + 0.2f * (rpm / 2000.0f);  // 0.8 to 1.0
                } else if (rpm > 4000.0f) {
                    // At high RPM: slight reduction for realism (engines don't get higher pitched)
                    pitchMultiplier = 1.0f - 0.05f * std::min(1.0f, (rpm - 4000.0f) / 2000.0f);  // 1.0 to 0.95
                }
                
                double crankRadPerSample = (smoothRpm / 60.0) * 2.0 * M_PI / sr * pitchMultiplier;

                // === 1. Per-cylinder exhaust flow + filter chain (engine-sim style) ===
                float throttle = (float)smoothThrottle;
                if (throttle < 0.1f) throttle = 0.1f;

                // Engine-sim style: airNoise & dF_F_mix parameters
                float airNoise = 1.0f;
                float dF_F_mix = 0.01f;
                if (rpm < 1500.0f) {
                    float f = rpm / 1500.0f;
                    airNoise = 0.2f + 0.3f * f;
                    dF_F_mix = 0.4f - 0.39f * f;
                } else if (rpm < 3000.0f) {
                    float f = (rpm - 1500.0f) / 1500.0f;
                    airNoise = 0.5f + 0.4f * f;
                    dF_F_mix = 0.01f + 0.14f * f;
                } else {
                    airNoise = 1.0f;
                    dF_F_mix = 0.15f;
                }

                // Each cylinder gets its own jitter → DC → derivative
                float signal = 0.0f;
                
                for (int c = 0; c < h->cylinderCount; c++) {
                    double cylOffset = (720.0 / h->cylinderCount) * c;
                    double crankDeg = std::fmod(crankAngle * 180.0 / M_PI, 720.0);
                    if (crankDeg < 0) crankDeg += 720.0;
                    double cylDeg = crankDeg - cylOffset;
                    if (cylDeg < 0) cylDeg += 720.0;

                    // Exhaust valve open: ~130° to ~400° in 720° cycle
                    float cylExhaust = 0.0f;
                    if (cylDeg > 130.0 && cylDeg < 400.0) {
                        double t = (cylDeg - 130.0) / 270.0;
                        cylExhaust = (float)(std::exp(-t * 3.0) * throttle);
                    }
                    cylExhaust += 0.02f; // idle base

                    // Per-cylinder Jitter
                    cylJitterHistory[c][cylJitterWritePos[c] % 64] = cylExhaust;
                    cylJitterWritePos[c]++;
                    float jitterOff = nextNoise() * 3.0f;
                    int jReadIdx = cylJitterWritePos[c] - 1 - (int)std::round(jitterOff);
                    if (jReadIdx < 0) jReadIdx += 64;
                    float f_in = cylJitterHistory[c][jReadIdx % 64];

                    // Per-cylinder DC filter
                    cylPrevDc[c] = cylPrevDc[c] + 0.99f * (f_in - cylPrevDc[c]);
                    float f_ac = f_in - cylPrevDc[c];

                    // Per-cylinder Derivative
                    float f_deriv = (f_in - cylPrevSample[c]) * (float)sr;
                    cylPrevSample[c] = f_in;

                    // RPM-based bass boost (풍부한 저음)
                    if (rpm < 2500.0f) {
                        f_deriv *= (3.0f + 2.0f * (rpm / 2500.0f));  // 3.0x → 5.0x
                    } else if (rpm < 5000.0f) {
                        f_deriv *= (5.0f - 4.0f * ((rpm - 2500.0f) / 2500.0f));  // 5.0x → 1.0x
                    }

                    // Engine-sim style v_in
                    float r_mixed = airNoise * prevFilteredNoise + (1.0f - airNoise);
                    float v_in = f_deriv * dF_F_mix + f_ac * r_mixed * (1.0f - dF_F_mix);

                    signal += v_in;
                }

                // === 2. Air noise channel ===
                float noise = nextNoise();
                float noiseAlpha = 0.02f;
                prevFilteredNoise = prevFilteredNoise + noiseAlpha * (noise - prevFilteredNoise);

                // === 7. Backfire / popcorn ===
                double throttleDelta = h->prevSmoothThrottle - smoothThrottle;
                h->prevSmoothThrottle = smoothThrottle;

                // More frequent: lower threshold, lower RPM gate, more slots
                if (throttleDelta > 0.05 && smoothRpm > 1500.0) {
                    int prob = std::min(90, (int)(smoothRpm / 50));
                    if (h->cylinderCount >= 6) prob = std::min(99, (int)(prob * 1.3));
                    if (h->cylinderCount >= 8) prob = std::min(99, (int)(prob * 1.2));
                    popcornSeed = popcornSeed * 1103515245u + 12345u;
                    if (((popcornSeed >> 16) & 0xffff) % 100 < prob) {
                        for (int p = 0; p < 4; p++) {
                            if (!h->popcornEvents[p].active) {
                                h->popcornEvents[p].active = true;
                                h->popcornEvents[p].position = 0;
                                h->popcornEvents[p].length = 200 + (rand() % 500);  // shorter to longer range
                                h->popcornEvents[p].amplitude = 0.6 + smoothThrottle * 0.8;
                                // Vary tone: low rumble or sharp crack
                                if (rand() % 3 == 0) {
                                    // Sharp crack (backfire)
                                    h->popcornEvents[p].baseFreq = 120 + (rand() % 100);
                                    h->popcornEvents[p].length = 100 + (rand() % 200);
                                    h->popcornEvents[p].amplitude *= 1.5f;
                                } else {
                                    // Popcorn burble
                                    h->popcornEvents[p].baseFreq = 40 + (rand() % 80);
                                }
                                h->popcornEvents[p].prevFiltered = 0;
                                break;
                            }
                        }
                    }
                }

                for (int p = 0; p < 4; p++) {
                    if (h->popcornEvents[p].active) {
                        double t = (double)h->popcornEvents[p].position / h->popcornEvents[p].length;
                        double envelope = std::exp(-t * 4.0);  // faster decay
                        double freq = h->popcornEvents[p].baseFreq * (1.0 - 0.5 * t);  // more pitch drop
                        double tSec = (double)h->popcornEvents[p].position / 44100.0;
                        double sine = sin(2.0 * M_PI * freq * tSec);
                        popcornSeed = popcornSeed * 1103515245u + 12345u;
                        double pnoise = (double)((popcornSeed >> 16) & 0x7fff) / 16383.5 - 1.0;
                        double popSample = (sine * 0.8 + pnoise * 0.2) * envelope * h->popcornEvents[p].amplitude;
                        double dt = 1.0 / 44100.0;
                        double rc = 1.0 / (2.0 * M_PI * 250.0);  // lower cutoff = more rumble
                        double alpha = dt / (rc + dt);
                        double filtered = h->popcornEvents[p].prevFiltered + alpha * (popSample - h->popcornEvents[p].prevFiltered);
                        h->popcornEvents[p].prevFiltered = filtered;
                        signal += (float)filtered;
                        h->popcornEvents[p].position++;
                        if (h->popcornEvents[p].position >= h->popcornEvents[p].length)
                            h->popcornEvents[p].active = false;
                    }
                }

                // Store in history for convolution
                history[histWritePos % historyLen] = signal;
                histWritePos++;

                // === 8. Engine-sim style gradual convolution mixing ===
                const float convAmount = std::min(1.0f, static_cast<float>(smoothRpm) / 4000.0f);  // 0 to 1 based on RPM
                const float baseAmount = 1.0f - convAmount;
                
                // RPM-based dynamic IR selection with enhanced mixing
                float convOut = 0.0f;
                float totalVolume = 0.0f;
                
                // Use preset volumes as base, with RPM-based enhancement
                float rpmVolumes[EngineSimHandle::MAX_IR_LAYERS];
                for (int l = 0; l < EngineSimHandle::MAX_IR_LAYERS; l++) {
                    rpmVolumes[l] = h->irLayers[l].volume;  // Start with preset volumes
                }
                
                // RPM-based enhancement (multiply preset volumes)
                if (rpm < 2000.0f) {
                    // Low RPM: enhance smooth IRs
                    rpmVolumes[0] *= 1.8f;  // smooth_01 main emphasis
                    rpmVolumes[7] *= 1.4f;  // smooth_02 secondary
                    rpmVolumes[1] *= 1.2f;  // smooth_05 light
                }
                else if (rpm < 4000.0f) {
                    // Mid RPM: balanced enhancement
                    rpmVolumes[2] *= 1.5f;  // smooth_10 sporty
                    rpmVolumes[5] *= 1.3f;  // smooth_39 main
                    rpmVolumes[9] *= 1.2f;  // smooth_15 character
                }
                else {
                    // High RPM: performance enhancement  
                    rpmVolumes[4] *= 1.6f;  // smooth_27 rumble main
                    rpmVolumes[11] *= 1.4f; // smooth_30 racing
                    rpmVolumes[6] *= 1.2f;  // smooth_45 high end
                    rpmVolumes[12] *= 1.1f; // smooth_35 turbo
                }
                
                // Apply RPM-adjusted volumes for convolution
                for (int l = 0; l < EngineSimHandle::MAX_IR_LAYERS; l++) {
                    float vol = rpmVolumes[l];
                    if (vol <= 0.0f) continue;
                    
                    int irLen = (int)h->irLayers[l].data.size();
                    for (int j = 0; j < irLen; j++) {
                        int idx = histWritePos - 1 - j;
                        while (idx < 0) idx += historyLen;
                        convOut += history[idx % historyLen] * h->irLayers[l].data[j] * vol;
                    }
                    totalVolume += vol;
                }
                
                // Normalize convolution result
                if (totalVolume > 0.0f) {
                    convOut /= totalVolume;
                }
                
                // Engine-sim style: gradual blending (not baseConv + enhancedConv)
                float finalConv = baseAmount * signal + convAmount * convOut;
                
                // === 9. Anti-aliasing LPF (engine-sim: cutoff ~0.45*sr = ~19845Hz, nearly passthrough) ===
                float aaAlpha = 0.95f;  // Very light filtering, engine-sim uses ~19845Hz cutoff
                float antiAliased = prevAntiAlias + aaAlpha * (finalConv - prevAntiAlias);
                prevAntiAlias = antiAliased;

                // === 10. Leveling filter (engine-sim peak-tracking AGC) ===
                // During cranking: bypass leveling (signal too weak, AGC kills it)
                if (h->isStarting && !h->isRunning) {
                    levelAttenuation = 1.0f;
                    levelPeak = 0.001f;
                } else {
                    levelPeak = 0.999f * levelPeak;
                    if (std::abs(antiAliased) > levelPeak) levelPeak = std::abs(antiAliased);
                    if (levelPeak > 0.001f) {
                        float levelAtten = levelTarget / levelPeak;
                        if (levelAtten < 0.00001f) levelAtten = 0.00001f;
                        else if (levelAtten > 1.9f) levelAtten = 1.9f;
                        levelAttenuation = 0.9f * levelAttenuation + 0.1f * levelAtten;
                    }
                }
                float output = antiAliased * levelAttenuation * h->volume;

                // RPM-based volume boost (up to 1.5x at max RPM)
                float rpmBoost = 1.0f + 0.5f * std::min(1.0f, rpm / 6800.0f);
                output *= rpmBoost;

                // INT16 hard clipping (engine-sim style)
                if (output > 1.0f) output = 1.0f;
                else if (output < -1.0f) output = -1.0f;

                // === 11. Cabin filter layer (car interior effect - AGGRESSIVE) ===
                // A. Low shelf boost: ~200Hz, +12dB
                cabinLowShelf = 0.995f * cabinLowShelf + 0.005f * output;
                output = output + 3.0f * (cabinLowShelf - output);

                // B. High shelf cut: ~1kHz, -8dB (more aggressive)
                cabinHighShelf = 0.90f * cabinHighShelf + 0.10f * output;
                output = 0.4f * output + 0.6f * cabinHighShelf;

                // C. Reverb: 8ms, 25% wet (stronger)
                cabinReverbBuf[cabinReverbPos] = output;
                cabinReverbPos = (cabinReverbPos + 1) % CABIN_REVERB_LEN;
                float reverb = 0.0f;
                for (int r = 0; r < CABIN_REVERB_LEN; r++) {
                    reverb += cabinReverbBuf[(cabinReverbPos + r) % CABIN_REVERB_LEN];
                }
                reverb /= CABIN_REVERB_LEN;
                output = 0.75f * output + 0.25f * reverb;

                // Clamp and convert
                if (output > 1.0f) output = 1.0f;
                if (output < -1.0f) output = -1.0f;
                audioBuffer[i] = (int16_t)(output * 32767.0f);

                crankAngle += crankRadPerSample;
                if (crankAngle >= cycleRad) crankAngle -= cycleRad;
            }

            h->audioEngine->writeAudio(audioBuffer, chunkSize);

            writeCount++;
            if (writeCount % 200 == 0) {
                LOGI("audioPump: count=%d, rpm=%.0f, throttle=%.2f", writeCount, h->currentRpm, h->currentThrottle);
            }
        } else {
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
    }

    delete[] audioBuffer;
    LOGI("Audio pump stopped");
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_tesla_enginesound_EngineAudioBridge_nativeCreate(JNIEnv* env, jobject thiz) {
    LOGI("Creating EngineSim handle");

    EngineSimHandle* handle = new EngineSimHandle();

    handle->engine = std::make_unique<Engine>();
    handle->vehicle = std::make_unique<Vehicle>();
    handle->transmission = std::make_unique<Transmission>();

    handle->currentRpm = 800.0f;
    handle->cylinderCount = 4;

    LOGI("EngineSim handle created: %p", handle);
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT void JNICALL
Java_com_tesla_enginesound_EngineAudioBridge_nativeDestroy(JNIEnv* env, jobject thiz, jlong handle) {
    LOGI("Destroying EngineSim handle: %ld", handle);

    if (handle != 0) {
        EngineSimHandle* h = reinterpret_cast<EngineSimHandle*>(handle);

        h->audioPumpRunning.store(false);
        if (h->audioPumpThread) {
            h->audioPumpThread->join();
            delete h->audioPumpThread;
            h->audioPumpThread = nullptr;
        }

        if (h->audioEngine) {
            h->audioEngine->shutdown();
            h->audioEngine.reset();
        }

        delete h;
    }
}

JNIEXPORT void JNICALL
Java_com_tesla_enginesound_EngineAudioBridge_nativeStart(JNIEnv* env, jobject thiz, jlong handle) {
    if (handle == 0) return;

    EngineSimHandle* h = reinterpret_cast<EngineSimHandle*>(handle);
    h->isStarting = true;
    h->isRunning = false;
    h->starterStartTime = 0; // Will be set on first audioPump iteration
    h->currentRpm = 0.0f;
    LOGI("Engine cranking started");
}

JNIEXPORT void JNICALL
Java_com_tesla_enginesound_EngineAudioBridge_nativeStop(JNIEnv* env, jobject thiz, jlong handle) {
    if (handle == 0) return;

    EngineSimHandle* h = reinterpret_cast<EngineSimHandle*>(handle);
    h->isRunning = false;
    h->isStarting = false;
    h->currentRpm = 0.0f;
    h->currentThrottle = 0.0f;
    LOGI("Engine stopped");
}

JNIEXPORT void JNICALL
Java_com_tesla_enginesound_EngineAudioBridge_nativeSetThrottle(JNIEnv* env, jobject thiz,
    jlong handle, jfloat throttle) {

    if (handle == 0) return;

    EngineSimHandle* h = reinterpret_cast<EngineSimHandle*>(handle);
    h->currentThrottle = throttle;

    if (h->isRunning) {
        float targetRpm = 800.0f + (throttle * 6000.0f);
        h->currentRpm = targetRpm;

        float maxTorque = 400.0f;
        h->currentTorque = throttle * maxTorque * (1.0f - (h->currentRpm / 10000.0f));
        if (h->currentTorque < 0) h->currentTorque = 0;

        h->currentPower = (h->currentTorque * h->currentRpm) / 5252.0f;
        if (h->currentPower < 0) h->currentPower = 0;
    }
}

JNIEXPORT void JNICALL
Java_com_tesla_enginesound_EngineAudioBridge_nativeSetVolume(JNIEnv* env, jobject thiz,
    jlong handle, jfloat volume) {

    if (handle == 0) return;

    EngineSimHandle* h = reinterpret_cast<EngineSimHandle*>(handle);
    h->volume = volume;

    if (h->audioEngine) {
        h->audioEngine->setVolume(volume);
    }
}

JNIEXPORT jfloat JNICALL
Java_com_tesla_enginesound_EngineAudioBridge_nativeGetRpm(JNIEnv* env, jobject thiz, jlong handle) {
    if (handle == 0) return 800.0f;
    return reinterpret_cast<EngineSimHandle*>(handle)->currentRpm;
}

JNIEXPORT jfloat JNICALL
Java_com_tesla_enginesound_EngineAudioBridge_nativeGetTorque(JNIEnv* env, jobject thiz, jlong handle) {
    if (handle == 0) return 0.0f;
    return reinterpret_cast<EngineSimHandle*>(handle)->currentTorque;
}

JNIEXPORT jfloat JNICALL
Java_com_tesla_enginesound_EngineAudioBridge_nativeGetPower(JNIEnv* env, jobject thiz, jlong handle) {
    if (handle == 0) return 0.0f;
    return reinterpret_cast<EngineSimHandle*>(handle)->currentPower;
}

JNIEXPORT jint JNICALL
Java_com_tesla_enginesound_EngineAudioBridge_nativeReadAudio(JNIEnv* env, jobject thiz,
    jlong handle, jshortArray buffer, jint samples) {
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_tesla_enginesound_EngineAudioBridge_nativeSimulateStep(JNIEnv* env, jobject thiz, jlong handle) {
    if (handle == 0) return JNI_FALSE;

    EngineSimHandle* h = reinterpret_cast<EngineSimHandle*>(handle);

    if (!h->isRunning) return JNI_TRUE;

    float targetRpm = 800.0f + (h->currentThrottle * 5500.0f);
    float rpmDelta = targetRpm - h->currentRpm;
    h->currentRpm += rpmDelta * 0.03f;

    if (h->currentRpm < 800.0f) h->currentRpm = 800.0f;
    if (h->currentRpm > 8000.0f) h->currentRpm = 8000.0f;

    float maxTorque = 450.0f;
    float torqueFactor = 1.0f - (h->currentRpm / 9000.0f);
    if (torqueFactor < 0) torqueFactor = 0;
    h->currentTorque = h->currentThrottle * maxTorque * torqueFactor;

    h->currentPower = (h->currentTorque * h->currentRpm) / 5252.0f;
    if (h->currentPower < 0) h->currentPower = 0;

    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_tesla_enginesound_EngineAudioBridge_nativeInitializeAudio(JNIEnv* env, jobject thiz,
    jlong handle, jint sampleRate, jint channelCount, jint bufferSizeFrames,
    jobject assetManager) {

    LOGI("Initializing audio: rate=%d, channels=%d, buffer=%d",
         sampleRate, channelCount, bufferSizeFrames);

    if (handle == 0) {
        LOGE("Invalid handle");
        return JNI_FALSE;
    }

    EngineSimHandle* h = reinterpret_cast<EngineSimHandle*>(handle);
    h->sampleRate = sampleRate;

    // --- Load impulse response WAV from assets ---
    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
    if (!mgr) {
        LOGE("Failed to get AAssetManager");
        return JNI_FALSE;
    }

    // Load all IR layers
    // Index: 0=smooth_01, 1=smooth_05, 2=smooth_27, 3=smooth_39, 4=smooth_45
    const char* irPaths[15] = {
        "sound-library/smooth/smooth_01.wav",    // Low RPM smooth
        "sound-library/smooth/smooth_05.wav",    // Mid-low RPM
        "sound-library/smooth/smooth_10.wav",    // Mid RPM sporty
        "sound-library/smooth/smooth_17.wav",    // Mid-high RPM
        "sound-library/smooth/smooth_27.wav",    // High RPM rumble
        "sound-library/smooth/smooth_39.wav",    // Very high RPM
        "sound-library/smooth/smooth_45.wav",    // Ultra high RPM
        "sound-library/smooth/smooth_02.wav",    // Alternative low RPM
        "sound-library/smooth/smooth_08.wav",    // Alternative mid RPM
        "sound-library/smooth/smooth_15.wav",    // Sporty mid RPM
        "sound-library/smooth/smooth_22.wav",    // High performance
        "sound-library/smooth/smooth_30.wav",    // Racing style
        "sound-library/smooth/smooth_35.wav",    // Turbo sound
        "sound-library/smooth/smooth_40.wav",    // Track day
        "sound-library/smooth/smooth_48.wav"     // Extreme RPM
    };
    for (int i = 0; i < 15; i++) {
        if (loadWavFromAssets(mgr, irPaths[i], h->irLayers[i].data)) {
            h->activeIrCount++;
        }
    }
    // Apply engine-sim style IR clipping: trim trailing samples where abs <= 100 (in int16 scale)
    for (int l = 0; l < h->MAX_IR_LAYERS; l++) {
        if (h->irLayers[l].data.empty()) continue;
        int clippedLen = 0;
        for (size_t j = 0; j < h->irLayers[l].data.size(); j++) {
            if (std::abs(h->irLayers[l].data[j]) > 100.0f / 32768.0f) {
                clippedLen = (int)(j + 1);
            }
        }
        if (clippedLen > 0 && clippedLen < (int)h->irLayers[l].data.size()) {
            h->irLayers[l].data.resize(clippedLen);
            LOGI("IR layer %d clipped to %d samples (engine-sim style)", l, clippedLen);
        }
    }
    LOGI("Loaded %d IR layers", h->activeIrCount);

    // Set default preset IR volumes (I4)
    h->currentPreset = "I4";
    for (int l = 0; l < h->MAX_IR_LAYERS; l++) h->irLayers[l].volume = 0.0f;
    // 4-cylinder engine: balanced sound across RPM range
    h->irLayers[5].volume = 0.6f;  // smooth_39 - main mid-high RPM
    h->irLayers[0].volume = 0.3f;  // smooth_01 - low RPM smooth
    h->irLayers[2].volume = 0.2f;  // smooth_10 - sporty character
    h->irLayers[8].volume = 0.2f;  // smooth_08 - alternative tone

    // --- Initialize AAudio engine ---
    h->audioEngine = std::make_unique<AAudioEngine>();

    if (!h->audioEngine->initialize(sampleRate, channelCount, bufferSizeFrames)) {
        LOGE("Failed to initialize AAudio engine");
        h->audioEngine.reset();
        return JNI_FALSE;
    }

    h->audioEngine->setVolume(h->volume);

    // --- Start audio pump thread ---
    h->audioPumpRunning.store(true);
    h->audioPumpThread = new std::thread(audioPump, h);

    LOGI("Audio initialized successfully (direct synthesis + FIR convolution)");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_tesla_enginesound_EngineAudioBridge_nativeLoadEnginePreset(JNIEnv* env, jobject thiz,
    jlong handle, jstring presetName, jint cylinderCount, jfloat boreMm,
    jfloat strokeMm, jfloat compressionRatio) {

    if (handle == 0) return JNI_FALSE;

    EngineSimHandle* h = reinterpret_cast<EngineSimHandle*>(handle);
    h->cylinderCount = cylinderCount;

    const char* name = env->GetStringUTFChars(presetName, nullptr);
    std::string presetStr = name ? name : "I4";
    LOGI("Loading preset: %s (cylinders=%d, bore=%.1fmm, stroke=%.1fmm, comp=%.1f)",
         name, cylinderCount, boreMm, strokeMm, compressionRatio);
    env->ReleaseStringUTFChars(presetName, name);

    // Set IR layers based on preset
    h->currentPreset = presetStr;
    // Keep all WAV data, just adjust volumes for different character
    for (int l = 0; l < EngineSimHandle::MAX_IR_LAYERS; l++)
        h->irLayers[l].volume = 0.0f;  // Reset, but WAV data remains

    // 15-layer IR index: 0=smooth_01, 1=smooth_05, 2=smooth_10, 3=smooth_17, 4=smooth_27
    //  5=smooth_39, 6=smooth_45, 7=smooth_02, 8=smooth_08, 9=smooth_15
    //  10=smooth_22, 11=smooth_30, 12=smooth_35, 13=smooth_40, 14=smooth_48
    if (h->currentPreset == "I4") {
        h->irLayers[5].volume = 0.5f;  // smooth_39 - main character
        h->irLayers[0].volume = 0.4f;  // smooth_01 - smooth low end
        h->irLayers[2].volume = 0.3f;  // smooth_10 - sporty mid
        h->irLayers[8].volume = 0.2f;  // smooth_08 - alternative tone
        h->irLayers[7].volume = 0.2f;  // smooth_02 - warm character
        h->irLayers[9].volume = 0.15f; // smooth_15 - bright
    } else if (h->currentPreset == "V6") {
        h->irLayers[1].volume = 0.5f;  // smooth_05 - smooth V6 character
        h->irLayers[4].volume = 0.4f;  // smooth_27 - V6 rumble
        h->irLayers[9].volume = 0.3f;  // smooth_15 - sporty V6 tone
        h->irLayers[11].volume = 0.2f; // smooth_30 - racing V6
        h->irLayers[0].volume = 0.2f;  // smooth_01 - low end
        h->irLayers[3].volume = 0.15f; // smooth_17 - mid-high
    } else if (h->currentPreset == "V8") {
        h->irLayers[4].volume = 0.6f;  // smooth_27 - V8 rumble main
        h->irLayers[0].volume = 0.3f;  // smooth_01 - low end
        h->irLayers[12].volume = 0.3f; // smooth_35 - turbo V8
        h->irLayers[7].volume = 0.2f;  // smooth_02 - alternative V8
        h->irLayers[2].volume = 0.15f; // smooth_10 - bite
        h->irLayers[14].volume = 0.1f; // smooth_48 - extreme
    } else if (h->currentPreset == "V12") {
        h->irLayers[1].volume = 0.4f;  // smooth_05 - smooth V12
        h->irLayers[6].volume = 0.4f;  // smooth_45 - high-end V12
        h->irLayers[10].volume = 0.3f; // smooth_22 - performance V12
        h->irLayers[13].volume = 0.2f; // smooth_40 - track V12
        h->irLayers[5].volume = 0.2f;  // smooth_39 - mid presence
        h->irLayers[3].volume = 0.15f; // smooth_17 - refinement
    } else {
        h->irLayers[5].volume = 0.5f;  // smooth_39 fallback main
        h->irLayers[0].volume = 0.3f;  // smooth_01 fallback aux
    }

    LOGI("Preset IR volumes: L0=%.2f L1=%.2f L2=%.2f L3=%.2f L4=%.2f L5=%.2f L6=%.2f L7=%.2f",
         h->irLayers[0].volume, h->irLayers[1].volume, h->irLayers[2].volume,
         h->irLayers[3].volume, h->irLayers[4].volume, h->irLayers[5].volume,
         h->irLayers[6].volume, h->irLayers[7].volume);

    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_tesla_enginesound_EngineAudioBridge_nativeLoadEnginePresetByName(JNIEnv* env, jobject thiz,
    jlong handle, jstring presetName) {

    if (handle == 0) return JNI_FALSE;

    const char* name = env->GetStringUTFChars(presetName, nullptr);
    LOGI("Loading preset by name: %s", name);
    env->ReleaseStringUTFChars(presetName, name);

    return JNI_TRUE;
}

} // extern "C"

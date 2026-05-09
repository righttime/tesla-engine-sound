cmake_minimum_required(VERSION 3.18)
project(engine_sim_jni)

set(CMAKE_CXX_STANDARD 17)

# Minimal JNI stub for now - audio integration comes later
add_library(engine_sim_jni SHARED
    JniBridge.cpp
    aaudio_engine.cpp
)

find_library(AAUDIO_LIB aaudio)
find_library(LOG_LIB log)
find_library(ANDROID_LIB android)

target_link_libraries(engine_sim_jni
    ${AAUDIO_LIB}
    ${LOG_LIB}
    ${ANDROID_LIB}
)

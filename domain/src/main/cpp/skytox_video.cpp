// SPDX-FileCopyrightText: 2026 skyTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

#include <jni.h>
#include <stdint.h>

namespace {

inline int clamp(int value) {
    if (value < 0) {
        return 0;
    }
    if (value > 255) {
        return 255;
    }
    return value;
}

inline void map_rotated_point(
    int out_x,
    int out_y,
    int width,
    int height,
    int rotation,
    int *input_x,
    int *input_y
) {
    switch (rotation) {
        case 90:
            *input_x = out_y;
            *input_y = height - 1 - out_x;
            break;
        case 180:
            *input_x = width - 1 - out_x;
            *input_y = height - 1 - out_y;
            break;
        case 270:
            *input_x = width - 1 - out_y;
            *input_y = out_x;
            break;
        default:
            *input_x = out_x;
            *input_y = out_y;
            break;
    }

    if (*input_x < 0) {
        *input_x = 0;
    } else if (*input_x >= width) {
        *input_x = width - 1;
    }

    if (*input_y < 0) {
        *input_y = 0;
    } else if (*input_y >= height) {
        *input_y = height - 1;
    }
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_ltd_evilcorp_domain_av_VideoFrameConverter_nativeYuv420ToArgb(
    JNIEnv *env,
    jobject,
    jint width,
    jint height,
    jbyteArray yArray,
    jbyteArray uArray,
    jbyteArray vArray,
    jint yStride,
    jint uStride,
    jint vStride,
    jintArray pixelsArray
) {
    if (width <= 0 || height <= 0 || yStride <= 0 || uStride <= 0 || vStride <= 0) {
        return JNI_FALSE;
    }

    const jsize yLength = env->GetArrayLength(yArray);
    const jsize uLength = env->GetArrayLength(uArray);
    const jsize vLength = env->GetArrayLength(vArray);
    const jsize pixelLength = env->GetArrayLength(pixelsArray);
    const int requiredPixels = width * height;
    if (pixelLength < requiredPixels) {
        return JNI_FALSE;
    }

    jboolean yCopy = JNI_FALSE;
    jboolean uCopy = JNI_FALSE;
    jboolean vCopy = JNI_FALSE;
    jboolean pixelsCopy = JNI_FALSE;
    auto *y = reinterpret_cast<uint8_t *>(env->GetPrimitiveArrayCritical(yArray, &yCopy));
    auto *u = reinterpret_cast<uint8_t *>(env->GetPrimitiveArrayCritical(uArray, &uCopy));
    auto *v = reinterpret_cast<uint8_t *>(env->GetPrimitiveArrayCritical(vArray, &vCopy));
    auto *pixels = reinterpret_cast<uint32_t *>(env->GetPrimitiveArrayCritical(pixelsArray, &pixelsCopy));

    if (y == nullptr || u == nullptr || v == nullptr || pixels == nullptr) {
        if (pixels != nullptr) {
            env->ReleasePrimitiveArrayCritical(pixelsArray, pixels, JNI_ABORT);
        }
        if (v != nullptr) {
            env->ReleasePrimitiveArrayCritical(vArray, v, JNI_ABORT);
        }
        if (u != nullptr) {
            env->ReleasePrimitiveArrayCritical(uArray, u, JNI_ABORT);
        }
        if (y != nullptr) {
            env->ReleasePrimitiveArrayCritical(yArray, y, JNI_ABORT);
        }
        return JNI_FALSE;
    }

    for (int row = 0; row < height; ++row) {
        const int yRow = row * yStride;
        const int uRow = (row / 2) * uStride;
        const int vRow = (row / 2) * vStride;
        const int pixelRow = row * width;
        for (int col = 0; col < width; ++col) {
            const int uvCol = col / 2;
            const int yIndex = yRow + col;
            const int uIndex = uRow + uvCol;
            const int vIndex = vRow + uvCol;

            const int yy = yIndex >= 0 && yIndex < yLength ? y[yIndex] : 0;
            const int uu = (uIndex >= 0 && uIndex < uLength ? u[uIndex] : 128) - 128;
            const int vv = (vIndex >= 0 && vIndex < vLength ? v[vIndex] : 128) - 128;

            const int r = clamp((yy * 1024 + 1436 * vv) >> 10);
            const int g = clamp((yy * 1024 - 352 * uu - 731 * vv) >> 10);
            const int b = clamp((yy * 1024 + 1815 * uu) >> 10);
            pixels[pixelRow + col] =
                0xff000000u |
                (static_cast<uint32_t>(r) << 16u) |
                (static_cast<uint32_t>(g) << 8u) |
                static_cast<uint32_t>(b);
        }
    }

    env->ReleasePrimitiveArrayCritical(pixelsArray, pixels, 0);
    env->ReleasePrimitiveArrayCritical(vArray, v, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(uArray, u, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(yArray, y, JNI_ABORT);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_ltd_evilcorp_domain_av_VideoFrameConverter_nativeNv21ToI420(
    JNIEnv *env,
    jobject,
    jbyteArray dataArray,
    jint width,
    jint height,
    jint rotation,
    jbyteArray yArray,
    jbyteArray uArray,
    jbyteArray vArray
) {
    if (width <= 0 || height <= 0) {
        return JNI_FALSE;
    }

    int normalized_rotation = rotation % 360;
    if (normalized_rotation < 0) {
        normalized_rotation += 360;
    }
    if (
        normalized_rotation != 0 &&
        normalized_rotation != 90 &&
        normalized_rotation != 180 &&
        normalized_rotation != 270
    ) {
        normalized_rotation = 0;
    }

    const int out_width = (normalized_rotation == 90 || normalized_rotation == 270) ? height : width;
    const int out_height = (normalized_rotation == 90 || normalized_rotation == 270) ? width : height;
    const int frame_size = width * height;
    const int out_frame_size = out_width * out_height;
    const int out_chroma_size = out_frame_size / 4;

    if (
        env->GetArrayLength(dataArray) < frame_size + frame_size / 2 ||
        env->GetArrayLength(yArray) < out_frame_size ||
        env->GetArrayLength(uArray) < out_chroma_size ||
        env->GetArrayLength(vArray) < out_chroma_size
    ) {
        return JNI_FALSE;
    }

    jboolean dataCopy = JNI_FALSE;
    jboolean yCopy = JNI_FALSE;
    jboolean uCopy = JNI_FALSE;
    jboolean vCopy = JNI_FALSE;
    auto *data = reinterpret_cast<uint8_t *>(env->GetPrimitiveArrayCritical(dataArray, &dataCopy));
    auto *y = reinterpret_cast<uint8_t *>(env->GetPrimitiveArrayCritical(yArray, &yCopy));
    auto *u = reinterpret_cast<uint8_t *>(env->GetPrimitiveArrayCritical(uArray, &uCopy));
    auto *v = reinterpret_cast<uint8_t *>(env->GetPrimitiveArrayCritical(vArray, &vCopy));

    if (data == nullptr || y == nullptr || u == nullptr || v == nullptr) {
        if (v != nullptr) {
            env->ReleasePrimitiveArrayCritical(vArray, v, JNI_ABORT);
        }
        if (u != nullptr) {
            env->ReleasePrimitiveArrayCritical(uArray, u, JNI_ABORT);
        }
        if (y != nullptr) {
            env->ReleasePrimitiveArrayCritical(yArray, y, JNI_ABORT);
        }
        if (data != nullptr) {
            env->ReleasePrimitiveArrayCritical(dataArray, data, JNI_ABORT);
        }
        return JNI_FALSE;
    }

    for (int out_y = 0; out_y < out_height; ++out_y) {
        for (int out_x = 0; out_x < out_width; ++out_x) {
            int input_x = 0;
            int input_y = 0;
            map_rotated_point(out_x, out_y, width, height, normalized_rotation, &input_x, &input_y);
            y[out_y * out_width + out_x] = data[input_y * width + input_x];
        }
    }

    const int out_chroma_width = out_width / 2;
    for (int out_y = 0; out_y < out_height / 2; ++out_y) {
        for (int out_x = 0; out_x < out_chroma_width; ++out_x) {
            int input_x = 0;
            int input_y = 0;
            map_rotated_point(out_x * 2, out_y * 2, width, height, normalized_rotation, &input_x, &input_y);
            const int chroma_index = frame_size + (input_y / 2) * width + (input_x / 2) * 2;
            const int out_index = out_y * out_chroma_width + out_x;
            v[out_index] = data[chroma_index];
            u[out_index] = data[chroma_index + 1];
        }
    }

    env->ReleasePrimitiveArrayCritical(vArray, v, 0);
    env->ReleasePrimitiveArrayCritical(uArray, u, 0);
    env->ReleasePrimitiveArrayCritical(yArray, y, 0);
    env->ReleasePrimitiveArrayCritical(dataArray, data, JNI_ABORT);
    return JNI_TRUE;
}

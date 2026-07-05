#include <jni.h>
#include <string.h>
#include "ble_adv_hci.h"

JNIEXPORT jint JNICALL
Java_com_ava_mods_bleadv_BleAdvNative_nativePrepController(JNIEnv *env, jclass clazz,
                                                           jint dev) {
    (void) env;
    (void) clazz;
    return (jint) ble_adv_prep_controller((int) dev);
}

JNIEXPORT jstring JNICALL
Java_com_ava_mods_bleadv_BleAdvNative_nativeRun(JNIEnv *env, jclass clazz,
                                                jint dev, jstring jmode,
                                                jint duration, jbyteArray jpdu) {
    (void) clazz;
    if (jmode == NULL || jpdu == NULL) {
        return (*env)->NewStringUTF(env, "FAIL null_args");
    }
    const char *mode = (*env)->GetStringUTFChars(env, jmode, NULL);
    if (mode == NULL) {
        return (*env)->NewStringUTF(env, "FAIL mode");
    }
    jsize len = (*env)->GetArrayLength(env, jpdu);
    jbyte *bytes = (*env)->GetByteArrayElements(env, jpdu, NULL);
    if (bytes == NULL || len <= 0) {
        (*env)->ReleaseStringUTFChars(env, jmode, mode);
        return (*env)->NewStringUTF(env, "FAIL empty_pdu");
    }

    char out[512];
    memset(out, 0, sizeof(out));
    int rc = ble_adv_execute((int) dev, mode, (int) duration,
                             (const uint8_t *) bytes, (int) len,
                             out, (int) sizeof(out));

    (*env)->ReleaseByteArrayElements(env, jpdu, bytes, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, jmode, mode);

    if (out[0] == '\0') {
        if (rc == 0) {
            strncpy(out, "OK", sizeof(out) - 1);
        } else {
            strncpy(out, "FAIL unknown", sizeof(out) - 1);
        }
    }
    return (*env)->NewStringUTF(env, out);
}

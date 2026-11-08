// SPDX-License-Identifier: Apache-2.0
// Fmq: one end of an AIDL Fast Message Queue (SynchronizedReadWrite), what
// libfmq's AidlMessageQueue does for a HAL, without libfmq (a C++ platform
// library, not in the NDK).
//
// The framework (sensorservice) creates the queue and sends its descriptor:
// shared memory described by "grantors" (libfmq MQDescriptorBase.h):
//   0 READPTRPOS     uint64 read counter (bytes ever read)
//   1 WRITEPTRPOS    uint64 write counter (bytes ever written)
//   2 DATAPTRPOS     the ring of items, extent = its size in bytes
//   3 EVFLAGWORDPOS  uint32 event flag word: a futex the reader waits on
// Writing: copy at write % size (wrapping), advance the write counter with
// release order, then set the flag bits and FUTEX_WAKE_BITSET them
// (libfmq EventFlag::wake).
#pragma once

#include <aidl/android/hardware/common/fmq/MQDescriptor.h>
#include <aidl/android/hardware/common/fmq/SynchronizedReadWrite.h>

#include <linux/futex.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <atomic>
#include <climits>
#include <cstdint>
#include <cstring>

class Fmq {
  public:
    Fmq() = default;
    Fmq(const Fmq&) = delete;
    Fmq& operator=(const Fmq&) = delete;
    ~Fmq() { unmap(); }

    /** Map the queue; false if the descriptor is not one we can use. Resets
     *  both counters, as the reference HAL does (resetPointers = true). */
    template <typename T>
    bool init(const aidl::android::hardware::common::fmq::MQDescriptor<
                      T, aidl::android::hardware::common::fmq::SynchronizedReadWrite>& d,
              size_t quantum) {
        unmap();
        if (d.quantum != static_cast<int32_t>(quantum) || d.grantors.size() < 3) return false;
        size_t n = d.grantors.size() > 3 ? 4 : 3;
        for (size_t i = 0; i < n; i++) {
            const auto& g = d.grantors[i];
            if (g.fdIndex < 0 || g.fdIndex >= static_cast<int>(d.handle.fds.size())) return false;
            if (g.extent < 0 || (g.offset & 3) != 0) return false;
            long page = sysconf(_SC_PAGESIZE);
            off_t mapOffset = (g.offset / page) * page;
            size_t length = g.offset - mapOffset + g.extent;
            void* base = mmap(nullptr, length, PROT_READ | PROT_WRITE, MAP_SHARED,
                              d.handle.fds[g.fdIndex].get(), mapOffset);
            if (base == MAP_FAILED) return false;
            mMaps[i] = {base, length};
            mPtr[i] = static_cast<uint8_t*>(base) + (g.offset - mapOffset);
        }
        if (d.grantors[0].extent < 8 || d.grantors[1].extent < 8) return false;
        mSize = d.grantors[2].extent;
        mQuantum = quantum;
        if (mSize == 0 || mSize % quantum != 0) return false;
        readPtr()->store(0, std::memory_order_release);
        writePtr()->store(0, std::memory_order_release);
        return true;
    }

    bool valid() const { return mSize != 0; }

    /** Append count items, all or none (false: not enough room). */
    bool write(const void* items, size_t count) {
        if (!valid()) return false;
        uint64_t wp = writePtr()->load(std::memory_order_relaxed);
        uint64_t rp = readPtr()->load(std::memory_order_acquire);
        size_t bytes = count * mQuantum;
        if (wp - rp > mSize || mSize - (wp - rp) < bytes) return false;
        size_t pos = wp % mSize;
        size_t first = bytes < mSize - pos ? bytes : mSize - pos;
        memcpy(ring() + pos, items, first);
        memcpy(ring(), static_cast<const uint8_t*>(items) + first, bytes - first);
        writePtr()->store(wp + bytes, std::memory_order_release);
        return true;
    }

    /** Take up to max items; how many were taken. */
    size_t read(void* items, size_t max) {
        if (!valid()) return 0;
        uint64_t wp = writePtr()->load(std::memory_order_acquire);
        uint64_t rp = readPtr()->load(std::memory_order_relaxed);
        if (wp - rp > mSize) {  // corrupted: drop everything
            readPtr()->store(wp, std::memory_order_release);
            return 0;
        }
        size_t n = (wp - rp) / mQuantum;
        if (n > max) n = max;
        size_t bytes = n * mQuantum;
        size_t pos = rp % mSize;
        size_t first = bytes < mSize - pos ? bytes : mSize - pos;
        memcpy(items, ring() + pos, first);
        memcpy(static_cast<uint8_t*>(items) + first, ring(), bytes - first);
        readPtr()->store(rp + bytes, std::memory_order_release);
        return n;
    }

    /** Set bits in the event flag word and wake whoever waits on them. */
    void wake(uint32_t bits) {
        auto* word = flagWord();
        if (word == nullptr || bits == 0) return;
        uint32_t old = word->fetch_or(bits);
        if ((~old & bits) != 0) syscall(SYS_futex, word, FUTEX_WAKE_BITSET, INT_MAX, nullptr, nullptr, bits);
    }

  private:
    std::atomic<uint64_t>* readPtr() { return reinterpret_cast<std::atomic<uint64_t>*>(mPtr[0]); }
    std::atomic<uint64_t>* writePtr() { return reinterpret_cast<std::atomic<uint64_t>*>(mPtr[1]); }
    uint8_t* ring() { return mPtr[2]; }
    std::atomic<uint32_t>* flagWord() { return reinterpret_cast<std::atomic<uint32_t>*>(mPtr[3]); }

    void unmap() {
        for (int i = 0; i < 4; i++) {
            if (mMaps[i].base != nullptr) munmap(mMaps[i].base, mMaps[i].length);
            mMaps[i] = {};
            mPtr[i] = nullptr;
        }
        mSize = 0;
    }

    struct Map {
        void* base = nullptr;
        size_t length = 0;
    };
    Map mMaps[4];
    uint8_t* mPtr[4] = {};
    size_t mSize = 0;
    size_t mQuantum = 0;
};

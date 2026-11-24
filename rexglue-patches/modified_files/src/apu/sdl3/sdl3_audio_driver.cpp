#if defined(__ANDROID__)
// REX_POP_AUDIO_TOP_INCLUDES
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#endif
#include "apu/sdl3/sdl3_audio_driver.h"

#include <algorithm>
#include <array>
#include <format>

#include <SDL3/SDL.h>

#include <rex/apu/audio_driver.h>
#include <rex/logging/log_category.h>
#include <rex/platform/perf.h>
#include <rex/driver.h>
#include <rex/driver_context.h>
#include <rex/driver_registration.h>

namespace rex::apu::sdl3 {

namespace {

std::unique_ptr<IDriver> CreateDriver() {
  return std::make_unique<SDL3AudioDriver>();
}

void SDLCALL OnPull(void* userdata, SDL_AudioStream* stream, int additional_amount, int) {
  static_cast<SDL3AudioDriver*>(userdata)->Pull(stream, additional_amount);
}

constexpr std::array<DriverRegistration, 1> kDrivers = {
    DriverRegistration{DriverType::kAudio, "sdl3", {}, &CreateDriver}};

}  // namespace

std::string_view SDL3AudioDriver::GetName() const {
  return "sdl3";
}

Result<void> SDL3AudioDriver::Initialize(IDriverContext& context) {
  context_ = &context;
  Result<void> result = Ok();
  const Result<void> ran = context.RunOnUIThread([&result] {
    SDL_SetHint(SDL_HINT_AUDIO_CATEGORY, "playback");
    if (!SDL_InitSubSystem(SDL_INIT_AUDIO)) {
      result = Err(ErrorCategory::kPlatform,
                   std::format("SDL_InitSubSystem(SDL_INIT_AUDIO) failed: {}", SDL_GetError()));
    }
  });
  if (!ran) {
    return ran;
  }
  return result;
}

void SDL3AudioDriver::Shutdown() {
  CloseDevice();
  if (context_) {
    if (const auto quit = context_->RunOnUIThread([] { SDL_QuitSubSystem(SDL_INIT_AUDIO); });
        !quit) {
      REXAPU_WARN("SDL audio was not stopped, {}", quit.error().message);
    }
    context_ = nullptr;
  }
}

Result<u32> SDL3AudioDriver::OpenDevice(u32 channels, AudioFill fill) {
#if defined(__ANDROID__)
  // Force stereo downmix on Android so 5.1 center-channel dialogue is not dropped
  channels = 2;
#endif
  CloseDevice();
  fill_ = std::move(fill);
  SDL_AudioSpec spec = {};
  spec.freq = static_cast<int>(kAudioSampleRate);
  spec.format = SDL_AUDIO_F32;
  spec.channels = static_cast<int>(channels);
  channels_ = channels;
  stream_ = SDL_OpenAudioDeviceStream(SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK, &spec, &OnPull, this);
  if (!stream_) {
    channels_ = 0;
    return Err<u32>(ErrorCategory::kPlatform,
                    std::format("SDL_OpenAudioDeviceStream() failed: {}", SDL_GetError()));
  }
  SDL_AudioSpec device = {};
  if (SDL_GetAudioDeviceFormat(SDL_GetAudioStreamDevice(stream_), &device, nullptr) &&
      device.channels <= 2 && channels > 2) {
    SDL_DestroyAudioStream(stream_);
    spec.channels = 2;
    channels_ = 2;
    stream_ = SDL_OpenAudioDeviceStream(SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK, &spec, &OnPull, this);
    if (!stream_) {
      channels_ = 0;
      return Err<u32>(ErrorCategory::kPlatform,
                      std::format("SDL_OpenAudioDeviceStream() stereo failed: {}", SDL_GetError()));
    }
  }
  if (!SDL_ResumeAudioStreamDevice(stream_)) {
    const std::string why = SDL_GetError();
    CloseDevice();
    return Err<u32>(ErrorCategory::kPlatform,
                    std::format("SDL_ResumeAudioStreamDevice() failed: {}", why));
  }
  return channels_;
}

void SDL3AudioDriver::CloseDevice() {
  if (stream_) {
    SDL_DestroyAudioStream(stream_);
    stream_ = nullptr;
  }
  channels_ = 0;
  fill_ = nullptr;
}

bool SDL3AudioDriver::IsDeviceLost() const {
  return stream_ && SDL_GetAudioStreamDevice(stream_) == 0;
}

#if defined(__ANDROID__)
// REX_POP_AUDIO_BOOT_MUTE

namespace rex_pop_audio {
inline bool IsBootMuted() {
  static bool g_unmuted = false;
  if (g_unmuted) return false;
  using PfnReady = int32_t (*)();
  static PfnReady g_fn = nullptr;
  static bool g_looked_up = false;
  if (!g_looked_up) {
    void* h = ::dlopen("libtouchpad.so", RTLD_NOLOAD);
    if (h) g_fn = reinterpret_cast<PfnReady>(::dlsym(h, "rex_touchpad_is_boot_ready"));
    if (!g_fn) g_fn = reinterpret_cast<PfnReady>(::dlsym(RTLD_DEFAULT, "rex_touchpad_is_boot_ready"));
    if (g_fn) g_looked_up = true;
  }
  if (!g_fn) return false;
  if (g_fn() != 0) {
    g_unmuted = true;
    return false;
  }
  return true;
}
}  // namespace rex_pop_audio
#endif
void SDL3AudioDriver::Pull(SDL_AudioStream* stream, int additional_amount) {
  const u32 samples = static_cast<u32>(std::max(additional_amount, 0)) / sizeof(f32);
  const u32 count = samples - samples % channels_;
  if (count == 0 || !fill_) {
    return;
  }
  scratch_.resize(count);
  fill_(std::span<f32>(scratch_.data(), count), channels_);
#if defined(__ANDROID__)
  if (rex_pop_audio::IsBootMuted()) {
    std::memset(scratch_.data(), 0, count * sizeof(f32));
  }
#endif
  SDL_PutAudioStreamData(stream, scratch_.data(), static_cast<int>(count * sizeof(f32)));
}

std::string_view SDL3AudioProvider::GetName() const {
  return "rexapu-sdl3";
}

Result<void> SDL3AudioProvider::Connect(IInterfaceFactory&) {
  rex::memory::SetTagName(rex::apu::kAPU, "APU");
  for (const char* name : {"audio_frame_latency_us", "buffer_queue_depth"}) {
    if (auto counter = rex::platform::RegisterCounter(name); !counter) {
      REXAPU_WARN("{}", counter.error().message);
    }
  }
  return Ok();
}

void SDL3AudioProvider::Disconnect() {}

Result<void> SDL3AudioProvider::Init() {
  return Ok();
}

void SDL3AudioProvider::Shutdown() {}

std::span<const DriverRegistration> SDL3AudioProvider::GetDrivers() const {
  return kDrivers;
}

}  // namespace rex::apu::sdl3

REX_DECLARE_MODULE(rex::apu::sdl3::SDL3AudioProvider)

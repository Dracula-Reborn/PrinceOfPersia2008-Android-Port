#if defined(__APPLE__) && !defined(_XOPEN_SOURCE)
// Darwin exposes ucontext only with a feature level set before <ucontext.h>.
#define _XOPEN_SOURCE 700
#endif

#include <rex/platform.h>
#if REX_PLATFORM_LINUX || REX_PLATFORM_MAC

#include <rex/platform/thread.h>

#if defined(__APPLE__)
#pragma clang diagnostic ignored "-Wdeprecated-declarations"  // Darwin deprecates the ucontext API
#endif

#include <cassert>
#include <cstdint>
#include <cstring>
#include <memory>

#if defined(__ANDROID__) && defined(__aarch64__)
extern "C" void rex_arm64_fiber_swap(void* from, const void* to);

__asm__(
    ".text\n"
    ".p2align 4\n"
    ".globl rex_arm64_fiber_swap\n"
    ".hidden rex_arm64_fiber_swap\n"
    ".type rex_arm64_fiber_swap, @function\n"
    "rex_arm64_fiber_swap:\n"
    "  stp x19, x20, [x0, #0]\n"
    "  stp x21, x22, [x0, #16]\n"
    "  stp x23, x24, [x0, #32]\n"
    "  stp x25, x26, [x0, #48]\n"
    "  stp x27, x28, [x0, #64]\n"
    "  stp x29, x30, [x0, #80]\n"
    "  mov x9, sp\n"
    "  str x9, [x0, #96]\n"
    "  stp d8, d9, [x0, #112]\n"
    "  stp d10, d11, [x0, #128]\n"
    "  stp d12, d13, [x0, #144]\n"
    "  stp d14, d15, [x0, #160]\n"
    "  ldp x19, x20, [x1, #0]\n"
    "  ldp x21, x22, [x1, #16]\n"
    "  ldp x23, x24, [x1, #32]\n"
    "  ldp x25, x26, [x1, #48]\n"
    "  ldp x27, x28, [x1, #64]\n"
    "  ldp x29, x30, [x1, #80]\n"
    "  ldr x9, [x1, #96]\n"
    "  mov sp, x9\n"
    "  ldp d8, d9, [x1, #112]\n"
    "  ldp d10, d11, [x1, #128]\n"
    "  ldp d12, d13, [x1, #144]\n"
    "  ldp d14, d15, [x1, #160]\n"
    "  ret\n"
    ".size rex_arm64_fiber_swap, .-rex_arm64_fiber_swap\n");
#endif

namespace rex::platform {

namespace {
thread_local Fiber* g_current_fiber = nullptr;

#if defined(__ANDROID__) && defined(__aarch64__)
struct Arm64FiberCtx {
  uint64_t x19, x20;
  uint64_t x21, x22;
  uint64_t x23, x24;
  uint64_t x25, x26;
  uint64_t x27, x28;
  uint64_t x29, x30;
  uint64_t sp;
  uint64_t pad;
  uint64_t d8, d9;
  uint64_t d10, d11;
  uint64_t d12, d13;
  uint64_t d14, d15;
};
static_assert(sizeof(Arm64FiberCtx) <= sizeof(ucontext_t));
#endif
}  // namespace

Fiber* Fiber::GetCurrent() {
  return g_current_fiber;
}

Fiber* Fiber::ConvertCurrentThread() {
  auto f = std::make_unique<Fiber>();
#if defined(__ANDROID__) && defined(__aarch64__)
  std::memset(&f->context_, 0, sizeof(f->context_));
#else
  if (getcontext(&f->context_) == -1) {
    return nullptr;
  }
#endif
  f->is_thread_fiber_ = true;
  g_current_fiber = f.get();
  return f.release();
}

Fiber* Fiber::Create(size_t stack_size, void (*entry)(void*), void* arg) {
  auto f = std::make_unique<Fiber>();
  f->entry_ = entry;
  f->arg_ = arg;
  f->stack_.resize(stack_size);

#if defined(__ANDROID__) && defined(__aarch64__)
  auto* ctx = reinterpret_cast<Arm64FiberCtx*>(&f->context_);
  std::memset(ctx, 0, sizeof(Arm64FiberCtx));
  uintptr_t stack_top = reinterpret_cast<uintptr_t>(f->stack_.data() + f->stack_.size());
  stack_top &= ~uintptr_t{15};
  ctx->sp = static_cast<uint64_t>(stack_top);
  ctx->x30 = reinterpret_cast<uint64_t>(&Fiber::Trampoline);
#else
  if (getcontext(&f->context_) == -1) {
    return nullptr;
  }
  f->context_.uc_stack.ss_sp = f->stack_.data();
  f->context_.uc_stack.ss_size = f->stack_.size();
  f->context_.uc_link = nullptr;
  makecontext(&f->context_, &Fiber::Trampoline, 0);
#endif
  return f.release();
}

/*static*/ void Fiber::Trampoline() {
  Fiber* f = g_current_fiber;
  f->entry_(f->arg_);
}

void Fiber::SwitchTo(Fiber* target) {
  Fiber* from = g_current_fiber;
  g_current_fiber = target;
#if defined(__ANDROID__) && defined(__aarch64__)
  rex_arm64_fiber_swap(&from->context_, &target->context_);
#else
  swapcontext(&from->context_, &target->context_);
#endif
}

void Fiber::Destroy() {
  if (is_thread_fiber_) {
    g_current_fiber = nullptr;
  } else {
    assert(this != g_current_fiber && "Destroy called on the currently running fiber");
  }
  delete this;
}

}  // namespace rex::platform

#endif  // REX_PLATFORM_LINUX || REX_PLATFORM_MAC
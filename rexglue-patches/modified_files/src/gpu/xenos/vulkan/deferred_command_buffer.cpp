/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2020 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 *
 * @modified    Tom Clay, 2026 - Adapted for ReXGlue runtime
 */

#include <cstddef>
#include <cstdint>
#include <cstring>

#include <rex/assert.h>
#include <rex/platform/perf.h>
#include "gpu/xenos/vulkan/command_processor.h"
#include "gpu/xenos/vulkan/deferred_command_buffer.h"
#include <rex/math.h>

#if defined(__ANDROID__)
#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <mutex>

std::atomic<uint64_t> g_rex_gpu_total_ns{0};
std::atomic<uint64_t> g_rex_gpu_rp_ns{0};
std::atomic<uint64_t> g_rex_gpu_comp_ns{0};
std::atomic<uint64_t> g_rex_gpu_copy_ns{0};
std::atomic<uint64_t> g_rex_gpu_submits{0};
std::atomic<uint64_t> g_rex_vk_draws{0};
std::atomic<uint64_t> g_rex_vk_dispatches{0};

namespace {
constexpr uint64_t kRexBakedSkipPsHash = 0x09F78A325236804AULL;
constexpr size_t kRexMaxSkipPipes = 128;
std::mutex g_rex_skip_pipes_mutex;
std::atomic<uint64_t> g_rex_skip_pipes[kRexMaxSkipPipes];
std::atomic<size_t> g_rex_skip_pipe_count{0};
// 0 = skip all matching draws, 1 = filter OFF, 2 = keep 1 of 2, 3 = keep 1 of 3
std::atomic<uint32_t> g_rex_ps_mode{0};

// REX_GLOBAL_SMOKE_SKIP
constexpr size_t kRexSmokeTableMask = 2047u;
std::atomic<uint64_t> g_rex_smoke_keys[kRexSmokeTableMask + 1u];
std::atomic<uint8_t> g_rex_smoke_kind[kRexSmokeTableMask + 1u];

inline uint32_t RexGetSmokeMode() {
  static uint32_t s_mode = 99u;
  if (s_mode == 99u) {
    s_mode = 1u;  // 1 = skip all 3D alpha/subtractive smoke, dust, mist & corruption haze
    std::FILE* fp = std::fopen("/sdcard/Android/data/com.princeofpersia.files/files/smoke_mode.txt", "r");
    if (fp) {
      const int ch = std::fgetc(fp);
      std::fclose(fp);
      if (ch == '0') s_mode = 0u;
      else if (ch == '1') s_mode = 1u;
      else if (ch == '2') s_mode = 2u;
    }
  }
  return s_mode;
}

inline uint8_t RexLookupSmokeKind(uint64_t pipe_handle) {
  if (pipe_handle == 0u) return 0u;
  size_t slot = static_cast<size_t>((pipe_handle * 11400714819323198485ULL) >> 53) & kRexSmokeTableMask;
  for (size_t probe = 0; probe < 32u; ++probe) {
    const uint64_t k = g_rex_smoke_keys[slot].load(std::memory_order_acquire);
    if (k == 0u) return 0u;
    if (k == pipe_handle) return g_rex_smoke_kind[slot].load(std::memory_order_relaxed);
    slot = (slot + 1u) & kRexSmokeTableMask;
  }
  return 0u;
}

inline bool RexIsSkipPipeline(uint64_t pipe_handle) {
  const size_t n = g_rex_skip_pipe_count.load(std::memory_order_acquire);
  for (size_t i = 0; i < n; ++i) {
    if (g_rex_skip_pipes[i].load(std::memory_order_relaxed) == pipe_handle) {
      return true;
    }
  }
  const uint32_t smode = RexGetSmokeMode();
  if (smode != 0u) {
    const uint8_t kind = RexLookupSmokeKind(pipe_handle);
    if (kind == 1u || (smode >= 2u && kind == 2u)) {
      return true;
    }
  }
  return false;
}

uint32_t RexRefreshPsMode() {
  return g_rex_ps_mode.load(std::memory_order_relaxed);
}
}  // namespace

namespace rex::gpu::vulkan {
void RexRegisterSmokePipeline(uint64_t pipe_handle, const VkGraphicsPipelineCreateInfo* ci) {
  if (pipe_handle == 0u || !ci || !ci->pDepthStencilState || !ci->pColorBlendState ||
      !ci->pMultisampleState || ci->pColorBlendState->attachmentCount == 0u ||
      !ci->pColorBlendState->pAttachments) {
    return;
  }
  // Protect all 2D menus, HUD & fonts: only filter 3D 2x MSAA passes with active depth comparison
  if (ci->pMultisampleState->rasterizationSamples != VK_SAMPLE_COUNT_2_BIT) {
    return;
  }
  const bool depth_test = (ci->pDepthStencilState->depthTestEnable == VK_TRUE);
  const bool depth_write = (ci->pDepthStencilState->depthWriteEnable == VK_TRUE);
  const VkCompareOp depth_cmp = ci->pDepthStencilState->depthCompareOp;
  if (!depth_test || depth_write ||
      depth_cmp == VK_COMPARE_OP_ALWAYS || depth_cmp == VK_COMPARE_OP_NEVER) {
    return;
  }
  const VkPipelineColorBlendAttachmentState& att0 = ci->pColorBlendState->pAttachments[0];
  if (att0.blendEnable != VK_TRUE) {
    return;
  }
  // Kind 1: alpha/premultiplied/subtractive smoke, dust, mist, fog & corruption clouds
  // Kind 2: additive 3D VFX (glows, sparks, light magic)
  const bool is_alpha_smoke =
      (att0.dstColorBlendFactor == VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA ||
       att0.dstColorBlendFactor == VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR ||
       att0.dstColorBlendFactor == VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA ||
       att0.colorBlendOp == VK_BLEND_OP_REVERSE_SUBTRACT ||
       att0.colorBlendOp == VK_BLEND_OP_SUBTRACT);
  const uint8_t kind = is_alpha_smoke ? 1u : 2u;

  std::lock_guard<std::mutex> lk(g_rex_skip_pipes_mutex);
  size_t slot = static_cast<size_t>((pipe_handle * 11400714819323198485ULL) >> 53) & kRexSmokeTableMask;
  for (size_t probe = 0; probe < 32u; ++probe) {
    const uint64_t k = g_rex_smoke_keys[slot].load(std::memory_order_relaxed);
    if (k == 0u || k == pipe_handle) {
      g_rex_smoke_kind[slot].store(kind, std::memory_order_relaxed);
      g_rex_smoke_keys[slot].store(pipe_handle, std::memory_order_release);
      break;
    }
    slot = (slot + 1u) & kRexSmokeTableMask;
  }
}

void RexRegisterPipelineMeta(uint64_t pipe_handle, uint64_t /*vs_h*/, uint64_t ps_h,
                             uint32_t /*rp_k*/, uint32_t msaa_s, uint32_t b_mask,
                             uint8_t /*d_test*/, uint8_t d_write) {
  if (ps_h != kRexBakedSkipPsHash || pipe_handle == 0 ||
      msaa_s != 2 || b_mask == 0 || d_write != 0) {
    return;
  }
  std::lock_guard<std::mutex> lk(g_rex_skip_pipes_mutex);
  const size_t n = g_rex_skip_pipe_count.load(std::memory_order_relaxed);
  for (size_t i = 0; i < n; ++i) {
    if (g_rex_skip_pipes[i].load(std::memory_order_relaxed) == pipe_handle) {
      return;
    }
  }
  if (n < kRexMaxSkipPipes) {
    g_rex_skip_pipes[n].store(pipe_handle, std::memory_order_relaxed);
    g_rex_skip_pipe_count.store(n + 1, std::memory_order_release);
  }
}
}  // namespace rex::gpu::vulkan
#endif

namespace rex::gpu::vulkan {

DeferredCommandBuffer::DeferredCommandBuffer(const VulkanCommandProcessor& command_processor,
                                             size_t initial_size)
    : command_processor_(command_processor) {
  command_stream_.Reserve(initial_size);
}

void DeferredCommandBuffer::Reset() {
  hoisted_slots_.clear();
  hoisted_nodes_.clear();
  hoisted_regions_.clear();
  active_hoist_slot_ = UINT32_MAX;
  command_stream_.Clear();
}

void DeferredCommandBuffer::Execute(VkCommandBuffer command_buffer) {
#if defined(__ANDROID__)
  bool rex_skip_cur_pipe = false;
  const uint32_t rex_ps_mode = RexRefreshPsMode();
  uint32_t rex_rp_skip_idx = 0;
#endif

#if REXGPU_FINE_GRAINED_DRAW_SCOPES
  REX_PROFILE_SCOPE("gpu");
#endif  // REXGPU_FINE_GRAINED_DRAW_SCOPES

  const gpu::vulkan::VulkanDevice::Functions& dfn =
      command_processor_.GetVulkanDevice()->GetFunctions();
  const uintmax_t* stream = command_stream_.Data();
  size_t stream_remaining = command_stream_.Size();
  while (stream_remaining) {
    const CommandHeader& header = *reinterpret_cast<const CommandHeader*>(stream);
    stream += kCommandHeaderSizeElements;
    stream_remaining -= kCommandHeaderSizeElements;

    switch (header.command) {
      case Command::kVkBeginRenderPass: {
        auto& args = *reinterpret_cast<const ArgsVkBeginRenderPass*>(stream);
        size_t offset_bytes = sizeof(ArgsVkBeginRenderPass);
        VkRenderPassBeginInfo render_pass_begin_info;
        render_pass_begin_info.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
        render_pass_begin_info.pNext = nullptr;
        render_pass_begin_info.renderPass = args.render_pass;
        render_pass_begin_info.framebuffer = args.framebuffer;
        render_pass_begin_info.renderArea = args.render_area;
        render_pass_begin_info.clearValueCount = args.clear_value_count;
        if (render_pass_begin_info.clearValueCount) {
          offset_bytes = rex::RoundUpPow2(offset_bytes, alignof(VkClearValue));
          render_pass_begin_info.pClearValues = reinterpret_cast<const VkClearValue*>(
              reinterpret_cast<const u8*>(stream) + offset_bytes);
          offset_bytes += sizeof(VkClearValue) * render_pass_begin_info.clearValueCount;
        } else {
          render_pass_begin_info.pClearValues = nullptr;
        }
                if (args.hoist_slot < hoisted_slots_.size()) {
          u32 node_idx = hoisted_slots_[args.hoist_slot].head;
          if (node_idx != UINT32_MAX) {
            VkMemoryBarrier pre_barrier = {};
            pre_barrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
            pre_barrier.srcAccessMask =
                VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT | VK_ACCESS_INDEX_READ_BIT |
                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT |
                VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
            pre_barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            dfn.vkCmdPipelineBarrier(
                command_buffer,
                VK_PIPELINE_STAGE_VERTEX_INPUT_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT |
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT |
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &pre_barrier, 0, nullptr, 0, nullptr);
            while (node_idx != UINT32_MAX) {
              const HoistedCopyNode& node = hoisted_nodes_[node_idx];
              dfn.vkCmdCopyBuffer(command_buffer, node.src_buffer, node.dst_buffer,
                                  node.region_count, hoisted_regions_.data() + node.first_region);
              node_idx = node.next;
            }
            VkMemoryBarrier post_barrier = {};
            post_barrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
            post_barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            post_barrier.dstAccessMask =
                VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_INDEX_READ_BIT |
                VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT | VK_ACCESS_SHADER_READ_BIT |
                VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT |
                VK_ACCESS_TRANSFER_WRITE_BIT;
            dfn.vkCmdPipelineBarrier(
                command_buffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_VERTEX_INPUT_BIT |
                    VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT |
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                0, 1, &post_barrier, 0, nullptr, 0, nullptr);
          }
        }
#if defined(__ANDROID__)
        rex_rp_skip_idx = 0;
#endif
        dfn.vkCmdBeginRenderPass(command_buffer, &render_pass_begin_info, args.contents);
      } break;

      case Command::kVkBeginQuery: {
        auto& args = *reinterpret_cast<const ArgsVkBeginQuery*>(stream);
        dfn.vkCmdBeginQuery(command_buffer, args.query_pool, args.query, args.flags);
      } break;

      case Command::kVkBindDescriptorSets: {
        auto& args = *reinterpret_cast<const ArgsVkBindDescriptorSets*>(stream);
        size_t offset_bytes =
            rex::RoundUpPow2(sizeof(ArgsVkBindDescriptorSets), alignof(VkDescriptorSet));
        const VkDescriptorSet* descriptor_sets = reinterpret_cast<const VkDescriptorSet*>(
            reinterpret_cast<const u8*>(stream) + offset_bytes);
        offset_bytes += sizeof(VkDescriptorSet) * args.descriptor_set_count;
        const u32* dynamic_offsets = nullptr;
        if (args.dynamic_offset_count) {
          offset_bytes = rex::RoundUpPow2(offset_bytes, alignof(u32));
          dynamic_offsets =
              reinterpret_cast<const u32*>(reinterpret_cast<const u8*>(stream) + offset_bytes);
          offset_bytes += sizeof(u32) * args.dynamic_offset_count;
        }
        dfn.vkCmdBindDescriptorSets(command_buffer, args.pipeline_bind_point, args.layout,
                                    args.first_set, args.descriptor_set_count, descriptor_sets,
                                    args.dynamic_offset_count, dynamic_offsets);
      } break;

      case Command::kVkBindIndexBuffer: {
        auto& args = *reinterpret_cast<const ArgsVkBindIndexBuffer*>(stream);
        dfn.vkCmdBindIndexBuffer(command_buffer, args.buffer, args.offset, args.index_type);
      } break;

      case Command::kVkBindPipeline: {
        auto& args = *reinterpret_cast<const ArgsVkBindPipeline*>(stream);
        #if defined(__ANDROID__)
      if (args.pipeline_bind_point == VK_PIPELINE_BIND_POINT_GRAPHICS) {
        rex_skip_cur_pipe = RexIsSkipPipeline(reinterpret_cast<uint64_t>(args.pipeline));
      }
#endif
      dfn.vkCmdBindPipeline(command_buffer, args.pipeline_bind_point, args.pipeline);
      } break;

      case Command::kVkBindVertexBuffers: {
        auto& args = *reinterpret_cast<const ArgsVkBindVertexBuffers*>(stream);
        size_t offset_bytes = rex::RoundUpPow2(sizeof(ArgsVkBindVertexBuffers), alignof(VkBuffer));
        const VkBuffer* buffers =
            reinterpret_cast<const VkBuffer*>(reinterpret_cast<const u8*>(stream) + offset_bytes);
        offset_bytes = rex::RoundUpPow2(offset_bytes + sizeof(VkBuffer) * args.binding_count,
                                        alignof(VkDeviceSize));
        const VkDeviceSize* offsets = reinterpret_cast<const VkDeviceSize*>(
            reinterpret_cast<const u8*>(stream) + offset_bytes);
        dfn.vkCmdBindVertexBuffers(command_buffer, args.first_binding, args.binding_count, buffers,
                                   offsets);
      } break;

      case Command::kVkClearAttachments: {
        auto& args = *reinterpret_cast<const ArgsVkClearAttachments*>(stream);
        size_t offset_bytes =
            rex::RoundUpPow2(sizeof(ArgsVkClearAttachments), alignof(VkClearAttachment));
        const VkClearAttachment* attachments = reinterpret_cast<const VkClearAttachment*>(
            reinterpret_cast<const u8*>(stream) + offset_bytes);
        offset_bytes = rex::RoundUpPow2(
            offset_bytes + sizeof(VkClearAttachment) * args.attachment_count, alignof(VkClearRect));
        const VkClearRect* rects = reinterpret_cast<const VkClearRect*>(
            reinterpret_cast<const u8*>(stream) + offset_bytes);
        dfn.vkCmdClearAttachments(command_buffer, args.attachment_count, attachments,
                                  args.rect_count, rects);
      } break;

      case Command::kVkClearColorImage: {
        auto& args = *reinterpret_cast<const ArgsVkClearColorImage*>(stream);
        dfn.vkCmdClearColorImage(
            command_buffer, args.image, args.image_layout, &args.color, args.range_count,
            reinterpret_cast<const VkImageSubresourceRange*>(
                reinterpret_cast<const u8*>(stream) +
                rex::RoundUpPow2(sizeof(ArgsVkClearColorImage), alignof(VkImageSubresourceRange))));
      } break;

      case Command::kVkCopyBuffer: {
        auto& args = *reinterpret_cast<const ArgsVkCopyBuffer*>(stream);
        dfn.vkCmdCopyBuffer(command_buffer, args.src_buffer, args.dst_buffer, args.region_count,
                            reinterpret_cast<const VkBufferCopy*>(
                                reinterpret_cast<const u8*>(stream) +
                                rex::RoundUpPow2(sizeof(ArgsVkCopyBuffer), alignof(VkBufferCopy))));
      } break;

      case Command::kVkCopyBufferToImage: {
        auto& args = *reinterpret_cast<const ArgsVkCopyBufferToImage*>(stream);
        dfn.vkCmdCopyBufferToImage(
            command_buffer, args.src_buffer, args.dst_image, args.dst_image_layout,
            args.region_count,
            reinterpret_cast<const VkBufferImageCopy*>(
                reinterpret_cast<const u8*>(stream) +
                rex::RoundUpPow2(sizeof(ArgsVkCopyBufferToImage), alignof(VkBufferImageCopy))));
      } break;

      case Command::kVkCopyQueryPoolResults: {
        auto& args = *reinterpret_cast<const ArgsVkCopyQueryPoolResults*>(stream);
        dfn.vkCmdCopyQueryPoolResults(command_buffer, args.query_pool, args.first_query,
                                      args.query_count, args.dst_buffer, args.dst_offset,
                                      args.stride, args.flags);
      } break;

      case Command::kVkDispatch: {
        auto& args = *reinterpret_cast<const ArgsVkDispatch*>(stream);
        dfn.vkCmdDispatch(command_buffer, args.group_count_x, args.group_count_y,
                          args.group_count_z);
      } break;

      case Command::kVkDraw: {
        auto& args = *reinterpret_cast<const ArgsVkDraw*>(stream);
        #if defined(__ANDROID__)
      {
        bool rex_do_skip = false;
        if (rex_skip_cur_pipe && rex_ps_mode != 1u) {
          const uint32_t idx = rex_rp_skip_idx++;
          rex_do_skip = (rex_ps_mode == 0u) ? true : ((idx % rex_ps_mode) != 0u);
        }
        if (!rex_do_skip) {
          g_rex_vk_draws.fetch_add(1, std::memory_order_relaxed);
          dfn.vkCmdDraw(command_buffer, args.vertex_count, args.instance_count, args.first_vertex,
                      args.first_instance);
        }
      }
#else
      dfn.vkCmdDraw(command_buffer, args.vertex_count, args.instance_count, args.first_vertex,
                      args.first_instance);
#endif
      } break;

      case Command::kVkDrawIndexed: {
        auto& args = *reinterpret_cast<const ArgsVkDrawIndexed*>(stream);
        #if defined(__ANDROID__)
      {
        bool rex_do_skip = false;
        if (rex_skip_cur_pipe && rex_ps_mode != 1u) {
          const uint32_t idx = rex_rp_skip_idx++;
          rex_do_skip = (rex_ps_mode == 0u) ? true : ((idx % rex_ps_mode) != 0u);
        }
        if (!rex_do_skip) {
          g_rex_vk_draws.fetch_add(1, std::memory_order_relaxed);
          dfn.vkCmdDrawIndexed(command_buffer, args.index_count, args.instance_count,
                             args.first_index, args.vertex_offset, args.first_instance);
        }
      }
#else
      dfn.vkCmdDrawIndexed(command_buffer, args.index_count, args.instance_count,
                             args.first_index, args.vertex_offset, args.first_instance);
#endif
      } break;

      case Command::kVkEndQuery: {
        auto& args = *reinterpret_cast<const ArgsVkEndQuery*>(stream);
        dfn.vkCmdEndQuery(command_buffer, args.query_pool, args.query);
      } break;

      case Command::kVkEndRenderPass:
        dfn.vkCmdEndRenderPass(command_buffer);
        break;

      case Command::kVkBeginRendering: {
        auto& args = *reinterpret_cast<const ArgsVkBeginRendering*>(stream);
        size_t offset_bytes =
            rex::RoundUpPow2(sizeof(ArgsVkBeginRendering), alignof(VkRenderingAttachmentInfo));
        const VkRenderingAttachmentInfo* color_attachments =
            args.color_attachment_count ? reinterpret_cast<const VkRenderingAttachmentInfo*>(
                                              reinterpret_cast<const u8*>(stream) + offset_bytes)
                                        : nullptr;
        offset_bytes += sizeof(VkRenderingAttachmentInfo) * args.color_attachment_count;
        const VkRenderingAttachmentInfo* depth_attachment =
            args.has_depth_attachment ? reinterpret_cast<const VkRenderingAttachmentInfo*>(
                                            reinterpret_cast<const u8*>(stream) + offset_bytes)
                                      : nullptr;
        if (args.has_depth_attachment) {
          offset_bytes += sizeof(VkRenderingAttachmentInfo);
        }
        const VkRenderingAttachmentInfo* stencil_attachment =
            args.has_stencil_attachment ? reinterpret_cast<const VkRenderingAttachmentInfo*>(
                                              reinterpret_cast<const u8*>(stream) + offset_bytes)
                                        : nullptr;
        VkRenderingInfo rendering_info = {};
        rendering_info.sType = VK_STRUCTURE_TYPE_RENDERING_INFO;
        rendering_info.flags = args.flags;
        rendering_info.renderArea = args.render_area;
        rendering_info.layerCount = args.layer_count;
        rendering_info.viewMask = args.view_mask;
        rendering_info.colorAttachmentCount = args.color_attachment_count;
        rendering_info.pColorAttachments = color_attachments;
        rendering_info.pDepthAttachment = depth_attachment;
        rendering_info.pStencilAttachment = stencil_attachment;
        dfn.vkCmdBeginRendering(command_buffer, &rendering_info);
      } break;

      case Command::kVkEndRendering:
        dfn.vkCmdEndRendering(command_buffer);
        break;

      case Command::kVkPipelineBarrier: {
        auto& args = *reinterpret_cast<const ArgsVkPipelineBarrier*>(stream);
        size_t barrier_offset_bytes = sizeof(ArgsVkPipelineBarrier);
        const VkMemoryBarrier* memory_barriers = nullptr;
        if (args.memory_barrier_count) {
          barrier_offset_bytes = rex::RoundUpPow2(barrier_offset_bytes, alignof(VkMemoryBarrier));
          memory_barriers = reinterpret_cast<const VkMemoryBarrier*>(
              reinterpret_cast<const u8*>(stream) + barrier_offset_bytes);
          barrier_offset_bytes += sizeof(VkMemoryBarrier) * args.memory_barrier_count;
        }
        const VkBufferMemoryBarrier* buffer_memory_barriers = nullptr;
        if (args.buffer_memory_barrier_count) {
          barrier_offset_bytes =
              rex::RoundUpPow2(barrier_offset_bytes, alignof(VkBufferMemoryBarrier));
          buffer_memory_barriers = reinterpret_cast<const VkBufferMemoryBarrier*>(
              reinterpret_cast<const u8*>(stream) + barrier_offset_bytes);
          barrier_offset_bytes += sizeof(VkBufferMemoryBarrier) * args.buffer_memory_barrier_count;
        }
        const VkImageMemoryBarrier* image_memory_barriers = nullptr;
        if (args.image_memory_barrier_count) {
          barrier_offset_bytes =
              rex::RoundUpPow2(barrier_offset_bytes, alignof(VkImageMemoryBarrier));
          image_memory_barriers = reinterpret_cast<const VkImageMemoryBarrier*>(
              reinterpret_cast<const u8*>(stream) + barrier_offset_bytes);
          barrier_offset_bytes += sizeof(VkImageMemoryBarrier) * args.image_memory_barrier_count;
        }
        dfn.vkCmdPipelineBarrier(command_buffer, args.src_stage_mask, args.dst_stage_mask,
                                 args.dependency_flags, args.memory_barrier_count, memory_barriers,
                                 args.buffer_memory_barrier_count, buffer_memory_barriers,
                                 args.image_memory_barrier_count, image_memory_barriers);
      } break;

      case Command::kVkPushConstants: {
        auto& args = *reinterpret_cast<const ArgsVkPushConstants*>(stream);
        dfn.vkCmdPushConstants(command_buffer, args.layout, args.stage_flags, args.offset,
                               args.size,
                               reinterpret_cast<const u8*>(stream) + sizeof(ArgsVkPushConstants));
      } break;

      case Command::kVkResetQueryPool: {
        auto& args = *reinterpret_cast<const ArgsVkResetQueryPool*>(stream);
        dfn.vkCmdResetQueryPool(command_buffer, args.query_pool, args.first_query,
                                args.query_count);
      } break;

      case Command::kVkSetBlendConstants: {
        auto& args = *reinterpret_cast<const ArgsVkSetBlendConstants*>(stream);
        dfn.vkCmdSetBlendConstants(command_buffer, args.blend_constants);
      } break;

      case Command::kVkSetDepthBias: {
        auto& args = *reinterpret_cast<const ArgsVkSetDepthBias*>(stream);
        dfn.vkCmdSetDepthBias(command_buffer, args.depth_bias_constant_factor,
                              args.depth_bias_clamp, args.depth_bias_slope_factor);
      } break;

      case Command::kVkSetScissor: {
        auto& args = *reinterpret_cast<const ArgsVkSetScissor*>(stream);
        dfn.vkCmdSetScissor(command_buffer, args.first_scissor, args.scissor_count,
                            reinterpret_cast<const VkRect2D*>(
                                reinterpret_cast<const u8*>(stream) +
                                rex::RoundUpPow2(sizeof(ArgsVkSetScissor), alignof(VkRect2D))));
      } break;

      case Command::kVkSetStencilCompareMask: {
        auto& args = *reinterpret_cast<const ArgsVkSetStencilMaskReference*>(stream);
        dfn.vkCmdSetStencilCompareMask(command_buffer, args.face_mask, args.mask_reference);
      } break;

      case Command::kVkSetStencilReference: {
        auto& args = *reinterpret_cast<const ArgsVkSetStencilMaskReference*>(stream);
        dfn.vkCmdSetStencilReference(command_buffer, args.face_mask, args.mask_reference);
      } break;

      case Command::kVkSetStencilWriteMask: {
        auto& args = *reinterpret_cast<const ArgsVkSetStencilMaskReference*>(stream);
        dfn.vkCmdSetStencilWriteMask(command_buffer, args.face_mask, args.mask_reference);
      } break;

      case Command::kVkSetViewport: {
        auto& args = *reinterpret_cast<const ArgsVkSetViewport*>(stream);
        dfn.vkCmdSetViewport(command_buffer, args.first_viewport, args.viewport_count,
                             reinterpret_cast<const VkViewport*>(
                                 reinterpret_cast<const u8*>(stream) +
                                 rex::RoundUpPow2(sizeof(ArgsVkSetViewport), alignof(VkViewport))));
      } break;

      default:
        REX_UNHANDLED_CASE(header.command);
        break;
    }

    stream += header.arguments_size_elements;
    stream_remaining -= header.arguments_size_elements;
  }
}

void DeferredCommandBuffer::CmdVkPipelineBarrier(
    VkPipelineStageFlags src_stage_mask, VkPipelineStageFlags dst_stage_mask,
    VkDependencyFlags dependency_flags, u32 memory_barrier_count,
    const VkMemoryBarrier* memory_barriers, u32 buffer_memory_barrier_count,
    const VkBufferMemoryBarrier* buffer_memory_barriers, u32 image_memory_barrier_count,
    const VkImageMemoryBarrier* image_memory_barriers) {
  size_t arguments_size = sizeof(ArgsVkPipelineBarrier);
  size_t memory_barriers_offset = 0;
  if (memory_barrier_count) {
    arguments_size = rex::RoundUpPow2(arguments_size, alignof(VkMemoryBarrier));
    memory_barriers_offset = arguments_size;
    arguments_size += sizeof(VkMemoryBarrier) * memory_barrier_count;
  }
  size_t buffer_memory_barriers_offset = 0;
  if (buffer_memory_barrier_count) {
    arguments_size = rex::RoundUpPow2(arguments_size, alignof(VkBufferMemoryBarrier));
    buffer_memory_barriers_offset = arguments_size;
    arguments_size += sizeof(VkBufferMemoryBarrier) * buffer_memory_barrier_count;
  }
  size_t image_memory_barriers_offset = 0;
  if (image_memory_barrier_count) {
    arguments_size = rex::RoundUpPow2(arguments_size, alignof(VkImageMemoryBarrier));
    image_memory_barriers_offset = arguments_size;
    arguments_size += sizeof(VkImageMemoryBarrier) * image_memory_barrier_count;
  }
  u8* args_ptr = reinterpret_cast<u8*>(WriteCommand(Command::kVkPipelineBarrier, arguments_size));
  auto& args = *reinterpret_cast<ArgsVkPipelineBarrier*>(args_ptr);
  args.src_stage_mask = src_stage_mask;
  args.dst_stage_mask = dst_stage_mask;
  args.dependency_flags = dependency_flags;
  args.memory_barrier_count = memory_barrier_count;
  args.buffer_memory_barrier_count = buffer_memory_barrier_count;
  args.image_memory_barrier_count = image_memory_barrier_count;
  if (memory_barrier_count) {
    std::memcpy(args_ptr + memory_barriers_offset, memory_barriers,
                sizeof(VkMemoryBarrier) * memory_barrier_count);
  }
  if (buffer_memory_barrier_count) {
    std::memcpy(args_ptr + buffer_memory_barriers_offset, buffer_memory_barriers,
                sizeof(VkBufferMemoryBarrier) * buffer_memory_barrier_count);
  }
  if (image_memory_barrier_count) {
    std::memcpy(args_ptr + image_memory_barriers_offset, image_memory_barriers,
                sizeof(VkImageMemoryBarrier) * image_memory_barrier_count);
  }
}

void DeferredCommandBuffer::CmdVkBeginRendering(const VkRenderingInfo* rendering_info) {
  REX_ASSERT(rendering_info->pNext == nullptr);

  size_t arguments_size =
      rex::RoundUpPow2(sizeof(ArgsVkBeginRendering), alignof(VkRenderingAttachmentInfo));
  size_t color_attachments_offset = arguments_size;
  arguments_size += sizeof(VkRenderingAttachmentInfo) * rendering_info->colorAttachmentCount;
  size_t depth_attachment_offset = arguments_size;
  if (rendering_info->pDepthAttachment) {
    arguments_size += sizeof(VkRenderingAttachmentInfo);
  }
  size_t stencil_attachment_offset = arguments_size;
  if (rendering_info->pStencilAttachment) {
    arguments_size += sizeof(VkRenderingAttachmentInfo);
  }

  u8* args_ptr = reinterpret_cast<u8*>(WriteCommand(Command::kVkBeginRendering, arguments_size));
  auto& args = *reinterpret_cast<ArgsVkBeginRendering*>(args_ptr);
  args.flags = rendering_info->flags;
  args.render_area = rendering_info->renderArea;
  args.layer_count = rendering_info->layerCount;
  args.view_mask = rendering_info->viewMask;
  args.color_attachment_count = rendering_info->colorAttachmentCount;
  args.has_depth_attachment = rendering_info->pDepthAttachment != nullptr;
  args.has_stencil_attachment = rendering_info->pStencilAttachment != nullptr;

  if (rendering_info->colorAttachmentCount) {
    std::memcpy(args_ptr + color_attachments_offset, rendering_info->pColorAttachments,
                sizeof(VkRenderingAttachmentInfo) * rendering_info->colorAttachmentCount);
  }
  if (rendering_info->pDepthAttachment) {
    std::memcpy(args_ptr + depth_attachment_offset, rendering_info->pDepthAttachment,
                sizeof(VkRenderingAttachmentInfo));
  }
  if (rendering_info->pStencilAttachment) {
    std::memcpy(args_ptr + stencil_attachment_offset, rendering_info->pStencilAttachment,
                sizeof(VkRenderingAttachmentInfo));
  }
}

void* DeferredCommandBuffer::WriteCommand(Command command, size_t arguments_size_bytes) {
  return command_stream_.Write(command, arguments_size_bytes);
}

}  // namespace rex::gpu::vulkan

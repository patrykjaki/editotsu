/* Forced include for the mpv build (libmpv-vulkan recipe).
 * mpv's vulkan/context.c calls these Vulkan 1.1 entry points directly. Android's
 * libvulkan.so exports them only from API 28, and libmpv targets API 26, so a strong
 * reference would make dlopen("libmpv.so") fail on API 26/27 devices even when Vulkan
 * is never used. Weak references keep the lib loadable; the app only selects
 * gpu-api=vulkan on API 28+. */
#pragma once
#include <vulkan/vulkan_core.h>
__attribute__((weak)) VKAPI_ATTR void VKAPI_CALL vkGetPhysicalDeviceProperties2(VkPhysicalDevice, VkPhysicalDeviceProperties2 *);
__attribute__((weak)) VKAPI_ATTR void VKAPI_CALL vkGetPhysicalDeviceQueueFamilyProperties2(VkPhysicalDevice, uint32_t *, VkQueueFamilyProperties2 *);

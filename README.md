# Prince of Persia (2008) — Android ARM64 Port

Android ARM64 port wrapper, custom touch controls, and Vulkan/Adreno performance patches for **Prince of Persia (2008)** built on top of the ReXGlue static recompilation runtime.

## Features
- **Custom Android Launcher & Activity** (ndroid/java/com/rexglue/pop/) with FPS cap, VSync, Anisotropic Filtering, 2x MSAA, GPU driver selector, and built-in game folder importer.
- **Custom Touch Overlay** (TouchOverlayView.java) with medallion controls and enlarged Xbox key badges (A, B, X, Y, LB, RB, LT, RT, BACK, START).
- **Vulkan & Adreno Optimizations** (exglue-patches/) including 2x MSAA handling, Adreno UBWC hints, and rendering filters.
- **Package ID / Data Directory:** com.princeofpersia.files (/sdcard/Android/data/com.princeofpersia.files/files).

## Repository Structure
- ndroid/ — Android Java sources (LauncherActivity.java, PopActivity.java, TouchOverlayView.java), AndroidManifest.xml, and launcher icon resources (es/).
- exglue-patches/ — Unified Git patch (exglue_optimizations.patch) and modified ReXGlue Vulkan C++ files.
- 
ative/ — Project CMake/configuration and native bridge source files (excluding recompiled game code).

## Legal Disclaimer
This repository contains **source code only**. No copyrighted game files, .forge archives, .xex executables, or assets from Ubisoft's *Prince of Persia (2008)* are included. You must legally own the Xbox 360 version of the game and supply your own extracted game files.

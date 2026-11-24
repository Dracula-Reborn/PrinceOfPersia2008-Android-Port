package com.rexglue.pop;

import android.os.Bundle;
import android.system.Os;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import org.libsdl.app.SDLActivity;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class PopActivity extends SDLActivity {
    private static final double[] FPS_CAPS = new double[] { 30.0, 45.0, 60.0, 0.0 };
    private static final int[] AF_VALUES   = new int[] { 0, 2, 3, 5 };

    private static native void nativeSetUnfocused(boolean unfocused);
    private static native void nativeSetSurface(Surface surface);

    private static void setUnfocusedSafe(boolean unfocused) {
        try {
            nativeSetUnfocused(unfocused);
        } catch (Throwable ignored) {}
    }

    private static void setSurfaceSafe(Surface surface) {
        try {
            nativeSetSurface(surface);
        } catch (Throwable ignored) {}
    }

    private void attachSurfaceLifecycleCallback(View view) {
        if (view instanceof SurfaceView) {
            SurfaceHolder holder = ((SurfaceView) view).getHolder();
            Surface current = holder.getSurface();
            if (current != null && current.isValid()) {
                setSurfaceSafe(current);
            }
            holder.addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder h) {
                    setSurfaceSafe(h.getSurface());
                }
                @Override
                public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
                    setSurfaceSafe(h.getSurface());
                }
                @Override
                public void surfaceDestroyed(SurfaceHolder h) {
                    setSurfaceSafe(null);
                }
            });
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                attachSurfaceLifecycleCallback(vg.getChildAt(i));
            }
        }
    }

    private void applyFixed720pSurface(View view) {
        if (view instanceof SurfaceView) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int maxDim = Math.max(dm.widthPixels, dm.heightPixels);
            int minDim = Math.min(dm.widthPixels, dm.heightPixels);
            if (minDim > 720) {
                int targetH = 720;
                int targetW = (Math.round(targetH * (float) maxDim / (float) minDim) + 15) & ~15;
                ((SurfaceView) view).getHolder().setFixedSize(targetW, targetH);
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                applyFixed720pSurface(vg.getChildAt(i));
            }
        }
    }

    private Properties loadCfg() {
        Properties p = new Properties();
        File dir = getExternalFilesDir(null);
        File f = new File(dir != null ? dir : getFilesDir(), LauncherActivity.SETTINGS_FILE);
        if (f.isFile()) {
            try (FileInputStream fis = new FileInputStream(f)) { p.load(fis); }
            catch (Exception ignored) {}
        }
        return p;
    }

    private int cfgInt(Properties p, String k, int def) {
        try { return Integer.parseInt(p.getProperty(k, String.valueOf(def))); }
        catch (Exception e) { return def; }
    }

    private boolean cfgBool(Properties p, String k, boolean def) {
        return Boolean.parseBoolean(p.getProperty(k, String.valueOf(def)));
    }

    @Override
    protected String[] getLibraries() {
        return new String[] { "c++_shared", "SDL3", "touchpad", "rexgpu-xenos-vulkan", "main" };
    }

    @Override
    protected String[] getArguments() {
        Properties cfg = loadCfg();

        int fpsIdx       = Math.max(0, Math.min(FPS_CAPS.length - 1, cfgInt(cfg, "fps_idx", 0)));
        int afIdx        = Math.max(0, Math.min(AF_VALUES.length - 1, cfgInt(cfg, "af_idx", 0)));
        boolean vsync    = cfgBool(cfg, "vsync", true);
        boolean fastBoot = cfgBool(cfg, "fast_boot", true);

        double fpsCap = FPS_CAPS[fpsIdx];
        int af = AF_VALUES[afIdx];
        boolean storeShaders = true;

        List<String> args = new ArrayList<>();
        File dir = getExternalFilesDir(null);
        if (dir != null) {
            dir.mkdirs();
            File userDir  = new File(dir, "user");
            File cacheDir = new File(dir, "cache");
            userDir.mkdirs();
            cacheDir.mkdirs();
            String root = dir.getAbsolutePath();

            File tomlFile = new File(dir, "pop_user.toml");
            String toml =
                "[ui]\n" +
                "video_mode_width = 1280\n" +
                "video_mode_height = 720\n\n" +
                "[gpu]\n" +
                "vsync = " + vsync + "\n" +
                "frame_limit = 0.0\n" +
                "anisotropic_override = " + af + "\n\n" +
                "[gpu.xenos]\n" +
                "native_2x_msaa = true\n" +
                "readback_resolve = \"none\"\n" +
                "readback_memexport = false\n" +
                "occlusion_query_enable = false\n" +
                "depth_transfer_not_equal_test = false\n" +
                "texture_cache_memory_limit_soft = 256\n" +
                "texture_cache_memory_limit_hard = 512\n" +
                "texture_cache_memory_limit_soft_lifetime = 60\n" +
                "clear_memory_page_state = false\n" +
                "gamma_render_target_as_unorm16 = false\n" +
                "snorm16_render_target_full_range = false\n" +
                "store_shaders = " + storeShaders + "\n\n" +
                "[gpu.vulkan]\n" +
                "pipeline_creation_threads = 2\n" +
                "dynamic_rendering = false\n" +
                "submit_on_primary_buffer_end = false\n" +
                "sparse_shared_memory = false\n" +
                "cube_map_edge_filtering_off = true\n" +
                "render_pass_clear = true\n" +
                "readback_resolve = false\n" +
                "readback_memexport = false\n" +
                "async_skip_incomplete_frames = true\n" +
                "allow_present_mode_mailbox = " + (!vsync) + "\n" +
                "allow_present_mode_fifo_relaxed = false\n" +
                "allow_present_mode_immediate = " + (!vsync) + "\n";

            try (FileOutputStream fos = new FileOutputStream(tomlFile)) {
                fos.write(toml.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {}

            args.add("--app.root=" + root);
            args.add("--config_file=" + tomlFile.getAbsolutePath());
            args.add("--game_data_root=" + root);
            args.add("--user_data_root=" + userDir.getAbsolutePath());
            args.add("--cache_root=" + cacheDir.getAbsolutePath());
        }

        args.add("--ui.video_mode_width=1280");
        args.add("--ui.video_mode_height=720");
        args.add("--gpu.vsync=" + vsync);
        args.add("--gpu.frame_limit=0.0");
        args.add("--gpu.anisotropic_override=" + af);
        args.add("--gpu.xenos.native_2x_msaa=true");
        args.add("--gpu.xenos.readback_resolve=none");
        args.add("--gpu.xenos.readback_memexport=false");
        args.add("--gpu.xenos.occlusion_query_enable=false");
        args.add("--gpu.xenos.depth_transfer_not_equal_test=false");
        args.add("--gpu.xenos.texture_cache_memory_limit_soft=256");
        args.add("--gpu.xenos.texture_cache_memory_limit_hard=512");
        args.add("--gpu.xenos.texture_cache_memory_limit_soft_lifetime=60");
        args.add("--gpu.xenos.clear_memory_page_state=false");
        args.add("--gpu.xenos.gamma_render_target_as_unorm16=false");
        args.add("--gpu.xenos.snorm16_render_target_full_range=false");
        args.add("--gpu.xenos.store_shaders=" + storeShaders);
        args.add("--gpu.vulkan.pipeline_creation_threads=2");
        args.add("--gpu.vulkan.dynamic_rendering=false");
        args.add("--gpu.vulkan.submit_on_primary_buffer_end=false");
        args.add("--gpu.vulkan.sparse_shared_memory=false");
        args.add("--gpu.vulkan.cube_map_edge_filtering_off=true");
        args.add("--gpu.vulkan.render_pass_clear=true");
        args.add("--gpu.vulkan.readback_resolve=false");
        args.add("--gpu.vulkan.readback_memexport=false");
        args.add("--gpu.vulkan.async_skip_incomplete_frames=true");
        args.add("--gpu.vulkan.allow_present_mode_mailbox=" + (!vsync));
        args.add("--gpu.vulkan.allow_present_mode_fifo_relaxed=false");
        args.add("--gpu.vulkan.allow_present_mode_immediate=" + (!vsync));

        return args.toArray(new String[0]);
    }

    private java.io.File extractTexOverrides() {
        java.io.File outDir = new java.io.File(getFilesDir(), "tex_override");
        if (!outDir.exists()) outDir.mkdirs();
        try {
            String[] names = getAssets().list("tex_override");
            if (names != null) {
                byte[] buf = new byte[4096];
                for (String name : names) {
                    if (!name.endsWith(".raw")) continue;
                    java.io.File dst = new java.io.File(outDir, name);
                    try (java.io.InputStream in = getAssets().open("tex_override/" + name);
                         java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                }
            }
        } catch (Exception ignored) {}
        return outDir;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Properties cfg = loadCfg();
        int fpsIdx = Math.max(0, Math.min(FPS_CAPS.length - 1, cfgInt(cfg, "fps_idx", 0)));
        try {
            Os.setenv("REX_ANDROID_FPS_CAP", String.valueOf((int) FPS_CAPS[fpsIdx]), true);
            Os.setenv("SDL_ACCELEROMETER_AS_JOYSTICK", "0", true);
            java.io.File ovrDir = extractTexOverrides();
            Os.setenv("REX_POP_OVR_DIR", ovrDir.getAbsolutePath(), true);
            Os.setenv("REX_POP_VKCACHE_PATH", new java.io.File(getFilesDir(), "vk_pipeline_cache.bin").getAbsolutePath(), true);
        } catch (Exception ignored) {}

        super.onCreate(savedInstanceState);
        attachSurfaceLifecycleCallback(getWindow().getDecorView());

        // Native full-screen SurfaceView restored

        TouchOverlayView overlay = new TouchOverlayView(this);
        addContentView(overlay, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        setUnfocusedSafe(!hasFocus);
    }

    @Override
    protected void onPause() {
        setUnfocusedSafe(true);
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        setUnfocusedSafe(false);
    }

    @Override
    protected void onDestroy() {
        setUnfocusedSafe(true);
        setSurfaceSafe(null);
        super.onDestroy();
        if (isFinishing()) {
            System.exit(0);
        }
    }
}
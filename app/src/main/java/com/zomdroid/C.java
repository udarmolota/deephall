package com.zomdroid;

public class C {
    // Follows the application id so the Deephall build can be installed beside Zomdroid:
    // two apps may not claim the same provider authority on one device.
    public static final String STORAGE_PROVIDER_AUTHORITY = BuildConfig.APPLICATION_ID + ".STORAGE_PROVIDER_AUTHORITY";

    /**
     * On-disk cache of ETC2-compressed textures, one file per texture, named by content hash.
     * Written by NG_GL4ES and by our Mesa build for ZINK (same encoder, same hash, same store).
     * Shared by every instance on purpose - the addressing is by content, so two instances
     * using the same texture share one entry. Safe to delete whole or in part at any time: the
     * renderer re-encodes what it misses. Its size ceiling and eviction are the library's business
     * (LRU, LIBGL_ETC2CACHE_MB), not the launcher's.
     *
     * <p>Relative to {@link AppStorage#getHomePath()}.
     */
    public static final String NGG_ETC2_CACHE_DIR = "ngg_etc2cache";

    public static class deps {
        public static final String ROOT = "dependencies";
        public static final String JRE_21 = ROOT + "/jre21";
        public static final String LIBS = ROOT + "/libs";
        public static final String LIBS_LINUX_X86_64 = LIBS + "/linux-x86_64";
        public static final String LIBS_ANDROID_ARM64_v8a = LIBS + "/android-arm64-v8a";
        public static final String LIBS_LWJGL_336 = LIBS_ANDROID_ARM64_v8a + "/lwjgl-3.3.6";

        // Custom user-supplied Vulkan driver. Stored directly in LIBS_ANDROID_ARM64_v8a.
        public static final String CUSTOM_DRIVER_FILENAME = "custom_driver.so";
        public static final String CUSTOM_DRIVER = LIBS_ANDROID_ARM64_v8a + "/" + CUSTOM_DRIVER_FILENAME;
    }

    public static class assets {
        public static final String BUNDLES = "bundles";
        public static final String BUNDLES_JRE21 = BUNDLES + "/jre21.tar.xz";
        public static final String BUNDLES_LIBS = BUNDLES + "/libs.tar.xz";
        public static final String DEFAULT_CONTROLS = "default_controls.json";
    }

    public static class shprefs {
        public static final String NAME = "com.zomdroid.PREFS";

        public static class keys {
            public static final String LAUNCHER_VERSION = "launcherVersion";
            public static final String INPUT_CONTROLS = "inputControls";
            public static final String GAME_INSTANCES = "gameInstances";
            public static final String LAUNCHER_PREFS = "launcherPrefs";
            public static final String INSTALLED_BUNDLES = "installedBundles";
            public static final String ARE_DEPENDENCIES_INSTALLED = "areDependenciesInstalled";
            public static final String IS_LEGAL_NOTICE_ACCEPTED = "isLegalNoticeAccepted";
            // versionName the release notes were last shown for (or recorded silently on a fresh
            // install). Differing from BuildConfig.VERSION_NAME means an update happened.
            public static final String RELEASE_NOTES_SHOWN_FOR = "releaseNotesShownFor";
        }
    }
}
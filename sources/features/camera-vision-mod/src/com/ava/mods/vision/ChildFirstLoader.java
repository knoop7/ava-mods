package com.ava.mods.vision;

import dalvik.system.DexClassLoader;

/**
 * Loads the vision core with child-first delegation.
 *
 * Android classloading is parent-first, and the host APK keeps a handful of
 * org.tensorflow.lite classes under their original names (the ones with native
 * methods) while renaming the rest. A plain DexClassLoader would therefore
 * resolve Interpreter from this JAR but NativeInterpreterWrapper from the host —
 * mixed versions across two loaders in one runtime package, which ends in
 * IllegalAccessError. Preferring this JAR's dex for everything keeps the
 * bundled TFLite (and ZXing) fully self-contained.
 *
 * The api package is the one exception: it must come from the parent so the
 * facade and the core agree on the same VisionApi class object.
 */
public final class ChildFirstLoader extends DexClassLoader {

    private static final String SHARED_API_PREFIX = "com.ava.mods.vision.api.";

    public ChildFirstLoader(String dexPath, String cacheDir, ClassLoader parent) {
        super(dexPath, cacheDir, null, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith(SHARED_API_PREFIX)) {
            return super.loadClass(name, resolve);
        }
        // Android's ClassLoader has no getClassLoadingLock; lock the loader itself.
        synchronized (this) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                try {
                    loaded = findClass(name);
                } catch (ClassNotFoundException notInJar) {
                    return super.loadClass(name, resolve);
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }
}

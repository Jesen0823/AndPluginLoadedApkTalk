package com.jesen.loadedapk_host;

import dalvik.system.DexClassLoader;

/**
 * 自定义ClassLoader 用来加载插件class
 * */
public class PluginClassLoader extends DexClassLoader {

    // optimizedDirectory 缓冲路径
    public PluginClassLoader(String dexPath, String optimizedDirectory, String librarySearchPath, ClassLoader parent) {
        super(dexPath, optimizedDirectory, librarySearchPath, parent);
    }



}

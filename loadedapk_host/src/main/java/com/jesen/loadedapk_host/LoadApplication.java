package com.jesen.loadedapk_host;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

public class LoadApplication extends Application {

    private static final String TAG = "LoadApplication";

    @Override
    public void onCreate() {
        super.onCreate();

        try {
            loadedApkPlugin();
        }catch (Exception e){
            Log.w(TAG,"插件初始化失败");
            e.printStackTrace();
        }
    }

    /**
     * 自定义LoadedApk添加到mPackage,专用来加载插件class
     * */
    private void loadedApkPlugin() throws Exception{
        File pluginFile = FileUtil.getPluginPath(this);

        if (!pluginFile.exists()){
            throw new FileNotFoundException("plugin file not exists.path: "+ pluginFile.getAbsolutePath());
        }

        /**
         * 1. mPackages
         * */
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        // 执行 currentActivityThread()拿到ActivityThread实例
        Method currentActivityThreadMethod = activityThreadClass.getDeclaredMethod("currentActivityThread");
        // 执行静态方法
        Object sCurrentActivityThread = currentActivityThreadMethod.invoke(null);
        // 拿到mPackages
        Field mPackagesField = activityThreadClass.getField("mPackages");
        mPackagesField.setAccessible(true);
        Object mPackagesObj = mPackagesField.get(sCurrentActivityThread);
        Map mPackages = (Map) mPackagesObj;

        /**
         * 2. 自定义LoadedApk
         *  源码执行以下方法返回一个LoadedApk
         *  *************************************************************
         * public final LoadedApk getPackageInfoNoCheck(ApplicationInfo ai,
         *             CompatibilityInfo compatInfo) {
         *     return getPackageInfo(ai, compatInfo, null, false, true, false);
         * }
         * ***************************************************************
         * */
         // CompatibilityInfo是 @hide 标记的，需要反射拿到它
        Class compatibilityInfoClass = Class.forName("android.content.res.CompatibilityInfo");
        Method getPackageInfoNoCheckMethod = activityThreadClass.getMethod("getPackageInfoNoCheck", ApplicationInfo.class,
                compatibilityInfoClass);
        // 拿到参数2：compatibilityInfoClass
        Field defaultCompatibilityField = compatibilityInfoClass.getDeclaredField("DEFAULT_COMPATIBILITY_INFO");
        defaultCompatibilityField.setAccessible(true);
        Object defaultCompatibilityObj = defaultCompatibilityField.get(null);

        // 拿到参数1：:applicationInfo
        ApplicationInfo applicationInfo = generateApplicationInfo(pluginFile);

        // 执行方法拿到LoadedApk
        Object mLoadedApk = getPackageInfoNoCheckMethod.invoke(
                sCurrentActivityThread,
                applicationInfo,
                defaultCompatibilityObj);

        // 自定义插件加载的ClassLoader
        String pluginPath = pluginFile.getAbsolutePath();
        File fileTmp = getDir("pluginDir", Context.MODE_PRIVATE);
        ClassLoader customClassLoader = new PluginClassLoader(pluginPath,fileTmp.getAbsolutePath(),
                null, getClassLoader());

        Field mClassLoaderField = mLoadedApk.getClass().getDeclaredField("mClassLoader");
        mClassLoaderField.setAccessible(true);
        mClassLoaderField.set(mLoadedApk, customClassLoader);

        /**
         * 3. 向mPackage添加LoadedApk
         * */
        WeakReference weakReference = new WeakReference(mLoadedApk);
        mPackages.put(applicationInfo.packageName,weakReference);
    }


    /**
     *  获取 ApplicationInfo
     *  执行源码以下方法
     * ********************************************************************************
     * public static ApplicationInfo generateApplicationInfo(Package p, int flags,
     *             PackageUserState state) {
     *   return generateApplicationInfo(p, flags, state, UserHandle.getCallingUserId());
     * }
     * *********************************************************************************
     * */
    private ApplicationInfo generateApplicationInfo(File file) throws Exception{
        ApplicationInfo mApplicationInfo = null;

        Class<?> packageParserClass = Class.forName("android.content.pm.PackageParser");
        Class packageClass = Class.forName("android.content.pm.PackageParser$Package");
        Class<?> packageUserStateClass = Class.forName("android.content.pm.PackageUserState");
        // 获取到方法
        Method generateApplicationInfoMth = packageParserClass.getMethod("generateApplicationInfo", packageClass, int.class
                , packageUserStateClass);

        // 参数：PackageParser实例
        Object packageParser = packageParserClass.newInstance();
        /**
         * 参数： 执行方法拿到 package
         * public Package parsePackage(File packageFile, int flags) throws PackageParserException {
         *   return parsePackage(packageFile, flags, false );
         * }
         * */
        Method parsePackageMth = packageParserClass.getMethod("parsePackage", File.class, int.class);
        Object packageObj = parsePackageMth.invoke(packageParser,file, PackageManager.GET_ACTIVITIES);

        mApplicationInfo = (ApplicationInfo) generateApplicationInfoMth.invoke(packageParser, packageObj,0,packageUserStateClass.newInstance());

        // 赋值插件路径
        String pluginPath = file.getAbsolutePath();
        mApplicationInfo.publicSourceDir = pluginPath;
        mApplicationInfo.sourceDir = pluginPath;

        return mApplicationInfo;
    }
}
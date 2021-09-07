package com.jesen.loadedapk_host;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

public class LoadApplication extends Application {

    private static final String TAG = "LoadApplication";

    @Override
    public void onCreate() {
        super.onCreate();

        try {
            hookLaunchActivity();
        } catch (Exception e) {
            Log.w(TAG, "hookLaunchActivity 失败");
            e.printStackTrace();
        }

        try {
            loadedApkPlugin();
        } catch (Exception e) {
            Log.w(TAG, "插件初始化失败");
            e.printStackTrace();
        }
    }


    private void hookLaunchActivity() throws Exception {
        Field mCallbackFiled = Handler.class.getDeclaredField("mCallback");
        mCallbackFiled.setAccessible(true);

        // 获得ActivityThread对象
        Class activityThreadClass = Class.forName("android.app.ActivityThread");
        Object activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null);
        // 通过 ActivityThread 取得 H
        Field mHField = activityThreadClass.getDeclaredField("mH");
        mHField.setAccessible(true);
        // 获取真正对象
        Handler mH = (Handler) mHField.get(activityThread);

        // 替换 增加自己的回调逻辑
        mCallbackFiled.set(mH, new MyCallback(mH));
    }

    /**
     * 自定义LoadedApk添加到mPackage,专用来加载插件class
     */
    private void loadedApkPlugin() throws Exception {
        File pluginFile = FileUtil.getPluginPath(this);

        if (!pluginFile.exists()) {
            throw new FileNotFoundException("plugin file not exists.path: " + pluginFile.getAbsolutePath());
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
        ClassLoader customClassLoader = new PluginClassLoader(pluginPath, fileTmp.getAbsolutePath(),
                null, getClassLoader());

        Field mClassLoaderField = mLoadedApk.getClass().getDeclaredField("mClassLoader");
        mClassLoaderField.setAccessible(true);
        mClassLoaderField.set(mLoadedApk, customClassLoader);

        /**
         * 3. 向mPackage添加LoadedApk
         * */
        WeakReference weakReference = new WeakReference(mLoadedApk);
        mPackages.put(applicationInfo.packageName, weakReference);
    }


    /**
     * 获取 ApplicationInfo
     * 执行源码以下方法
     * ********************************************************************************
     * public static ApplicationInfo generateApplicationInfo(Package p, int flags,
     * PackageUserState state) {
     * return generateApplicationInfo(p, flags, state, UserHandle.getCallingUserId());
     * }
     * *********************************************************************************
     */
    private ApplicationInfo generateApplicationInfo(File file) throws Exception {
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
        Object packageObj = parsePackageMth.invoke(packageParser, file, PackageManager.GET_ACTIVITIES);

        mApplicationInfo = (ApplicationInfo) generateApplicationInfoMth.invoke(packageParser, packageObj, 0, packageUserStateClass.newInstance());

        // 赋值插件路径
        String pluginPath = file.getAbsolutePath();
        mApplicationInfo.publicSourceDir = pluginPath;
        mApplicationInfo.sourceDir = pluginPath;

        return mApplicationInfo;
    }


    /**
     * Hook 拦截 getPackageInfo 做动态代理替换
     */
    private void hookGetPackageInfo() {
        try {
            // sPackageManager 替换 动态代理
            Class mActivityThreadClass = Class.forName("android.app.ActivityThread");
            Field sCurrentActivityThreadField = mActivityThreadClass.getDeclaredField("sCurrentActivityThread");
            sCurrentActivityThreadField.setAccessible(true);

            Field sPackageManagerField = mActivityThreadClass.getDeclaredField("sPackageManager");
            sPackageManagerField.setAccessible(true);
            final Object packageManager = sPackageManagerField.get(null);

            /**
             * 动态代理
             */
            Class mIPackageManagerClass = Class.forName("android.content.pm.IPackageManager");

            Object mIPackageManagerProxy = Proxy.newProxyInstance(getClassLoader(),

                    new Class[]{mIPackageManagerClass}, // 要监听的接口

                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            if ("getPackageInfo".equals(method.getName())) {
                                // 使源码中 pi != null 绕过 PMS, 欺骗系统
                                return new PackageInfo(); // 成功绕过 PMS检测
                            }
                            // 让系统正常继续执行下去
                            return method.invoke(packageManager, args);
                        }
                    });

            //  换成自己的动态代理
            sPackageManagerField.set(null, mIPackageManagerProxy);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    public static final int LAUNCH_ACTIVITY = 100;

    class MyCallback implements Handler.Callback {

        private Handler mH;

        public MyCallback(Handler mH) {
            this.mH = mH;
        }

        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {

                case LAUNCH_ACTIVITY:
                    Object obj = msg.obj; // 本质 ActivityClientRecord

                    try {
                        // 我们要获取之前Hook携带过来的 TestActivity
                        Field intentField = obj.getClass().getDeclaredField("intent");
                        intentField.setAccessible(true);

                        // 获取 intent 对象，才能取出携带过来的 actionIntent
                        Intent intent = (Intent) intentField.get(obj);
                        // actionIntent == TestActivity的Intent
                        Intent actionIntent = intent.getParcelableExtra("actionIntent");

                        if (actionIntent != null) {

                            /***
                             *  对插件和宿主进行区分
                             */
                            Field activityInfoField = obj.getClass().getDeclaredField("activityInfo");
                            activityInfoField.setAccessible(true); //授权
                            ActivityInfo activityInfo = (ActivityInfo) activityInfoField.get(obj);

                            // getPackage == null 是插件，否则是宿主
                            if (actionIntent.getPackage() == null) {
                                activityInfo.applicationInfo.packageName = actionIntent.getComponent().getPackageName();

                                // Hook 拦截PMS检查 getPackageInfo 做自己的逻辑
                                hookGetPackageInfo();
                            } else {
                                activityInfo.applicationInfo.packageName = actionIntent.getPackage();
                            }
                        }

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    break;
            }

            mH.handleMessage(msg);
            // 让系统继续正常往下执行
            return true;
        }
    }
}
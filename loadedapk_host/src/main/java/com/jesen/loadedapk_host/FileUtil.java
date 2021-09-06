package com.jesen.loadedapk_host;

import android.content.Context;
import android.os.Environment;

import java.io.File;

public class FileUtil {

    public static File getPluginPath(Context context){
        String path = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
        return new File(path , "plugin.apk");
    }
}

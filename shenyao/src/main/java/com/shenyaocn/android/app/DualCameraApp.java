package com.shenyaocn.android.app;
/*
******************* Copyright (c) ***********************\
**
**         (c) Copyright 2018, 蒋朋, china
**                  All Rights Reserved
**
**                       _oo0oo_
**                      o8888888o
**                      88" . "88
**                      (| -_- |)
**                      0\  =  /0
**                    ___/`---'\___
**                  .' \\|     |// '.
**                 / \\|||  :  |||// \
**                / _||||| -:- |||||- \
**               |   | \\\  -  /// |   |
**               | \_|  ''\---/''  |_/ |
**               \  .-\__  '-'  ___/-. /
**             ___'. .'  /--.--\  `. .'___
**          ."" '<  `.___\_<|>_/___.' >' "".
**         | | :  `- \`.;`\ _ /`;.`/ - ` : | |
**         \  \ `_.   \_ __\ /__ _/   .-` /  /
**     =====`-.____`.___ \_____/___.-`___.-'=====
**                       `=---='
**
**
**     ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
**
**               佛祖保佑         永无BUG
**
**
**                   南无本师释迦牟尼佛
**                   南无大乘妙法莲华经
**

**-----------------------版本信息------------------------
** 版    本: V0.1
**
******************** End of Head **********************\
*/


import android.app.Application;
import android.content.Context;

public class DualCameraApp extends Application {
    private static Context mContext;

    @Override
    public void onCreate() {
        super.onCreate();
        mContext = this;
    }

    public static Context getAppInstance() {
        return mContext;
    }
}

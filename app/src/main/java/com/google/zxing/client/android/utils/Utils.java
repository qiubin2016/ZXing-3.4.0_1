package com.google.zxing.client.android.utils;

public final class Utils {
    //获取行号
    public static int getLineNumber(Exception e){
        StackTraceElement[] trace =e.getStackTrace();
        if(trace==null||trace.length==0) return -1;
        return trace[0].getLineNumber();
    }

    //获取函数名
    public static String fun(Exception e) {
        StackTraceElement[] trace = e.getStackTrace();
        if (trace == null)
            return "";
        return trace[0].getMethodName()+"()";
    }
}

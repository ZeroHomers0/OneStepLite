package io.github.zerohomers0.onesteplite;

import java.util.Locale;

/** Recent files are ordered by addition time; unknown sizes remain explicit. */
final class RecentFilePolicy {
    static final int LIMIT=60;
    private RecentFilePolicy(){}
    static String size(long bytes) {
        if(bytes<0)return "大小未知";
        if(bytes<1024)return bytes+" B";
        if(bytes<1024L*1024)return String.format(Locale.ROOT,"%.1f KB",bytes/1024d);
        if(bytes<1024L*1024*1024)return String.format(Locale.ROOT,"%.1f MB",bytes/(1024d*1024));
        return String.format(Locale.ROOT,"%.1f GB",bytes/(1024d*1024*1024));
    }
    static String type(String name,String mime) {
        int dot=name==null?-1:name.lastIndexOf('.');
        if(dot>=0 && dot<name.length()-1) {
            String suffix=name.substring(dot+1);
            if(suffix.length()<=8 && suffix.matches("[a-zA-Z0-9]+"))return suffix.toUpperCase(Locale.ROOT);
        }
        if(mime!=null && mime.startsWith("text/"))return "文本";
        if(mime!=null && mime.startsWith("audio/"))return "音频";
        if(mime!=null && mime.startsWith("video/"))return "视频";
        return "文件";
    }
    static boolean visible(String name,String mime,long added) {
        return name!=null && !name.isBlank() && !name.startsWith(".") && added>0
                && (mime==null || !mime.startsWith("image/"));
    }
    static String age(long added,long now) {
        if(added<=0)return "时间未知";
        long minutes=Math.max(0,now-added)/60_000;
        if(minutes<1)return "刚刚";
        if(minutes<60)return minutes+"分钟前";
        if(minutes<1440)return minutes/60+"小时前";
        if(minutes<10080)return minutes/1440+"天前";
        return new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.CHINA).format(new java.util.Date(added));
    }
}

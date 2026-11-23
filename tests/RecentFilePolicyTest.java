package io.github.zerohomers0.onesteplite;

public final class RecentFilePolicyTest {
    private static int checks;
    public static void main(String[] args){
        check(RecentFilePolicy.size(-1).equals("大小未知"),"unknown size");
        check(RecentFilePolicy.size(0).equals("0 B"),"empty file");
        check(RecentFilePolicy.size(1024).equals("1.0 KB"),"KB boundary");
        check(RecentFilePolicy.size(1048576).equals("1.0 MB"),"MB boundary");
        check(RecentFilePolicy.size(1073741824).equals("1.0 GB"),"GB boundary");
        check(RecentFilePolicy.type("项目计划.pdf","application/pdf").equals("PDF"),"extension label");
        check(RecentFilePolicy.type("README","text/plain").equals("文本"),"text fallback");
        check(RecentFilePolicy.type("file.verylongsuffix",null).equals("文件"),"bounded suffix");
        check(RecentFilePolicy.visible("report.pdf","application/pdf",1),"document included");
        check(RecentFilePolicy.visible("voice.m4a","audio/mp4",1),"audio included");
        check(!RecentFilePolicy.visible(".secret","application/pdf",1),"hidden file excluded");
        check(!RecentFilePolicy.visible("photo.jpg","image/jpeg",1),"photo belongs to image page");
        check(!RecentFilePolicy.visible("report.pdf","application/pdf",0),"undated item excluded");
        check(!RecentFilePolicy.visible(null,null,1),"invalid name");
        check(RecentFilePolicy.age(1,1).equals("刚刚"),"just added");
        check(RecentFilePolicy.age(1,600001).equals("10分钟前"),"Chinese minute metadata");
        check(RecentFilePolicy.age(1,7200001).equals("2小时前"),"hour metadata");
        check(RecentFilePolicy.age(0,1).equals("时间未知"),"unknown timestamp");
        System.out.println("RecentFilePolicyTest checks="+checks+" passed");
    }
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
}

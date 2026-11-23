package io.github.zerohomers0.onesteplite;

/** Capture transport, scaling and crop limits never imply unrestricted screenshot access. */
public final class ContentCapturePolicyTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args) {
        check(ContentCapturePolicy.validImage(512*1024,1600,1600),"boundary capture accepted");
        check(!ContentCapturePolicy.validImage(512*1024+1,1600,1000),"oversized Binder transport rejected");
        check(!ContentCapturePolicy.validImage(0,100,100),"empty data rejected");
        check(!ContentCapturePolicy.validImage(10,0,100),"empty decoded image rejected");
        check(!ContentCapturePolicy.validImage(10,1601,100),"oversized decoded width rejected");
        check(!ContentCapturePolicy.validImage(10,100,1601),"oversized decoded height rejected");
        check(ContentCapturePolicy.validCrop(0,0,100,100,100,100),"full explicit region accepted");
        check(ContentCapturePolicy.validCrop(4,8,12,16,100,100),"minimum crop accepted");
        check(!ContentCapturePolicy.validCrop(-1,0,10,10,100,100),"negative left rejected");
        check(!ContentCapturePolicy.validCrop(0,-1,10,10,100,100),"negative top rejected");
        check(!ContentCapturePolicy.validCrop(0,0,101,10,100,100),"right overflow rejected");
        check(!ContentCapturePolicy.validCrop(0,0,10,101,100,100),"bottom overflow rejected");
        check(!ContentCapturePolicy.validCrop(0,0,7,10,100,100),"narrow crop rejected");
        check(!ContentCapturePolicy.validCrop(0,0,10,7,100,100),"short crop rejected");
        check(!ContentCapturePolicy.validCrop(10,10,0,0,100,100),"reversed crop rejected");
        check(java.util.Arrays.equals(ContentCapturePolicy.fit(3200,1600),new int[]{1600,800}),"landscape scales once");
        check(java.util.Arrays.equals(ContentCapturePolicy.fit(1600,3200),new int[]{800,1600}),"portrait scales once");
        check(java.util.Arrays.equals(ContentCapturePolicy.fit(300,500),new int[]{300,500}),"small images are never enlarged");
        check(java.util.Arrays.equals(ContentCapturePolicy.fit(1,3200),new int[]{1,1600}),"thin image retains positive width");
        boolean rejected=false;try{ContentCapturePolicy.fit(0,50);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"invalid geometry rejected before bitmap allocation");
        System.out.println("ContentCapturePolicyTest checks="+checks+" passed");
    }
}

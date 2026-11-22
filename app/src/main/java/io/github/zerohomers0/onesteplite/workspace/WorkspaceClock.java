package io.github.zerohomers0.onesteplite;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** System time/date broadcasts avoid a background timer and follow timezone changes. */
final class WorkspaceClock extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Scratch ink bounds; the clock paints once per frame. */
    private final android.graphics.Rect timeInk=new android.graphics.Rect(),dateInk=new android.graphics.Rect(),lunarInk=new android.graphics.Rect();
    private boolean showLunar=true,registered;
    private String time,date,lunar;
    private android.graphics.Bitmap wallpaper;
    private int wallpaperWidth,wallpaperHeight;
    private float startInset=-1f;
    void setStartInset(float value){if(Math.abs(startInset-value)>.1f){startInset=value;invalidate();}}
    void setWallpaper(android.graphics.Bitmap bitmap,int width,int height){
        wallpaper=bitmap;wallpaperWidth=width;wallpaperHeight=height;invalidate();
    }
    private final BroadcastReceiver ticks=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){refresh();}
    };
    WorkspaceClock(Context context){super(context);refresh();}
    void setShowLunar(boolean show){showLunar=show;refresh();}
    private void refresh(){
        Date now=new Date();
        time=new SimpleDateFormat("HH:mm",Locale.CHINA).format(now);
        date=new SimpleDateFormat("M月d日  EEEE",Locale.CHINA).format(now);
        lunar="";
        if(showLunar){
            android.icu.util.ChineseCalendar c=new android.icu.util.ChineseCalendar();
            c.setTime(now);
            String[] months={"正","二","三","四","五","六","七","八","九","十","冬","腊"};
            int month=c.get(android.icu.util.Calendar.MONTH),day=c.get(android.icu.util.Calendar.DAY_OF_MONTH);
            String[] digits={"一","二","三","四","五","六","七","八","九","十"};
            String dayText=day==10?"初十":day==20?"二十":day==30?"三十":
                    (day<10?"初":day<20?"十":"廿")+digits[(day-1)%10];
            lunar="农历 "+(c.get(android.icu.util.ChineseCalendar.IS_LEAP_MONTH)==1?"闰":"")+months[month]+"月"+dayText;
        }
        setContentDescription(time+"，"+date+(showLunar?"，"+lunar:""));invalidate();
    }
    @Override protected void onAttachedToWindow(){
        super.onAttachedToWindow();refresh();
        IntentFilter f=new IntentFilter(Intent.ACTION_TIME_TICK);f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);f.addAction(Intent.ACTION_DATE_CHANGED);f.addAction(Intent.ACTION_LOCALE_CHANGED);
        getContext().registerReceiver(ticks,f,Context.RECEIVER_NOT_EXPORTED);registered=true;
    }
    @Override protected void onDetachedFromWindow(){
        if(registered){getContext().unregisterReceiver(ticks);registered=false;}super.onDetachedFromWindow();
    }
    private float measureInk(String text,android.graphics.Typeface face,float size,float available,
            android.graphics.Rect ink){
        paint.setTypeface(face);paint.setTextSize(Math.max(.1f,size));
        float width=paint.measureText(text);
        if(width>available)paint.setTextSize(paint.getTextSize()*available/width);
        paint.getTextBounds(text,0,text.length(),ink);
        return paint.getTextSize();
    }
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        float d=getResources().getDisplayMetrics().density,font=getResources().getDisplayMetrics().scaledDensity;
        float anchor=startInset>=0?startInset:20*d,cy=getHeight()*.5f;
        paint.setColor(HyperOsStyle.dark(getContext())?0xffffffff:HyperOsStyle.wallpaperTextColor(wallpaper,wallpaperWidth,wallpaperHeight,
                getLeft(),getTop(),getWidth(),getHeight(),HyperOsStyle.textPrimary(getContext())));paint.setAlpha(255);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));
        paint.setTextSize(Math.min(46*font,getHeight()*.84f));
        float advance=paint.measureText(time);
        paint.setTextSize(Math.min(paint.getTextSize(),Math.max(.1f,(getWidth()*.51f-anchor)/Math.max(1,advance))*paint.getTextSize()));
        advance=paint.measureText(time);paint.getTextBounds(time,0,time.length(),timeInk);
        float origin=anchor-timeInk.left;
        c.drawText(time,origin,cy-(timeInk.top+timeInk.bottom)*.5f,paint);
        float x=origin+advance+18*d;
        android.graphics.Typeface dateFace=android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.BOLD);
        android.graphics.Typeface lunarFace=android.graphics.Typeface.create("sans-serif-medium",0);
        float available=Math.max(1,getWidth()-x-12*d);
        float dateSize=measureInk(date,dateFace,13*font,available,dateInk);
        float lunarSize=showLunar?measureInk(lunar,lunarFace,12*font,available,lunarInk):0;
        if(showLunar){
            // Width fitting can shrink the clock more than the two date lines.
            // Fit the actual glyphs together, leaving a visible gap at every font scale.
            float gap=Math.min(4*d,timeInk.height()*.2f);
            float budget=Math.max(1,timeInk.height()-gap);
            for(int pass=0;pass<4&&dateInk.height()+lunarInk.height()>budget;pass++){
                float scale=Math.min(.95f,budget/Math.max(1,dateInk.height()+lunarInk.height())*.98f);
                dateSize=measureInk(date,dateFace,dateSize*scale,available,dateInk);
                lunarSize=measureInk(lunar,lunarFace,lunarSize*scale,available,lunarInk);
            }
        }
        paint.setTypeface(dateFace);paint.setTextSize(dateSize);
        float baseline=showLunar?cy-timeInk.height()*.5f-dateInk.top:cy-(dateInk.top+dateInk.bottom)*.5f;
        c.drawText(date,x,baseline,paint);
        if(showLunar){
            paint.setAlpha(180);paint.setTypeface(lunarFace);paint.setTextSize(lunarSize);
            c.drawText(lunar,x,cy+timeInk.height()*.5f-lunarInk.bottom,paint);paint.setAlpha(255);
        }
    }
}

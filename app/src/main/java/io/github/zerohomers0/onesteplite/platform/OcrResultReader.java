package io.github.zerohomers0.onesteplite;

import android.os.BadParcelableException;
import android.os.Parcel;

/** Bounds the OEM parcel arrays before returning the aggregate recognized text. */
final class OcrResultReader {
    private OcrResultReader(){}
    static String read(Parcel parcel) {
        if(!present(parcel))return null;
        for(int paragraphs=count(parcel);paragraphs>0;paragraphs--)if(present(parcel)) {
            for(int lines=count(parcel);lines>0;lines--)if(present(parcel)) {
                location(parcel);parcel.readString();parcel.readInt();
                for(int characters=count(parcel);characters>0;characters--)if(present(parcel)) {
                    location(parcel);parcel.readString();parcel.readFloat();
                }
                parcel.readFloat();
            }
            parcel.readString();
        }
        String text=parcel.readString();
        for(int entities=count(parcel);entities>0;entities--)if(present(parcel)) {
            location(parcel);parcel.readString();for(int i=0;i<4;i++)parcel.readInt();
        }
        if(text==null || text.length()>ContentSharePolicy.MAX_TEXT)throw new BadParcelableException("Invalid OCR text size");
        return text;
    }
    private static boolean present(Parcel parcel) {
        int value=parcel.readInt();if(value!=0 && value!=1)throw new BadParcelableException("Invalid OCR presence");
        return value==1;
    }
    private static int count(Parcel parcel) {
        int value=parcel.readInt();
        if(value< -1 || value>100_000 || value>parcel.dataAvail()/4)throw new BadParcelableException("Invalid OCR array size");
        return Math.max(0,value);
    }
    private static void location(Parcel parcel) {
        String type=parcel.readString();if(type==null)return;
        if(!"com.xiaomi.aicr.vision.ocr.OCRRes$Location".equals(type))throw new BadParcelableException("Invalid OCR location");
        for(int array=0;array<2;array++)for(int coordinates=count(parcel);coordinates>0;coordinates--)parcel.readFloat();
    }
}

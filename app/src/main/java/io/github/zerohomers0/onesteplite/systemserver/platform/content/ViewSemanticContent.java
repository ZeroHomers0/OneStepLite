package io.github.zerohomers0.onesteplite;

import android.graphics.Rect;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import de.robv.android.xposed.XposedHelpers;

/** Reads only visible virtual nodes on the touched branch of one view. */
record ViewSemanticContent(String text, boolean selected, Rect image) {
    private static final int MAX_NODES = 64;

    static ViewSemanticContent find(View view, int x, int y, int minImage) {
        AccessibilityNodeProvider provider=view.getAccessibilityNodeProvider();
        if(provider==null)return null;
        int[] origin=new int[2];
        view.getLocationOnScreen(origin);
        Rect visible=new Rect();
        if(!view.getLocalVisibleRect(visible))return null;
        visible.offset(origin[0],origin[1]);
        ArrayDeque<Integer> pending=new ArrayDeque<>();
        HashSet<Integer> visited=new HashSet<>();
        pending.push(AccessibilityNodeProvider.HOST_VIEW_ID);
        ViewSemanticContent best=null;
        long bestArea=Long.MAX_VALUE;
        while(!pending.isEmpty() && visited.size()<MAX_NODES) {
            int id=pending.pop();
            if(!visited.add(id))continue;
            AccessibilityNodeInfo node=provider.createAccessibilityNodeInfo(id);
            boolean host=id==AccessibilityNodeProvider.HOST_VIEW_ID;
            if(node==null || !host && !node.isVisibleToUser() || node.isPassword() || node.isEditable()
                    || node.isAccessibilityDataSensitive())continue;
            Rect bounds=new Rect();
            node.getBoundsInScreen(bounds);
            if(host && bounds.isEmpty())bounds.set(visible);
            if(!bounds.intersect(visible) || !bounds.contains(x,y))continue;
            int children=node.getChildCount();
            int limit=Math.min(children,MAX_NODES-pending.size());
            for(int i=limit-1;i>=0;i--){
                long child=(Long)XposedHelpers.callMethod(node,"getChildId",i);
                int childId=(Integer)XposedHelpers.callStaticMethod(
                        AccessibilityNodeInfo.class,"getVirtualDescendantId",child);
                if(childId!=AccessibilityNodeProvider.HOST_VIEW_ID)pending.push(childId);
            }
            ViewSemanticContent candidate=fromNode(node,bounds,children,minImage);
            if(candidate==null)continue;
            long area=(long)bounds.width()*bounds.height();
            if(best!=null && (best.selected && !candidate.selected || best.selected==candidate.selected && area>=bestArea))continue;
            if(candidate.image!=null)candidate.image.offset(-origin[0],-origin[1]);
            best=candidate;bestArea=area;
        }
        return best;
    }

    private static ViewSemanticContent fromNode(AccessibilityNodeInfo node, Rect bounds, int children, int minImage) {
        if(children==0 && bounds.width()>=minImage && bounds.height()>=minImage && isImageNode(node))
            return new ViewSemanticContent(null,false,bounds);
        CharSequence text=node.getText();
        if(text!=null) {
            int start=node.getTextSelectionStart(),end=node.getTextSelectionEnd();
            boolean selected=start>=0 && end>=0 && start!=end && Math.max(start,end)<=text.length();
            if(selected)text=text.subSequence(Math.min(start,end),Math.max(start,end));
            if((selected || children==0) && text.length()<=ContentSharePolicy.MAX_TEXT) {
                String value=text.toString();
                if(!value.isBlank())return new ViewSemanticContent(value,selected,null);
            }
        }
        return null;
    }

    /** Browser images are exposed as ImageView-like virtual leaf nodes, often with alt text. */
    private static boolean isImageNode(AccessibilityNodeInfo node) {
        String className=String.valueOf(node.getClassName()).toLowerCase(Locale.ROOT);
        if(className.contains("image") || className.contains("photo") || className.contains("picture"))return true;
        CharSequence description=node.getContentDescription();
        if(description==null)return false;
        String value=description.toString().toLowerCase(Locale.ROOT);
        return value.contains("图片") || value.contains("图像") || value.contains("照片")
                || value.contains("image") || value.contains("photo") || value.contains("picture");
    }
}

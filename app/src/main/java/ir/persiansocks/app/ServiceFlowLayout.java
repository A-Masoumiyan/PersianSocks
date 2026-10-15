package ir.persiansocks.app;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Wraps service badges at the available width, including narrow RTL windows. */
final class ServiceFlowLayout extends ViewGroup {
    private final int gap;
    ServiceFlowLayout(Context context) {
        super(context);
        gap=Math.round(4*getResources().getDisplayMetrics().density);
        setLayoutDirection(AppLanguage.direction());
    }
    @Override protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT,LayoutParams.WRAP_CONTENT);
    }
    @Override protected void onMeasure(int widthSpec,int heightSpec) {
        int available=MeasureSpec.getMode(widthSpec)==MeasureSpec.UNSPECIFIED?Integer.MAX_VALUE:
            Math.max(0,MeasureSpec.getSize(widthSpec)-getPaddingLeft()-getPaddingRight());
        int used=0,rowHeight=0,height=0,widest=0;
        for(int i=0;i<getChildCount();i++) {
            View child=getChildAt(i);if(child.getVisibility()==GONE)continue;
            measureChild(child,widthSpec,heightSpec);
            int width=child.getMeasuredWidth();
            if(used>0&&used+gap+width>available){widest=Math.max(widest,used);height+=rowHeight+gap;used=0;rowHeight=0;}
            used+=(used==0?0:gap)+width;rowHeight=Math.max(rowHeight,child.getMeasuredHeight());
        }
        setMeasuredDimension(resolveSize(Math.max(widest,used)+getPaddingLeft()+getPaddingRight(),widthSpec),
            resolveSize(height+rowHeight+getPaddingTop()+getPaddingBottom(),heightSpec));
    }
    @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom) {
        int available=right-left-getPaddingLeft()-getPaddingRight(),used=0,rowHeight=0,y=getPaddingTop();
        boolean rtl=getLayoutDirection()==LAYOUT_DIRECTION_RTL;
        for(int i=0;i<getChildCount();i++) {
            View child=getChildAt(i);if(child.getVisibility()==GONE)continue;
            int width=child.getMeasuredWidth(),height=child.getMeasuredHeight();
            if(used>0&&used+gap+width>available){y+=rowHeight+gap;used=0;rowHeight=0;}
            if(used>0)used+=gap;
            int x=rtl?right-left-getPaddingRight()-used-width:getPaddingLeft()+used;
            child.layout(x,y,x+width,y+height);used+=width;rowHeight=Math.max(rowHeight,height);
        }
    }
}

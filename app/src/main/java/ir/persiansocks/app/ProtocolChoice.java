package ir.persiansocks.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.View;
import android.widget.CheckBox;

/** One checkable touch target; the protocol and its mark cannot separate under RTL. */
public final class ProtocolChoice extends CheckBox {
    public ProtocolChoice(Context context,String protocol,boolean checked){
        super(context);
        setText(protocol);setTextSize(12);setButtonDrawable(null);
        setLayoutDirection(View.LAYOUT_DIRECTION_LTR);setTextDirection(View.TEXT_DIRECTION_LTR);
        setMinWidth(0);setMinimumWidth(0);setMinHeight(0);setMinimumHeight(0);
        setPadding(0,0,0,0);setBackgroundTintList(null);setStateListAnimator(null);
        StateListDrawable states=new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_checked},panel(0xff163c39,0xff46e2b8));
        states.addState(new int[]{},panel(0xff172131,0xff304150));
        setBackground(new RippleDrawable(ColorStateList.valueOf(0x4046e2b8),states,null));
        setChecked(checked);
    }
    private GradientDrawable panel(int fill,int border){
        float d=getResources().getDisplayMetrics().density;
        GradientDrawable bg=new GradientDrawable();bg.setColor(fill);bg.setCornerRadius(10*d);bg.setStroke(Math.max(1,Math.round(d)),border);return bg;
    }
    @Override protected void onDraw(Canvas canvas){
        float d=getResources().getDisplayMetrics().density;
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.BOLD));
        paint.setTextSize(getTextSize());String label=getText().toString();float mark=14*d,gap=5*d,available=Math.max(1,getWidth()-16*d-mark-gap);
        float width=paint.measureText(label);if(width>available)paint.setTextSize(paint.getTextSize()*available/width);
        width=paint.measureText(label);float x=(getWidth()-mark-gap-width)/2,y=getHeight()/2f;
        paint.setColor(isChecked()?0xff46e2b8:0xff97a9bd);paint.setAlpha(isEnabled()?255:100);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.5f*d);
        canvas.drawRoundRect(x,y-mark/2,x+mark,y+mark/2,3*d,3*d,paint);
        if(isChecked()){paint.setStrokeCap(Paint.Cap.ROUND);canvas.drawLine(x+3*d,y,x+6*d,y+3*d,paint);canvas.drawLine(x+6*d,y+3*d,x+11*d,y-4*d,paint);}
        paint.setStyle(Paint.Style.FILL);paint.setColor(isChecked()?0xff46e2b8:Color.WHITE);paint.setAlpha(isEnabled()?255:100);
        Paint.FontMetrics metrics=paint.getFontMetrics();canvas.drawText(label,x+mark+gap,y-(metrics.ascent+metrics.descent)/2,paint);
    }
}

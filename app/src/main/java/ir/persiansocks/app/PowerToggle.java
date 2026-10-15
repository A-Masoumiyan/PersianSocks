package ir.persiansocks.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** A drawn power symbol with a rotating waiting ring; no font/emoji dependency. */
public final class PowerToggle extends View {
    @Override public CharSequence getAccessibilityClassName(){return android.widget.Button.class.getName();}
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean connected,waiting;
    private float angle;
    private ValueAnimator spinner;
    public PowerToggle(Context context){super(context);setClickable(true);setFocusable(true);setMinimumWidth((int)dp(48));setMinimumHeight((int)dp(48));}
    private float dp(float value){return value*getResources().getDisplayMetrics().density;}
    public void showState(boolean connected,boolean waiting){
        boolean changed=this.waiting!=waiting;boolean visualChange=this.connected!=connected||changed;this.connected=connected;this.waiting=waiting;
        setContentDescription(AppLanguage.t(waiting?R.string.ui_design_cancel_accessibility:connected?R.string.ui_design_disconnect_accessibility:R.string.ui_design_connect_accessibility));
        setSelected(connected);
        if(changed)animation();if(visualChange)invalidate();
    }
    private void animation(){
        if(spinner!=null){spinner.cancel();spinner=null;}
        if(waiting&&isAttachedToWindow()&&getWindowVisibility()==VISIBLE){
            spinner=ValueAnimator.ofFloat(0,360);spinner.setDuration(1100);spinner.setRepeatCount(ValueAnimator.INFINITE);
            spinner.setInterpolator(new LinearInterpolator());spinner.addUpdateListener(a->{angle=(float)a.getAnimatedValue();invalidate();});spinner.start();
        }
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();animation();}
    @Override protected void onDetachedFromWindow(){if(spinner!=null){spinner.cancel();spinner=null;}super.onDetachedFromWindow();}
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);animation();}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);float x=getWidth()/2f,y=getHeight()/2f,r=Math.min(x,y)-dp(3);
        paint.setStyle(Paint.Style.FILL);paint.setColor(connected?Color.rgb(22,75,68):Color.rgb(22,32,48));canvas.drawCircle(x,y,r,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));paint.setColor(connected?Color.rgb(70,226,184):Color.rgb(60,79,96));canvas.drawCircle(x,y,r,paint);
        if(waiting){paint.setColor(Color.rgb(70,226,184));paint.setStrokeWidth(dp(2.5f));canvas.drawArc(x-r,y-r,x+r,y+r,angle,95,false,paint);}
        float icon=dp(11);paint.setStrokeWidth(dp(2.8f));paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(connected?Color.rgb(70,226,184):Color.WHITE);
        canvas.drawArc(x-icon,y-icon,x+icon,y+icon,-45,270,false,paint);canvas.drawLine(x,y-dp(15),x,y-dp(1),paint);
    }
}

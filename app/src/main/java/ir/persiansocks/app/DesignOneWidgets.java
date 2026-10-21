package ir.persiansocks.app;

import android.content.Context;
import android.graphics.*;
import android.widget.CheckBox;

/** Native, scalable controls matching the approved charcoal/teal design. */
final class DesignOneWidgets {
 static final int TEAL=Color.rgb(28,205,187), SURFACE=Color.rgb(25,31,37), STROKE=Color.rgb(46,54,64);
 static final class Switch extends CheckBox {
  final Paint p=new Paint(3);
  Switch(Context c){super(c);setButtonDrawable(null);setBackground(null);setPadding(0,0,0,0);setMinWidth(0);setMinimumWidth(0);}
  @Override public CharSequence getAccessibilityClassName(){return "android.widget.Switch";}
  @Override protected void onDraw(Canvas c){float d=getResources().getDisplayMetrics().density,w=Math.min(getWidth(),44*d),h=26*d,x=(getWidth()-w)/2,y=(getHeight()-h)/2;p.setColor(isChecked()?TEAL:Color.rgb(65,72,84));c.drawRoundRect(x,y,x+w,y+h,h/2,h/2,p);p.setColor(Color.WHITE);float r=h/2-3*d;c.drawCircle(isChecked()?x+w-h/2:x+h/2,y+h/2,r,p);}
 }
 static final class Choice extends android.widget.TextView {
  private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
  Choice(Context c){super(c);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
  @Override protected void onDraw(Canvas canvas){float d=getResources().getDisplayMetrics().density,x=getWidth()/2f,y=getHeight()/2f;p.setColor(getCurrentTextColor());p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.6f*d);canvas.drawCircle(x,y,9*d,p);if(isSelected()){p.setStyle(Paint.Style.FILL);canvas.drawCircle(x,y,4.5f*d,p);}}
 }
 static final class Signal extends android.view.View {
  private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);private long delay=Long.MAX_VALUE;
  Signal(Context c){super(c);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
  void latency(long value){if(delay!=value){delay=value;invalidate();}}
  @Override protected void onDraw(Canvas canvas){float d=getResources().getDisplayMetrics().density,base=(getHeight()+13*d)/2;int bars=delay==Long.MAX_VALUE?0:delay<150?4:delay<300?3:2;int color=delay<150?TEAL:delay<300?0xffffbf37:0xffff8740;for(int i=0;i<4;i++){p.setColor(i<bars?color:0xff354047);float x=i*3*d;canvas.drawRoundRect(x,base-(4+i*3)*d,x+2*d,base,.7f*d,.7f*d,p);}}
 }
 static final class Scan extends UiIconButton {
  final Paint p=new Paint(3);Kind current=Kind.PLAY;String caption="";
  Scan(Context c){super(c,Kind.PLAY,AppLanguage.t(R.string.ui_scan_fast));setBackground(null);}
  @Override public void kind(Kind k){super.kind(k);current=k;invalidate();}
  @Override public void caption(String s){caption=s;invalidate();}
  @Override public void active(boolean on){setSelected(on);invalidate();}
  @Override protected void onDraw(Canvas c){float d=getResources().getDisplayMetrics().density,w=getWidth(),h=getHeight();p.setShader(new LinearGradient(0,0,w,h,new int[]{0xff16c3bd,0xff26d6b6},null,Shader.TileMode.CLAMP));p.setStyle(Paint.Style.FILL);c.drawRoundRect(0,0,w,h,13*d,13*d,p);p.setShader(null);p.setColor(0xff092a2e);p.setTextSize(17*getResources().getDisplayMetrics().scaledDensity);p.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));String label=current==Kind.PAUSE||current==Kind.STOP?AppLanguage.t(R.string.ui_scan_pause):AppLanguage.t(R.string.ui_scan_fast);float tw=p.measureText(label),x=(w-tw-34*d)/2;c.drawText(label,x+34*d,(h-p.ascent()-p.descent())/2,p);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2*d);float cx=x+12*d,cy=h/2;c.drawCircle(cx,cy,9*d,p);c.drawCircle(cx,cy,4*d,p);c.drawArc(cx-14*d,cy-14*d,cx+14*d,cy+14*d,25,300,false,p);p.setStyle(Paint.Style.FILL);}
 }
}

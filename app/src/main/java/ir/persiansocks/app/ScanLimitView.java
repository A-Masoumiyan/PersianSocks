package ir.persiansocks.app;
import android.content.Context;
import android.graphics.*;
import android.view.*;
/** Four visible stops. Physical left to right; no duplicate numeric input. */
public final class ScanLimitView extends View {
    public interface Listener{void selected(int index);}
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final String[] LABELS={"5000","10000","20000","Max"};
    private int selected;private final Listener listener;
    public ScanLimitView(Context c,int index,Listener l){super(c);selected=index;listener=l;setContentDescription(AppLanguage.t(R.string.ui_041));setFocusable(true);}
    public void selected(int index){selected=index;invalidate();}
    protected void onDraw(Canvas c){Paint p=paint;float d=getResources().getDisplayMetrics().density,left=20*d,right=getWidth()-20*d,y=20*d;p.setStrokeWidth(3*d);p.setColor(Color.rgb(61,82,98));c.drawLine(left,y,right,y,p);
        String[] labels=LABELS;for(int i=0;i<4;i++){float x=left+(right-left)*i/3;p.setColor(i==selected?Color.rgb(70,226,184):Color.rgb(137,157,177));c.drawCircle(x,y,(i==selected?7:4)*d,p);p.setTextSize(10*d);p.setTextAlign(i==0?Paint.Align.LEFT:i==3?Paint.Align.RIGHT:Paint.Align.CENTER);c.drawText(labels[i],x,y+24*d,p);}}
    public boolean onTouchEvent(MotionEvent e){if(e.getAction()==MotionEvent.ACTION_DOWN||e.getAction()==MotionEvent.ACTION_MOVE||e.getAction()==MotionEvent.ACTION_UP){getParent().requestDisallowInterceptTouchEvent(true);float d=getResources().getDisplayMetrics().density;int index=Math.max(0,Math.min(3,Math.round((e.getX()-20*d)/(getWidth()-40*d)*3)));if(index!=selected){selected=index;listener.selected(index);invalidate();}if(e.getAction()==MotionEvent.ACTION_UP){performClick();getParent().requestDisallowInterceptTouchEvent(false);}return true;}return false;}
    public boolean performClick(){super.performClick();return true;}
    public boolean onKeyDown(int key,android.view.KeyEvent e){if(key==android.view.KeyEvent.KEYCODE_DPAD_LEFT||key==android.view.KeyEvent.KEYCODE_DPAD_RIGHT){selected=Math.max(0,Math.min(3,selected+(key==android.view.KeyEvent.KEYCODE_DPAD_RIGHT?1:-1)));listener.selected(selected);invalidate();return true;}return super.onKeyDown(key,e);}
}

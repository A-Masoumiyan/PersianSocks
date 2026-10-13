package ir.persiansocks.app;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.content.res.ColorStateList;
import android.widget.Button;
/** Small vector actions with a generous touch target and an accessible description. */
public final class UiIconButton extends Button {
    public enum Kind {STAR,INFO,SYNC,CONNECT,DOWNLOAD,SPLIT,SOURCE,TARGET,PRIVACY,TELEGRAM,UPDATE}
    private final Kind kind;private boolean active;
    public UiIconButton(Context c,Kind k,String description){super(c);kind=k;setContentDescription(description);setTooltipText(description);setText("");setMinWidth(0);setMinHeight(0);setMinimumWidth(0);setMinimumHeight(0);setPadding(0,0,0,0);setBackgroundTintList(null);setStateListAnimator(null);setTextColor(Color.rgb(166,188,204));
        GradientDrawable bg=new GradientDrawable();bg.setColor(Color.rgb(23,33,49));bg.setCornerRadius(12*c.getResources().getDisplayMetrics().density);bg.setStroke(1,Color.rgb(48,65,80));setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(60,70,226,184)),bg,null));}
    public void active(boolean on){active=on;invalidate();}
    @Override protected void onDraw(Canvas canvas){
        float d=getResources().getDisplayMetrics().density,s=Math.min(24*d,Math.min(getWidth(),getHeight())*.62f);canvas.save();canvas.translate((getWidth()-s)/2,(getHeight()-s)/2);canvas.scale(s/24,s/24);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setColor(active?Color.rgb(70,226,184):getCurrentTextColor());p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.8f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
        switch(kind){
            case STAR:{Path path=new Path();for(int i=0;i<10;i++){double a=-Math.PI/2+i*Math.PI/5;float r=i%2==0?10:4.5f;float x=12+(float)Math.cos(a)*r,y=12+(float)Math.sin(a)*r;if(i==0)path.moveTo(x,y);else path.lineTo(x,y);}path.close();if(active)p.setStyle(Paint.Style.FILL);canvas.drawPath(path,p);break;}
            case INFO:case PRIVACY:canvas.drawCircle(12,12,9,p);canvas.drawLine(12,11,12,17,p);p.setStyle(Paint.Style.FILL);canvas.drawCircle(12,7,1.2f,p);break;
            case SYNC:case UPDATE:canvas.drawArc(4,4,20,20,35,140,false,p);canvas.drawArc(4,4,20,20,215,140,false,p);canvas.drawLine(4,12,4,17,p);canvas.drawLine(4,17,9,17,p);canvas.drawLine(20,12,20,7,p);canvas.drawLine(20,7,15,7,p);break;
            case DOWNLOAD:canvas.drawLine(12,3,12,15,p);canvas.drawLine(7,10,12,15,p);canvas.drawLine(12,15,17,10,p);canvas.drawLine(4,17,4,21,p);canvas.drawLine(4,21,20,21,p);canvas.drawLine(20,21,20,17,p);break;
            case CONNECT:canvas.drawRect(2,8,8,16,p);canvas.drawRect(16,8,22,16,p);canvas.drawLine(0,12,2,12,p);canvas.drawLine(22,12,24,12,p);canvas.drawLine(8,10,11,10,p);canvas.drawLine(8,14,11,14,p);canvas.drawLine(13,10,16,10,p);canvas.drawLine(13,14,16,14,p);break;
            case SPLIT:canvas.drawLine(12,21,12,12,p);canvas.drawLine(12,12,5,5,p);canvas.drawLine(12,12,19,5,p);canvas.drawLine(5,5,5,10,p);canvas.drawLine(5,5,10,5,p);canvas.drawLine(19,5,14,5,p);canvas.drawLine(19,5,19,10,p);break;
            case SOURCE:canvas.drawOval(3,3,21,9,p);canvas.drawArc(3,9,21,15,0,180,false,p);canvas.drawArc(3,15,21,21,0,180,false,p);canvas.drawLine(3,6,3,18,p);canvas.drawLine(21,6,21,18,p);break;
            case TARGET:canvas.drawCircle(12,12,9,p);canvas.drawOval(8,3,16,21,p);canvas.drawLine(3,12,21,12,p);break;
            case TELEGRAM:{p.setColor(Color.rgb(71,171,225));p.setStyle(Paint.Style.FILL);Path path=new Path();path.moveTo(2,10);path.lineTo(22,3);path.lineTo(17,21);path.lineTo(11,15);path.lineTo(7,19);path.lineTo(8,13);path.close();canvas.drawPath(path,p);p.setColor(Color.WHITE);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);canvas.drawLine(8,13,18,7,p);break;}
        }
        canvas.restore();if(kind==Kind.UPDATE&&active){p.setColor(Color.rgb(255,89,107));p.setStyle(Paint.Style.FILL);canvas.drawCircle(getWidth()-8*d,8*d,3.5f*d,p);}setAlpha(isEnabled()?1f:.45f);
    }
}

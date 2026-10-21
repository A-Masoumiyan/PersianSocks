package ir.persiansocks.app;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.content.res.ColorStateList;
import android.widget.Button;
/** Small vector actions with a generous touch target and an accessible description. */
public class UiIconButton extends Button {
    public enum Kind {STAR,INFO,SYNC,CONNECT,DOWNLOAD,SPLIT,SOURCE,TARGET,PRIVACY,TELEGRAM,UPDATE,FILTER,SORT,PING,PROTOCOL,COLLECT,SCAN,PLAY,PAUSE,RESTART,STOP,BACK,MENU,CLOSE,SERVERS,SETTINGS,SHIELD}
    private Kind kind;private boolean active;
    private final Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    private final android.text.TextPaint labelPaint=new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final GradientDrawable surface=new GradientDrawable();
    private String caption="",visibleCaption="";
    public void caption(String value){if(caption.equals(value))return;caption=value;fitCaption();invalidate();}
    private void fitCaption(){labelPaint.setTextSize(10*getResources().getDisplayMetrics().scaledDensity);labelPaint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));visibleCaption=android.text.TextUtils.ellipsize(caption,labelPaint,Math.max(0,getWidth()-8*getResources().getDisplayMetrics().density),android.text.TextUtils.TruncateAt.END).toString();}
    @Override protected void onSizeChanged(int w,int h,int ow,int oh){super.onSizeChanged(w,h,ow,oh);fitCaption();}
    @Override public void setEnabled(boolean enabled){super.setEnabled(enabled);setAlpha(enabled?1f:.4f);}

    public UiIconButton(Context c,Kind k,String description){super(c);kind=k;setContentDescription(description);setTooltipText(description);setText("");setMinWidth(0);setMinHeight(0);setMinimumWidth(0);setMinimumHeight(0);setPadding(0,0,0,0);setBackgroundTintList(null);setStateListAnimator(null);setTextColor(Color.rgb(166,188,204));
        surface.setColor(Color.rgb(23,33,49));surface.setCornerRadius(12*c.getResources().getDisplayMetrics().density);surface.setStroke(Math.max(1,Math.round(c.getResources().getDisplayMetrics().density)),Color.rgb(43,58,76));setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(60,70,226,184)),surface,null));}
    public void kind(Kind value){if(kind==value)return;kind=value;invalidate();}
    public void active(boolean on){if(active==on)return;active=on;setSelected(on);surface.setColor(on?Color.rgb(22,54,57):Color.rgb(23,33,49));surface.setStroke(Math.max(1,Math.round(getResources().getDisplayMetrics().density)),on?Color.rgb(46,111,103):Color.rgb(43,58,76));invalidate();}
    @Override protected void onDraw(Canvas canvas){
        float d=getResources().getDisplayMetrics().density,s=Math.min(24*d,Math.min(getWidth(),getHeight())*.62f);float labelHeight=caption.isEmpty()?0:labelPaint.getFontSpacing()+5*d;canvas.save();canvas.translate((getWidth()-s)/2,Math.max(3*d,(getHeight()-labelHeight-s)/2));canvas.scale(s/24,s/24);
        Paint p=ink;p.setColor((kind==Kind.STOP||kind==Kind.PAUSE)?Color.rgb(255,89,107):active?Color.rgb(70,226,184):getCurrentTextColor());p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.8f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
        switch(kind){
            case SERVERS:canvas.drawRoundRect(3,3,21,9,2,2,p);canvas.drawRoundRect(3,14,21,20,2,2,p);canvas.drawCircle(7,6,.7f,p);canvas.drawCircle(7,17,.7f,p);break;
            case SETTINGS:canvas.drawCircle(12,12,7,p);canvas.drawCircle(12,12,3,p);for(int a=0;a<8;a++){double t=a*Math.PI/4;canvas.drawLine(12+(float)Math.cos(t)*8,12+(float)Math.sin(t)*8,12+(float)Math.cos(t)*11,12+(float)Math.sin(t)*11,p);}break;
            case SHIELD:path.reset();path.moveTo(12,2);path.lineTo(21,5);path.lineTo(20,14);path.quadTo(18,20,12,23);path.quadTo(6,20,4,14);path.lineTo(3,5);path.close();canvas.drawPath(path,p);break;
            case MENU:canvas.drawLine(4,6,20,6,p);canvas.drawLine(4,12,20,12,p);canvas.drawLine(4,18,20,18,p);break;
            case CLOSE:canvas.drawLine(6,6,18,18,p);canvas.drawLine(18,6,6,18,p);break;
            case FILTER:{path.reset();path.moveTo(3,4);path.lineTo(21,4);path.lineTo(14,12);path.lineTo(14,20);path.lineTo(10,18);path.lineTo(10,12);path.close();canvas.drawPath(path,p);break;}
            case SORT:canvas.drawLine(4,6,20,6,p);canvas.drawLine(4,12,16,12,p);canvas.drawLine(4,18,11,18,p);break;
            case PING:{path.reset();path.moveTo(2,12);path.lineTo(6,12);path.lineTo(9,5);path.lineTo(13,19);path.lineTo(17,10);path.lineTo(20,12);path.lineTo(22,12);canvas.drawPath(path,p);break;}
            case PROTOCOL:canvas.drawRect(3,4,10,10,p);canvas.drawRect(14,14,21,20,p);canvas.drawLine(6,10,6,17,p);canvas.drawLine(6,17,14,17,p);canvas.drawLine(10,7,18,7,p);canvas.drawLine(18,7,18,14,p);break;
            case COLLECT:canvas.drawLine(6,3,6,13,p);canvas.drawLine(2,9,6,13,p);canvas.drawLine(6,13,10,9,p);canvas.drawLine(14,5,22,5,p);canvas.drawLine(14,10,22,10,p);canvas.drawLine(4,18,22,18,p);canvas.drawLine(4,22,22,22,p);break;
            case SCAN:canvas.drawCircle(10,10,7,p);canvas.drawLine(15,15,22,22,p);canvas.drawLine(10,6,10,14,p);canvas.drawLine(6,10,14,10,p);break;
            case PLAY:{p.setStyle(Paint.Style.FILL);path.reset();path.moveTo(7,4);path.lineTo(21,12);path.lineTo(7,20);path.close();canvas.drawPath(path,p);break;}
            case PAUSE:p.setStyle(Paint.Style.FILL);canvas.drawRoundRect(5,4,10,20,1.5f,1.5f,p);canvas.drawRoundRect(14,4,19,20,1.5f,1.5f,p);break;
            case RESTART:canvas.drawArc(4,4,20,20,200,300,false,p);canvas.drawLine(4,3,4,9,p);canvas.drawLine(4,9,10,9,p);break;
            case STOP:p.setStyle(Paint.Style.FILL);canvas.drawRoundRect(5,5,19,19,3,3,p);break;
            case BACK:canvas.drawLine(20,12,4,12,p);canvas.drawLine(4,12,10,6,p);canvas.drawLine(4,12,10,18,p);break;
            case STAR:{path.reset();for(int i=0;i<10;i++){double a=-Math.PI/2+i*Math.PI/5;float r=i%2==0?10:4.5f;float x=12+(float)Math.cos(a)*r,y=12+(float)Math.sin(a)*r;if(i==0)path.moveTo(x,y);else path.lineTo(x,y);}path.close();if(active)p.setStyle(Paint.Style.FILL);canvas.drawPath(path,p);break;}
            case INFO:case PRIVACY:canvas.drawCircle(12,12,9,p);canvas.drawLine(12,11,12,17,p);p.setStyle(Paint.Style.FILL);canvas.drawCircle(12,7,1.2f,p);break;
            case SYNC:case UPDATE:canvas.drawArc(4,4,20,20,35,140,false,p);canvas.drawArc(4,4,20,20,215,140,false,p);canvas.drawLine(4,12,4,17,p);canvas.drawLine(4,17,9,17,p);canvas.drawLine(20,12,20,7,p);canvas.drawLine(20,7,15,7,p);break;
            case DOWNLOAD:canvas.drawLine(12,3,12,15,p);canvas.drawLine(7,10,12,15,p);canvas.drawLine(12,15,17,10,p);canvas.drawLine(4,17,4,21,p);canvas.drawLine(4,21,20,21,p);canvas.drawLine(20,21,20,17,p);break;
            case CONNECT:canvas.drawRect(2,8,8,16,p);canvas.drawRect(16,8,22,16,p);canvas.drawLine(0,12,2,12,p);canvas.drawLine(22,12,24,12,p);canvas.drawLine(8,10,11,10,p);canvas.drawLine(8,14,11,14,p);canvas.drawLine(13,10,16,10,p);canvas.drawLine(13,14,16,14,p);break;
            case SPLIT:canvas.drawLine(12,21,12,12,p);canvas.drawLine(12,12,5,5,p);canvas.drawLine(12,12,19,5,p);canvas.drawLine(5,5,5,10,p);canvas.drawLine(5,5,10,5,p);canvas.drawLine(19,5,14,5,p);canvas.drawLine(19,5,19,10,p);break;
            case SOURCE:canvas.drawOval(3,3,21,9,p);canvas.drawArc(3,9,21,15,0,180,false,p);canvas.drawArc(3,15,21,21,0,180,false,p);canvas.drawLine(3,6,3,18,p);canvas.drawLine(21,6,21,18,p);break;
            case TARGET:canvas.drawCircle(12,12,9,p);canvas.drawOval(8,3,16,21,p);canvas.drawLine(3,12,21,12,p);break;
            case TELEGRAM:{p.setColor(Color.rgb(71,171,225));p.setStyle(Paint.Style.FILL);path.reset();path.moveTo(2,10);path.lineTo(22,3);path.lineTo(17,21);path.lineTo(11,15);path.lineTo(7,19);path.lineTo(8,13);path.close();canvas.drawPath(path,p);p.setColor(Color.WHITE);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);canvas.drawLine(8,13,18,7,p);break;}
        }
        canvas.restore();if(kind==Kind.UPDATE&&active){p.setColor(Color.rgb(255,89,107));p.setStyle(Paint.Style.FILL);canvas.drawCircle(getWidth()-8*d,8*d,3.5f*d,p);}
        if(!caption.isEmpty()){labelPaint.setColor(active?Color.rgb(70,226,184):getCurrentTextColor());labelPaint.setTextAlign(Paint.Align.CENTER);canvas.drawText(visibleCaption,getWidth()/2f,getHeight()-7*d-labelPaint.descent(),labelPaint);}
    }
}

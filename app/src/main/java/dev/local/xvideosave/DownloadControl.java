package dev.local.xvideosave;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Compact download control drawn to match the player's white line icons. */
final class DownloadControl extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private String status="保存视频";
    DownloadControl(Context context){
        super(context);setClickable(true);setLongClickable(true);setFocusable(true);
        setContentDescription("保存视频，长按选择画质");
    }
    void setStatus(String value){status=value;invalidate();}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        float density=getResources().getDisplayMetrics().density;
        float cx=getWidth()/2f,cy=getHeight()/2f;
        paint.setColor(Color.WHITE);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.2f*density);
        paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        if(isPressed()){
            paint.setStyle(Paint.Style.FILL);paint.setColor(0x442f3d47);canvas.drawCircle(cx,cy,19*density,paint);
            paint.setColor(Color.WHITE);paint.setStyle(Paint.Style.STROKE);
        }
        if("保存视频".equals(status)){
            Path icon=new Path();
            icon.moveTo(cx,cy-10*density);icon.lineTo(cx,cy+3*density);
            icon.moveTo(cx-5*density,cy-1*density);icon.lineTo(cx,cy+4*density);icon.lineTo(cx+5*density,cy-1*density);
            icon.moveTo(cx-9*density,cy+6*density);icon.lineTo(cx-9*density,cy+9*density);icon.lineTo(cx+9*density,cy+9*density);icon.lineTo(cx+9*density,cy+6*density);
            canvas.drawPath(icon,paint);
        }else{
            paint.setStyle(Paint.Style.FILL);paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(9*density);
            String label=status.length()>7?status.substring(0,7):status;
            Paint.FontMetrics fm=paint.getFontMetrics();canvas.drawText(label,cx,cy-(fm.ascent+fm.descent)/2,paint);
        }
    }
}

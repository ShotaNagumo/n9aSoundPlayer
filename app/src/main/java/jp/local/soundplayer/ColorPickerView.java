package jp.local.soundplayer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/** HSV saturation/value picker. The hue slider is supplied by the settings page. */
public final class ColorPickerView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] hsv = new float[3];
    private Runnable listener;
    public ColorPickerView(Context context, int color) { super(context); Color.colorToHSV(color, hsv); setContentDescription("彩度と明度を選択"); }
    public int color() { return Color.HSVToColor(hsv); }
    public void hue(float hue) { hsv[0] = hue; invalidate(); if (listener != null) listener.run(); }
    public float hue() { return hsv[0]; }
    public void onChange(Runnable listener) { this.listener = listener; }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        paint.setShader(new LinearGradient(0, 0, w, 0, Color.WHITE, Color.HSVToColor(new float[]{hsv[0],1,1}), Shader.TileMode.CLAMP));
        canvas.drawRect(0,0,w,h,paint);
        paint.setShader(new LinearGradient(0, 0, 0, h, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP));
        canvas.drawRect(0,0,w,h,paint);
        paint.setShader(null); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3); paint.setColor(Color.WHITE);
        canvas.drawCircle(hsv[1]*w,(1-hsv[2])*h,12,paint); paint.setStyle(Paint.Style.FILL);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
            getParent().requestDisallowInterceptTouchEvent(true);
            hsv[1] = Math.max(0,Math.min(1,event.getX()/getWidth()));
            hsv[2] = 1-Math.max(0,Math.min(1,event.getY()/getHeight()));
            invalidate(); if(listener != null) listener.run(); return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP) { performClick(); return true; }
        return super.onTouchEvent(event);
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}

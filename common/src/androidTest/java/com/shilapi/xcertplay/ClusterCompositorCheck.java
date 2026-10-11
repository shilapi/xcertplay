package com.shilapi.xcertplay;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import com.shilapi.xcertplay.airplay.*;
import com.shilapi.xcertplay.media.MediaCodecSupport;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import kotlin.Unit;

public class ClusterCompositorCheck extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try { runCheck(); result.putString("stream", "OK: phone background preserved, rounded map and local gauges composed; no preview Surface\n"); finish(Activity.RESULT_OK, result); }
        catch (Throwable e) { android.util.Log.e("ClusterCompositorCheck", "FAIL", e); result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(e)); finish(Activity.RESULT_CANCELED, result); }
    }
    private void runCheck() throws Exception {
        Context context = getTargetContext();
        CarPlayOemThemeRuntime runtime = CarPlayOemAssetDeployment.INSTANCE.prepare(new File(context.getCacheDir(), "theme"), name -> {
            try { return context.getAssets().open("carplay-ultra/" + name); } catch (IOException e) { throw new RuntimeException(e); }
        }, message -> Unit.INSTANCE);
        Map<String,Object> values = new HashMap<>(); values.put("vehicleSpeed", 88.0); values.put("engineRPM", 3500.0);
        values.put("vehicleSpeedUnit", 1032L); values.put("fuelLevel", 70.0);
        runtime.updateVehicleValues(values);
        AtomicReference<byte[]> config = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        LinkedBlockingQueue<byte[]> frames = new LinkedBlockingQueue<>();
        CarPlayClusterVideoCompositor compositor = new CarPlayClusterVideoCompositor(context, runtime, 1920, 720,
            () -> true, data -> { config.set(data); return Unit.INSTANCE; },
            (data, pts) -> { frames.offer(data); return Unit.INSTANCE; }, failure -> { error.set(failure); return Unit.INSTANCE; });
        try {
            byte[] localOnly = frames.poll(10, TimeUnit.SECONDS);
            if (error.get() != null) throw new AssertionError(error.get());
            if (localOnly == null || config.get() == null) throw new AssertionError("No independently encoded local gauges");
            compositor.remoteActive(true);
            Canvas canvas = compositor.getInputSurface().lockCanvas(null);
            canvas.drawColor(Color.rgb(180, 40, 40));
            android.graphics.Paint mapPaint = new android.graphics.Paint();
            mapPaint.setColor(Color.rgb(40, 170, 80));
            // The actual Ultra phone stream already renders the map's asset-defined round corners.
            canvas.drawRoundRect(660, 120, 1260, 600, 56, 56, mapPaint);
            compositor.getInputSurface().unlockCanvasAndPost(canvas);
            Thread.sleep(300);
            frames.clear(); compositor.requestKeyFrame();
            byte[] key = null; long end = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < end) {
                byte[] candidate = frames.poll(1, TimeUnit.SECONDS);
                if (candidate != null && MediaCodecSupport.INSTANCE.lengthPrefixedHeaders(candidate).stream().anyMatch(n -> (n[0] & 31) == 5)) { key = candidate; break; }
            }
            if (key == null) throw new AssertionError("No requested key frame");
            Bitmap bitmap = decode(config.get(), key);
            try (FileOutputStream imageFile = new FileOutputStream(new File(context.getFilesDir(), "cluster-composed.png"))) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, imageFile);
            }
            // Keep the map region and phone background beneath the locally drawn instruments.
            int green = 0, local = 0;
            for (int y=0; y<bitmap.getHeight(); y+=4) for (int x=0; x<bitmap.getWidth(); x+=4) {
                int c=bitmap.getPixel(x,y); if (Color.green(c)>110 && Color.green(c)>Color.red(c)*2 && Color.green(c)>Color.blue(c)*1.5) green++; else local++;
            }
            if (green < 200 || local < 2000) throw new AssertionError("Missing phone/local composition: green="+green+" local="+local);
            // The asset's DCA rectangle is (660,120,600,480), radius 56. A full-frame
            // phone texture must only show through that rounded window, never outside it.
            if (!isGreen(bitmap.getPixel(960,360))) throw new AssertionError("Map window is missing");
            for (int[] point : new int[][]{{640,360},{1280,360},{960,100},{960,620},{665,125},{1255,125},{665,595},{1255,595}}) {
                if (isGreen(bitmap.getPixel(point[0],point[1]))) throw new AssertionError("Map leaked outside rounded asset mask at "+Arrays.toString(point));
            }
            for (int[] point : new int[][]{{665,125},{1255,125},{665,595},{1255,595}}) {
                int c = bitmap.getPixel(point[0],point[1]);
                if (!(Color.red(c)>110 && Color.red(c)>Color.green(c)*2 && Color.red(c)>Color.blue(c)*2)) {
                    throw new AssertionError("Map corner background replaced by opaque mask at "+Arrays.toString(point));
                }
            }
            int background = bitmap.getPixel(960,700);
            if (!(Color.red(background)>110 && Color.red(background)>Color.green(background)*2 && Color.red(background)>Color.blue(background)*2)) {
                throw new AssertionError("Phone video background was covered below instruments");
            }
            compositor.remoteActive(false);
            frames.clear();
            if (frames.poll(5,TimeUnit.SECONDS)==null) throw new AssertionError("Gauges stopped when phone video stopped");
            if (error.get()!=null) throw new AssertionError(error.get());
            android.util.Log.i("ClusterCompositorCheck", "PASS green="+green+" local="+local);
        } finally { compositor.close(); }
    }
    private Bitmap decode(byte[] config, byte[] frame) throws Exception {
        kotlin.Pair<byte[],byte[]> sets = MediaCodecSupport.INSTANCE.avcParameterSets(config);
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,1920,720);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(concat(sets.getFirst())));
        format.setByteBuffer("csd-1", ByteBuffer.wrap(concat(sets.getSecond())));
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        MediaCodec decoder=MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        try {
            decoder.configure(format,null,null,0); decoder.start();
            int index=decoder.dequeueInputBuffer(1000000); if(index<0)throw new AssertionError("Decoder input stalled");
            byte[] annex=MediaCodecSupport.INSTANCE.toAnnexB(frame,4,true);
            decoder.getInputBuffer(index).put(annex);decoder.queueInputBuffer(index,0,annex.length,1,0);
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();long end=System.currentTimeMillis()+5000;
            while(System.currentTimeMillis()<end) {
                int out=decoder.dequeueOutputBuffer(info,100000);
                if(out<0)continue;
                Image image=decoder.getOutputImage(out); if(image==null)throw new AssertionError("No decoded image");
                Bitmap bitmap=Bitmap.createBitmap(image.getWidth(),image.getHeight(),Bitmap.Config.ARGB_8888);
                Image.Plane[] p=image.getPlanes(); int[] pixels=new int[image.getWidth()*image.getHeight()];
                for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++) {
                    int yy=sample(p[0],x,y),u=sample(p[1],x/2,y/2)-128,v=sample(p[2],x/2,y/2)-128;
                    int c=Math.max(0,yy-16); pixels[y*image.getWidth()+x]=Color.rgb(clamp((298*c+409*v+128)>>8),clamp((298*c-100*u-208*v+128)>>8),clamp((298*c+516*u+128)>>8));
                }
                bitmap.setPixels(pixels,0,image.getWidth(),0,0,image.getWidth(),image.getHeight());image.close();decoder.releaseOutputBuffer(out,false);return bitmap;
            }
            throw new AssertionError("Composed video could not be decoded");
        } finally { try { decoder.stop(); } finally { decoder.release(); } }
    }
    private static int sample(Image.Plane p,int x,int y){return p.getBuffer().get(y*p.getRowStride()+x*p.getPixelStride())&255;}
    private static int clamp(int v){return Math.max(0,Math.min(255,v));}
    private static boolean isGreen(int c){return Color.green(c)>110 && Color.green(c)>Color.red(c)*2 && Color.green(c)>Color.blue(c)*1.5;}
    private static byte[] concat(byte[] data){byte[] result=new byte[data.length+4];result[3]=1;System.arraycopy(data,0,result,4,data.length);return result;}
}

// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.linker2k.myembro.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.ui.unit.IntSize
import io.github.linker2k.myembro.engine.model.EmbPattern
import io.github.linker2k.myembro.engine.render.realistic.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.math.*

internal data class ViewerRealisticPlan(val width:Int,val height:Int,val unitsToPixels:Float,
    val centerX:Float,val centerY:Float,val hoopWidth:Float,val hoopHeight:Float,
    val background:Int,val fabric:Int,val hoopCenterX:Float=centerX,val hoopCenterY:Float=centerY,
    val originXUnits:Double?=null,val originYUnits:Double?=null,val renderContent:Boolean=true) {
    val leftUnits get()=originXUnits ?: (centerX-width/(2.0*unitsToPixels))
    val topUnits get()=originYUnits ?: (centerY-height/(2.0*unitsToPixels))
    val svgScale get()=unitsToPixels*10.0/ThreadShape.SVG_PIXELS_PER_MM
    val dpi get()=(svgScale*96).roundToInt().coerceAtLeast(1)
    val outputBytes get()=width.toLong()*height*4
    // Deliberate first-app budget: NO retained tile cache. Exact cache keys have additional
    // object overhead not bounded by a pixel-only allowance. Engine bounded tiles still apply.
    val cachePixelBudgetBytes get()=0L
}
internal data class ViewerRealisticImage(val bitmap:Bitmap,val plan:ViewerRealisticPlan,val renderMs:Double,
    val maxTilePixels:Int,val nativeCrop:RectF?=null,private val release:(()->Unit)?=null) {
    private val closed=AtomicBoolean(false)
    fun close(){if(closed.compareAndSet(false,true)){if(release!=null)release.invoke() else if(!bitmap.isRecycled)bitmap.recycle()}}
}

/** API26 software coverage. One reusable scratch bitmap + one exact-size alpha array, never a page copy. */
private class AndroidThreadAlpha:ThreadAlphaRasterizer,AutoCloseable {
    private var bitmap:Bitmap?=null
    private val path=Path()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE;style=Paint.Style.FILL }
    var maxPixels=0;private set
    override fun rasterize(commands:List<ThreadPathCommand>,width:Int,height:Int):IntArray {
        val count=Math.multiplyExact(width,height);maxPixels=max(maxPixels,count)
        var target=bitmap
        if(target==null || target.allocationByteCount<count*4) {
            target?.recycle();target=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);bitmap=target
        } else if(target.width!=width || target.height!=height)target.reconfigure(width,height,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(target)
        canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR)
        path.reset()
        for(command in commands)when(command) {
            is ThreadPathCommand.Move->path.moveTo(command.p.x.toFloat(),command.p.y.toFloat())
            is ThreadPathCommand.Line->path.lineTo(command.p.x.toFloat(),command.p.y.toFloat())
            is ThreadPathCommand.Cubic->path.cubicTo(command.a.x.toFloat(),command.a.y.toFloat(),command.b.x.toFloat(),command.b.y.toFloat(),command.end.x.toFloat(),command.end.y.toFloat())
            ThreadPathCommand.Close->path.close()
        }
        canvas.drawPath(path,paint)
        return IntArray(count).also { pixels->
            target.getPixels(pixels,0,width,0,0,width,height)
            for(i in pixels.indices)pixels[i]=pixels[i] ushr 24
        }
    }
    override fun close() { bitmap?.recycle();bitmap=null }
}

/** Same background/rounded hoop fabric for the native source and the resolved viewport. */
private fun drawViewerFabric(canvas:Canvas,plan:ViewerRealisticPlan) {
    canvas.drawColor(plan.background)
    val fit=plan.unitsToPixels
    val fabric=Paint().apply { color=plan.fabric }
    canvas.drawRoundRect(((plan.hoopCenterX-plan.hoopWidth/2-plan.leftUnits)*fit).toFloat(),
        ((plan.hoopCenterY-plan.hoopHeight/2-plan.topUnits)*fit).toFloat(),
        ((plan.hoopCenterX+plan.hoopWidth/2-plan.leftUnits)*fit).toFloat(),
        ((plan.hoopCenterY+plan.hoopHeight/2-plan.topUnits)*fit).toFloat(),60f*fit,60f*fit,fabric)
}

/** Render only: never writes pattern, source bytes, sewing gates or export settings. */
private fun renderNativeViewerRealistic(pattern:EmbPattern,plan:ViewerRealisticPlan,isCancelled:()->Boolean):ViewerRealisticImage {
    require(plan.width>0&&plan.height>0&&plan.unitsToPixels>0)
    val started=System.nanoTime()
    val bitmap=Bitmap.createBitmap(plan.width,plan.height,Bitmap.Config.ARGB_8888).apply { density=Bitmap.DENSITY_NONE }
    try {
        val canvas=Canvas(bitmap)
        drawViewerFabric(canvas,plan)
        var scratch=IntArray(0)
        var maxTile=0
        AndroidThreadAlpha().use { alpha ->
            val segments=if(plan.renderContent)PatternThreadSegments.from(pattern,plan.leftUnits,plan.topUnits).asIterable() else emptyList()
            RealisticThreadRenderer.renderTiles(FixedRealisticPreview.dpi,segments,alpha,cache=null,pixelsPerSvgUnit=FixedRealisticPreview.svgScale,
                isCancelled=isCancelled) { left,top,width,height,tile ->
                val x=max(0,left);val y=max(0,top);val right=min(plan.width,left+width);val bottom=min(plan.height,top+height)
                if(right>x && bottom>y) {
                    val w=right-x;val h=bottom-y
                    if(scratch.size<w*h)scratch=IntArray(w*h)
                    bitmap.getPixels(scratch,0,w,x,y,w,h)
                    for(yy in 0 until h)for(xx in 0 until w) {
                        val i=yy*w+xx
                        scratch[i]=RealisticThreadRenderer.compositeOpaque(scratch[i],tile[(y-top+yy)*width+x-left+xx])
                    }
                    bitmap.setPixels(scratch,0,w,x,y,w,h)
                }
            }
            maxTile=alpha.maxPixels
        }
        if(isCancelled())throw java.util.concurrent.CancellationException("Preview cancelled")
        val elapsed=(System.nanoTime()-started)/1_000_000.0
        Log.i("RealisticPreview","ready stitches=${pattern.stitchCount} size=${plan.width}x${plan.height} dpi=${plan.dpi} ms=$elapsed outputBytes=${plan.outputBytes} cacheBytes=${plan.cachePixelBudgetBytes} maxTilePixels=$maxTile")
        return ViewerRealisticImage(bitmap,plan,elapsed,maxTile)
    } catch(failure:Throwable) { bitmap.recycle();throw failure }
}

/** A closeable lease: native pixels outlive both cancellation and a displayed native crop. */
private class NativePreviewCache {
    private var master:ViewerRealisticImage?=null
    private var users=0
    private var closed=false
    @Synchronized fun acquire():ViewerRealisticImage? {
        if(closed)throw java.util.concurrent.CancellationException("Preview disposed")
        return master?.also { users++ }
    }
    @Synchronized fun install(value:ViewerRealisticImage):ViewerRealisticImage {
        if(closed){value.close();throw java.util.concurrent.CancellationException("Preview disposed")}
        if(master==null)master=value else value.close()
        users++;return master!!
    }
    @Synchronized fun release(){check(users>0);users--;if(closed&&users==0){master?.close();master=null}}
    @Synchronized fun close(){closed=true;if(users==0){master?.close();master=null}}
}

internal fun nativePlan(pattern:EmbPattern,plan:ViewerRealisticPlan):ViewerRealisticPlan {
    val b=pattern.bounds
    val hoopLeft=plan.hoopCenterX-plan.hoopWidth/2.0
    val hoopTop=plan.hoopCenterY-plan.hoopHeight/2.0
    val left=max(hoopLeft,b?.minX?.toDouble() ?: hoopLeft)
    val top=max(hoopTop,b?.minY?.toDouble() ?: hoopTop)
    val right=min(plan.hoopCenterX+plan.hoopWidth/2.0,b?.maxX?.toDouble() ?: hoopLeft)
    val bottom=min(plan.hoopCenterY+plan.hoopHeight/2.0,b?.maxY?.toDouble() ?: hoopTop)
    val nativeUnits=FixedRealisticPreview.PIXELS_PER_MM/10
    val intersects=right>=left&&bottom>=top
    val x=if(intersects)left else hoopLeft;val y=if(intersects)top else hoopTop
    val width=if(intersects)ceil((right-left)*nativeUnits).toInt().coerceAtLeast(1) else 1
    val height=if(intersects)ceil((bottom-top)*nativeUnits).toInt().coerceAtLeast(1) else 1
    return plan.copy(width=width,height=height,unitsToPixels=nativeUnits.toFloat(),
        centerX=(x+width/(2*nativeUnits)).toFloat(),centerY=(y+height/(2*nativeUnits)).toFloat(),
        originXUnits=x,originYUnits=y,renderContent=intersects&&b!=null)
}

// 미측정, 체감용: owner-requested gesture settling delay, not a sewing/rendering limit.
internal const val REALISTIC_GESTURE_DEBOUNCE_MS=100L

/** Keep a completed image even when its camera plan is stale, until its replacement is ready. */
internal fun displayedRealisticImage(enabled:Boolean,image:ViewerRealisticImage?):ViewerRealisticImage? =
    image?.takeIf { enabled&&!it.bitmap.isRecycled }

/** Direct entry point also used by JVM/Android tests. */
internal fun renderViewerRealistic(pattern:EmbPattern,plan:ViewerRealisticPlan,isCancelled:()->Boolean):ViewerRealisticImage {
    val cache=NativePreviewCache()
    return try { resolveViewerRealistic(pattern,plan,plan,cache,isCancelled) } finally { cache.close() }
}

private fun resolveViewerRealistic(pattern:EmbPattern,base:ViewerRealisticPlan,output:ViewerRealisticPlan,
    cache:NativePreviewCache,isCancelled:()->Boolean):ViewerRealisticImage {
    val started=System.nanoTime()
    val master=cache.acquire() ?: cache.install(renderNativeViewerRealistic(pattern,nativePlan(pattern,base),isCancelled))
    var held=true
    try {
        val ppm=output.unitsToPixels*10.0
        val nativeUnits=FixedRealisticPreview.PIXELS_PER_MM/10
        val left=(output.leftUnits-master.plan.leftUnits)*nativeUnits
        val top=(output.topUnits-master.plan.topUnits)*nativeUnits
        val bitmap=FixedRealisticPreview.output(ppm,master.bitmap) {
            val target=Bitmap.createBitmap(output.width,output.height,Bitmap.Config.ARGB_8888).apply { density=Bitmap.DENSITY_NONE }
            try {
                drawViewerFabric(Canvas(target),output)
                FixedRealisticPreview.areaAverage(master.bitmap.width,master.bitmap.height,
                    { y,row->master.bitmap.getPixels(row,0,row.size,0,y,row.size,1) },left,top,
                    FixedRealisticPreview.PIXELS_PER_MM/ppm,output.width,output.height,output.background,
                    isCancelled,backgroundRow={y,row->target.getPixels(row,0,row.size,0,y,row.size,1)}) { y,row->target.setPixels(row,0,row.size,0,y,row.size,1) }
                target
            }catch(failure:Throwable){target.recycle();throw failure}
        }
        val original=bitmap===master.bitmap
        val result=ViewerRealisticImage(bitmap,output,(System.nanoTime()-started)/1_000_000.0,
            master.maxTilePixels,if(original)RectF(left.toFloat(),top.toFloat(),
                (left+output.width*FixedRealisticPreview.PIXELS_PER_MM/ppm).toFloat(),
                (top+output.height*FixedRealisticPreview.PIXELS_PER_MM/ppm).toFloat()) else null,
            if(original)({cache.release()}) else null)
        if(original)held=false
        return result
    } finally { if(held)cache.release() }
}

internal data class ViewerRealisticState(val image:ViewerRealisticImage?,val busy:Boolean,val failed:Boolean,val current:Boolean=true)

@Composable
internal fun rememberViewerRealistic(enabled:Boolean,data:RenderData,size:IntSize,fit:Float,cx:Float,cy:Float,
    hoopW:Float,hoopH:Float,background:Int,fabric:Int,zoom:Float=1f,panX:Float=0f,panY:Float=0f):ViewerRealisticState {
    val basePlan=remember(data,size,fit,cx,cy,hoopW,hoopH,background,fabric) {
        ViewerRealisticPlan(size.width,size.height,fit,cx,cy,hoopW,hoopH,background,fabric)
    }
    // The bounded native source no longer depends on canvas size, fit, zoom or pan.
    val source=remember(data,hoopW,hoopH,cx,cy,background,fabric,enabled){NativePreviewCache()}
    DisposableEffect(source){onDispose{source.close()}}
    val plan=remember(basePlan,zoom,panX,panY){
        val scale=fit*zoom
        basePlan.copy(unitsToPixels=scale,centerX=if(scale>0)cx-panX/scale else cx,
            centerY=if(scale>0)cy-panY/scale else cy)
    }
    var image by remember(data) { mutableStateOf<ViewerRealisticImage?>(null) }
    var busy by remember(data) { mutableStateOf(false) }
    var failed by remember(data) { mutableStateOf(false) }
    var ticket by remember { mutableStateOf<Any?>(null) }
    // Hold through worker completion/pending cleanup: a replacement never allocates a
    // second output while a cancelled worker still owns the first.
    val renderMutex=remember { Mutex() }
    DisposableEffect(image) {
        val owned=image
        onDispose { owned?.close() }
    }
    LaunchedEffect(enabled,data,plan) {
        val mine=Any();ticket=mine
        failed=false;busy=false
        if(!enabled || size.width<3 || size.height<3 || fit<=0) {
            image?.close();image=null
            return@LaunchedEffect
        }
        busy=true
        if(image!=null||zoom!=1f||panX!=0f||panY!=0f)delay(REALISTIC_GESTURE_DEBOUNCE_MS)
        renderMutex.withLock {
        val pending=AtomicReference<ViewerRealisticImage?>(null)
        try {
            val rendered=withContext(Dispatchers.Default) {
                val context=coroutineContext
                resolveViewerRealistic(data.pattern,basePlan,plan,source) { !context.isActive }.also { pending.set(it) }
            }
            coroutineContext.ensureActive()
            if(ticket===mine) { val previous=image;image=rendered;pending.set(null);previous?.close() }
        } catch(cancelled:java.util.concurrent.CancellationException) { throw cancelled }
        catch(_:Exception) { if(ticket===mine)failed=true }
        catch(_:OutOfMemoryError) { if(ticket===mine)failed=true }
        finally {
            pending.getAndSet(null)?.close()
            if(ticket===mine)busy=false
        }
        }
    }
    return ViewerRealisticState(displayedRealisticImage(enabled,image),enabled&&busy,enabled&&failed,image?.plan==plan)
}

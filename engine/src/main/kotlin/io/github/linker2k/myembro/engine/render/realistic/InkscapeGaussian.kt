// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Inkscape src/display/nr-filter-gaussian.cpp and src/util/fixed_point.h
// @ebf0e940d050842d608d57f6a04e6daaa5c65f37 (GPL-2.0-or-later),
// Copyright (C) 2006-2008 Inkscape authors (see THIRD_PARTY_NOTICES.md).
package io.github.linker2k.myembro.engine.render.realistic

import kotlin.math.*

/** Original non-subsampled Gaussian (export BEST), scalar alpha channel only.
 * FIR for sigma<=3, Young–van Vliet IIR with Triggs–Sdika boundaries otherwise.
 * No empirical fitting to reference images; every coefficient below is upstream.
 */
internal object InkscapeGaussian {
    fun blur(input: IntArray, width: Int, height: Int, sigma: Double): IntArray {
        require(width > 0 && height > 0 && input.size == width*height)
        require(sigma.isFinite() && sigma >= 0)
        if (sigma == 0.0) return input.copyOf()
        // nr-filter-gaussian.cpp:619–620 threshold; X then Y at lines 650–668.
        return if (sigma <= 3) fir(fir(input,width,height,sigma,false),width,height,sigma,true)
               else iir(iir(input,width,height,sigma,false),width,height,sigma,true)
    }

    private fun fir(input: IntArray, width: Int, height: Int, sigma: Double, vertical: Boolean): IntArray {
        // _effect_area_scr, _make_kernel:135–168, 16 fractional bits from FIRValue:68.
        val radius=ceil(abs(sigma)*3).toInt()
        val values=DoubleArray(radius+1) { exp(-it.toDouble()*it/(2*sigma*sigma)) }
        var sum=values[0]
        for(i in 1..radius) sum+=2*values[i]
        val fixedOne=1 shl 16
        val kernel=IntArray(radius+1)
        var exactSum=0.0;var fixedSum=0
        for(i in radius downTo 1) {
            exactSum+=values[i]/sum
            kernel[i]=floor((exactSum-fixedSum.toDouble()/fixedOne)*fixedOne).toInt()
            fixedSum+=kernel[i]
        }
        kernel[0]=fixedOne-2*fixedSum
        val output=IntArray(input.size)
        val lines=if(vertical)width else height;val length=if(vertical)height else width
        val stride=if(vertical)width else 1
        for(line in 0 until lines) {
            val offset=if(vertical)line else line*width
            for(i in 0 until length) {
                var weighted=kernel[0]*input[offset+i*stride]
                for(k in 1..radius) weighted+=kernel[k]*(input[offset+(i-k).coerceAtLeast(0)*stride]+input[offset+(i+k).coerceAtMost(length-1)*stride])
                output[offset+i*stride]=(weighted+fixedOne/2)/fixedOne
            }
        }
        return output
    }

    private fun coefficients(sigma: Double): DoubleArray {
        // calcFilter:220–250. Original complex poles; real arithmetic expansion only.
        val poleReal=1.40098;val poleImag=1.00236;val thirdPole=1.85132
        fun poles(q:Double):DoubleArray {
            val r=hypot(poleReal,poleImag).pow(1/q)
            val angle=atan2(poleImag,poleReal)/q
            return doubleArrayOf(r*cos(angle),r*sin(angle),thirdPole.pow(1/q))
        }
        var low=1.0;var high=2*sigma
        do {
            val q=(low+high)/2;val (re,im,d3)=poles(q)
            val denRe=(re-1)*(re-1)-im*im;val denIm=2*(re-1)*im
            val ratio=(re*denRe+im*denIm)/(denRe*denRe+denIm*denIm)
            val variance=2*(2*ratio+d3/((d3-1)*(d3-1)))
            if(variance < sigma*sigma)low=q else high=q
        }while(high-low>sigma/(1 shl 30))
        val(re,im,d3)=poles((low+high)/2)
        val norm=re*re+im*im;val scale=1/(norm*d3)
        // gaussian_pass_IIR:482 negates calcFilter's coefficients.
        return doubleArrayOf(scale*(norm+d3*2*re),-scale*(d3+2*re),scale)
    }

    private fun iir(input: IntArray,width:Int,height:Int,sigma:Double,vertical:Boolean):IntArray {
        val b=coefficients(sigma);val(a1,a2,a3)=b
        val alpha=1-a1-a2-a3
        // calcTriggsSdikaM:252–266.
        val scale=1/((1+a1-a2+a3)*(1-a1-a2-a3)*(1+a2+(a1-a3)*a3))
        val matrix=doubleArrayOf(
            1-a2-a1*a3-a3*a3,(a1+a3)*(a2+a1*a3),a3*(a1+a2*a3),
            a1+a2*a3,(1-a2)*(a2+a1*a3),a3*(1-a2-a1*a3-a3*a3),
            a1*(a1+a3)+a2*(1-a2),a1*(a2-a3*a3)+a3*(1+a2*(a2-1)-a3*a3),a3*(a1+a2*a3),
        ).map { it*scale }
        val output=IntArray(input.size)
        val lines=if(vertical)width else height;val length=if(vertical)height else width
        val stride=if(vertical)width else 1
        for(line in 0 until lines) {
            val offset=if(vertical)line else line*width
            val first=input[offset].toDouble();val last=input[offset+(length-1)*stride].toDouble()
            val u=DoubleArray(4) { first };val tmp=DoubleArray(length)
            for(i in 0 until length) {
                for(j in 3 downTo 1)u[j]=u[j-1]
                u[0]=input[offset+i*stride]*alpha
                for(j in 1..3)u[0]+=u[j]*b[j-1]
                tmp[i]=u[0]
            }
            // calcTriggsSdikaInitialization:269–285 and backward pass:335–359.
            val v=DoubleArray(4)
            for(i in 0..2) {
                for(j in 0..2)v[i]+=(u[j]-last)*matrix[i*3+j]
                v[i]=v[i]*alpha+last
            }
            output[offset+(length-1)*stride]=floor(v[0]+.5).toInt().coerceIn(0,255)
            for(i in length-2 downTo 0) {
                for(j in 3 downTo 1)v[j]=v[j-1]
                v[0]=tmp[i]*alpha
                for(j in 1..3)v[0]+=v[j]*b[j-1]
                output[offset+i*stride]=floor(v[0]+.5).toInt().coerceIn(0,255)
            }
        }
        return output
    }
}

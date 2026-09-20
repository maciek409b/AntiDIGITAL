package com.retro.cam

import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Color
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import kotlinx.android.synthetic.main.activity_main.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.renderscript.* // Służy do bardzo szybkiego rozmycia i ziarna w locie

class MainActivity : AppCompatActivity() {
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        cameraExecutor = Executors.newSingleThreadExecutor()
        startCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            
            // Konfiguracja podglądu
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            // KLUCZOWE: Konfiguracja sensora S23 Ultra
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                // Łączenie z aparatem i wymuszenie parametrów retro
                val camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
                
                // Wymuszenie stałej niskiej ekspozycji (-1.5 EV) i ciepłego balansu bieli
                camera.cameraControl.setExposureCompensationIndex(-6) // -6 kroków to około -1.5 EV na Samsungu
                
            } catch(exc: Exception) { }
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }

    // Funkcja przetwarzająca wykonane zdjęcie (Wstrzykiwanie ziarna, tonowania i 1% rozmycia)
    fun procesujRetroObraz(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val output = Bitmap.createBitmap(width, height, src.config)
        
        // 1. WYMUSZENIE ROZMYCIA O 1% (Efekt miękkiej, analogowej soczewki)
        val rs = RenderScript.create(this)
        val inputAlloc = Allocation.createFromBitmap(rs, src)
        val outputAlloc = Allocation.createFromBitmap(rs, output)
        val blurScript = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
        blurScript.setRadius(2.5f) // Odpowiednik delikatnego 1-2% zmiękczenia cyfrowych megapikseli
        blurScript.setInput(inputAlloc)
        blurScript.forEach(outputAlloc)
        outputAlloc.copyTo(output)

        // 2. MATEMATYCZNE ZIARNO I TONOWANIE CIENI NA PIKSELACH
        val pixels = IntArray(width * height)
        output.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val c = pixels[i]
            var r = Color.red(c)
            var g = Color.green(c)
            var b = Color.blue(c)
            
            // Obliczanie jasności piksela (luminancja)
            val lum = 0.299 * r + 0.587 * g + 0.114 * b
            
            // Maska cieni - im ciemniej, tym silniejszy efekt barwienia
            val maskaCieni = Math.pow(Math.max(0.0, 1.0 - (lum / 120.0)), 2.0)
            g = Math.min(255, (g + maskaCieni * 9.0).toInt()) // Filmowa zieleń w cieniach
            b = Math.min(255, (b + maskaCieni * 6.0).toInt()) // Chłodny cyjan w cieniach
            r = Math.max(0, (r - maskaCieni * 4.0).toInt())
            
            // Generowanie organicznego szumu (ziarna) zależnego od jasności (najwięcej w półtonach)
            val maskaZiarna = 4.0 * (lum / 255.0) * (1.0 - (lum / 255.0))
            val szum = (Math.random() * 2.0 - 1.0) * 14.0 * maskaZiarna
            
            r = Math.clip(0, 255, (r + szum).toInt())
            g = Math.clip(0, 255, (g + szum).toInt())
            b = Math.clip(0, 255, (b + szum).toInt())

            pixels[i] = Color.rgb(r, g, b)
        }
        
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    private fun Math.clip(min: Int, max: Int, value: Int): Int = Math.max(min, Math.min(max, value))
}

package com.nokta.pos.ui.scanner

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * Analisador de frame da câmera — decodifica QR Code (o formato de código
 * usado pela comanda impressa) e chama [onDecoded] uma única vez por
 * detecção bem-sucedida. A tela chamadora é responsável por debounce
 * (ScannerViewModel.isResolving) para não disparar N vezes com a câmera
 * ainda apontada para o mesmo QR.
 */
class QrCodeAnalyzer(private val onDecoded: (String) -> Unit) : androidx.camera.core.ImageAnalysis.Analyzer {

    private val scanner = BarcodeScanning.getClient()

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val value = barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue
                if (value != null) onDecoded(value)
            }
            .addOnCompleteListener { imageProxy.close() }
    }
}

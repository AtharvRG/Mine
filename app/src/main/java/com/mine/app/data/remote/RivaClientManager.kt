package com.mine.app.data.remote

import android.util.Log
import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.stub.MetadataUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

import nvidia.riva.asr.RivaAsr.RecognitionConfig
import nvidia.riva.asr.RivaAsr.RecognizeRequest
import nvidia.riva.asr.RivaSpeechRecognitionGrpc
import nvidia.riva.RivaAudio.AudioEncoding

class RivaClientManager @Inject constructor() {

    private val host = "grpc.nvcf.nvidia.com"
    private val port = 443
    private val functionId = "b702f636-f60c-4a3d-a6f4-f3568c13bd7d"

    suspend fun transcribeAudio(apiKey: String, audioBytes: ByteArray): String = withContext(Dispatchers.IO) {
        val channel = ManagedChannelBuilder.forAddress(host, port)
            .useTransportSecurity()
            .build()

        try {
            val headers = Metadata().apply {
                put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer $apiKey")
                put(Metadata.Key.of("function-id", Metadata.ASCII_STRING_MARSHALLER), functionId)
            }

            val interceptor = MetadataUtils.newAttachHeadersInterceptor(headers)
            // Back to Blocking Stub for Offline processing!
            val blockingStub = RivaSpeechRecognitionGrpc.newBlockingStub(channel).withInterceptors(interceptor)

            val config = RecognitionConfig.newBuilder()
                .setLanguageCode("en-US") // NVIDIA requires en-US
                .setEncoding(AudioEncoding.LINEAR_PCM) // Raw PCM
                .setSampleRateHertz(16000)
                .setAudioChannelCount(1)
                .build()

            val request = RecognizeRequest.newBuilder()
                .setConfig(config)
                .setAudio(com.google.protobuf.ByteString.copyFrom(audioBytes))
                .build()

            Log.d("RivaClient", "Sending ${audioBytes.size} bytes to NVIDIA Riva Offline...")

            // This blocks until the transcript is ready
            val response = blockingStub.recognize(request)

            val transcriptBuilder = java.lang.StringBuilder()
            for (i in 0 until response.resultsCount) {
                val result = response.getResults(i)
                if (result.alternativesCount > 0) {
                    transcriptBuilder.append(result.getAlternatives(0).transcript).append(" ")
                }
            }

            val finalTranscript = transcriptBuilder.toString().trim()
            Log.d("RivaClient", "Riva Transcript: $finalTranscript")
            return@withContext finalTranscript

        } catch (e: Exception) {
            Log.e("RivaClient", "Network Error calling Riva", e)
            throw e
        } finally {
            channel.shutdown()
        }
    }
}

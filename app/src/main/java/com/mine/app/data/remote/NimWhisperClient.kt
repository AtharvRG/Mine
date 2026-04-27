package com.mine.app.data.remote

import io.grpc.ManagedChannelBuilder
import io.grpc.Metadata
import io.grpc.stub.MetadataUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nvidia.riva.RivaAudio.AudioEncoding
import nvidia.riva.asr.RivaAsr.RecognitionConfig
import nvidia.riva.asr.RivaAsr.RecognizeRequest
import nvidia.riva.asr.RivaSpeechRecognitionGrpcKt
import javax.inject.Inject



class NimWhisperClient @Inject constructor() {

    private val host = "grpc.nvcf.nvidia.com"
    private val port = 443
    private val functionId = "b702f636-f60c-4a3d-a6f4-f3568c13bd7d"

    suspend fun transcribeAudio(apiKey: String, audioBytes: ByteArray): String = withContext(Dispatchers.IO) {
        // 1. Build the Secure Channel
        val channel = ManagedChannelBuilder.forAddress(host, port)
            .useTransportSecurity() // Required for port 443
            .build()

        try {
            // 2. Attach the required NVIDIA Metadata Headers
            val metadata = Metadata().apply {
                put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer $apiKey")
                put(Metadata.Key.of("function-id", Metadata.ASCII_STRING_MARSHALLER), functionId)
            }

            // 3. Create the Stub with the Interceptor
            val stub = RivaSpeechRecognitionGrpcKt.RivaSpeechRecognitionCoroutineStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))

            // 4. Configure the Whisper Model (The maxAlternatives bug fix is here!)
            val config = RecognitionConfig.newBuilder()
                .setEncoding(AudioEncoding.LINEAR_PCM)
                .setSampleRateHertz(16000)
                .setLanguageCode("en")
                .setAudioChannelCount(1)
                .setMaxAlternatives(1)
                .setEnableAutomaticPunctuation(true)
                .build()

            // 5. Build the Request
            val request = RecognizeRequest.newBuilder()
                .setConfig(config)
                .setAudio(com.google.protobuf.ByteString.copyFrom(audioBytes))
                .build()

            // 6. Execute the gRPC Call
            val response = stub.recognize(request)

            // 7. Extract the transcript
            if (response.resultsList.isNotEmpty() && response.resultsList[0].alternativesList.isNotEmpty()) {
                return@withContext response.resultsList[0].alternativesList[0].transcript
            } else {
                return@withContext ""
            }
        } finally {
            channel.shutdown()
        }
    }
}

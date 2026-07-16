package com.nexora.mobile

import android.content.Context
import coil3.ImageLoader
import coil3.bitmapFactoryMaxParallelism
import coil3.decode.BitmapFactoryDecoder
import coil3.request.CachePolicy
import coil3.serviceLoaderEnabled
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody

internal fun createSecurePosterImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
    .serviceLoaderEnabled(false)
    .components {
        add(
            OkHttpNetworkFetcherFactory(
                callFactory = { createSecurePosterHttpClient() },
            ),
        )
        add(BitmapFactoryDecoder.Factory())
    }
    .diskCache(null)
    .diskCachePolicy(CachePolicy.DISABLED)
    .logger(null)
    .bitmapFactoryMaxParallelism(2)
    .build()

internal fun createSecurePosterHttpClient(
    dns: Dns = PublicOnlyPosterDns(),
): OkHttpClient = OkHttpClient.Builder()
    .dns(dns)
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .callTimeout(POSTER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .connectTimeout(POSTER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .readTimeout(POSTER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .addInterceptor(HttpsOnlyPosterInterceptor)
    .addInterceptor(LimitedPosterBodyInterceptor)
    .build()

internal class PublicOnlyPosterDns(
    private val delegate: Dns = Dns.SYSTEM,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (addresses.isEmpty() || addresses.any(::isForbiddenPosterAddress)) {
            throw UnknownHostException("Poster host is not public")
        }
        return addresses
    }
}

internal fun isForbiddenPosterAddress(address: InetAddress): Boolean {
    val bytes = address.address
    val carrierGradeNat = bytes.size == 4 &&
        (bytes[0].toInt() and 0xff) == 100 &&
        (bytes[1].toInt() and 0xc0) == 64
    val uniqueLocalIpv6 = bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
    return address.isAnyLocalAddress ||
        address.isLoopbackAddress ||
        address.isLinkLocalAddress ||
        address.isSiteLocalAddress ||
        address.isMulticastAddress ||
        carrierGradeNat ||
        uniqueLocalIpv6
}

private object HttpsOnlyPosterInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!chain.request().url.isHttps) throw IOException("Poster request must use HTTPS")
        return chain.proceed(chain.request())
    }
}

private object LimitedPosterBodyInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = response.body
        return try {
            response.newBuilder()
                .body(body.readWithinLimit(MAX_POSTER_RESPONSE_BYTES))
                .build()
        } catch (failure: IOException) {
            response.close()
            throw failure
        }
    }
}

internal fun ResponseBody.readWithinLimit(maxBytes: Long): ResponseBody {
    if (contentLength() > maxBytes) throw IOException("Poster response exceeds limit")
    val bufferedSource = source()
    if (bufferedSource.request(maxBytes + 1L)) throw IOException("Poster response exceeds limit")
    val contentType = contentType()
    return bufferedSource.readByteArray().toResponseBody(contentType)
}

private const val POSTER_TIMEOUT_SECONDS: Long = 10L
private const val MAX_POSTER_RESPONSE_BYTES: Long = 4L * 1024L * 1024L

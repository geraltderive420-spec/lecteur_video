package com.lecteur.feature.cast.stream

import android.content.Context
import android.net.Uri
import com.lecteur.core.common.cast.http.StreamOpener
import com.lecteur.core.common.cast.http.StreamSource
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/** Opens a shared file through its content (or file) URI, positioned at the requested byte. */
class ContentResolverStreamOpener(private val context: Context) : StreamOpener {

    @Throws(IOException::class)
    override fun open(source: StreamSource, offset: Long): InputStream {
        val uri = Uri.parse(source.id)
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("cannot open ${source.fileName}")
        val stream = FileInputStream(descriptor.fileDescriptor)
        return try {
            // A seekable file (the normal case) jumps straight to the offset; a pipe has to be read through.
            stream.channel.position(offset)
            ParcelBackedStream(stream, descriptor)
        } catch (e: IOException) {
            runCatching { descriptor.close() }
            throw e
        }
    }

    /** Closing the stream must close the descriptor too, or every seek of the player leaks a file handle. */
    private class ParcelBackedStream(
        private val inner: FileInputStream,
        private val descriptor: android.os.ParcelFileDescriptor
    ) : InputStream() {
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
        override fun available(): Int = inner.available()
        override fun close() {
            runCatching { inner.close() }
            runCatching { descriptor.close() }
        }
    }
}

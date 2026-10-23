/**************************************************************************/
/*  DataAccess.kt                                                         */
/**************************************************************************/
/*                         This file is part of:                          */
/*                             GODOT ENGINE                               */
/*                        https://godotengine.org                         */
/**************************************************************************/
/* Copyright (c) 2014-present Godot Engine contributors (see AUTHORS.md). */
/* Copyright (c) 2007-2014 Juan Linietsky, Ariel Manzur.                  */
/*                                                                        */
/* Permission is hereby granted, free of charge, to any person obtaining  */
/* a copy of this software and associated documentation files (the        */
/* "Software"), to deal in the Software without restriction, including    */
/* without limitation the rights to use, copy, modify, merge, publish,    */
/* distribute, sublicense, and/or sell copies of the Software, and to     */
/* permit persons to whom the Software is furnished to do so, subject to  */
/* the following conditions:                                              */
/*                                                                        */
/* The above copyright notice and this permission notice shall be         */
/* included in all copies or substantial portions of the Software.        */
/*                                                                        */
/* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,        */
/* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF     */
/* MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. */
/* IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY   */
/* CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,   */
/* TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE      */
/* SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.                 */
/**************************************************************************/

package org.godotengine.godot.io.file

import android.content.Context
import android.os.Build
import android.util.Log
import org.godotengine.godot.error.Error
import org.godotengine.godot.io.StorageScope
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.channels.NonWritableChannelException
import kotlin.jvm.Throws
import kotlin.math.max

/**
 * Base class for file IO operations.
 *
 * Its derived instances provide concrete implementations to handle regular file access, as well
 * as file access through the media store API on versions of Android were scoped storage is enabled.
 */
internal abstract class DataAccess {

	companion object {
		private val TAG = DataAccess::class.java.simpleName

		/** Enginehost: the read-ahead window of a file opened for reading only (FileChannelDataAccess). */
		private const val READ_AHEAD = 64 * 1024

		@Throws(java.lang.Exception::class, FileNotFoundException::class)
		fun getInputStream(storageScope: StorageScope, context: Context, filePath: String): InputStream? {
			return when(storageScope) {
				StorageScope.ASSETS -> {
					val assetData = AssetData(context, filePath, FileAccessFlags.READ)
					Channels.newInputStream(assetData.readChannel)
				}

				StorageScope.APP -> {
					val fileData = FileData(filePath, FileAccessFlags.READ)
					Channels.newInputStream(fileData.fileChannel)
				}
				StorageScope.SHARED -> {
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
						val mediaStoreData = MediaStoreData(context, filePath, FileAccessFlags.READ)
						Channels.newInputStream(mediaStoreData.fileChannel)
					} else {
						null
					}
				}

				StorageScope.UNKNOWN -> null
			}
		}

		@Throws(java.lang.Exception::class, FileNotFoundException::class)
		fun generateDataAccess(
			storageScope: StorageScope,
			context: Context,
			filePath: String,
			accessFlag: FileAccessFlags
		): DataAccess? {
			return when (storageScope) {
				StorageScope.APP -> FileData(filePath, accessFlag)

				StorageScope.ASSETS -> AssetData(context, filePath, accessFlag)

				StorageScope.SHARED -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
					MediaStoreData(context, filePath, accessFlag)
				} else {
					null
				}

				StorageScope.UNKNOWN -> null
			}
		}

		fun fileExists(storageScope: StorageScope, context: Context, path: String): Boolean {
			return when(storageScope) {
				StorageScope.APP -> FileData.fileExists(path)
				StorageScope.ASSETS -> AssetData.fileExists(context, path)
				StorageScope.SHARED -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
					MediaStoreData.fileExists(context, path)
				} else {
					false
				}

				StorageScope.UNKNOWN -> false
			}
		}

		fun fileLastModified(storageScope: StorageScope, context: Context, path: String): Long {
			return when(storageScope) {
				StorageScope.APP -> FileData.fileLastModified(path)
				StorageScope.ASSETS -> AssetData.fileLastModified(path)
				StorageScope.SHARED -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
					MediaStoreData.fileLastModified(context, path)
				} else {
					0L
				}

				StorageScope.UNKNOWN -> 0L
			}
		}

		fun fileLastAccessed(storageScope: StorageScope, context: Context, path: String): Long {
			return when(storageScope) {
				StorageScope.APP -> FileData.fileLastAccessed(path)
				StorageScope.ASSETS -> AssetData.fileLastAccessed(path)
				StorageScope.SHARED -> MediaStoreData.fileLastAccessed(context, path)
				StorageScope.UNKNOWN -> 0L
			}
		}

		fun fileSize(storageScope: StorageScope, context: Context, path: String): Long {
			return when(storageScope) {
				StorageScope.APP -> FileData.fileSize(path)
				StorageScope.ASSETS -> AssetData.fileSize(path)
				StorageScope.SHARED -> MediaStoreData.fileSize(context, path)
				StorageScope.UNKNOWN -> -1L
			}
		}

		fun removeFile(storageScope: StorageScope, context: Context, path: String): Boolean {
			return when(storageScope) {
				StorageScope.APP -> FileData.delete(path)
				StorageScope.ASSETS -> AssetData.delete(path)
				StorageScope.SHARED -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
					MediaStoreData.delete(context, path)
				} else {
					false
				}

				StorageScope.UNKNOWN -> false
			}
		}

		fun renameFile(storageScope: StorageScope, context: Context, from: String, to: String): Boolean {
			return when(storageScope) {
				StorageScope.APP -> FileData.rename(from, to)
				StorageScope.ASSETS -> AssetData.rename(from, to)
				StorageScope.SHARED -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
					MediaStoreData.rename(context, from, to)
				} else {
					false
				}

				StorageScope.UNKNOWN -> false
			}
		}
	}

	internal var endOfFile = false
	abstract fun close()
	abstract fun flush()
	abstract fun seek(position: Long)
	abstract fun resize(length: Long): Error
	abstract fun position(): Long
	abstract fun size(): Long
	abstract fun read(buffer: ByteBuffer): Int
	abstract fun write(buffer: ByteBuffer): Boolean

	fun seekFromEnd(positionFromEnd: Long) {
		val positionFromBeginning = max(0, size() - positionFromEnd)
		seek(positionFromBeginning)
	}

	abstract class FileChannelDataAccess(
		private val filePath: String,
		private val readAhead: Boolean = false,
	) : DataAccess() {
		internal abstract val fileChannel: FileChannel

		// Enginehost: a file opened for reading only is read through a
		// read-ahead window. Godot reads a pack a few bytes at a time (each
		// get_32 and get_buffer is one call here), and each was one read from
		// the file: parsing a 2.2 GB pack's directory and loading its first
		// scene on BlueStacks' shared folder kept the GL thread in fileRead for
		// minutes (Goodbye Eternity, Droidtop/tracker#604). Such a file keeps
		// its position here too, so a seek or a tell costs no call either. A
		// file opened for writing is read and positioned exactly as before.
		private var window: ByteBuffer? = null
		private var windowStart = 0L
		private var windowEnd = 0L
		private var at = -1L

		private fun current(): Long {
			if (at < 0) {
				at = fileChannel.position()
			}
			return at
		}

		override fun close() {
			try {
				fileChannel.close()
			} catch (e: IOException) {
				Log.w(TAG, "Exception when closing file $filePath.", e)
			}
		}

		override fun flush() {
			try {
				fileChannel.force(false)
			} catch (e: IOException) {
				Log.w(TAG, "Exception when flushing file $filePath.", e)
			}
		}

		override fun seek(position: Long) {
			if (readAhead && position >= 0) {
				at = position
				return
			}
			try {
				fileChannel.position(position)
			} catch (e: Exception) {
				Log.w(TAG, "Exception when seeking file $filePath.", e)
			}
		}

		override fun resize(length: Long): Error {
			return try {
				fileChannel.truncate(length)
				Error.OK
			} catch (e: NonWritableChannelException) {
				Error.ERR_FILE_CANT_OPEN
			} catch (e: ClosedChannelException) {
				Error.ERR_FILE_CANT_OPEN
			} catch (e: IllegalArgumentException) {
				Error.ERR_INVALID_PARAMETER
			} catch (e: IOException) {
				Error.FAILED
			}
		}

		override fun position(): Long {
			return try {
				if (readAhead) current() else fileChannel.position()
			} catch (e: IOException) {
				Log.w(
					TAG,
					"Exception when retrieving position for file $filePath.",
					e
				)
				0L
			}
		}

		override fun size() = try {
			fileChannel.size()
		} catch (e: IOException) {
			Log.w(TAG, "Exception when retrieving size for file $filePath.", e)
			0L
		}

		override fun read(buffer: ByteBuffer): Int {
			if (readAhead) {
				return readAhead(buffer)
			}
			return try {
				val readBytes = fileChannel.read(buffer)
				if (readBytes == -1) {
					endOfFile = true
					0
				} else {
					readBytes
				}
			} catch (e: IOException) {
				Log.w(TAG, "Exception while reading from file $filePath.", e)
				0
			}
		}

		/** [read] through the window; the same answers as a plain read, from fewer reads of the file. */
		private fun readAhead(buffer: ByteBuffer): Int {
			return try {
				var position = current()
				if (buffer.remaining() >= READ_AHEAD) {
					// Large enough to read straight into Godot's own buffer.
					val readBytes = fileChannel.read(buffer, position)
					if (readBytes == -1) {
						endOfFile = true
						return 0
					}
					at = position + readBytes
					return readBytes
				}
				var copied = 0
				while (buffer.hasRemaining()) {
					if (position < windowStart || position >= windowEnd) {
						val fresh = window ?: ByteBuffer.allocate(READ_AHEAD).also { window = it }
						fresh.clear()
						val readBytes = fileChannel.read(fresh, position)
						if (readBytes <= 0) {
							windowStart = 0L
							windowEnd = 0L
							break
						}
						windowStart = position
						windowEnd = position + readBytes
					}
					val from = window!!.duplicate()
					val offset = (position - windowStart).toInt()
					val count = minOf(buffer.remaining().toLong(), windowEnd - position).toInt()
					from.limit(offset + count)
					from.position(offset)
					buffer.put(from)
					position += count
					copied += count
				}
				at = position
				// As a plain read: one that finds nothing left is the end of the file.
				if (copied == 0) {
					endOfFile = true
				}
				copied
			} catch (e: IOException) {
				Log.w(TAG, "Exception while reading from file $filePath.", e)
				0
			}
		}

		override fun write(buffer: ByteBuffer): Boolean {
			try {
				val writtenBytes = fileChannel.write(buffer)
				if (writtenBytes > 0) {
					endOfFile = false
				}
				return true
			} catch (e: IOException) {
				Log.w(TAG, "Exception while writing to file $filePath.", e)
				return false
			}
		}
	}
}

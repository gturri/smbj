/*
 * Copyright (C)2016 - SMBJ Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hierynomus.smbj.share;

import com.hierynomus.smbj.ProgressListener;
import com.hierynomus.smbj.io.ByteChunkProvider;

import java.io.IOException;
import java.io.OutputStream;

class FileOutputStream extends OutputStream {

    private SMB2Writer writer;
    private ProgressListener progressListener;
    private boolean isClosed = false;
    private ByteArrayProvider provider;

    FileOutputStream(SMB2Writer writer, int bufferSize, long offset, ProgressListener progressListener) {
        this.writer = writer;
        this.progressListener = progressListener;
        this.provider = new ByteArrayProvider(bufferSize,offset);
    }

    @Override
    public void write(int b) throws IOException {
        verifyConnectionNotClosed();

        if (provider.isBufferFull()) {
            flush();
        }

        if (!provider.isBufferFull()) {
            provider.writeByte(b);
        }
    }

    @Override
    public void write(byte b[]) throws IOException {
        write(b, 0, b.length);
    }

    @Override
    public void write(byte b[], int off, int len) throws IOException {
        verifyConnectionNotClosed();
        int offset = off;
        int length = len;
        do {
            int writeLen = Math.min(length, provider.maxSize());

            while (provider.isBufferFull(writeLen)) {
                flush();
            }

            if (!provider.isBufferFull()) {
                provider.writeBytes(b, offset, writeLen);
            }

            offset += writeLen;
            length -= writeLen;

        } while (length > 0);
    }

    @Override
    public void flush() throws IOException {
        verifyConnectionNotClosed();
        if (provider.isAvailable()) {
            sendWriteRequest();
        }
    }

    private void sendWriteRequest() {
        writer.write(provider, progressListener);
    }

    @Override
    public void close() throws IOException {

        while (provider.isAvailable()) {
            sendWriteRequest();
        }

        provider.reset();

        isClosed = true;
        writer = null;
        System.out.println("tempGT2: EOF, " + provider.getOffset() + " bytes written");
    }

    private void verifyConnectionNotClosed() throws IOException {
        if (isClosed) throw new IOException("Stream is closed");
    }

    private static class ByteArrayProvider extends ByteChunkProvider {

        private RingBuffer buf;

        private ByteArrayProvider(int maxWriteSize, long offset) {
            this.buf = new RingBuffer(maxWriteSize);
            this.offset = offset;
        }

        @Override
        public boolean isAvailable() {
            return buf != null && !buf.isEmpty();
        }

        @Override
        protected int getChunk(byte[] chunk) {
            return buf.read(chunk);
        }

        @Override
        public int bytesLeft() {
            return buf.size();
        }

        public void writeBytes(byte[] b, int off, int len) {
            buf.write(b, off, len);
        }

        public void writeByte(int b) {
            buf.write(b);
        }

        public boolean isBufferFull() {
            return buf.isFull();
        }

        public boolean isBufferFull(int len) {
            return buf.isFull(len);
        }

        public int maxSize() {
            return buf.maxSize();
        }

        private void reset() {
            this.buf = null;
        }
    }
}

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

import com.hierynomus.mssmb2.SMB2FileId;
import com.hierynomus.mssmb2.messages.SMB2WriteResponse;
import com.hierynomus.protocol.commons.concurrent.AFuture;
import com.hierynomus.protocol.commons.concurrent.Futures;
import com.hierynomus.smbj.ProgressListener;
import com.hierynomus.smbj.common.SMBRuntimeException;
import com.hierynomus.smbj.io.ArrayByteChunkProvider;
import com.hierynomus.smbj.io.ByteChunkProvider;

import java.io.OutputStream;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Future;

/**
 * Generic class that allows to write data to a share entry (Be it a printer or
 * a file)
 */
public class SMB2Writer {
    private Share share;
    private SMB2FileId fileId;
    private String entryName;

    public SMB2Writer(Share share, SMB2FileId fileId, String entryName) {
        this.share = share;
        this.fileId = fileId;
        this.entryName = entryName;
    }

    /**
     * Write the data in buffer to this file at position fileOffset.
     *
     * @param buffer     the data to write
     * @param fileOffset The offset, in bytes, into the file to which the data
     *                   should be written
     * @return the actual number of bytes that was written to the file
     */
    public int write(byte[] buffer, long fileOffset) {
        return write(buffer, fileOffset, 0, buffer.length);
    }

    /**
     * Write the data in buffer to this file at position fileOffset.
     *
     * @param buffer     the data to write
     * @param fileOffset The offset, in bytes, into the file to which the data
     *                   should be written
     * @param offset     the start offset in the data
     * @param length     the number of bytes that are written
     * @return the actual number of bytes that was written to the file
     */
    public int write(byte[] buffer, long fileOffset, int offset, int length) {
        return write(new ArrayByteChunkProvider(buffer, offset, length, fileOffset), null);
    }

    /**
     * Write all available data from the byte chunk provider to this file. The
     * offset in the file to which data is written is determined by
     * {@link ByteChunkProvider#getOffset()}.
     *
     * @param provider the byte chunk provider
     * @return the actual number of bytes that was written to the file
     */
    public int write(ByteChunkProvider provider) {
        return write(provider, null);
    }

    /**
     * Write all available data from the byte chunk provider to this file. The
     * offset in the file to which data is written is determined by
     * {@link ByteChunkProvider#getOffset()}.
     *
     * @param provider         the byte chunk provider
     * @param progressListener an optional callback that will be invoked when data
     *                         has been written to the file
     * @return the actual number of bytes that was written to the file
     */
    public int write(ByteChunkProvider provider, ProgressListener progressListener) {
        int bytesWritten = 0;
        while (provider.isAvailable()) {
            System.out.println("tempGT2: Writing to " + this.entryName + " from offset " + provider.getOffset());
            SMB2WriteResponse wresp = share.write(fileId, provider);
            bytesWritten += wresp.getBytesWritten();
            if (progressListener != null)
                progressListener.onProgressChanged(wresp.getBytesWritten(), provider.getOffset());
        }
        return bytesWritten;
    }

    /***
     * Write the data Async in buffer to this file at position fileOffset.
     *
     * @param buffer     the data to write
     * @param fileOffset The offset, in bytes, into the file to which the data
     *                   should be written
     * @param offset     the start offset in the data
     * @param length     the number of bytes that are written
     * @return A Future containing the total number of bytes written
     */
    public Future<Integer> writeAsync(byte[] buffer, long fileOffset, int offset, int length) {
        return writeAsync(new ArrayByteChunkProvider(buffer, offset, length, fileOffset));
    }

    /**
     * Async Write all available data from the byte chunk provider to this file. The
     * offset in the file to which data is written is determined by
     * {@link ByteChunkProvider#getOffset()}.
     *
     * @param provider the byte chunk provider
     * @return the List of write response future
     */
    public Future<Integer> writeAsync(ByteChunkProvider provider) {
        final List<Future<Integer>> wrespFutureList = new ArrayList<Future<Integer>>();
        while (provider.isAvailable()) {
            // maybe more than one time, need array list to store the write response future
            System.out.println("tempGT2: Sending async write request to " + this.entryName + " from offset " + provider.getOffset());
            Future<SMB2WriteResponse> resp = share.writeAsync(fileId, provider);
            final int bytesWritten = provider.getLastWriteSize();
            wrespFutureList.add(Futures.transform(resp,
                    new AFuture.Function<SMB2WriteResponse, Integer>() {
                        @Override
                        public Integer apply(SMB2WriteResponse t) {
                            int receivedBytes = t.getBytesWritten();
                            if (receivedBytes == bytesWritten) {
                                return bytesWritten;
                            }
                            throw new SMBRuntimeException(
                                    "Possible remote file corruption detected, server wrote less bytes ("
                                            + receivedBytes + ") in async mode than we sent (" + bytesWritten + ").");
                        }
                    }));
        }

        return Futures.transform(Futures.sequence(wrespFutureList), new AFuture.Function<List<Integer>, Integer>(){
            public Integer apply(List<Integer> a) {
                int sum = 0;
                for (Integer i : a) {
                    sum += i;
                }
                return sum;
            };
        });
    }

    public OutputStream getOutputStream() {
        return getOutputStream(null, 0);
    }

    public OutputStream getOutputStream(ProgressListener listener, long offset) {
        return new FileOutputStream(this, share.getWriteBufferSize(), offset, listener);
    }
}

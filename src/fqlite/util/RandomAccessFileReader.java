package fqlite.util;

import java.io.IOException;
import java.nio.Buffer;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class RandomAccessFileReader extends LongPositionByteBuffer {

    private static final int BUFFER_SIZE = 65536;

    private FileChannel channel;
    private ByteBuffer buffer;
    private long position;
    private long size;
    private long bufferPosition;
    

    public RandomAccessFileReader(Path path) throws IOException {
        super();
        channel = FileChannel.open(path, StandardOpenOption.READ);
        size = channel.size();
        position = 0;
        bufferPosition = -1;
        buffer = ByteBuffer.allocate(BUFFER_SIZE);
        fillBuffer();
    }

    public long position() {
        synchronized (lock) {
            return position;
        }
    }

    public long position(long newPosition) throws IOException {
        if (newPosition > size) {
            throw new BufferUnderflowException();
        }
        synchronized (lock) {
            long oldPosition = position;
            position = newPosition;
            fillBuffer();
            return oldPosition;
        }
    }

    public byte get() throws IOException {
        synchronized (lock) {
            if (position >= size) {
                throw new BufferUnderflowException();
            }
            /* the buffer always holds the block of the current position (see fillBuffer) */
            if (!buffer.hasRemaining()) {
                fillBuffer();
                if (!buffer.hasRemaining()) {
                    throw new BufferUnderflowException();
                }
            }
            byte resp = buffer.get();
            position++;
            return resp;
        }
    }

    public LongPositionByteBuffer get(byte[] dst, int offset, int length) throws IOException {
        synchronized (lock) {
            position(position);
            while (length > 0) {
                int toRead = Math.min(length, buffer.remaining());
                if (toRead == 0) {
                    throw new BufferUnderflowException();
                }
                buffer.get(dst, offset, toRead);
                position(position + toRead);
                offset += toRead;
                length -= toRead;
            }
            return this;
        }
    }

    public LongPositionByteBuffer get(byte[] dst) throws IOException {
        synchronized (lock) {
            return get(dst, 0, dst.length);
        }
    }

    private void fillBuffer() throws IOException {
        long newBufferPos = position / BUFFER_SIZE;
        int positionInBuffer = (int) (position % BUFFER_SIZE);
        if (newBufferPos != bufferPosition) {
            bufferPosition = newBufferPos;
            ((Buffer) buffer).clear();
            channel.position(bufferPosition * BUFFER_SIZE);
            channel.read(buffer);
            buffer.flip();
        }
        buffer.position(positionInBuffer);
    }

    public long size() {
        return size;
    }

    @Override
    public void close() throws IOException {
        synchronized (lock) {
            channel.close();
        }
    }
}

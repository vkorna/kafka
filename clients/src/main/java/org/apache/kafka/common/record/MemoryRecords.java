/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.kafka.common.record;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;

import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.utils.AbstractIterator;
import org.apache.kafka.common.utils.Crc32;
import org.apache.kafka.common.utils.Utils;

/**
 * A {@link Records} implementation backed by a ByteBuffer.
 */
public class MemoryRecords implements Records {

    private final static int WRITE_LIMIT_FOR_READABLE_ONLY = -1;

    // the compressor used for writable records
    private final Compressor compressor;

    // the write limit for writable buffer, which may be smaller than the buffer capacity
    private final int writeLimit;

    // the capacity of the initial buffer, which is only used for de-allocation of writable records
    private final int initialCapacity;

    // the initial position of the buffer
    private final int initialPosition;

    // the underlying buffer used for read; while the records are still writable it is null
    private ByteBuffer buffer;

    // indicate if the memory records is writable or not (i.e. used for appends or read-only)
    private boolean writable;

    // producer context of the record set
    private ProducerContext context;

    // delta offset of the record set
    private int deltaOffset;

    // Construct a writable memory records
    private MemoryRecords(ByteBuffer buffer, CompressionType type, int writeLimit) {
        this.buffer = buffer;
        this.initialCapacity = buffer.capacity();
        this.initialPosition = buffer.position();

        this.writable = true;
        this.writeLimit = writeLimit;
        this.compressor = new Compressor(buffer, type);
        this.context = null;
        this.deltaOffset = 0;
    }

    // Construct a readable memory records
    private MemoryRecords(ByteBuffer buffer) {
        this.buffer = buffer;
        this.initialCapacity = buffer.capacity();
        this.initialPosition = buffer.position();

        this.writable = false;
        this.writeLimit = WRITE_LIMIT_FOR_READABLE_ONLY;
        this.compressor = null;
        this.context = context();
        this.deltaOffset = -1;
    }

    public static MemoryRecords emptyRecords(ByteBuffer buffer, CompressionType type, int writeLimit) {
        return new MemoryRecords(buffer, type, writeLimit);
    }

    public static MemoryRecords emptyRecords(ByteBuffer buffer, CompressionType type) {
        // use the buffer capacity as the default write limit
        return emptyRecords(buffer, type, buffer.capacity());
    }

    public static MemoryRecords readableRecords(ByteBuffer buffer) {
        return new MemoryRecords(buffer);
    }

    /**
     * Set the producer context for this record set
     */
    public void setContext(ProducerContext context) {
        this.context = context;
    }

    /**
     * Append the given record and offset to the buffer
     */
    public void append(Record record) {
        if (!writable)
            throw new IllegalStateException("Memory records is not writable");

        int size = record.size();
        compressor.put(record.buffer());
        compressor.recordWritten(size);
        record.buffer().rewind();
    }

    /**
     * Append a new record and offset to the buffer
     * @return crc of the record
     */
    public long append(long timestamp, int deltaOffset, byte[] key, byte[] value) {
        if (!writable)
            throw new IllegalStateException("Memory records is not writable");

        int size = Record.recordSize(deltaOffset, key, value);
        long crc = compressor.putRecord(timestamp, key, value);
        compressor.recordWritten(size);
        return crc;
    }

    public static long computeAttributes(CompressionType compressionType, TimestampType timestampType) {
        long attributes = 0x00000000L;
        attributes = compressionType.updateAttributes(attributes);
        attributes = timestampType.updateAttributes(attributes)

        return attributes;
    }

    /**
     * Compute the checksum of the record set from the record contents
     */
    public long computeChecksum() {
        return computeChecksum(buffer, MAGIC_OFFSET, buffer.limit() - MAGIC_OFFSET);
    }

    /**
     * Compute the checksum of the record set from the record contents
     */
    public static long computeChecksum(ByteBuffer buffer, int position, int size) {
        Crc32 crc = new Crc32();
        crc.update(buffer.array(), buffer.arrayOffset() + position, size);
        return crc.getValue();
    }

    /**
     * Compute the checksum of the record set from the attributes, key and value payloads
     */
    public static long computeChecksum(byte[] records, CompressionType type, long pid, int epoch, long sequence) {
        Crc32 crc = new Crc32();

        // update for the magic value
        crc.update(CURRENT_MAGIC_VALUE);

        // update for the attributes
        crc.update(computeAttributes(type));

        // update for the pid, epoch and sequence number
        crc.updateLong(pid);
        crc.updateInt(epoch);
        crc.updateLong(sequence);

        // update for the records
        if (records == null) {
            crc.updateInt(-1);
        } else {
            crc.updateInt(records.length);
            crc.update(records, 0, records.length);
        }

        return crc.getValue();
    }

    /**
     * Retrieve the previously computed CRC for this record
     */
    public long checksum() {
        return Utils.readUnsignedInt(buffer, initialPosition + CRC_OFFSET);
    }

    /**
     * Returns true if the crc stored with the record matches the crc computed off the record contents
     */
    public boolean isValid() {
        return checksum() == computeChecksum();
    }

    /**
     * Throw an InvalidRecordException if isValid is false for this record
     */
    public void ensureValid() {
        if (!isValid())
            throw new InvalidRecordException("Record is corrupt (stored crc = " + checksum()
                    + ", computed crc = "
                    + computeChecksum()
                    + ")");
    }

    /**
     * The starting offset of this record set
     */
    public long offset() {
        return buffer.getLong(initialPosition + OFFSET_OFFSET);
    }

    /**
     * The size of this record set
     */
    public int size() {
        return buffer.getInt(initialPosition + SIZE_OFFSET);
    }

    /**
     * The magic version of this record set
     */
    public byte magic() {
        return buffer.get(initialPosition + MAGIC_OFFSET);
    }

    /**
     * The attributes stored with this record set
     */
    public long attributes() {
        return Utils.readUnsignedInt(buffer, initialPosition + ATTRIBUTES_OFFSET);
    }

    /**
     * The producer context stored with this record set
     */
    public ProducerContext context() {
        return new ProducerContext(
                buffer.getLong(initialPosition + PID_OFFSET),
                buffer.getInt(initialPosition + EPOCH_OFFSET),
                buffer.getLong(initialPosition + SEQUENCE_NUMBER_OFFSET)
        );
    }

    /**
     * The timestamp of the message.
     */
    public TimestampType timestampType() {
        if (magic() == 0)
            return TimestampType.NO_TIMESTAMP_TYPE;
        else
            return TimestampType.forAttributes(attributes());
    }

    /**
     * The compression type used with this record
     */
    public CompressionType compressionType() {
        return CompressionType.forAttributes(attributes());
    }

    /**
     * Check if we have room for a new record containing the given key/value pair
     *
     * Note that the return value is based on the estimate of the bytes written to the compressor, which may not be
     * accurate if compression is really used. When this happens, the following append may cause dynamic buffer
     * re-allocation in the underlying byte buffer stream.
     *
     * There is an exceptional case when appending a single message whose size is larger than the batch size, the
     * capacity will be the message size which is larger than the write limit, i.e. the batch size. In this case
     * the checking should be based on the capacity of the initialized buffer rather than the write limit in order
     * to accept this single record.
     */
    public boolean hasRoomFor(byte[] key, byte[] value) {
        if (!this.writable)
            return false;

        return this.compressor.numRecords() == 0 ?
            this.initialCapacity >= Record.recordSize(this.deltaOffset, key, value) :
            this.writeLimit >= this.compressor.estimatedBytesWritten() + Record.recordSize(this.deltaOffset, key, value);
    }

    public boolean isFull() {
        return !this.writable || this.writeLimit <= this.compressor.estimatedBytesWritten();
    }

    /**
     * Close this batch for no more appends
     */
    public void close() {
        if (writable) {
            // close the compressor
            compressor.close();

            // start filling in the header of the record set by setting the starting position
            // to the initialized position first
            int pos = buffer.position();
            buffer.position(initialPosition);
            // first set the base offset as number of records, in fact it does not matter what values
            // are set on the producer end since it is always going to be overridden on the server side.
            buffer.putLong(compressor.numRecords());
            // set the record set size
            buffer.putInt(pos - initialPosition - Records.RECORDS_OVERHEAD);
            // compute and fill the crc
            long crc = computeChecksum(buffer, initialPosition + Records.MAGIC_OFFSET, pos - initialPosition - Records.MAGIC_OFFSET);
            Utils.writeUnsignedInt(buffer, initialPosition + Records.CRC_OFFSET, crc);
            buffer.position(initialPosition + Records.MAGIC_OFFSET);
            // fill the magic byte
            buffer.put(CURRENT_MAGIC_VALUE);
            // compute and fill the attributes
            byte attributes = computeAttributes(compressor.type());
            buffer.put(attributes);
            // write the producer context
            if (context == null)
                throw new IllegalStateException("The producer context should not be null");
            buffer.putLong(context.id());
            buffer.putInt(context.epoch());
            buffer.putLong(context.sequence());

            // reset the position
            buffer.position(pos);

            // flip the underlying buffer to be ready for reads
            buffer = compressor.buffer();
            buffer.flip();

            // reset the writable flag
            writable = false;
        }
    }

    /**
     * The size of this record set
     */
    public int sizeInBytes() {
        if (writable) {
            return compressor.buffer().position();
        } else {
            return buffer.limit();
        }
    }

    /**
     * The compression rate of this record set
     */
    public double compressionRate() {
        if (compressor == null)
            return 1.0;
        else
            return compressor.compressionRate();
    }

    /**
     * Return the capacity of the initial buffer, for writable records
     * it may be different from the current buffer's capacity
     */
    public int initialCapacity() {
        return this.initialCapacity;
    }

    /**
     * Get the byte buffer that backs this records instance for reading
     */
    public ByteBuffer buffer() {
        if (writable)
            throw new IllegalStateException("The memory records must not be writable any more before getting its underlying buffer");

        return buffer.duplicate();
    }

    @Override
    public Iterator<LogEntry> iterator() {
        if (writable) {
            // flip on a duplicate buffer for reading
            ByteBuffer buffer = (ByteBuffer) this.buffer.duplicate().flip();

            return MemoryRecords.readableRecords(buffer).iterator();
        } else {
            // read the starting offset
            long offset = offset();

            // read record set size
            int size = size();
            if (size < 0)
                throw new IllegalStateException("Record set has negative size " + size + ", which is not expected.");

            // read the magic byte
            byte magic = magic();

            // read the attribute bytes and read the compression type

            // construct the compressor according to the read type
            // and set the buffer position to the start of the records.
            CompressionType type = compressionType();

            buffer.position(initialPosition + RECORDS_HEADER_SIZE_V2);

            // do not need to flip for non-writable buffer
            return new RecordsIterator(buffer, offset, size, magic, type);
        }
    }
    
    @Override
    public String toString() {
        Iterator<LogEntry> iter = iterator();
        StringBuilder builder = new StringBuilder();
        builder.append('[');
        while (iter.hasNext()) {
            LogEntry entry = iter.next();
            builder.append('(');
            builder.append("offset=");
            builder.append(entry.offset());
            builder.append(",");
            builder.append("record=");
            builder.append(entry.record());
            builder.append(")");
        }
        builder.append(']');
        return builder.toString();
    }

    /** Visible for testing */
    public boolean isWritable() {
        return writable;
    }

    /**
     * Iterator for traversing through the records within this set
     */
    public static class RecordsIterator extends AbstractIterator<LogEntry> {
        private final DataInputStream stream;
        private final ByteBuffer buffer;
        private final long baseOffset;
        private final int size;

        public RecordsIterator(ByteBuffer buffer, long baseOffset, int size, byte magic, CompressionType type) {
            this.size = size;
            this.buffer = buffer;
            this.baseOffset = baseOffset;
            this.stream = Compressor.wrapForInput(new ByteBufferInputStream(this.buffer), type, magic);

            // read the record set header first, starting with the  base offset
        }

        /*
         * Read the next record from the buffer.
         */
        @Override
        protected LogEntry makeNext() {
            if (innerDone()) {
                try {
                    LogEntry entry = getNextEntry();
                    // No more record to return.
                    if (entry == null)
                        return allDone();

                    // Convert offset to absolute offset if needed.
                    if (absoluteBaseOffset >= 0) {
                        long absoluteOffset = absoluteBaseOffset + entry.offset();
                        entry = new LogEntry(absoluteOffset, entry.record());
                    }

                    // decide whether to go shallow or deep iteration if it is compressed
                    CompressionType compression = entry.record().compressionType();
                    if (compression == CompressionType.NONE || shallow) {
                        return entry;
                    } else {
                        // init the inner iterator with the value payload of the message,
                        // which will de-compress the payload to a set of messages;
                        // since we assume nested compression is not allowed, the deep iterator
                        // would not try to further decompress underlying messages
                        // There will be at least one element in the inner iterator, so we don't
                        // need to call hasNext() here.
                        innerIter = new RecordsIterator(entry);
                        return innerIter.next();
                    }
                } catch (EOFException e) {
                    return allDone();
                } catch (IOException e) {
                    throw new KafkaException(e);
                }
            } else {
                return innerIter.next();
            }
        }

        private LogEntry getNextEntry() throws IOException {
            // read the offset
            long timestamp = stream.readLong();
            // read record key size
            int size = stream.readInt();
            if (size < 0)
                throw new IllegalStateException("Record with size " + size);
            // read the record, if compression is used we cannot depend on size
            // and hence has to do extra copy
            ByteBuffer rec;
            if (type == CompressionType.NONE) {
                rec = buffer.slice();
                int newPos = buffer.position() + size;
                if (newPos > buffer.limit())
                    return null;
                buffer.position(newPos);
                rec.limit(size);
            } else {
                byte[] recordBuffer = new byte[size];
                stream.readFully(recordBuffer, 0, size);
                rec = ByteBuffer.wrap(recordBuffer);
            }
            return new LogEntry(offset, new Record(rec));
        }

        private boolean innerDone() {
            return innerIter == null || !innerIter.hasNext();
        }
    }
}

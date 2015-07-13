package io.confluent.streaming.internal;

import io.confluent.streaming.Processor;
import io.confluent.streaming.PunctuationScheduler;
import io.confluent.streaming.SyncGroup;
import io.confluent.streaming.TimestampExtractor;
import io.confluent.streaming.util.MinTimestampTracker;
import io.confluent.streaming.util.ParallelExecutor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.Deserializer;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * StreamSynchronizer tries to synchronize the progress of streams from different topics in the same {@link SyncGroup}.
 */
public class StreamSynchronizer implements SyncGroup, ParallelExecutor.Task {

  public static class Status {
    private AtomicBoolean pollRequired = new AtomicBoolean();

    public void pollRequired(boolean flag) {
      pollRequired.set(flag);
    }

    public boolean pollRequired() {
      return pollRequired.get();
    }
  }

  public final String name;
  private final Ingestor ingestor;
  private final Chooser chooser;
  private final TimestampExtractor timestampExtractor;
  private final Map<TopicPartition, RecordQueue> stash = new HashMap<>();

  private final int desiredUnprocessed;
  private final Map<TopicPartition, Long> consumedOffsets;
  private final PunctuationQueue punctuationQueue = new PunctuationQueue();
  private final ArrayDeque<NewRecords> newRecordBuffer = new ArrayDeque<>();

  private long streamTime = -1;
  private volatile int buffered = 0;

  /**
   * Creates StreamSynchronizer
   * @param name the name of {@link SyncGroup}
   * @param ingestor the instance of {@link Ingestor}
   * @param chooser the instance of {@link Chooser}
   * @param timestampExtractor the instance of {@link TimestampExtractor}
   * @param desiredUnprocessedPerPartition the target number of records kept in a queue for each topic
   */
  StreamSynchronizer(String name,
                     Ingestor ingestor,
                     Chooser chooser,
                     TimestampExtractor timestampExtractor,
                     int desiredUnprocessedPerPartition) {
    this.name = name;
    this.ingestor = ingestor;
    this.chooser = chooser;
    this.timestampExtractor = timestampExtractor;
    this.desiredUnprocessed = desiredUnprocessedPerPartition;
    this.consumedOffsets = new HashMap<>();
  }

  @Override
  public String name() {
    return name;
  }

  /**
   * Adds a partition and its receiver to this stream synchronizer
   * @param partition the partition
   * @param receiver the receiver
   */
  @SuppressWarnings("unchecked")
  public void addPartition(TopicPartition partition, KStreamImpl stream) {
    synchronized (this) {
      RecordQueue recordQueue = stash.get(partition);

      if (recordQueue == null) {
        stash.put(partition, createRecordQueue(partition, stream));
      } else {
        throw new IllegalStateException("duplicate partition");
      }
    }
  }

  /**
   * Adds records
   * @param partition the partition
   * @param iterator the iterator of records
   */
  @SuppressWarnings("unchecked")
  public void addRecords(TopicPartition partition, Iterator<ConsumerRecord<byte[], byte[]>> iterator) {
    synchronized (this) {
      newRecordBuffer.addLast(new NewRecords(partition, iterator));
    }
  }

  @SuppressWarnings("unchecked")
  private void ingestNewRecords() {
    for (NewRecords newRecords : newRecordBuffer) {
      TopicPartition partition = newRecords.partition;
      Iterator<ConsumerRecord<byte[], byte[]>> iterator = newRecords.iterator;

      RecordQueue recordQueue = stash.get(partition);
      if (recordQueue != null) {
        boolean wasEmpty = recordQueue.isEmpty();

        while (iterator.hasNext()) {
          ConsumerRecord<byte[], byte[]> record = iterator.next();

          // deserialize the raw record, extract the timestamp and put into the queue
          Deserializer<?> keyDeserializer = recordQueue.stream.context().keyDeserializer();
          Deserializer<?> valDeserializer = recordQueue.stream.context().valueDeserializer();

          Object key = keyDeserializer.deserialize(record.topic(), record.key());
          Object value = valDeserializer.deserialize(record.topic(), record.value());
          ConsumerRecord deserializedRecord = new ConsumerRecord<>(record.topic(), record.partition(), record.offset(), key, value);

          long timestamp = timestampExtractor.extract(record.topic(), key, value);
          recordQueue.add(new StampedRecord(deserializedRecord, timestamp));
          buffered++;
        }

        int queueSize = recordQueue.size();
        if (wasEmpty && queueSize > 0) chooser.add(recordQueue);

        // if we have buffered enough for this partition, pause
        if (queueSize >= this.desiredUnprocessed) {
          ingestor.pause(partition);
        }
      }
    }
    newRecordBuffer.clear();
  }

  /**
   * Returns a PunctuationScheduler
   * @param processor the processor requesting scheduler
   * @return PunctuationScheduler
   */
  public PunctuationScheduler getPunctuationScheduler(Processor<?, ?> processor) {
    return new PunctuationSchedulerImpl(punctuationQueue, processor);
  }

  /**
   * Processes one record
   * @param context an application specific context object for a task
   */
  @SuppressWarnings("unchecked")
  public void process(Object context) {
    Status status = (Status) context;
    synchronized (this) {
      ingestNewRecords();

      RecordQueue recordQueue = chooser.next();
      if (recordQueue == null) {
        status.pollRequired(true);
        return;
      }

      if (recordQueue.size() == 0) throw new IllegalStateException("empty record queue");

      if (recordQueue.size() == this.desiredUnprocessed) {
        ingestor.unpause(recordQueue.partition(), recordQueue.offset());
      }

      long trackedTimestamp = recordQueue.trackedTimestamp();
      StampedRecord record = recordQueue.next();

      if (recordQueue.size() < this.desiredUnprocessed)
        status.pollRequired(true);

      if (streamTime < trackedTimestamp) streamTime = trackedTimestamp;

      recordQueue.stream.receive(record.key(), record.value(), record.timestamp, streamTime);
      consumedOffsets.put(recordQueue.partition(), record.offset());

      if (recordQueue.size() > 0) chooser.add(recordQueue);

      buffered--;

      punctuationQueue.mayPunctuate(streamTime);
    }
  }

  /**
   * Returns consumed offsets
   * @return the map of partition to consumed offset
   */
  public Map<TopicPartition, Long> consumedOffsets() {
    return this.consumedOffsets;
  }

  public int buffered() {
    return buffered;
  }

  public void close() {
    chooser.close();
    stash.clear();
  }

  protected RecordQueue createRecordQueue(TopicPartition partition, KStreamImpl stream) {
    return new RecordQueue(partition, stream, new MinTimestampTracker<ConsumerRecord<Object, Object>>());
  }

  private static class NewRecords {
    final TopicPartition partition;
    final Iterator<ConsumerRecord<byte[], byte[]>> iterator;

    NewRecords(TopicPartition partition, Iterator<ConsumerRecord<byte[], byte[]>> iterator) {
      this.partition = partition;
      this.iterator = iterator;
    }
  }
}

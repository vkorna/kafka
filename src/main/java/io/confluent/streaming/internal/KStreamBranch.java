package io.confluent.streaming.internal;

import io.confluent.streaming.Predicate;

import java.lang.reflect.Array;
import java.util.Arrays;

/**
 * Created by yasuhiro on 6/18/15.
 */
class KStreamBranch<K, V> implements Receiver<K, V> {

  private final Predicate<K, V>[] predicates;
  final KStreamSource<K, V>[] branches;

  @SuppressWarnings("unchecked")
  KStreamBranch(Predicate<K, V>[] predicates, PartitioningInfo partitioningInfo, KStreamContextImpl context) {
    this.predicates = Arrays.copyOf(predicates, predicates.length);
    this.branches = (KStreamSource<K, V>[]) Array.newInstance(KStreamSource.class, predicates.length);
    for (int i = 0; i < branches.length; i++) {
      branches[i] = new KStreamSource<K, V>(partitioningInfo, context);
    }
  }

  @Override
  public void receive(K key, V value, long timestamp) {
    synchronized(this) {
      for (int i = 0; i < predicates.length; i++) {
        Predicate<K, V> predicate = predicates[i];
        if (predicate.apply(key, value)) {
          branches[i].receive(key, value, timestamp);
          return;
        }
      }
      return;
    }
  }

}

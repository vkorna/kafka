package io.confluent.streaming.util;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashSet;

/**
 * Created by yasuhiro on 6/22/15.
 */
public class Util {

  /**
   * Creates a set
   * @param elems the elements
   * @param <T> the type of element
   * @return Set
   */
  public static <T> HashSet<T> mkSet(T... elems) {
    HashSet<T> set = new HashSet<T>();
    for (T e : elems) set.add(e);
    return set;
  }

  /**
   * Makes a string of a comma separated list of collection elements
   * @param collection the collection
   * @param <E> the type of collection elements
   * @return String
   */
  public static <E> String mkString(Collection<E> collection) {
    StringBuilder sb = new StringBuilder();
    int count = collection.size();
    for (E elem : collection) {
      sb.append(elem.toString());
      count--;
      if (count > 0) sb.append(", ");
    }
    return sb.toString();
  }

  /**
   * Recursively delete the given file/directory and any subfiles (if any exist)
   *
   * @param file The root file at which to begin deleting
   */
  public static void rm(File file) {
    if (file == null) {
      return;
    } else if (file.isDirectory()) {
      File[] files = file.listFiles();
      if (files != null) {
        for (File f : files)
          rm(f);
      }
      file.delete();
    } else {
      file.delete();
    }
  }
}

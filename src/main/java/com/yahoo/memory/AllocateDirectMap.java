/*
 * Copyright 2017, Yahoo! Inc. Licensed under the terms of the
 * Apache License 2.0. See LICENSE file at the project root for terms.
 */

package com.yahoo.memory;


import static com.yahoo.memory.UnsafeUtil.unsafe;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import sun.misc.Cleaner;
import sun.nio.ch.FileChannelImpl;

/**
 * Allocates direct memory used to memory map files for read operations
 * (including those &gt; 2GB).
 *
 * @author Praveenkumar Venkatesan
 * @author Lee Rhodes
 */
class AllocateDirectMap extends WritableMemoryImpl implements ResourceHandler {
  final Cleaner cleaner;

  AllocateDirectMap(final ResourceState state) {
    super(state);
    this.cleaner = Cleaner.create(this,
        new Deallocator(state));
  }

  /**
   * Factory method for memory mapping a file. This should be called only if read access
   * is desired.
   *
   * <p>Memory maps a file directly in off heap leveraging native map0 method used in
   * FileChannelImpl.c. The owner will have read access to that address space.</p>
   *
   * @param state the ResourceState
   * @return A new AllocateDirectMap
   * @throws Exception file not found or RuntimeException, etc.
   */
  static AllocateDirectMap map(final ResourceState state) throws Exception {
    return new AllocateDirectMap(mapper(state));
  }

  //does the actual mapping work
  @SuppressWarnings("resource")
  static ResourceState mapper(final ResourceState state) throws Exception {
    final long fileOffset = state.getFileOffset();
    final long capacity = state.getCapacity();
    checkOffsetAndCapacity(fileOffset, capacity);

    final File file = state.getFile();

    if (isFileReadOnly(file)) {
      state.setResourceReadOnly(); //The file itself could be writable
    }

    final String mode = "rw"; //we can't map it unless we use rw mode
    final RandomAccessFile raf = new RandomAccessFile(file, mode);
    state.putRandomAccessFile(raf);
    final FileChannel fc = raf.getChannel();
    final long nativeBaseOffset = map(fc, fileOffset, capacity);
    state.putNativeBaseOffset(nativeBaseOffset);

    // len can be more than the file.length
    raf.setLength(capacity);
    final MappedByteBuffer mbb = createDummyMbbInstance(nativeBaseOffset);
    state.putMappedByteBuffer(mbb);
    return state;
  }

  @Override
  public Memory get() {
    return this;
  }

  @Override
  public void load() {
    madvise();
    // Read a byte from each page to bring it into memory.
    final int ps = unsafe.pageSize();
    final int count = pageCount(ps, super.capacity);
    long a = super.state.getNativeBaseOffset();
    for (int i = 0; i < count; i++) {
      unsafe.getByte(a);
      a += ps;
    }
  }

  @Override
  public boolean isLoaded() {
    final int ps = unsafe.pageSize();
    final long nativeBaseOffset = super.state.getNativeBaseOffset();
    try {

      final int pageCount = pageCount(ps, super.capacity);
      final Method method =
          MappedByteBuffer.class.getDeclaredMethod("isLoaded0", long.class, long.class, int.class);
      method.setAccessible(true);
      return (boolean) method.invoke(super.state.getMappedByteBuffer(), nativeBaseOffset, super.capacity,
          pageCount);
    } catch (final Exception e) {
      throw new RuntimeException(
          String.format("Encountered %s exception while loading", e.getClass()));
    }
  }

  @Override
  public void close() {
    try {
      this.cleaner.clean();
      this.state.setInvalid();
    } catch (final Exception e) {
      throw e;
    }
  }

  @Override
  public ResourceType getResourceType() {
    return ResourceType.MEMORY_MAPPED_FILE;
  }

  @Override
  public boolean isResourceType(final ResourceType resourceType) {
    return resourceType == ResourceType.MEMORY_MAPPED_FILE;
  }

  // Restricted methods

  static final int pageCount(final int ps, final long capacity) {
    return (int) ( (capacity == 0) ? 0 : (capacity - 1L) / ps + 1L);
  }

  static final MappedByteBuffer createDummyMbbInstance(final long nativeBaseAddress)
      throws RuntimeException {
    try {
      final Class<?> cl = Class.forName("java.nio.DirectByteBuffer");
      final Constructor<?> ctor =
          cl.getDeclaredConstructor(int.class, long.class, FileDescriptor.class, Runnable.class);
      ctor.setAccessible(true);
      final MappedByteBuffer mbb = (MappedByteBuffer) ctor.newInstance(0, // some junk capacity
          nativeBaseAddress, null, null);
      return mbb;
    } catch (final Exception e) {
      throw new RuntimeException(
          "Could not create Dummy MappedByteBuffer instance: " + e.getClass());
    }
  }

  /**
   * madvise is a system call made by load0 native method
   */
  void madvise() throws RuntimeException {
    try {
      final Method method = MappedByteBuffer.class.getDeclaredMethod("load0", long.class, long.class);
      method.setAccessible(true);
      method.invoke(super.state.getMappedByteBuffer(), super.state.getNativeBaseOffset(), super.capacity);
    } catch (final Exception e) {
      throw new RuntimeException(
          String.format("Encountered %s exception while loading", e.getClass()));
    }
  }

  /**
   * Creates a mapping of the file on disk starting at position and of size length to pages in OS.
   * May throw OutOfMemory error if you have exhausted memory. Force garbage collection and
   * re-attempt.
   * @param fileChannel the FileChannel
   * @param position the offset in bytes
   * @param len the length in bytes
   * @return the direct memory address
   * @throws RuntimeException Encountered an exception while mapping
   */
  static final long map(final FileChannel fileChannel, final long position, final long len)
      throws RuntimeException {
    final int pagePosition = (int) (position % unsafe.pageSize());
    final long mapPosition = position - pagePosition;
    final long mapSize = len + pagePosition;

    try {
      final Method method =
          FileChannelImpl.class.getDeclaredMethod("map0", int.class, long.class, long.class);
      method.setAccessible(true);
      final long addr = (long) method.invoke(fileChannel, 1, mapPosition, mapSize);
      return addr;
    } catch (final Exception e) {
      throw new RuntimeException(
          String.format("Encountered %s exception while mapping", e.getClass()));
    }
  }

  private static final class Deallocator implements Runnable {
    private RandomAccessFile myRaf;
    private FileChannel myFc;
    //This is the only place the actual native offset is kept for use by unsafe.freeMemory();
    //It can never be modified until it is deallocated.
    private long actualNativeBaseOffset;
    private final long myCapacity;
    private final ResourceState parentStateRef;

    private Deallocator(final ResourceState state) {
      this.myRaf = state.getRandomAccessFile();
      assert (myRaf != null);
      this.myFc = myRaf.getChannel();
      this.actualNativeBaseOffset = state.getNativeBaseOffset();
      assert (actualNativeBaseOffset != 0);
      this.myCapacity = state.getCapacity();
      assert (myCapacity != 0);
      this.parentStateRef = state;
    }

    @Override
    public void run() {
      if (this.myFc != null) {
        unmap();
      }
      this.actualNativeBaseOffset = 0L;
      this.parentStateRef.setInvalid(); //The only place valid is set invalid.
    }

    /**
     * Removes existing mapping
     */
    private void unmap() throws RuntimeException {
      try {
        final Method method = FileChannelImpl.class.getDeclaredMethod("unmap0", long.class, long.class);
        method.setAccessible(true);
        method.invoke(this.myFc, this.actualNativeBaseOffset, this.myCapacity);
        this.myRaf.close();
      } catch (final Exception e) {
        throw new RuntimeException(
            String.format("Encountered %s exception while freeing memory", e.getClass()));
      }
    }

  } //End of class Deallocator

  static final void checkOffsetAndCapacity(final long offset, final long capacity) {
    if (((offset) | (capacity - 1L) | (offset + capacity)) < 0) {
      throw new IllegalArgumentException(
          "offset: " + offset + ", capacity: " + capacity
          + ", offset + capacity: " + (offset + capacity));
    }
  }

  static final boolean isFileReadOnly(final File file) {
    if (System.getProperty("os.name").startsWith("Windows")) {
      return !file.canWrite();
    }
    //All Unix-like OSes
    final Path path = Paths.get(file.getAbsolutePath());
    PosixFileAttributes attributes = null;
    try {
      attributes = Files.getFileAttributeView(path, PosixFileAttributeView.class).readAttributes();
    } catch (final IOException e) {
      // File presence is guaranteed. Ignore
      e.printStackTrace();
    }
    if (attributes != null) {
      // A file is read-only in Linux-derived OSes only when it has 0444 permissions.
      // Here we are going to ignore the Owner W,E bits to allow root/owner testing.
      final Set<PosixFilePermission> permissions = attributes.permissions();
      int bits = 0;
      bits |= ((permissions.contains(PosixFilePermission.OWNER_READ))     ? 1 << 8 : 0);
      //bits |= ((permissions.contains(PosixFilePermission.OWNER_WRITE))    ? 1 << 7 : 0);
      //bits |= ((permissions.contains(PosixFilePermission.OWNER_EXECUTE))  ? 1 << 6 : 0);
      bits |= ((permissions.contains(PosixFilePermission.GROUP_READ))     ? 1 << 5 : 0);
      bits |= ((permissions.contains(PosixFilePermission.GROUP_WRITE))    ? 1 << 4 : 0);
      bits |= ((permissions.contains(PosixFilePermission.GROUP_EXECUTE))  ? 1 << 3 : 0);
      bits |= ((permissions.contains(PosixFilePermission.OTHERS_READ))    ? 1 << 2 : 0);
      bits |= ((permissions.contains(PosixFilePermission.OTHERS_WRITE))   ? 1 << 1 : 0);
      bits |= ((permissions.contains(PosixFilePermission.OTHERS_EXECUTE)) ? 1      : 0);
      //System.out.println(Util.zeroPad(Integer.toBinaryString(bits), 32));
      //System.out.println(Util.zeroPad(Integer.toOctalString(bits), 4));
      if (bits == 0444) {
        return true;
      }
    }
    return false;
  }

}
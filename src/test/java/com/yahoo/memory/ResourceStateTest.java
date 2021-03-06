/*
 * Copyright 2017, Yahoo! Inc. Licensed under the terms of the
 * Apache License 2.0. See LICENSE file at the project root for terms.
 */

package com.yahoo.memory;

import static com.yahoo.memory.UnsafeUtil.ARRAY_DOUBLE_INDEX_SCALE;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.nio.ByteOrder;

import org.testng.annotations.Test;

public class ResourceStateTest {

  @Test
  public void checkByteOrder() {
    ResourceState state = new ResourceState(false);
    assertEquals(state.getResourceOrder(), ByteOrder.nativeOrder());
    if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
      state.putResourceOrder(ByteOrder.BIG_ENDIAN); //set to opposite
    } else {
      state.putResourceOrder(ByteOrder.LITTLE_ENDIAN);
    }
    assertTrue(state.getResourceOrder() != ByteOrder.nativeOrder());
    assertTrue(state.isSwapBytes());
  }

  @Test
  public void checkExceptions() {
    ResourceState state = new ResourceState(false);

    try {
      state.putUnsafeObject(null);
      fail();
    } catch (IllegalArgumentException e) {
      //ok
    }

    try {
      state.putByteBuffer(null);
      fail();
    } catch (IllegalArgumentException e) {
      //ok
    }

    try {
      state.putRegionOffset( -16L);
      fail();
    } catch (IllegalArgumentException e) {
      //ok
    }
  }

  @Test
  public void checkPrimOffset() {
    int off = (int)Prim.BYTE.off();
    assertTrue(off > 0);
  }

  @Test
  public void checkIsSameResource() {
    WritableMemory wmem = WritableMemory.allocate(16);
    Memory mem = wmem;
    assertFalse(wmem.isSameResource(null));
    assertTrue(wmem.isSameResource(mem));

    WritableBuffer wbuf = wmem.asWritableBuffer();
    Buffer buf = wbuf;
    assertFalse(wbuf.isSameResource(null));
    assertTrue(wbuf.isSameResource(buf));
  }

  //StepBoolean checks
  @Test
  public void checkStepBoolean() {
    checkStepBoolean(true);
    checkStepBoolean(false);
  }

  private static void checkStepBoolean(boolean initialState) {
    StepBoolean step = new StepBoolean(initialState);
    assertTrue(step.get() == initialState); //confirm initialState
    step.change();
    assertTrue(step.hasChanged());      //1st change was successful
    assertTrue(step.get() != initialState); //confirm it is different from initialState
    step.change();
    assertTrue(step.get() != initialState); //Still different from initialState
    assertTrue(step.hasChanged());  //confirm it was changed from initialState value
  }

  @Test
  public void checkPrim() {
    assertEquals(Prim.DOUBLE.scale(), ARRAY_DOUBLE_INDEX_SCALE);
  }

  @SuppressWarnings("unused")
  @Test(expectedExceptions = IllegalArgumentException.class)
  public void checkArrLen() {
    byte[] arr = new byte[64];
    ResourceState state = new ResourceState(arr, Prim.BYTE, -1);
  }

  @Test
  public void checkIdentity() {
    byte[] arr = new byte[64];
    ResourceState state = new ResourceState(arr, Prim.BYTE, 64);
    boolean same = state.isSameResource(state);
    assertTrue(same);
  }

  @Test(expectedExceptions = IllegalStateException.class)
  public void checkValid() {
    try (WritableHandle wh = WritableMemory.allocateDirect(1024)) {
      WritableMemory wmem = wh.get();
      wh.close();
      ResourceState state = wmem.getResourceState();
      state.checkValid();
    }
  }


  @Test
  public void printlnTest() {
    println("PRINTING: "+this.getClass().getName());
  }

  /**
   * @param s value to print
   */
  static void println(String s) {
    //System.out.println(s); //disable here
  }

}

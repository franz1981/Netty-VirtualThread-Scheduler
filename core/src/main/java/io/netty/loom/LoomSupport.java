package io.netty.loom;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.util.concurrent.Executor;

public final class LoomSupport {
   private static final boolean SUPPORTED;
   private static final MethodHandle SCHEDULER;
   private static final VarHandle CARRIER_THREAD;
   private static Throwable FAILURE;

   static {
      boolean sup;
      MethodHandle scheduler;
      VarHandle carrierThread;
      try {
         // this is required to override the default scheduler
         MethodHandles.Lookup lookup = MethodHandles.lookup();
         Field schedulerField = Class.forName("java.lang.ThreadBuilders$VirtualThreadBuilder")
               .getDeclaredField("scheduler");
         schedulerField.setAccessible(true);
         scheduler = lookup.unreflectSetter(schedulerField);

         // this is to make sure we fail earlier!
         var builder = Thread.ofVirtual();
         scheduler.invoke(builder, new Executor() {
            @Override
            public void execute(Runnable command) {

            }
         });

         carrierThread = findVarHandle(Class.forName("java.lang.VirtualThread"), "carrierThread", Thread.class);

         FAILURE = null;

         sup = true;
      } catch (Throwable e) {
         scheduler = null;
         carrierThread = null;
         sup = false;
         FAILURE = e;
      }

      SCHEDULER = scheduler;
      SUPPORTED = sup;
      CARRIER_THREAD = carrierThread;
   }

   private static VarHandle findVarHandle(Class<?> declaringClass, String fieldName, Class<?> fieldType) {
      try {
         MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(declaringClass, MethodHandles.lookup());
         return lookup.findVarHandle(declaringClass, fieldName, fieldType);
      } catch (NoSuchFieldException | IllegalAccessException | IllegalArgumentException e) {
         throw new RuntimeException("Cannot get VarHandle for " +
                 declaringClass.getSimpleName() + "." + fieldName, e);
      }
   }

   private LoomSupport() {
   }

   public static boolean isSupported() {
      return SUPPORTED;
   }

   public static void checkSupported() {
      if (!isSupported()) {
         throw new UnsupportedOperationException(FAILURE);
      }
   }

   public static Thread getCarrierThread(Thread t) {
      if (!t.isVirtual()) {
         return t;
      }
      try {
         return (Thread) CARRIER_THREAD.getVolatile(t);
      } catch (Throwable e) {
         throw new RuntimeException(e);
      }
   }

   public static Thread.Builder.OfVirtual setVirtualThreadFactoryScheduler(Thread.Builder.OfVirtual builder,
                                                                           Executor vthreadScheduler) {
      checkSupported();
      try {
         SCHEDULER.invoke(builder, vthreadScheduler);
         return builder;
      } catch (Throwable e) {
         throw new RuntimeException(e);
      }
   }
}

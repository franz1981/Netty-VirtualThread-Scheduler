package io.netty.loom;

import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.channel.IoEventLoop;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;

public class VirtualMultithreadIoEventLoopGroup extends MultiThreadIoEventLoopGroup {

	private static final int RESUMED_CONTINUATIONS_EXPECTED_COUNT = Integer
			.getInteger("io.netty.loom.resumed.continuations", 1024);
	private ArrayList<EventLoopScheduler> eventLoopSchedulers;
	private AtomicLong nextScheduler;
	private ThreadFactory threadFactory;

	public VirtualMultithreadIoEventLoopGroup(int nThreads, IoHandlerFactory ioHandlerFactory) {
		super(nThreads, (Executor) command -> {
			throw new UnsupportedOperationException("this executor is not supposed to be used");
		}, ioHandlerFactory);
	}

	private static void validateNettyAvailability() {
		if (!NettyScheduler.isAvailable()) {
			throw new IllegalStateException(
					"-Djdk.virtualThreadScheduler.implClass=io.netty.loom.NettyScheduler is required to use VirtualMultithreadIoEventLoopGroup");
		}
	}

	/**
	 * Return a {@link ThreadFactory} that creates virtual threads tied to an
	 * {@link EventLoopScheduler} of this group.
	 *
	 * <p>
	 * If the current thread has an associated {@link EventLoopScheduler} whose
	 * {@link io.netty.channel.IoEventLoop#parent()} is this group, that scheduler's
	 * {@code virtualThreadFactory()} is returned so newly created virtual threads
	 * are associated with the current event loop.
	 * </p>
	 *
	 * <p>
	 * Otherwise a randomly assigned scheduler from this group is used and its
	 * {@code virtualThreadFactory()} is returned.
	 * </p>
	 *
	 * @return a {@link ThreadFactory} producing virtual threads backed by an
	 *         {@link EventLoopScheduler} of this group
	 **/
	public ThreadFactory vThreadFactory() {
		var schedulerRef = EventLoopScheduler.currentThreadSchedulerContext().scheduler();
		if (schedulerRef != null) {
			var scheduler = schedulerRef.get();
			if (scheduler != null && scheduler.ioEventLoop().parent() == this) {
				return scheduler.virtualThreadFactory();
			}
		}
		// assign a random one
		int schedulerIndex = (int) (nextScheduler.getAndIncrement() % executorCount());
		return eventLoopSchedulers.get(schedulerIndex).virtualThreadFactory();
	}

	@Override
	protected IoEventLoop newChild(Executor executor, IoHandlerFactory ioHandlerFactory,
			@SuppressWarnings("unused") Object... args) {
		validateNettyAvailability();
		if (eventLoopSchedulers == null) {
			eventLoopSchedulers = new ArrayList<>(executorCount());
			nextScheduler = new AtomicLong();
		}
		if (threadFactory == null) {
			threadFactory = newDefaultThreadFactory();
		}
		var customScheduler = new EventLoopScheduler(this, threadFactory, ioHandlerFactory,
				RESUMED_CONTINUATIONS_EXPECTED_COUNT);
		eventLoopSchedulers.add(customScheduler);
		return customScheduler.ioEventLoop();
	}

	@Override
	public void close() {
		shutdownGracefully();
	}
}

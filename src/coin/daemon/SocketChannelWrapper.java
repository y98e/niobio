package coin.daemon;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;

import org.json.*;

import coin.util.*;

//only write again if more than 4s passed
public class SocketChannelWrapper {
	private final SocketChannel socketChannel;

	private final ByteBuffer buffer;
	int errorCount = 0;
	public int unknownBlockCount = 0;
	private final LinkedList<Obj> toSend = new LinkedList<Obj>(); // TODO sync?
	public long lastTimeWrite = System.currentTimeMillis();

	public SocketChannelWrapper(final SocketChannel socketChannel, final int capacity) {
		this.socketChannel = socketChannel;
		this.buffer = ByteBuffer.allocate(capacity);
	}

	public byte[] array() {
		return buffer.array();
	}

	public int capacity() {
		return buffer.capacity();
	}

	public void clear() {
		buffer.clear();
		errorCount = 0;
	}

	public void close() throws IOException {
		socketChannel.close();
	}

	public SocketAddress getLocalAddress() throws IOException {
		return socketChannel.getLocalAddress();
	}

	public SocketAddress getRemoteAddress() throws IOException {
		return socketChannel.getRemoteAddress();
	}

	public boolean hasSomethingToSend() {
		return !(toSend.size() == 0);
	}

	public boolean isBlocking() {
		return socketChannel.isBlocking();
	}

	public boolean isOpen() {
		return socketChannel.isOpen();
	}

	public int read() throws IOException {
		try {
			return socketChannel.read(buffer);
		} catch (final IOException e) {
			return -1;
		}
	}

	public int remaining() {
		return buffer.remaining();
	}

	public int send() throws IOException {
		if (!hasSomethingToSend() || unknownBlockCount > 50) return -1;

		final Obj m = toSend.removeFirst();

		Util.p("INFO: WRITE request " + this);
		lastTimeWrite = System.currentTimeMillis();
		final byte[] bytes = Util.serialize(m);
		if (bytes.length > 50_000) socketChannel.configureBlocking(true);
		final int wrote = socketChannel.write(ByteBuffer.wrap(bytes));
		socketChannel.configureBlocking(false);
		Util.p("INFO: WROTE " + wrote + " bytes. bytes.length = " + bytes.length);
		return wrote;
	}

	public void send(final Obj msg) {
		toSend.add(msg);
	}

	@Override
	public String toString() {
		try {
			return socketChannel.getLocalAddress() + " -> " + socketChannel.getRemoteAddress();
		} catch (final IOException e) {
			return "ERROR: socketChannel problem: " + e.getMessage();
		}
	}
}

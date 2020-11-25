package coin.daemon;

import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.text.*;
import java.util.*;

import org.json.*;

import coin.crypto.*;
import coin.util.*;

class Node {

	private final static long startTime = System.currentTimeMillis();

	private final long id = Util.random.nextLong();

	private final ServerSocketChannel serverSocket;

	final List<String> mempoolTxs = new ArrayList<String>();

	private final List<String> mempoolInputs = new ArrayList<String>();

	private final int capacity;

	private final int blockHeaderSize;

	private long lastRequest = System.currentTimeMillis();

	private List<SocketChannelWrapper> p2pChannels;

	private final Blockchain blockchain;

	Node(final Blockchain blockchain) throws IOException, ParseException {
		super();
		this.capacity = Util.conf.getInt("maxBlockSize");
		this.blockHeaderSize = Util.conf.getInt("blockHeaderSize");
		this.serverSocket = DUtil.serverConfig(Util.conf.getInt("portP2P"));
		this.p2pChannels = DUtil.clientConfigAndConnect((String[]) Util.conf.get("seeds"), Util.conf.getInt("portP2P"),
				capacity);
		this.blockchain = blockchain;
	}

	private void askForBlocks() throws IOException {
		Util.p("Asking all for blocks");

		for (final SocketChannelWrapper s : p2pChannels) {
			DUtil.giveMeABlockMessage(s, blockchain.bestChain.getString("blockHash"), true);
		}

	}

	private void doNotConnectToYourSelf(final SocketChannelWrapper channel) throws IOException, InterruptedException {
		Util.p("INFO: Detecting self connections " + channel);
		final Obj msg = new Obj();
		msg.put("id", id);
		channel.send(msg);
	}

	private boolean readData(final SocketChannelWrapper channel) throws Exception {

		boolean disconnect = false;
		boolean added = false;

		if (channel.remaining() > 0) {

			final int readedBytes = channel.read();
			if (readedBytes <= 0) {
				// Util.p("INFO: nothing to be read");

			} else {
				try {
					String str = Util.deserializeString(channel.array());
					str = str.trim();
					if (str != null && str.length() > 10) {
						Util.p("INFO: net data: " + str.substring(0, 10) + ".."
								+ str.substring(str.length() - 10, str.length()));
					} else {
						Util.p("ERROR: String too small: " + str);
					}

					final Obj txOrBlockOrMsg = new Obj(str);
					channel.clear();

					if (txOrBlockOrMsg.has("lastBlockHash")) { // block
						Util.p("INFO: READ a BLOCK " + channel);
						added = blockchain.addBlock(txOrBlockOrMsg, channel);

						if (added) {
							// ask for the next block
							Util.p("INFO: Asking for the next block " + channel);
							DUtil.giveMeABlockMessage(channel, Crypto.sha(txOrBlockOrMsg), true);

							// send this block to all
							for (final SocketChannelWrapper s : p2pChannels) {
								if (!channel.equals(s)) channel.send(txOrBlockOrMsg);
							}
						}

					} else if (txOrBlockOrMsg.has("outputs")) { // transaction
						Util.p("INFO: READ a TRANSACTION " + channel);
						added = addTxToMemPool(txOrBlockOrMsg);

						if (added) {
							for (final SocketChannelWrapper s : p2pChannels) {
								if (!channel.equals(s)) channel.send(txOrBlockOrMsg);
							}
						}

					} else if (txOrBlockOrMsg.has("next")) { // request block or next block by hash
						final Boolean next = txOrBlockOrMsg.getBoolean("next");
						final String blockHash = txOrBlockOrMsg.getString("blockHash");
						Util.p("INFO: Somebody is asking for " + (next ? "a block after this:" : "this block:")
								+ blockHash + " - " + channel);

						Obj b = null;
						if (DUtil.blockExists(txOrBlockOrMsg.getString("blockHash"))) {
							if (next) {
								Util.p("INFO: next block:" + blockHash);
								b = DUtil.getNextBlock(blockHash);
							} else {
								Util.p("INFO: exactly block:" + blockHash);
								b = DUtil.getBlock(blockHash);
							}
						} else if (next) {
							Util.p("INFO: which block is this guy talking about? " + channel);
							DUtil.giveMeABlockMessage(channel, txOrBlockOrMsg.getString("blockHash"), false);
						}

						if (b != null) {
							Util.p("INFO: block response");
							channel.send(b);
						}

					} else if (txOrBlockOrMsg.has("id")) { // detect self connection
						final long nodeId = txOrBlockOrMsg.getLong("id");
						if (id == nodeId) {
							disconnect = true;
							doNotConnectToYourSelf(channel);
							DUtil.whoAmI.add(((InetSocketAddress) channel.getLocalAddress()).getHostString());
							DUtil.whoAmI.add(((InetSocketAddress) channel.getRemoteAddress()).getHostString());
						}
					} else {
						disconnect = true;
					}
				} catch (final StreamCorruptedException | JSONException e) {
					// e.printStackTrace();
					channel.errorCount++;
					if (channel.errorCount > 5) channel.clear();
					Util.p("WARN: is data not ready? " + readedBytes + " bytes: " + channel);

				} catch (ClassNotFoundException | IOException | InterruptedException e) {
					e.printStackTrace();
					disconnect = true;
				}
			}

		} else {
			Util.p("WARN: Are we under DoS attack? disconnecting " + channel);
			disconnect = true;
		}

		if (disconnect) {
			// disconnect after 5s to give some time to other node receive disconnect message
			new Thread(() -> {
				try {
					Thread.sleep(5000);
					synchronized (blockchain) {
						Util.p("WARN: disconnecting " + channel);
						channel.close();
					}
				} catch (IOException | InterruptedException e) {
					e.printStackTrace();
				}
			}).start();
		}

		return added;
	}

	boolean addTxToMemPool(final Obj tx) throws Exception {
		Util.p("INFO: trying add tx to mempool:" + tx);

		final String stx = tx.toString();
		if (stx.length() > 1024 || mempoolTxs.contains(stx)) {
			Util.p("WARN: tx add to mempool FAILED. Already added or size problem.");
			return false;
		}

		// check if some tx in mempool already use some input
		Arr inputs = tx.getArr("inputs");
		final List<String> txInputs = new ArrayList<String>();
		for (int i = 0; i < inputs.length(); i++) {
			final Obj input = inputs.getObj(i);
			final Obj out = DUtil.getOutput(input, blockchain.bestChain);
			if (out == null) return false;
			txInputs.add(out.toString());
		}

		if (!Collections.disjoint(txInputs, mempoolInputs)) {
			Util.p("WARN: tx add to mempool FAILED. Some inputs is already in mempool.");
			return false;
		}

		boolean success = false;
		if (DUtil.isValidTx(tx, blockchain.bestChain) != null) {
			mempoolTxs.add(tx.toString());
			inputs = tx.getArr("inputs");
			for (int i = 0; i < inputs.length(); i++) {
				final Obj input = inputs.getObj(i);
				final Obj out = DUtil.getOutput(input, blockchain.bestChain);
				mempoolInputs.add(out.toString());
			}
			success = true;
		}

		if (success) {
			Util.p("INFO: tx add to mempool SUCESS mempoolTxs.size=" + mempoolTxs.size());
		} else {
			Util.p("WARN: tx add to mempool FAILED mempoolTxs.size=" + mempoolTxs.size());
		}

		return success;
	}

	Arr getFromMemPool() throws Exception {
		// just setting vars
		final List<Obj> txs = new ArrayList<Obj>();
		final List<Obj> toRemove = new ArrayList<Obj>();
		int txBytesSize = 0;
		int totalSize = 0;
		final List<String> allInputs = new ArrayList<String>();
		List<String> txInputs = null;
		// ------------

		// mempool step. add txs from mempool until hit maxBlockSize (capacity)
		for (final String stx : mempoolTxs) {
			final Obj tx = new Obj(stx);
			txInputs = DUtil.isValidTx(tx, blockchain.bestChain);
			if (txInputs != null && Collections.disjoint(allInputs, txInputs)) {
				allInputs.addAll(txInputs);
				txBytesSize = Util.serialize(tx).length;
				if ((txBytesSize + totalSize) <= (capacity - blockHeaderSize)) { // is blockHeaderSize 389 bytes?
					txs.add(tx);
					totalSize += txBytesSize;
				}
			} else { // inputs already used
				toRemove.add(tx);
			}
		}

		// remove from mempool txs with used inputs
		for (final Obj t : toRemove) {
			mempoolTxs.remove(t.toString());
			final Arr inputs = t.getArr("inputs");
			for (int i = 0; i < inputs.length(); i++) {
				final Obj input = inputs.getObj(i);
				final Obj out = DUtil.getOutput(input, blockchain.bestChain);
				if (out != null) mempoolInputs.remove(out.toString());
			}
		}

		// fusion tx step. (to reduce UTXO)
		// 1) create a list of outputs (possible inputs) per user
		// 2) if user outputs > 1, then it can be joined in one output
		// 3) create fusion tx
		final Obj utxo = blockchain.bestChain.getObj("UTXO");
		final String[] txsUTXO = Obj.getNames(utxo);
		final Map<String, List<Obj>> userToPossibleInputs = new HashMap<String, List<Obj>>();
		if (txsUTXO != null && (txBytesSize + totalSize) <= (capacity - blockHeaderSize)) {
			// step 1
			tx: for (final String txHash : txsUTXO) {
				final Obj tx = utxo.getObj(txHash);
				final Arr outputs = tx.getArr("outputs");
				for (int i = 0; i < outputs.length(); i++) {
					final Obj out = outputs.getObj(i);
					// was some input used already? (in mempool step)
					if (allInputs.contains(out.toString())) break tx;
				}
				for (int i = 0; i < outputs.length(); i++) {
					final Obj out = outputs.getObj(i);
					if (!out.has("pubkey")) continue;
					final Obj in = new Obj();
					in.put("txHash", txHash);
					in.put("outIdx", i);
					final String user = out.getString("pubkey");
					List<Obj> userInputs = userToPossibleInputs.get(user);
					if (userInputs == null) userInputs = new ArrayList<Obj>();
					userInputs.add(in);
					userToPossibleInputs.put(user, userInputs);
				}
			}
			// step 2 and part of 3
			final List<Obj> fusionInputs = new ArrayList<Obj>();
			final List<Obj> fusionOutputs = new ArrayList<Obj>();
			for (final String user : userToPossibleInputs.keySet()) {
				final List<Obj> userInputs = userToPossibleInputs.get(user);
				if (userInputs.size() > 1) {
					fusionInputs.addAll(userInputs);
					// create one output for this user inputs
					long amount = 0;
					for (final Obj in : userInputs) {
						final Obj lastOutputs = DUtil.getOutput(in, blockchain.bestChain);
						amount += lastOutputs.getLong("amount");
					}
					final Obj newOutput = new Obj();
					newOutput.put("pubkey", user);
					newOutput.put("amount", amount);
					fusionOutputs.add(newOutput);
				}
			}
			// step 3
			if (fusionInputs.size() > 1 && fusionOutputs.size() > 0) {
				final Obj fusionTx = new Obj();
				fusionTx.put("time", System.currentTimeMillis());
				fusionTx.put("inputs", fusionInputs);
				fusionTx.put("outputs", fusionOutputs);
				txBytesSize = Util.serialize(fusionTx).length;
				if ((txBytesSize + totalSize) <= (capacity - blockHeaderSize)) { // is blockHeaderSize 389 bytes?
					txs.add(fusionTx);
					totalSize += txBytesSize;
				}
			}
		}
		return new Arr(txs);
	}

	// This method is called in a loop forever - while(true).
	// First: check for new client. If exists, put him on the channel list.
	// Second: If not new client and has something to send, send it.
	// Third: If nothing to send, read channels.
	// Fourth: After read all channels and has nothing to send, ask for more blocks or sleep a little
	// (check shouldIDoSomethingNow method)
	boolean p2pHandler() throws Exception {

		// is somebody trying connect to me?
		final SocketChannel newClient = serverSocket != null ? serverSocket.accept() : null;

		boolean somethingToSend = false;

		if (newClient == null) {
			Util.p("INFO: ...no new connection..handle the open channels.. (" + p2pChannels.size() + " peers)");

			// clear closed channels from list (clean p2p list)
			final List<SocketChannelWrapper> toRemove = new ArrayList<SocketChannelWrapper>();
			for (final SocketChannelWrapper channel : p2pChannels) {
				if (!channel.isOpen() || channel.isBlocking()) {
					toRemove.add(channel);
				}
			}
			p2pChannels.removeAll(toRemove);

			// send all
			SocketChannelWrapper c = null; // var just to remove channel if throw IOException
			try {
				for (final SocketChannelWrapper channel : p2pChannels) {
					c = channel;
					if (channel.hasSomethingToSend()) {
						channel.send();
						if (channel.hasSomethingToSend()) {
							somethingToSend = true;
							break;
						}
					}
				}
			} catch (final IOException e) {
				p2pChannels.remove(c);
			}

			// read channels until has something to send
			if (!somethingToSend) {
				for (final SocketChannelWrapper channel : p2pChannels) {
					somethingToSend = readData(channel);
					if (somethingToSend) break;
				}
			}

		} else {
			Util.p("INFO: SERVER *** We have a NEW CLIENT!!! ***");
			newClient.configureBlocking(false);
			final SocketChannelWrapper channel = new SocketChannelWrapper(newClient, capacity);
			p2pChannels.add(channel);
			doNotConnectToYourSelf(channel);
			somethingToSend = true;
		}

		return somethingToSend;

	}

	void sendAll(final Obj json) {
		for (final SocketChannelWrapper s : p2pChannels) s.send(json);
	}

	boolean shouldIDoSomethingNow() throws Exception {
		final long now = System.currentTimeMillis();
		final long secondsFromLastRequest = (now - lastRequest) / 1000;
		final long secondsFromStartTime = (now - startTime) / 1000;
		boolean sleep = false;

		// force update after 4 hours
		if (secondsFromStartTime > 14400) throw new Exception("update"); // 14400

		if (secondsFromLastRequest > 5 && p2pChannels.size() == 0) {

			lastRequest = now;
			Util.p("WARN: you are NOT connected to anyone. Trying connect seeds..");
			p2pChannels = DUtil.clientConfigAndConnect((String[]) Util.conf.get("seeds"), Util.conf.getInt("portP2P"),
					Util.conf.getInt("maxBlockSize"));

		} else if (secondsFromLastRequest > 20 && p2pChannels.size() != 0) { // Ask for more blocks
			lastRequest = now;
			askForBlocks();
		} else {
			sleep = true;
		}
		return sleep;
	}

}

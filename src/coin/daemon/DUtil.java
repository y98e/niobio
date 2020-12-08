package coin.daemon;

import java.io.*;
import java.math.*;
import java.net.*;
import java.nio.channels.*;
import java.security.*;
import java.security.spec.*;
import java.text.*;
import java.util.*;

import org.json.*;

import coin.crypto.*;
import coin.util.*;

// all static methods
public abstract class DUtil {
	static List<String> whoAmI;

	// load whoAmI
	static {
		whoAmI = new ArrayList<String>();
		Enumeration<NetworkInterface> e;
		try {
			e = NetworkInterface.getNetworkInterfaces();
			while (e.hasMoreElements()) {
				final NetworkInterface n = e.nextElement();
				final Enumeration<InetAddress> ee = n.getInetAddresses();
				while (ee.hasMoreElements()) {
					final InetAddress i = ee.nextElement();
					whoAmI.add(i.getHostAddress());
				}
			}
			Util.p("INFO: WhoAmI? " + whoAmI);
		} catch (final SocketException e1) {
			e1.printStackTrace();
			System.exit(0);
		}
	}

	public static String getBlockFileName(final long height, final int i) {
		String folder = null;
		if (height == 0) folder = "";
		else folder = String.format("%012d", (((height - 1) / 10000) * 10000 + 1)) + "/";
		return Util.conf.getString("folderBlocks") + folder + String.format("%012d", height) + "_" + i + ".json";
	}

	// input = txHash, outIdx
	private static boolean checkInputFormat(final Obj in) {
		final String[] keys = Obj.getNames(in);
		if (keys.length != 2) return false;
		final List<String> list = Arrays.asList(keys);
		if (!list.contains("txHash") || !list.contains("outIdx")) return false;
		return true;
	}

	// output = pubkey, amount
	private static boolean checkOutputFormat(final Obj out) {
		final String[] keys = Obj.getNames(out);
		if (keys.length != 2) return false;
		final List<String> list = Arrays.asList(keys);
		if (!list.contains("pubkey") || !list.contains("amount")) return false;
		return true;
	}

	private static long getReward(final Obj chain, final boolean next) {
		long reward = Util.conf.getLong("reward");
		long height = chain.getLong("height");
		if (next) height++;
		final long divReward = height / 210_000;
		reward >>>= divReward;
		return reward;
	}

	private static double targetAdjustment(final Obj block, final Obj newChain) {
		Util.p("INFO: block time: " + Util.simpleDateFormat.format(new Date(block.getLong("time"))) + ", expected: "
				+ Util.simpleDateFormat.format(new Date((newChain.getLong("height") * Util.conf.getLong("blockTime"))
						+ Util.conf.getLong("startTime"))));
		return ((double) ((newChain.getLong("height") * Util.conf.getLong("blockTime"))
				+ Util.conf.getLong("startTime")) / block.getLong("time"));
	}

	static boolean blockExists(final String blockHash) {
		if (new File(Util.conf.getString("folderUTXO") + blockHash + ".json").exists()) return true;
		else return false;
	}

	static boolean checkFusionTx(final Obj chain, final Obj tx) {
		final Map<String, Long> user2Balance = new HashMap<String, Long>();

		String pk = null;
		// get old outputs
		final Arr inputs = tx.getArr("inputs");
		for (int i = 0; i < inputs.length(); i++) {
			final Obj in = inputs.getObj(i);
			final Obj out = getOutput(in, chain);
			if (out == null) return false;
			pk = out.getString("pubkey");
			if (user2Balance.containsKey(pk)) {
				user2Balance.put(pk, user2Balance.get(pk) + out.getLong("amount"));
			} else {
				user2Balance.put(pk, out.getLong("amount"));
			}
		}

		// check if it is like the new outputs
		final Arr outputs = tx.getArr("outputs");
		for (int i = 0; i < outputs.length(); i++) {
			final Obj out = outputs.getObj(i);
			pk = out.getString("pubkey");
			if (!user2Balance.containsKey(pk) || user2Balance.get(pk) != out.getLong("amount")) {
				return false;
			}
		}
		return true;
	}

	static boolean checkInputsTxSignature(final Obj chain, final Obj tx) throws Exception {
		String signatureString = null;

		if (tx.has("inputs")) {
			final Arr inputs = tx.getArr("inputs");
			for (int i = 0; i < inputs.length(); i++) {
				final Obj in = inputs.getObj(i);
				final Obj out = getOutput(in, chain);
				if (out == null || !out.has("pubkey")) return false;
				signatureString = tx.getString("sig");
				tx.remove("sig");
				if (!Crypto.verify(out.getString("pubkey"), tx, signatureString)) {
					return false;
				}
				tx.put("sig", signatureString);
			}
		}
		return true;
	}

	static boolean checkPositiveValues(final Obj chain, final Obj tx) {
		if (tx.has("inputs")) {
			final Arr inputs = tx.getArr("inputs");
			for (int i = 0; i < inputs.length(); i++) {
				final Obj in = inputs.getObj(i);
				if (!checkInputFormat(in)) return false;
				final Obj out = getOutput(in, chain);
				if (out == null) return false;
				if (out.getLong("amount") <= 0) return false;
			}
		}

		final Arr outputs = tx.getArr("outputs");
		for (int i = 0; i < outputs.length(); i++) {
			final Obj out = outputs.getObj(i);
			if (!checkOutputFormat(out)) return false;
			if (out == null) return false;
			if (out.getLong("amount") <= 0) return false;
		}
		return true;
	}

	static List<SocketChannelWrapper> clientConfigAndConnect(final String[] seeds, final int port, final int capacity)
			throws IOException {
		final List<SocketChannelWrapper> p2pChannels = new ArrayList<SocketChannelWrapper>();
		SocketChannel socketChannel = null;

		if (seeds != null) {
			for (final String s : seeds) {
				try {
					InetAddress server = null;
					try {
						server = InetAddress.getByName(s);
					} catch (final UnknownHostException e) {
						Util.p("WARN: " + e.getMessage());
						continue;
					}

					// Am i trying to connect to myself?
					if (DUtil.whoAmI.contains(server.getHostAddress())) {
						Util.p("WARN: do NOT connect to yourself");
						continue;
					}

					socketChannel = SocketChannel.open(new InetSocketAddress(s, port));
					socketChannel.configureBlocking(false);
					p2pChannels.add(new SocketChannelWrapper(socketChannel, capacity));
					Util.p("INFO: i am CLIENT: " + socketChannel.getLocalAddress() + " of SERVER "
							+ socketChannel.getRemoteAddress());
				} catch (ConnectException | UnresolvedAddressException e) {
					Util.p("WARN: can NOT connect to SERVER " + s);
				}
			}
		}
		return p2pChannels;
	}

	// block with just genesis message inside
	static Obj createGenesisBlock() throws IOException, ParseException {

		final String genesis = Util.conf.getString("genesis");
		final long reward = Util.conf.getLong("reward");
		final Long startTime = Util.simpleDateFormat.parse(Util.conf.getString("time")).getTime();

		final Obj out = new Obj();
		out.put("pubkey", "A015Vnj1lLskdbDCcXXrD4ty18qNmrc33Mj34QPeL06W");
		out.put("amount", reward);

		final Arr outputs = new Arr();
		outputs.put(out);

		final Obj genesisTx = new Obj();
		genesisTx.put("outputs", outputs);
		genesisTx.put("msg", genesis);
		genesisTx.put("time", startTime);

		final Arr txs = new Arr();
		txs.put(genesisTx);

		final Obj genesisBlock = new Obj();
		// String.format("%064d", 0)
		genesisBlock.put("lastBlockHash", "0000000000000000000000000000000000000000000000000000000000000000");
		genesisBlock.put("txsHash", Crypto.sha(txs));
		genesisBlock.put("time", startTime);
		genesisBlock.put("nonce", 40513L);

//		final BigInteger target = new BigInteger(Util.conf.getString("target"), 16);
//		long i = 0;
//		Util.p("Mining genesis");
//		for (; i < Long.MAX_VALUE; i++) {
//			genesisBlock.put("nonce", i);
//			final BigInteger sha = Crypto.shaMine(genesisBlock);
//			if (target.compareTo(sha) > 0) {
//				System.out.println("NONCE=" + i);
//				System.exit(0);
//			}
//		}

		genesisBlock.put("txs", txs);

		Util.conf.put("genesisHash", Crypto.sha(genesisBlock));

		return genesisBlock;
	}

	static Obj createGenesisChain(final Obj genesisBlock) {
		final Obj chain = new Obj();
		chain.put("height", -1);
		chain.put("chainWork", BigInteger.ZERO);
		chain.put("blockHash", genesisBlock.getString("lastBlockHash"));
		chain.put("target", Util.conf.getString("target"));
		chain.put("UTXO", new Obj());
		return chain;
	}

	// delete old UTXO to avoid deep reorg (delete = rename utxo to snapshot)
	static void deleteOldChain(final long actualHeight) throws IOException, ClassNotFoundException {
		String fileName = null;
		final long height = actualHeight - 10;
		if (height > 0) {
			int j;
			Obj b = null;
			for (j = 1; j < 10; j++) {
				fileName = getBlockFileName(height, j);
				if (new File(fileName).exists()) {
					b = Util.loadObjFromFile(fileName);
				} else {
					break;
				}
			}
			// if only one block at that height, save snapshot
			if (j == 2) {
				final File lastUTXO = new File(Util.conf.getString("folderUTXO") + Crypto.sha(b) + ".json");
				if (lastUTXO.exists()) {
					final File snapshot = new File(Util.conf.getString("snapshot"));
					if (snapshot.exists()) snapshot.delete();

					lastUTXO.renameTo(new File(Util.conf.getString("snapshot")));
				} /* else Util.p("WARN: NO SNAPSHOT"); */
			}
		}
	}

	static long getActualReward(final Obj chain) {
		return getReward(chain, false);
	}

	static Obj getBlock(final String blockHash) throws ClassNotFoundException, IOException {
		final Obj chain = Util.loadObjFromFile(Util.conf.getString("folderUTXO") + blockHash + ".json");
		for (int j = 1; j < 10; j++) {
			final String fileName = getBlockFileName(chain.getInt("height"), j);
			if (new File(fileName).exists()) {
				final Obj block = Util.loadObjFromFile(fileName);
				if (blockHash.equals(Crypto.sha(block))) {
					return block;
				}
			} else break;
		}
		return null;
	}

	static Obj getNextBlock(final String blockHash) throws ClassNotFoundException, IOException {
		Obj next = null;
		final List<Obj> candidates = new ArrayList<Obj>();

		final Obj chain = Util.loadObjFromFile(Util.conf.getString("folderUTXO") + blockHash + ".json");
		for (int j = 1; j < 10; j++) {
			final String fileName = getBlockFileName((chain.getInt("height") + 1), j);
			if (new File(fileName).exists()) {
				final Obj block = Util.loadObjFromFile(fileName);
				if (block.getString("lastBlockHash").equals(blockHash)) {
					candidates.add(block);
					break;
				}
			} else break;
		}

		if (candidates.size() == 1) {
			next = candidates.get(0);
		} else if (candidates.size() > 1) {
			next = candidates.get(Util.random.nextInt(candidates.size()));
		}

		return next;
	}

	static long getNextReward(final Obj chain) {
		return getReward(chain, true);
	}

	// read comment below
	static Obj getOutput(final Obj input, final Obj chain) {
		final Obj utxo = chain.getObj("UTXO");
		final String txHash = input.getString("txHash");
		if (!utxo.has(txHash)) return null;
		final Obj tx = utxo.getObj(txHash);
		final Arr outputs = tx.getArr("outputs");
		final Obj out = outputs.getObj(input.getInt("outIdx"));

		final Obj newOut = new Obj(out.toString());
		// put input to be able to diff outputs with same value and pubkey
		newOut.put("txHash", input.getString("txHash"));
		newOut.put("outIdx", input.getInt("outIdx"));
		return newOut;
	}

	static void giveMeABlockMessage(final SocketChannelWrapper channel, final String blockHash, final boolean next)
			throws IOException {
		final Obj giveMeABlockMessage = new Obj();
		giveMeABlockMessage.put("blockHash", blockHash);
		giveMeABlockMessage.put("next", next);
		channel.send(giveMeABlockMessage);
	}

	static boolean isGenesisBlock(final Obj block) throws IOException {
		return block.getString("lastBlockHash")
				.equals("0000000000000000000000000000000000000000000000000000000000000000")
				&& Crypto.sha(block).equals(Util.conf.getString("genesisHash"));
	}

	// return null = return false
	static List<String> isValidTx(final Obj tx, final Obj chain) throws Exception {

		final String stx = tx.toString();
		if (stx.length() > 1024) return null;

		if (!tx.has("time") || !tx.has("outputs") || tx.getArr("outputs").length() < 1) return null;

		if (!DUtil.checkInputsTxSignature(chain, tx)) return null;

		if (!DUtil.checkPositiveValues(chain, tx)) return null;

		final Arr array = new Arr();
		array.put(tx);
		if (DUtil.sumOfInputs(chain, array) != DUtil.sumOfOutputs(chain, array)) return null;

		// check if inputs still valid until now
		final Arr inputs = tx.getArr("inputs");
		List<String> inputList = null;
		final Obj utxo = chain.getObj("UTXO");
		String inputStr = null;
		if (inputs != null) {
			for (int i = 0; i < inputs.length(); i++) {
				final Obj in = inputs.getObj(i);
				final Obj lastTx = utxo.getObj(in.getString("txHash"));
				if (lastTx == null) return null;
				final Arr outputs = lastTx.getArr("outputs");
				final Obj out = outputs.getObj(in.getInt("outIdx"));
				if (out == null) return null;
				inputStr = out.toString();
				if (inputList == null) inputList = new ArrayList<String>();
				if (inputList.contains(inputStr)) return null;
				else inputList.add(inputStr);
			}
		}
		return inputList;
	}

	static Obj newChain(final Obj block, final Obj chain)
			throws IOException, ClassNotFoundException, InvalidKeySpecException, NoSuchAlgorithmException {

		final String blockHash = Crypto.sha(block);
		final Obj newChain = new Obj(chain.toString());
		newChain.put("height", chain.getLong("height") + 1);
		newChain.put("blockHash", blockHash);

		final BigInteger target = new BigInteger(newChain.getString("target"), 16);

		// blockWork = 2^256 / (target+1)
		final BigInteger max = new BigInteger("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", 16);
		final BigInteger blockWork = max.divide(target.add(BigInteger.ONE));
		newChain.put("chainWork", newChain.getBigInteger("chainWork").add(blockWork));

		final double x = targetAdjustment(block, newChain);

		if (x == 1.0) {
			// do nothing
		} else if (x < 1) {
			// if newTarget > maxTarget then do not decrease difficult
			final BigInteger maxTarget = new BigInteger(Util.conf.getString("target"), 16);
			BigInteger newTarget = target.subtract(target.shiftRight(5));

			if (newTarget.compareTo(maxTarget) > 0) {
				newTarget = maxTarget;
				newChain.put("target", Util.targetToString(newTarget));
				Util.p("INFO: minimal diff (target value in conf file)");
			} else {
				// target = target + (3.125% target)
				newChain.put("target", Util.targetToString(target.add(target.shiftRight(5))));
				Util.p("INFO: diff decrease 3.125%");
			}

		} else {
			// target = target - (3.125% target)
			newChain.put("target", Util.targetToString(target.subtract(target.shiftRight(5))));
			Util.p("INFO: diff increase 3.125%");
		}

		DUtil.utxoUpdate(newChain.getObj("UTXO"), block);

		return newChain;
	}

	static void saveBlock(final Obj chain, final Obj block) throws IOException {
		String fileName = null;
		int i = 0;
		final long height = chain.getLong("height");
		do {
			i++;
			fileName = getBlockFileName(height, i);
		} while (new File(fileName).exists());

		new File(Util.conf.getString("folderBlocks")).mkdirs();

		// stop saving genesis block all the time
		if (height == 0 && i > 1) return;

		Util.writeToFile(fileName, block);
	}

	static void saveNewChain(final Obj chain) throws IOException {
		Util.writeToFile(Util.conf.getString("folderUTXO") + chain.getString("blockHash") + ".json", chain);
	}

	static ServerSocketChannel serverConfig(final int port) throws IOException {
		final ServerSocketChannel serverSC = ServerSocketChannel.open();
		try {
			serverSC.configureBlocking(false);
			serverSC.bind(new InetSocketAddress(port));
		} catch (final BindException e) {
			Util.p("ERROR: can NOT start a SERVER");
			return null;
		}
		return serverSC;
	}

	static long sumOfInputs(final Obj chain, final Arr txs) {
		long s = 0;
		if (txs.length() > 0) {
			for (int i = 0; i < txs.length(); i++) {
				final Obj tx = txs.getObj(i);
				if (tx.has("inputs")) {
					final Arr inputs = tx.getArr("inputs");
					for (int j = 0; j < inputs.length(); j++) {
						final Obj in = inputs.getObj(j);
						final Obj out = getOutput(in, chain);
						if (out == null) return -1;
						s += out.getLong("amount");
					}
				}
			}
		}
		return s;
	}

	static long sumOfOutputs(final Obj chain, final Arr txs) {
		long s = 0;
		for (int i = 0; i < txs.length(); i++) {
			final Obj tx = txs.getObj(i);
			if (tx.has("outputs")) {
				final Arr outputs = tx.getArr("outputs");
				for (int j = 0; j < outputs.length(); j++) {
					final Obj out = outputs.getObj(j);
					if (out.getLong("amount") <= 0) return -1;
					s += out.getLong("amount");
				}
			}
		}
		return s;
	}

	// clean outputs (block inputs) and add new outputs (block outputs)
	static void utxoUpdate(final Obj UTXO, final Obj block) throws IOException, ClassNotFoundException {
		boolean tryRemoveLastTx = false;
		final Arr txs = block.getArr("txs");

		for (int i = 0; i < txs.length(); i++) {
			final Obj tx = txs.getObj(i);
			if (tx.has("inputs")) {
				final Arr inputs = tx.getArr("inputs");
				for (int j = 0; j < inputs.length(); j++) {
					final Obj in = inputs.getObj(j);
					final Obj lastTx = UTXO.getObj(in.getString("txHash"));

					if (lastTx == null) continue;
					final Arr outputs = lastTx.getArr("outputs");
					outputs.put(in.getInt("outIdx"), new Obj()); // this output was spend

					// TODO getOutput(in) is wrong. check!!!
					// did i already solve?

					// if all outputs are null, delete tx from utxo
					tryRemoveLastTx = true;
					for (int k = 0; k < outputs.length(); k++) {
						final Obj out = outputs.getObj(k);
						if (out.has("pubkey")) {
							tryRemoveLastTx = false;
							break;
						}

					}
					if (tryRemoveLastTx) UTXO.remove(in.getString("txHash"));
				}
			}
		}

		for (int i = 0; i < txs.length(); i++) {
			final Obj tx = txs.getObj(i);
			final String txHash = Crypto.sha(tx);
			// utxo dont need inputs and other stuffs. only outputs and signature
			final Obj clone = new Obj();
			clone.put("outputs", tx.getArr("outputs"));
			if (tx.has("sig")) clone.put("sig", tx.getString("sig"));
			UTXO.put(txHash, clone);
		}
	}
}

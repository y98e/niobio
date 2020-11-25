package coin.daemon;

import java.io.*;
import java.math.*;
import java.util.*;

import org.json.*;

import coin.crypto.*;
import coin.util.*;

class Blockchain {

	Obj bestChain;

	// last5 is data just to keep lastest transactions in memory.
	// so user can check the status of last transactions that he made
	private final LinkedList<Obj> last5Blocks = new LinkedList<Obj>();
	Obj userToStatements = new Obj();

	// first time? create genesis block. otherwise load snapshot and read a few blocks after that to create UTXOs
	Blockchain() throws Exception {

		// clean UTXOs
		Util.cleanFolder(Util.conf.getString("folderUTXO"));

		long i = 1;
		String fileName = null;

		// if snapshot not exists
		if (!new File(Util.conf.getString("snapshot")).exists()) {
			Util.p("INFO: NO snapshot. Loading from the begining (height=0)");
			final Obj genesisBlock = DUtil.createGenesisBlock();
			bestChain = DUtil.createGenesisChain(genesisBlock);
			addBlock(genesisBlock, null);

		} else {
			Util.p("INFO: loading snapshot");
			final Obj snapshot = Util.loadObjFromFile(Util.conf.getString("snapshot"));
			Util.p("INFO: chain height: " + snapshot.getLong("height"));

			DUtil.saveNewChain(snapshot);
			bestChain = snapshot;
			i = bestChain.getLong("height") + 1;
		}

		// read blocks
		x: for (; i < Long.MAX_VALUE; i++) {
			for (int j = 1; j < 10; j++) {
				fileName = DUtil.getBlockFileName(i, j);
				if (new File(fileName).exists()) {
					final Obj block = Util.loadObjFromFile(fileName);
					addChain(block); // create UTXO
				} else {
					if (j == 1) break x;
					else break;
				}
			}
		}
	}

	public boolean addBlock(final Obj block, final boolean persistBlock, final boolean persistChain,
			final SocketChannelWrapper from) throws Exception {

		final String blockHash = Crypto.sha(block);

		if (DUtil.blockExists(blockHash)) {
			Util.p("INFO: we already have this block");
			return false;
		}

		final long now = System.currentTimeMillis();
		if (block.getLong("time") > now) {
			Util.p("WARN: is this block from the future? " + (block.getLong("time") - now) / 1000 + "s to be good");
			return false;
		}

		final String txsHash = Crypto.sha(block.getArr("txs"));
		if (!txsHash.equals(block.getString("txsHash"))) {
			Util.p("WARN: block.txsHash != sha(block.txs)");
			return false;
		}

		Obj chain = null;
		if (DUtil.isGenesisBlock(block)) {
			chain = bestChain;
		} else {
			try {
				chain = Util.loadObjFromFile(
						Util.conf.getString("folderUTXO") + block.getString("lastBlockHash") + ".json");
			} catch (final java.nio.file.NoSuchFileException e) {
			}
		}

		// if we know the chain of this block
		if (chain != null) {
//			Util.p("INFO: trying add BLOCK:" + block);
//			Util.p("INFO: in this CHAIN:" + chain);

			// calc reward
			final long reward = DUtil.getNextReward(chain);

			// check work
			final BigInteger chainTarget = new BigInteger(chain.getString("target"), 16);
			final BigInteger blockTarget = new BigInteger(blockHash, 16);

			// if the work was done, check transactions
			if (chainTarget.compareTo(blockTarget) > 0) {

				Util.p("INFO: NEW BLOCK! WORK DONE!");
				Util.p("INFO: target:" + chain.getString("target"));
				Util.p("INFO: bkHash:" + blockHash);

				if (block.getArr("txs") != null) { // null == not even coinbase tx??

					final Arr txs = block.getArr("txs");

					// check if some input was used more than once in any tx
					final List<String> allInputs = new ArrayList<String>();

					for (int i = 0; i < txs.length(); i++) {
						final Obj tx = txs.getObj(i);

						if (!tx.has("time") || !tx.has("outputs") || tx.getArr("outputs").length() < 1) {
							Util.p("WARN: INVALID BLOCK. Tx must have time and output.");
							return false;
						}

						// get all inputs of this tx (to see if some input was used twice)
						if (tx.has("inputs")) {
							final Arr inputs = tx.getArr("inputs");
							for (int j = 0; j < inputs.length(); j++) {
								final Obj in = inputs.getObj(j);
								final String inputStr = DUtil.getOutput(in, chain).toString();
								if (inputStr != null && !allInputs.contains(inputStr)) {
									allInputs.add(inputStr);
								} else {
									Util.p("WARN: INVALID BLOCK. Same input used twice." + inputStr);
									return false;
								}
							}
						}

						// if this is a regular tx, validate signature
						if (tx.has("sig")) {
							if (!DUtil.checkInputsTxSignature(chain, tx)) {
								Util.p("WARN: INVALID BLOCK. Wrong txs signature.");
								return false;
							}

						} else if (tx.has("inputs") && !DUtil.checkFusionTx(chain, tx)) { // validate fusion tx
							Util.p("WARN: INVALID BLOCK. Invalid fusion tx.");
							return false;
						}

						if (!DUtil.checkPositiveValues(chain, tx)) { // do not allow negative amounts
							Util.p("WARN: INVALID BLOCK. Invalid format or some negative amount.");
							return false;
						}
					}

					final long sumOfInputs = DUtil.sumOfInputs(chain, block.getArr("txs"));
					final long sumOfOutputs = DUtil.sumOfOutputs(chain, block.getArr("txs"));

					// check if inputs = outputs + reward
					if (sumOfOutputs == (sumOfInputs + reward)) {

						final Obj newChain = DUtil.newChain(block, chain);

						if (persistBlock) {
							DUtil.saveBlock(newChain, block);
						}

						if (persistChain) {
							DUtil.saveNewChain(newChain);
							DUtil.deleteOldChain(newChain.getLong("height"));
						}

						if (persistChain && newChain.getBigInteger("chainWork")
								.compareTo(bestChain.getBigInteger("chainWork")) > 0) {
							Util.p("INFO: new bestChain. height=" + newChain.getLong("height") + " blockHash="
									+ blockHash);
							bestChain = newChain;

							update5Blocks(block);

							// tell the miner about this new best chain to mine
							if (persistBlock && !DUtil.isGenesisBlock(block)) {
								final Obj blockTemplate = Daemon.getBlockTemplate();
								new Thread(() -> {
									Thread.currentThread().setName("Blockchain " + Util.random.nextInt(100));
									Util.p("INFO: Daemon to Miner (sending blockTemplate) " + blockTemplate);
									RPC.toMiner(blockTemplate);
								}).start();
							}

						} else {
							if (persistChain)
								Util.p("WARN: this new block is NOT to my best blockchain. chain: " + newChain);
						}

					} else {
						Util.p("WARN: INVALID BLOCK. Inputs + Reward != Outputs");
						return false;
					}
				} else {
					Util.p("WARN: INVALID BLOCK. No transactions.");
					return false;
				}
			} else {
				Util.p("WARN: INVALID BLOCK. Invalid PoW.");
				Util.p("INFO: target:" + chain.getString("target"));
				Util.p("INFO: bkHash:" + blockHash);
				return false;
			}
		} else {
			Util.p("WARN: Unknown 'last block' of this Block. Asking for block.");
			if (from != null) {
				from.unknownBlockCount++;
				DUtil.giveMeABlockMessage(from, block.getString("lastBlockHash"), false);
			}
			return false;
		}
		return true;
	}

	public boolean addBlock(final Obj block, final SocketChannelWrapper channel) throws Exception {
		return addBlock(block, true, true, channel);
	}

	public boolean addChain(final Obj block) throws Exception {
		return addBlock(block, false, true, null);
	}

	private void update5Blocks(final Obj block) throws ClassNotFoundException, JSONException, IOException {

		if (last5Blocks.size() == 5) {
			final Obj oldLastBlock = DUtil.getBlock(block.getString("lastBlockHash"));

			if (last5Blocks.get(4).equals(oldLastBlock)) {
				last5Blocks.add(block);
				if (last5Blocks.size() > 5) last5Blocks.removeFirst();
			} else {
				// re-create last5Blocks
				last5Blocks.clear();
				final Obj block4 = oldLastBlock;
				final Obj block3 = DUtil.getBlock(block4.getString("lastBlockHash"));
				final Obj block2 = DUtil.getBlock(block3.getString("lastBlockHash"));
				final Obj block1 = DUtil.getBlock(block2.getString("lastBlockHash"));
				last5Blocks.add(block1);
				last5Blocks.add(block2);
				last5Blocks.add(block3);
				last5Blocks.add(block4);
				last5Blocks.add(block);
			}
		} else {
			last5Blocks.add(block);
		}

		// re-create userToStatements

		// load alltxs and allusers
		final Map<String, Obj> alltxs = new HashMap<String, Obj>();
		final List<String> allusers = new ArrayList<String>();
		for (final Obj b : last5Blocks) {
			final Arr txs = b.getArr("txs");
			for (int i = 0; i < txs.length(); i++) {
				final Obj tx = txs.getObj(i);
				alltxs.put(Crypto.sha(tx), tx);
				final Arr outputs = tx.getArr("outputs");
				for (int j = 0; j < outputs.length(); j++) {
					final Obj out = outputs.getObj(j);
					final String pubkey = out.getString("pubkey");
					if (!allusers.contains(pubkey)) allusers.add(pubkey);
				}
			}
		}

		// load spent outputs (inputs) and unspent outputs (not yet inputs) for each user
		final Map<String, Arr> userLess = new HashMap<String, Arr>();
		final Map<String, Arr> userMore = new HashMap<String, Arr>();
		for (final Obj b : last5Blocks) {
			final String sha = Crypto.sha(b);
			final Arr txs = b.getArr("txs");
			for (int i = 0; i < txs.length(); i++) {
				final Obj tx = txs.getObj(i);
				if (tx.has("inputs")) {
					final Arr inputs = tx.getArr("inputs");
					// spent outputs
					for (int j = 0; j < inputs.length(); j++) {
						final Obj in = inputs.getObj(j);
						final String txHash = in.getString("txHash");
						if (alltxs.containsKey(txHash)) {
							final Obj oldTx = alltxs.get(txHash);
							final Arr outputs = oldTx.getArr("outputs");
							final Obj oldOut = outputs.getObj(in.getInt("outIdx"));
							final String pubkey = oldOut.getString("pubkey");
							Arr less = userLess.get(pubkey);
							if (less == null) less = new Arr();
							final Obj o = new Obj(oldOut.toString());
							o.remove("pubkey");
							o.put("blockHash", sha);
							less.put(o);
							userLess.put(pubkey, less);
						}
					}
				}
				// unspent outputs
				final Arr outputs = tx.getArr("outputs");
				for (int j = 0; j < outputs.length(); j++) {
					final Obj out = outputs.getObj(j);
					final String pubkey = out.getString("pubkey");
					Arr more = userMore.get(pubkey);
					if (more == null) more = new Arr();
					final Obj o = new Obj(out.toString());
					o.remove("pubkey");
					o.put("blockHash", sha);
					if (tx.has("inputs")) {
						o.put("outputs", outputs);
					}
					more.put(o);
					userMore.put(pubkey, more);
				}
			}
		}

		userToStatements = new Obj();

		for (final String u : allusers) {

			final Arr more = userMore.get(u);
			final Arr less = userLess.get(u);

			final Arr diff = new Arr();
			if (more != null) {
				for (int i = 0; i < more.length(); i++) {
					final Obj out = more.getObj(i);
					out.put("amount", "+" + out.getLong("amount"));
					diff.put(out);
				}
			}

			if (less != null) {
				for (int i = 0; i < less.length(); i++) {
					final Obj out = less.getObj(i);
					out.put("amount", "-" + out.getLong("amount"));
					diff.put(out);
				}
			}

			final List<Integer> toRemove = new ArrayList<Integer>();
			for (int i = 0; i < diff.length(); i++) {
				for (int j = 0; j < diff.length(); j++) {
					if (i == j || toRemove.contains(i)) continue;
					final Obj oi = diff.getObj(i);
					final Obj oj = diff.getObj(j);
					if (oi.getString("blockHash").equals(oj.getString("blockHash"))) {
						oi.put("amount", oj.getString("amount") + oi.getString("amount"));
						toRemove.add(j);
					}
				}
			}

			Collections.sort(toRemove, Collections.reverseOrder());
			for (final int i : toRemove) {
				diff.remove(i);
			}

			userToStatements.put(u, diff);
		}

	}

}
package coin.daemon;

import java.io.*;
import java.math.*;
import java.util.*;

import org.json.*;

import coin.crypto.*;
import coin.util.*;

class Blockchain {

	Obj bestChain;

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

							// tell the miner about this new best chain to mine
							if (persistBlock && !DUtil.isGenesisBlock(block)) {
								final Obj blockTemplate = Daemon.getBlockTemplate();
								new Thread(() -> {
									Thread.currentThread().setName("Blockchain " + Util.random.nextInt(100));
									Util.p("INFO: Daemon to Miner (sending blockTemplate) " + blockTemplate);
									RPC.toMiner(blockTemplate);
								}).start();
								new Thread(() -> {
									Thread.currentThread().setName("Blockchain " + Util.random.nextInt(100));
									Util.p("INFO: Daemon to Explorer");
									RPC.toExplorer(block);
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

}
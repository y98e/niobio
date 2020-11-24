package coin.miner;

import java.io.*;
import java.math.*;

import org.json.*;

import coin.crypto.*;
import coin.util.*;

class Candidate {

	private final Obj block;

	private final BigInteger target;

	private final Arr txs;

	private final long height;

	private final long time;

	// transform blockTemplate into a block to mine
	Candidate(final String pubkey, final Obj blockTemplate) throws IOException {
		block = blockTemplate;

		// get and remove target
		target = new BigInteger(block.getString("target"), 16);
		block.remove("target");

		height = block.getLong("height");
		block.remove("height");

		final long shouldBeAt = Util.conf.getLong("startTime") + ((height + 1) * Util.conf.getLong("blockTime"));

		final long now = System.currentTimeMillis();

		time = now > shouldBeAt ? shouldBeAt : now;

		// get and remove reward, txs and create coinbase
		final long reward = block.getLong("reward");
		txs = block.getArr("txs");
		if (reward > 0) {
			final Obj tx = new Obj();
			final Arr outputs = new Arr();
			final Obj out = new Obj("{\"pubkey\":\"" + pubkey + "\", \"amount\":" + block.getLong("reward") + "}");
			outputs.put(out);
			tx.put("outputs", outputs);
			tx.put("time", time);
			txs.put(tx);
		}
		block.remove("reward");
		block.remove("txs");

		// add txsHash
		block.put("txsHash", Crypto.sha(txs));
		block.put("time", time);
	}

	// start with a random number (nonce) and follow in sequence
	void mine() throws IOException, InterruptedException {
		long i = Util.random.nextLong();

		for (;; i++) {
			block.put("nonce", i);
			final BigInteger sha = Crypto.shaMine(block);
			if (target.compareTo(sha) > 0) {
				block.put("txs", txs);
				Util.p("*** YEAH ***: I mine! Sending to daemon " + block);
				new Thread(() -> {
					RPC.toDaemon(block);
				}).start();
				return;
			}

			if (i != 0 && i % 1000000 == 0) {
				Util.p("INFO: mining status (each 1000000 rounds)...");
				Util.p("INFO: " + block.toString(2));
				// block.put("time", System.currentTimeMillis());
			}
		}
	}
}
